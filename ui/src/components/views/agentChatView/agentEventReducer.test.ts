import { describe, expect, it } from "vitest";
import type { AgentEvent, ChatMessageVO, PlanSnapshot } from "../../../types";
import {
  createInitialAgentRunState,
  hasPersistedFinalAnswer,
  isAgentEvent,
  reduceAgentRunState,
} from "./agentEventReducer.ts";

function message(id: string, content: string): ChatMessageVO {
  return {
    id,
    sessionId: "session-1",
    role: "assistant",
    content,
  };
}

function event(
  type: AgentEvent["type"],
  payload: AgentEvent["payload"] = {},
): AgentEvent {
  return { type, payload };
}

const plan: PlanSnapshot = {
  planId: "plan-1",
  version: 2,
  revisionCount: 1,
  currentTaskId: "task-2",
  completed: false,
  steps: [
    {
      id: "task-1",
      title: "读取资料",
      dependsOn: [],
      status: "COMPLETED",
    },
    {
      id: "task-2",
      title: "整理结论",
      dependsOn: ["task-1"],
      status: "IN_PROGRESS",
      successCriteria: "给出可核验的结论",
    },
  ],
};

describe("agent event reducer", () => {
  it("upserts duplicate SSE messages instead of appending duplicates", () => {
    const live = reduceAgentRunState(createInitialAgentRunState(), {
      type: "event",
      event: event("AI_GENERATED_CONTENT", {
        message: message("assistant-1", "旧内容"),
      }),
    });
    const merged = reduceAgentRunState(live, {
      type: "event",
      event: event("AI_GENERATED_CONTENT", {
        message: message("assistant-1", "新内容"),
      }),
    });

    expect(merged.messages).toHaveLength(1);
    expect(merged.messages[0].content).toBe("新内容");
  });

  it("merges a stale persisted response without dropping a live message", () => {
    const state = createInitialAgentRunState();
    const withLiveMessage = reduceAgentRunState(state, {
      type: "event",
      event: event("AI_GENERATED_CONTENT", { message: message("live", "实时结果") }),
    });
    const hydrated = reduceAgentRunState(withLiveMessage, {
      type: "mergeMessages",
      messages: [message("user-1", "问题")],
    });

    expect(hydrated.messages.map((item) => item.id)).toEqual(["user-1", "live"]);
  });

  it("stores plan snapshots from both creation and revision events", () => {
    const created = reduceAgentRunState(createInitialAgentRunState(), {
      type: "event",
      event: event("PLAN_CREATED", { plan }),
    });
    const updatedPlan = { ...plan, version: 3, revisionCount: 2 };
    const updated = reduceAgentRunState(created, {
      type: "event",
      event: event("PLAN_UPDATED", { plan: updatedPlan }),
    });

    expect(created.active).toBe(true);
    expect(created.statusType).toBe("AI_PLANNING");
    expect(created.plan).toEqual(plan);
    expect(updated.plan?.version).toBe(3);
    expect(updated.plan?.revisionCount).toBe(2);
  });

  it("moves to terminal states and preserves structured error codes", () => {
    const running = reduceAgentRunState(createInitialAgentRunState(), {
      type: "start",
    });
    const failed = reduceAgentRunState(running, {
      type: "event",
      event: event("AI_ERROR", {
        statusText: "计划无效",
        errorCode: "PLAN_PROTOCOL_ERROR",
      }),
    });

    expect(failed.active).toBe(false);
    expect(failed.errorText).toBe("计划无效");
    expect(failed.errorCode).toBe("PLAN_PROTOCOL_ERROR");

    const completed = reduceAgentRunState(failed, {
      type: "event",
      event: event("AI_DONE", { done: true }),
    });
    expect(completed.active).toBe(false);
    expect(completed.errorText).toBeUndefined();
  });

  it("clears the ready marker only for the disconnected session", () => {
    const ready = reduceAgentRunState(createInitialAgentRunState(), {
      type: "sseReady",
      sessionId: "session-1",
    });
    const otherSession = reduceAgentRunState(ready, {
      type: "sseLost",
      sessionId: "session-2",
    });
    const disconnected = reduceAgentRunState(ready, {
      type: "sseLost",
      sessionId: "session-1",
    });

    expect(otherSession.sseReadySessionId).toBe("session-1");
    expect(disconnected.sseReadySessionId).toBeUndefined();
  });

  it("rejects malformed and unknown SSE payloads before reducer dispatch", () => {
    expect(isAgentEvent(null)).toBe(false);
    expect(isAgentEvent({ type: "UNKNOWN", payload: {} })).toBe(false);
    expect(
      isAgentEvent({
        type: "PLAN_CREATED",
        payload: { plan: { version: 1, steps: [{ status: "UNKNOWN" }] } },
      }),
    ).toBe(false);
    expect(isAgentEvent(event("AI_THINKING", { statusText: "思考中" }))).toBe(
      true,
    );
  });

  it("reconciles a persisted final answer after a lost terminal SSE event", () => {
    expect(
      hasPersistedFinalAnswer(
        [
          { ...message("user-1", "问题"), role: "user" },
          message("assistant-1", "最终答案"),
        ],
        "user-1",
      ),
    ).toBe(true);
    expect(
      hasPersistedFinalAnswer(
        [
          { ...message("user-1", "问题"), role: "user" },
          {
            ...message("assistant-1", "最终答案"),
            metadata: {
              toolCalls: [
                {
                  id: "terminate-1",
                  type: "function",
                  name: "terminate",
                  arguments: "{}",
                },
              ],
            },
          },
        ],
        "user-1",
      ),
    ).toBe(true);
    expect(
      hasPersistedFinalAnswer([message("assistant-1", "旧答案")], "user-1"),
    ).toBe(false);
    expect(
      hasPersistedFinalAnswer(
        [
          { ...message("user-1", "问题"), role: "user" },
          {
            ...message("assistant-1", "中间结果"),
            metadata: {
              toolCalls: [
                {
                  id: "tool-1",
                  type: "function",
                  name: "databaseQuery",
                  arguments: "{}",
                },
              ],
            },
          },
        ],
        "user-1",
      ),
    ).toBe(false);
  });
});
