import React, { useCallback, useEffect, useState } from "react";
import { useParams, useNavigate, useLocation } from "react-router-dom";
import { message as antdMessage } from "antd";
import AgentChatHistory from "./agentChatView/AgentChatHistory.tsx";
import AgentChatInput from "./agentChatView/AgentChatInput.tsx";
import {
  createChatMessage,
  createChatSession,
  getChatMessagesBySessionId,
  getChatSession,
} from "../../api/api.ts";
import { useAgents } from "../../hooks/useAgents.ts";
import { useChatSessions } from "../../hooks/useChatSessions.ts";
import EmptyAgentChatView from "./agentChatView/EmptyAgentChatView.tsx";
import type { AgentEvent, AgentEventType, ChatMessageVO } from "../../types";

const AgentChatView: React.FC = () => {
  const { chatSessionId } = useParams<{ chatSessionId: string }>();
  const navigate = useNavigate();
  const { state } = useLocation();
  const [loading, setLoading] = useState(false);
  const { agents } = useAgents();
  const { refreshChatSessions } = useChatSessions();

  const [messages, setMessages] = useState<ChatMessageVO[]>([]);

  const addMessage = useCallback((message: ChatMessageVO) => {
    setMessages((prevMessages) => [...prevMessages, message]);
  }, []);

  const [agentId, setAgentId] = useState<string>("");

  const getChatMessages = useCallback(async () => {
    if (!chatSessionId) {
      return;
    }
    const resp = await getChatMessagesBySessionId(chatSessionId);
    setMessages(resp.chatMessages);

    const fetchData = async () => {
      const resp = await getChatSession(chatSessionId);
      // setChatSession(resp.chatSession);
      setAgentId(resp.chatSession.agentId);
    };
    fetchData().then();
  }, [chatSessionId]);

  useEffect(() => {
    if (!chatSessionId) {
      return;
    }
    getChatMessages().then();
  }, [chatSessionId, getChatMessages]);

  const handleSendMessage = async (value: string | { text: string }) => {
    // 处理 Sender 组件可能传递的不同格式
    const message = typeof value === "string" ? value : value.text;

    console.log(message);

    if (!message || !message.trim()) return;

    // 如果没有 chatSessionId，创建新会话
    if (!chatSessionId) {
      if (!agentId) {
        antdMessage.warning("请先创建一个智能体助手");
        return;
      }
      setLoading(true);
      try {
        const response = await createChatSession({
          agentId: agentId,
          title: message.slice(0, 20),
        });
        // 刷新聊天会话列表
        await refreshChatSessions();
        // 导航到新创建的会话
        navigate(`/chat/${response.chatSessionId}`, {
          replace: true,
          // 携带初始化消息
          state: {
            init: false,
            initMessage: message,
          },
        });
      } catch (error) {
        console.error("创建聊天会话失败:", error);
        antdMessage.error("创建聊天会话失败，请重试");
      } finally {
        setLoading(false);
      }
    } else {
      if (state?.init) {
        console.log("init", state.initMessage);
        await createChatMessage({
          agentId: agentId ?? "",
          sessionId: chatSessionId,
          role: "user",
          content: state.initMessage ?? "",
        });
      } else {
        console.log("ask", message);
        await createChatMessage({
          agentId: agentId ?? "",
          sessionId: chatSessionId,
          role: "user",
          content: message,
        });
      }
      await getChatMessages();
    }
  };

  const [displayAgentStatus, setDisplayAgentStatus] = useState<boolean>(false);
  const [agentStatusText, setAgentStatusText] = useState("");
  const [agentStatusType, setAgentStatusType] = useState<
    AgentEventType | undefined
  >(undefined);
  const [agentErrorText, setAgentErrorText] = useState<string | undefined>();

  useEffect(() => {
    // sse 连接处理, 不是对话消息不开连接
    if (!chatSessionId) {
      return;
    }
    const es = new EventSource(
      `http://localhost:8080/sse/connect/${chatSessionId}`,
    );
    es.onerror = (error) => {
      console.warn("SSE transport error; EventSource will retry.", error);
    };

    es.addEventListener("message", (event) => {
      try {
        const eventMessage = JSON.parse(event.data) as AgentEvent;
        if (eventMessage.type === "AI_GENERATED_CONTENT") {
          if (eventMessage.payload.message) {
            addMessage(eventMessage.payload.message);
          }
        } else if (
          eventMessage.type === "AI_PLANNING" ||
          eventMessage.type === "AI_THINKING" ||
          eventMessage.type === "AI_EXECUTING"
        ) {
          setAgentErrorText(undefined);
          setDisplayAgentStatus(true);
          setAgentStatusText(eventMessage.payload.statusText ?? "");
          setAgentStatusType(eventMessage.type);
        } else if (eventMessage.type === "AI_ERROR") {
          setDisplayAgentStatus(false);
          setAgentStatusText("");
          setAgentStatusType(undefined);
          setAgentErrorText(eventMessage.payload.statusText ?? "执行失败，请重试");
        } else if (eventMessage.type === "AI_DONE") {
          setDisplayAgentStatus(false);
          setAgentStatusText("");
          setAgentStatusType(undefined);
        } else {
          console.warn("Unknown SSE message type", eventMessage.type);
        }
      } catch (error) {
        console.warn("Ignoring malformed SSE message", error);
      }
    });

    es.addEventListener("init", () => {
      setDisplayAgentStatus(false);
      setAgentStatusText("");
      setAgentStatusType(undefined);
      setAgentErrorText(undefined);
      void getChatMessages().catch((error) => {
        console.warn(
          "Unable to refresh persisted chat messages after SSE init",
          error,
        );
      });
    });

    return () => {
      es.close();
    };
  }, [addMessage, chatSessionId, getChatMessages]);

  // 如果没有 chatSessionId，显示提示界面
  if (!chatSessionId) {
    return (
      <EmptyAgentChatView
        agents={agents}
        loading={loading}
        handleSendMessage={handleSendMessage}
      />
    );
  }

  // 如果有 chatSessionId，显示正常的聊天界面
  return (
    <div className="flex flex-col h-full">
      <AgentChatHistory
        messages={messages}
        displayAgentStatus={displayAgentStatus}
        agentStatusText={agentStatusText}
        agentStatusType={agentStatusType}
        agentErrorText={agentErrorText}
      />
      <div className="border-t border-gray-200 p-4 bg-white">
        <AgentChatInput onSend={handleSendMessage} />
      </div>
    </div>
  );
};

export default AgentChatView;
