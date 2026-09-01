import type {
  AgentErrorCode,
  AgentEvent,
  AgentEventType,
  ChatMessageVO,
  MessageType,
  PlanSnapshot,
  PlanStepStatus,
} from "../../../types";

export interface AgentRunState {
  messages: ChatMessageVO[];
  active: boolean;
  statusType?: AgentEventType;
  statusText: string;
  errorText?: string;
  errorCode?: AgentErrorCode;
  plan?: PlanSnapshot;
  sseReadySessionId?: string;
}

export type AgentRunAction =
  | { type: "reset" }
  | { type: "start" }
  | { type: "sseReady"; sessionId: string }
  | { type: "sseLost"; sessionId: string }
  | { type: "mergeMessages"; messages: ChatMessageVO[] }
  | { type: "event"; event: AgentEvent }
  | { type: "transportError"; message: string };

export function createInitialAgentRunState(): AgentRunState {
  return {
    messages: [],
    active: false,
    statusText: "",
  };
}

export function upsertChatMessage(
  messages: ChatMessageVO[],
  message: ChatMessageVO,
): ChatMessageVO[] {
  const existingIndex = messages.findIndex((item) => item.id === message.id);
  if (existingIndex < 0) {
    return [...messages, message];
  }

  const merged = messages.slice();
  merged[existingIndex] = message;
  return merged;
}

/**
 * Merge persisted messages into the live stream without replacing messages
 * already received through SSE. This prevents an older GET response from
 * erasing an assistant/tool message that arrived while the request was in flight.
 */
export function mergeChatMessages(
  current: ChatMessageVO[],
  incoming: ChatMessageVO[],
): ChatMessageVO[] {
  const currentById = new Map(current.map((message) => [message.id, message]));
  const incomingIds = new Set(incoming.map((message) => message.id));
  const persistedInServerOrder = incoming.map(
    (message) => currentById.get(message.id) ?? message,
  );
  const liveOnlyMessages = current.filter(
    (message) => !incomingIds.has(message.id),
  );

  // The GET endpoint is the source of chronological order. Live-only SSE
  // messages are appended until the next refresh persists them.
  return [...persistedInServerOrder, ...liveOnlyMessages];
}

const eventTypes: AgentEventType[] = [
  "AI_GENERATED_CONTENT",
  "AI_PLANNING",
  "AI_THINKING",
  "AI_EXECUTING",
  "AI_ERROR",
  "PLAN_CREATED",
  "PLAN_UPDATED",
  "AI_DONE",
];

const messageTypes: MessageType[] = ["user", "assistant", "system", "tool"];
const planStepStatuses: PlanStepStatus[] = [
  "BLOCKED",
  "READY",
  "IN_PROGRESS",
  "COMPLETED",
  "FAILED",
  "SKIPPED",
];

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function isChatMessage(value: unknown): value is ChatMessageVO {
  if (!isRecord(value)) {
    return false;
  }

  return (
    typeof value.id === "string" &&
    typeof value.sessionId === "string" &&
    typeof value.content === "string" &&
    typeof value.role === "string" &&
    messageTypes.includes(value.role as MessageType)
  );
}

function isPlanSnapshot(value: unknown): value is PlanSnapshot {
  if (!isRecord(value) || !Array.isArray(value.steps)) {
    return false;
  }

  return (
    (value.planId === undefined || typeof value.planId === "string") &&
    typeof value.version === "number" &&
    (value.currentTaskId === undefined || typeof value.currentTaskId === "string") &&
    typeof value.revisionCount === "number" &&
    typeof value.completed === "boolean" &&
    value.steps.every((step) => {
      if (!isRecord(step)) {
        return false;
      }
      return (
        typeof step.id === "string" &&
        typeof step.title === "string" &&
        Array.isArray(step.dependsOn) &&
        step.dependsOn.every((dependency) => typeof dependency === "string") &&
        typeof step.status === "string" &&
        planStepStatuses.includes(step.status as PlanStepStatus) &&
        (step.successCriteria === undefined ||
          typeof step.successCriteria === "string")
      );
    })
  );
}

export function isAgentEvent(value: unknown): value is AgentEvent {
  if (!isRecord(value) || !isRecord(value.payload)) {
    return false;
  }

  const candidate = value as Partial<AgentEvent> & {
    payload: Record<string, unknown>;
  };
  return (
    typeof candidate.type === "string" &&
    eventTypes.includes(candidate.type as AgentEventType) &&
    (candidate.payload.errorCode === undefined ||
      typeof candidate.payload.errorCode === "string") &&
    (candidate.payload.message === undefined ||
      isChatMessage(candidate.payload.message)) &&
    (candidate.payload.plan === undefined ||
      isPlanSnapshot(candidate.payload.plan))
  );
}

/**
 * A completed assistant answer is persisted even if its terminal SSE event was
 * lost during a reconnect. The user message id keeps this check scoped to the
 * current run instead of an older answer in the same session.
 */
export function hasPersistedFinalAnswer(
  messages: ChatMessageVO[],
  userMessageId: string | undefined,
): boolean {
  if (!userMessageId) {
    return false;
  }

  const userIndex = messages.findIndex((message) => message.id === userMessageId);
  if (userIndex < 0) {
    return false;
  }

  return messages.slice(userIndex + 1).some((message) => {
    if (message.role !== "assistant" || !message.content.trim()) {
      return false;
    }

    const toolCalls = message.metadata?.toolCalls ?? [];
    // The backend persists a valid final answer together with the single
    // `terminate` signal tool call. Other tool calls mean the run is still in
    // progress and must not be reconciled as terminal.
    return (
      toolCalls.length === 0 ||
      (toolCalls.length === 1 && toolCalls[0].name === "terminate")
    );
  });
}

function statusForPlanEvent(event: AgentEvent): {
  statusType: AgentEventType;
  statusText: string;
} {
  return {
    statusType: "AI_PLANNING",
    statusText:
      event.payload.statusText ??
      (event.type === "PLAN_CREATED" ? "执行计划已创建" : "执行计划已更新"),
  };
}

export function reduceAgentRunState(
  state: AgentRunState,
  action: AgentRunAction,
): AgentRunState {
  switch (action.type) {
    case "reset":
      return createInitialAgentRunState();
    case "start":
      return {
        ...state,
        active: true,
        statusType: "AI_THINKING",
        statusText: "等待智能体响应...",
        errorText: undefined,
        errorCode: undefined,
        plan: undefined,
      };
    case "sseReady":
      return {
        ...state,
        sseReadySessionId: action.sessionId,
      };
    case "sseLost":
      return state.sseReadySessionId === action.sessionId
        ? { ...state, sseReadySessionId: undefined }
        : state;
    case "mergeMessages":
      return {
        ...state,
        messages: mergeChatMessages(state.messages, action.messages),
      };
    case "transportError":
      return {
        ...state,
        active: false,
        statusType: undefined,
        statusText: "",
        errorText: action.message,
        errorCode: undefined,
      };
    case "event": {
      const { event } = action;
      const payload = event.payload;

      switch (event.type) {
        case "AI_GENERATED_CONTENT":
          return {
            ...state,
            active: true,
            messages: payload.message
              ? upsertChatMessage(state.messages, payload.message)
              : state.messages,
            errorText: undefined,
            errorCode: undefined,
          };
        case "AI_PLANNING":
        case "AI_THINKING":
        case "AI_EXECUTING":
          return {
            ...state,
            active: true,
            statusType: event.type,
            statusText: payload.statusText ?? "",
            errorText: undefined,
            errorCode: undefined,
          };
        case "PLAN_CREATED":
        case "PLAN_UPDATED": {
          const planStatus = statusForPlanEvent(event);
          return {
            ...state,
            active: true,
            statusType: planStatus.statusType,
            statusText: planStatus.statusText,
            plan: payload.plan ?? state.plan,
            errorText: undefined,
            errorCode: undefined,
          };
        }
        case "AI_ERROR":
          return {
            ...state,
            active: false,
            statusType: undefined,
            statusText: "",
            errorText: payload.statusText ?? "执行失败，请重试",
            errorCode: payload.errorCode,
          };
        case "AI_DONE":
          return {
            ...state,
            active: false,
            statusType: undefined,
            statusText: "",
            errorText: undefined,
            errorCode: undefined,
          };
      }
    }
  }
}
