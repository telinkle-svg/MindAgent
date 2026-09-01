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

const AgentChatView: React.FC = () => {
  const { chatSessionId } = useParams<{ chatSessionId: string }>();
  const location = useLocation();
  const navigate = useNavigate();
  const { agents } = useAgents();
  const navigationState = location.state as
    | { initialMessage?: unknown; planningMode?: unknown }
    | null;
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

  const refreshMessages = useCallback(async (): Promise<ChatMessageVO[]> => {
    if (!chatSessionId) {
      return [];
    }

    const [messagesResponse, sessionResponse] = await Promise.all([
      getChatMessagesBySessionId(chatSessionId),
      getChatSession(chatSessionId),
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
  }, [chatSessionId]);

  const stopRunRecoveryTimer = useCallback(() => {
    if (runRecoveryTimerRef.current !== undefined) {
      window.clearTimeout(runRecoveryTimerRef.current);
      runRecoveryTimerRef.current = undefined;
    }
  }, []);

  const beginRun = useCallback(
    (sessionId: string) => {
      dispatch({ type: "start" });
      stopRunRecoveryTimer();
      runRecoveryTimerRef.current = window.setTimeout(() => {
        runRecoveryTimerRef.current = undefined;
        if (currentSessionIdRef.current !== sessionId) {
          return;
        }
        activeUserMessageIdRef.current = undefined;
        dispatch({
          type: "transportError",
          message: "执行状态未知，请刷新会话后重试",
        });
      }, RUN_RECOVERY_TIMEOUT_MS);
    },
    [stopRunRecoveryTimer],
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

      stopRunRecoveryTimer();
      activeUserMessageIdRef.current = undefined;
      dispatch({
        type: "event",
        event: { type: "AI_DONE", payload: { done: true } },
      });
    },
    [chatSessionId, stopRunRecoveryTimer],
  );

  useEffect(() => {
    currentSessionIdRef.current = chatSessionId;
    initialSubmissionSessionRef.current = undefined;
    activeUserMessageIdRef.current = undefined;
    stopRunRecoveryTimer();
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
      activeUserMessageIdRef.current = undefined;
    };
  }, [chatSessionId, refreshMessages, stopRunRecoveryTimer]);

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
          return refreshMessages();
        })
        .then((messages) => reconcilePersistedRun(messages))
        .catch((error) => {
          if (currentSessionIdRef.current !== chatSessionId) {
            return;
          }
          console.error("发送初始聊天消息失败:", error);
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
        // so an SSE event cannot be lost to an in-flight refresh.
        const messages = await refreshMessages();
        reconcilePersistedRun(messages);
      } catch (error) {
        if (currentSessionIdRef.current !== chatSessionId) {
          return;
        }
        console.error("发送聊天消息失败:", error);
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
        .then((messages) => reconcilePersistedRun(messages))
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
