import React, {
  useCallback,
  useEffect,
  useReducer,
  useRef,
  useState,
} from "react";
import { useLocation, useNavigate, useParams } from "react-router-dom";
import { message as antdMessage } from "antd";
import AgentChatHistory from "./agentChatView/AgentChatHistory.tsx";
import AgentChatInput from "./agentChatView/AgentChatInput.tsx";
import {
  createChatMessage,
  getChatMessagesBySessionId,
  getChatSession,
} from "../../api/api.ts";
import { buildSseUrl } from "../../api/http.ts";
import { useAgents } from "../../hooks/useAgents.ts";
import EmptyAgentChatView from "./agentChatView/EmptyAgentChatView.tsx";
import type { AgentEvent, ChatMessageVO, PlanningMode } from "../../types";
import {
  createInitialAgentRunState,
  hasPersistedFinalAnswer,
  isAgentEvent,
  reduceAgentRunState,
} from "./agentChatView/agentEventReducer.ts";

const RUN_RECOVERY_TIMEOUT_MS = 3 * 60 * 1000;
// A lost terminal SSE event should be recoverable without making the user
// resubmit the message. Keep this bounded so a disconnected browser does not
// turn one run into an unbounded polling loop.
const RUN_RECONCILIATION_DELAYS_MS = [
  250, 500, 1_000, 2_000, 4_000, 8_000, 12_000, 20_000, 30_000, 40_000, 50_000,
] as const;
const RUN_RECONCILIATION_WINDOW_MS = RUN_RECOVERY_TIMEOUT_MS - 10_000;
const RUN_RECONCILIATION_REQUEST_TIMEOUT_MS = 10_000;

const AgentChatView: React.FC = () => {
  const { chatSessionId } = useParams<{ chatSessionId: string }>();
  const location = useLocation();
  const navigate = useNavigate();
  const { agents } = useAgents();
  const navigationState = location.state as {
    initialMessage?: unknown;
    planningMode?: unknown;
  } | null;
  const initialMessage =
    typeof navigationState?.initialMessage === "string"
      ? navigationState.initialMessage.trim()
      : "";
  const initialPlanningMode: PlanningMode =
    navigationState?.planningMode === "REQUIRED" ||
    navigationState?.planningMode === "DISABLED"
      ? navigationState.planningMode
      : "AUTO";
  const [sessionInfo, setSessionInfo] = useState<{
    sessionId: string;
    agentId: string;
  } | null>(null);
  const [planningMode, setPlanningMode] = useState<PlanningMode>("AUTO");
  const [runState, dispatch] = useReducer(
    reduceAgentRunState,
    undefined,
    createInitialAgentRunState,
  );
  const currentSessionIdRef = useRef(chatSessionId);
  const initialSubmissionSessionRef = useRef<string | undefined>(undefined);
  const activeUserMessageIdRef = useRef<string | undefined>(undefined);
  const runRecoveryTimerRef = useRef<number | undefined>(undefined);
  const reconciliationTimerRef = useRef<number | undefined>(undefined);
  const reconciliationGenerationRef = useRef(0);
  const reconciliationSessionRef = useRef<string | undefined>(undefined);

  const refreshMessages = useCallback(
    async (signal?: AbortSignal): Promise<ChatMessageVO[]> => {
      if (!chatSessionId) {
        return [];
      }

      const [messagesResponse, sessionResponse] = await Promise.all([
        getChatMessagesBySessionId(chatSessionId, { signal }),
        getChatSession(chatSessionId, { signal }),
      ]);

      // Ignore an older request that resolved after the user navigated to another session.
      if (currentSessionIdRef.current !== chatSessionId) {
        return [];
      }

      dispatch({
        type: "mergeMessages",
        messages: messagesResponse.chatMessages,
      });
      setSessionInfo({
        sessionId: chatSessionId,
        agentId: sessionResponse.chatSession.agentId,
      });
      return messagesResponse.chatMessages;
    },
    [chatSessionId],
  );

  const stopRunRecoveryTimer = useCallback(() => {
    if (runRecoveryTimerRef.current !== undefined) {
      window.clearTimeout(runRecoveryTimerRef.current);
      runRecoveryTimerRef.current = undefined;
    }
  }, []);

  const stopRunReconciliation = useCallback(() => {
    reconciliationGenerationRef.current += 1;
    reconciliationSessionRef.current = undefined;
    if (reconciliationTimerRef.current !== undefined) {
      window.clearTimeout(reconciliationTimerRef.current);
      reconciliationTimerRef.current = undefined;
    }
  }, []);

  const beginRun = useCallback(
    (sessionId: string) => {
      dispatch({ type: "start" });
      stopRunRecoveryTimer();
      stopRunReconciliation();
      runRecoveryTimerRef.current = window.setTimeout(() => {
        runRecoveryTimerRef.current = undefined;
        if (currentSessionIdRef.current !== sessionId) {
          return;
        }
        stopRunReconciliation();
        activeUserMessageIdRef.current = undefined;
        dispatch({
          type: "transportError",
          message: "执行状态未知，请刷新会话后重试",
        });
      }, RUN_RECOVERY_TIMEOUT_MS);
    },
    [stopRunReconciliation, stopRunRecoveryTimer],
  );

  const reconcilePersistedRun = useCallback(
    (messages: ChatMessageVO[]) => {
      if (
        !chatSessionId ||
        currentSessionIdRef.current !== chatSessionId ||
        !hasPersistedFinalAnswer(messages, activeUserMessageIdRef.current)
      ) {
        return;
      }

      stopRunReconciliation();
      stopRunRecoveryTimer();
      activeUserMessageIdRef.current = undefined;
      dispatch({
        type: "event",
        event: { type: "AI_DONE", payload: { done: true } },
      });
    },
    [chatSessionId, stopRunReconciliation, stopRunRecoveryTimer],
  );

  const startRunReconciliation = useCallback(
    (sessionId: string, immediate = false) => {
      if (
        !chatSessionId ||
        sessionId !== chatSessionId ||
        currentSessionIdRef.current !== sessionId ||
        !activeUserMessageIdRef.current ||
        reconciliationSessionRef.current === sessionId
      ) {
        return;
      }

      stopRunReconciliation();
      reconciliationSessionRef.current = sessionId;
      const generation = reconciliationGenerationRef.current;
      const deadline = Date.now() + RUN_RECONCILIATION_WINDOW_MS;
      let attempt = 0;

      const isCurrentRun = () =>
        generation === reconciliationGenerationRef.current &&
        currentSessionIdRef.current === sessionId &&
        activeUserMessageIdRef.current !== undefined;

      const scheduleNext = () => {
        const remainingMs = deadline - Date.now();
        if (
          !isCurrentRun() ||
          remainingMs <= 0 ||
          attempt >= RUN_RECONCILIATION_DELAYS_MS.length
        ) {
          return;
        }
        const requestedDelay =
          immediate && attempt === 0
            ? 0
            : RUN_RECONCILIATION_DELAYS_MS[attempt];
        const delay = Math.min(requestedDelay, remainingMs);
        attempt += 1;
        reconciliationTimerRef.current = window.setTimeout(() => {
          reconciliationTimerRef.current = undefined;
          poll();
        }, delay);
      };

      const poll = () => {
        if (!isCurrentRun()) {
          return;
        }

        const requestController = new AbortController();
        const requestTimeout = window.setTimeout(
          () => requestController.abort(),
          RUN_RECONCILIATION_REQUEST_TIMEOUT_MS,
        );
        void refreshMessages(requestController.signal)
          .then((messages) => {
            if (!isCurrentRun()) {
              return;
            }
            reconcilePersistedRun(messages);
            if (activeUserMessageIdRef.current !== undefined) {
              scheduleNext();
            }
          })
          .catch((error) => {
            if (!isCurrentRun()) {
              return;
            }
            if (!(error instanceof Error && error.name === "AbortError")) {
              console.warn("Unable to reconcile persisted agent run", error);
            }
            scheduleNext();
          })
          .finally(() => {
            window.clearTimeout(requestTimeout);
          });
      };

      scheduleNext();
    },
    [
      chatSessionId,
      reconcilePersistedRun,
      refreshMessages,
      stopRunReconciliation,
    ],
  );

  useEffect(() => {
    currentSessionIdRef.current = chatSessionId;
    initialSubmissionSessionRef.current = undefined;
    activeUserMessageIdRef.current = undefined;
    stopRunRecoveryTimer();
    stopRunReconciliation();
    dispatch({ type: "reset" });
    if (!chatSessionId) {
      return;
    }

    const loadTimer = window.setTimeout(() => {
      void refreshMessages().catch((error) => {
        console.warn("Unable to load chat messages", error);
      });
    }, 0);
    return () => {
      window.clearTimeout(loadTimer);
      stopRunRecoveryTimer();
      stopRunReconciliation();
      activeUserMessageIdRef.current = undefined;
    };
  }, [
    chatSessionId,
    refreshMessages,
    stopRunReconciliation,
    stopRunRecoveryTimer,
  ]);

  useEffect(() => {
    if (
      !chatSessionId ||
      !initialMessage ||
      sessionInfo?.sessionId !== chatSessionId ||
      runState.sseReadySessionId !== chatSessionId ||
      initialSubmissionSessionRef.current === chatSessionId
    ) {
      return;
    }

    const submitTimer = window.setTimeout(() => {
      if (currentSessionIdRef.current !== chatSessionId) {
        return;
      }

      initialSubmissionSessionRef.current = chatSessionId;
      beginRun(chatSessionId);
      void createChatMessage({
        agentId: sessionInfo.agentId,
        sessionId: chatSessionId,
        role: "user",
        content: initialMessage,
        planningMode: initialPlanningMode,
      })
        .then((response) => {
          if (currentSessionIdRef.current === chatSessionId) {
            activeUserMessageIdRef.current = response.chatMessageId;
          }
          return refreshMessages()
            .then((messages) => reconcilePersistedRun(messages))
            .catch((error) => {
              console.warn(
                "Unable to refresh persisted messages after initial submission",
                error,
              );
            })
            .finally(() => startRunReconciliation(chatSessionId));
        })
        .catch((error) => {
          if (currentSessionIdRef.current !== chatSessionId) {
            return;
          }
          console.error("发送初始聊天消息失败:", error);
          stopRunReconciliation();
          stopRunRecoveryTimer();
          activeUserMessageIdRef.current = undefined;
          dispatch({
            type: "transportError",
            message: "消息发送失败，请重试",
          });
          antdMessage.error("消息发送失败，请重试");
        })
        .finally(() => {
          if (currentSessionIdRef.current !== chatSessionId) {
            return;
          }
          // Remove the one-shot navigation payload so a browser refresh cannot
          // submit the same initial message a second time.
          navigate(location.pathname, { replace: true, state: null });
        });
    }, 0);

    return () => window.clearTimeout(submitTimer);
  }, [
    chatSessionId,
    initialMessage,
    initialPlanningMode,
    location.pathname,
    navigate,
    beginRun,
    refreshMessages,
    reconcilePersistedRun,
    sessionInfo,
    startRunReconciliation,
    stopRunReconciliation,
    runState.sseReadySessionId,
    stopRunRecoveryTimer,
  ]);

  const handleSendMessage = useCallback(
    async (value: string) => {
      const content = value.trim();
      if (!content || !chatSessionId) {
        return;
      }
      if (initialMessage) {
        antdMessage.info("首条消息正在提交，请稍候");
        return;
      }
      if (runState.sseReadySessionId !== chatSessionId) {
        antdMessage.info("正在建立实时连接，请稍候");
        return;
      }
      if (runState.active) {
        antdMessage.info("智能体正在执行，请等待本轮完成");
        return;
      }
      const agentId =
        sessionInfo?.sessionId === chatSessionId ? sessionInfo.agentId : "";
      if (!agentId) {
        antdMessage.warning("会话信息尚未加载完成，请稍后重试");
        return;
      }

      beginRun(chatSessionId);
      try {
        const response = await createChatMessage({
          agentId,
          sessionId: chatSessionId,
          role: "user",
          content,
          planningMode,
        });
        if (currentSessionIdRef.current === chatSessionId) {
          activeUserMessageIdRef.current = response.chatMessageId;
        }
        // The GET response is merged, rather than replacing the live stream,
        // so an SSE event cannot be lost to an in-flight refresh. A transient
        // refresh failure is retried by the bounded reconciliation loop.
        try {
          const messages = await refreshMessages();
          reconcilePersistedRun(messages);
        } catch (error) {
          console.warn(
            "Unable to refresh persisted messages after submission",
            error,
          );
        }
        startRunReconciliation(chatSessionId);
      } catch (error) {
        if (currentSessionIdRef.current !== chatSessionId) {
          return;
        }
        console.error("发送聊天消息失败:", error);
        stopRunReconciliation();
        stopRunRecoveryTimer();
        activeUserMessageIdRef.current = undefined;
        dispatch({
          type: "transportError",
          message: "消息发送失败，请重试",
        });
        antdMessage.error("消息发送失败，请重试");
      }
    },
    [
      chatSessionId,
      beginRun,
      initialMessage,
      planningMode,
      reconcilePersistedRun,
      refreshMessages,
      runState.active,
      runState.sseReadySessionId,
      sessionInfo,
      startRunReconciliation,
      stopRunReconciliation,
      stopRunRecoveryTimer,
    ],
  );

  useEffect(() => {
    // 没有会话时不建立 SSE 连接；新会话页面由 EmptyAgentChatView 负责创建。
    if (!chatSessionId) {
      return;
    }

    const eventSource = new EventSource(buildSseUrl(chatSessionId));
    const handleSseError = (error: Event) => {
      console.warn("SSE transport error; EventSource will retry.", error);
      if (currentSessionIdRef.current === chatSessionId) {
        dispatch({ type: "sseLost", sessionId: chatSessionId });
        startRunReconciliation(chatSessionId, true);
      }
    };
    const handleMessage = (event: MessageEvent<string>) => {
      if (currentSessionIdRef.current !== chatSessionId) {
        return;
      }
      try {
        const parsed = JSON.parse(event.data) as unknown;
        if (!isAgentEvent(parsed)) {
          console.warn("Ignoring unknown SSE message", parsed);
          return;
        }

        const agentEvent = parsed as AgentEvent;
        dispatch({ type: "event", event: agentEvent });
        if (agentEvent.type === "AI_DONE" || agentEvent.type === "AI_ERROR") {
          stopRunReconciliation();
          stopRunRecoveryTimer();
          activeUserMessageIdRef.current = undefined;
          void refreshMessages().catch((error) => {
            console.warn("Unable to refresh persisted messages", error);
          });
        }
      } catch (error) {
        console.warn("Ignoring malformed SSE message", error);
      }
    };
    const handleInit = () => {
      if (currentSessionIdRef.current !== chatSessionId) {
        return;
      }
      // Do not clear a currently running status on reconnect; init is a transport
      // event, not an agent lifecycle event.
      dispatch({ type: "sseReady", sessionId: chatSessionId });
      void refreshMessages()
        .then((messages) => {
          reconcilePersistedRun(messages);
          startRunReconciliation(chatSessionId);
        })
        .catch((error) => {
          console.warn(
            "Unable to refresh persisted chat messages after SSE init",
            error,
          );
        });
    };

    eventSource.addEventListener("error", handleSseError);
    eventSource.addEventListener("message", handleMessage);
    eventSource.addEventListener("init", handleInit);

    return () => {
      eventSource.removeEventListener("error", handleSseError);
      eventSource.removeEventListener("message", handleMessage);
      eventSource.removeEventListener("init", handleInit);
      eventSource.close();
    };
  }, [
    chatSessionId,
    reconcilePersistedRun,
    refreshMessages,
    startRunReconciliation,
    stopRunReconciliation,
    stopRunRecoveryTimer,
  ]);

  if (!chatSessionId) {
    return <EmptyAgentChatView agents={agents} />;
  }

  return (
    <div className="flex flex-col h-full">
      <AgentChatHistory
        messages={runState.messages}
        displayAgentStatus={runState.active}
        agentStatusText={runState.statusText}
        agentStatusType={runState.statusType}
        agentErrorText={runState.errorText}
        agentErrorCode={runState.errorCode}
        plan={runState.plan}
      />
      <div className="border-t border-gray-200 p-4 bg-white">
        <AgentChatInput
          onSend={handleSendMessage}
          loading={
            runState.active ||
            Boolean(initialMessage) ||
            runState.sseReadySessionId !== chatSessionId
          }
          planningMode={planningMode}
          onPlanningModeChange={setPlanningMode}
        />
      </div>
    </div>
  );
};

export default AgentChatView;
