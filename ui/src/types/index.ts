export type MessageType = "user" | "assistant" | "system" | "tool";

export interface KnowledgeBase {
  knowledgeBaseId: string;
  name: string;
  description: string;
}

export interface ToolCall {
  id: string;
  type: string;
  name: string;
  arguments: string;
}

export interface ToolResponse {
  id: string;
  name: string;
  responseData: string;
}

export interface ChatMessageVOMetadata {
  toolCalls?: ToolCall[];
  toolResponse?: ToolResponse;
}

export interface ChatMessageVO {
  id: string;
  sessionId: string;
  role: MessageType;
  content: string;
  metadata?: ChatMessageVOMetadata;
}

export type AgentErrorCode =
  | "AGENT_PROTOCOL_ERROR"
  | "MODEL_CALL_FAILED"
  | "TOOL_EXECUTION_FAILED"
  | "FINAL_ANSWER_MISSING"
  | "MAX_STEPS_EXCEEDED"
  | "PLAN_REQUIRED"
  | "PLAN_DISABLED"
  | "PLAN_PROTOCOL_ERROR"
  | "MAX_PLAN_REVISIONS_EXCEEDED"
  | "MAX_MODEL_CALLS_EXCEEDED"
  | "MAX_TOOL_CALLS_EXCEEDED"
  | "MAX_RUN_DURATION_EXCEEDED";

export type PlanningMode = "AUTO" | "REQUIRED" | "DISABLED";

export type PlanStepStatus =
  | "BLOCKED"
  | "READY"
  | "IN_PROGRESS"
  | "COMPLETED"
  | "FAILED"
  | "SKIPPED";

export interface PlanStep {
  id: string;
  title: string;
  dependsOn: string[];
  status: PlanStepStatus;
  successCriteria?: string;
}

export interface PlanSnapshot {
  planId?: string;
  version: number;
  steps: PlanStep[];
  currentTaskId?: string;
  revisionCount: number;
  completed: boolean;
}

export type AgentEventType =
  | "AI_GENERATED_CONTENT"
  | "AI_PLANNING"
  | "AI_THINKING"
  | "AI_EXECUTING"
  | "AI_ERROR"
  | "PLAN_CREATED"
  | "PLAN_UPDATED"
  | "AI_DONE";

export interface AgentEventPayload {
  message?: ChatMessageVO;
  statusText?: string;
  done?: boolean;
  errorCode?: AgentErrorCode;
  plan?: PlanSnapshot;
}

export interface AgentEventMetadata {
  chatMessageId?: string;
}

export interface AgentEvent {
  type: AgentEventType;
  payload: AgentEventPayload;
  metadata?: AgentEventMetadata;
}
