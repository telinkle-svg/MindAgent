import React, { useState, useRef, useEffect, useCallback } from "react";
import { Bubble } from "@ant-design/x";
import XMarkdown from "@ant-design/x-markdown";
import {
  ToolOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  ExclamationCircleOutlined,
  LoadingOutlined,
  MinusCircleOutlined,
  RobotOutlined,
  DownOutlined,
  RightOutlined,
} from "@ant-design/icons";
import { Tag } from "antd";
import type {
  AgentErrorCode,
  ChatMessageVO,
  AgentEventType,
  PlanSnapshot,
  PlanStepStatus,
  ToolCall,
  ToolResponse,
} from "../../../types";

interface AgentChatHistoryProps {
  messages: ChatMessageVO[];
  displayAgentStatus?: boolean;
  agentStatusText?: string;
  agentStatusType?: AgentEventType;
  agentErrorText?: string;
  agentErrorCode?: AgentErrorCode;
  plan?: PlanSnapshot;
}

const planStatusConfig: Record<
  PlanStepStatus,
  { label: string; color: string; icon: React.ReactNode }
> = {
  BLOCKED: {
    label: "阻塞",
    color: "orange",
    icon: <ExclamationCircleOutlined />,
  },
  READY: { label: "待执行", color: "default", icon: <ClockCircleOutlined /> },
  IN_PROGRESS: {
    label: "执行中",
    color: "processing",
    icon: <LoadingOutlined />,
  },
  COMPLETED: {
    label: "已完成",
    color: "success",
    icon: <CheckCircleOutlined />,
  },
  FAILED: { label: "失败", color: "error", icon: <CloseCircleOutlined /> },
  SKIPPED: {
    label: "已跳过",
    color: "default",
    icon: <MinusCircleOutlined />,
  },
};

const errorCodeLabels: Partial<Record<AgentErrorCode, string>> = {
  AGENT_PROTOCOL_ERROR: "Agent 协议错误",
  MODEL_CALL_FAILED: "模型调用失败",
  TOOL_EXECUTION_FAILED: "工具执行失败",
  FINAL_ANSWER_MISSING: "缺少最终答案",
  MAX_STEPS_EXCEEDED: "超过最大迭代轮数",
  PLAN_REQUIRED: "必须先创建计划",
  PLAN_DISABLED: "当前会话已关闭规划",
  PLAN_PROTOCOL_ERROR: "计划工具协议错误",
  MAX_PLAN_REVISIONS_EXCEEDED: "超过计划修订次数",
  MAX_MODEL_CALLS_EXCEEDED: "超过模型调用次数",
  MAX_TOOL_CALLS_EXCEEDED: "超过工具调用次数",
  MAX_RUN_DURATION_EXCEEDED: "执行超时",
};

const PlanProgress: React.FC<{ plan: PlanSnapshot }> = ({ plan }) => {
  if (plan.steps.length === 0) {
    return null;
  }

  const completedCount = plan.steps.filter(
    (step) => step.status === "COMPLETED",
  ).length;

  return (
    <div className="mb-4 rounded-lg border border-blue-100 bg-blue-50/50 p-3">
      <div className="mb-2 flex items-center justify-between gap-2">
        <div className="flex items-center gap-2 text-sm font-medium text-blue-800">
          <span>{plan.completed ? "执行计划（已完成）" : "执行计划"}</span>
          <Tag color={plan.completed ? "success" : "processing"}>
            {completedCount}/{plan.steps.length}
          </Tag>
        </div>
        <span className="text-xs text-gray-500">
          v{plan.version} · 修订 {plan.revisionCount} 次
        </span>
      </div>
      <div className="space-y-1.5">
        {plan.steps.map((step, index) => {
          const status = planStatusConfig[step.status] ?? planStatusConfig.READY;
          const isCurrent = step.id === plan.currentTaskId;
          return (
            <div
              key={step.id || `${plan.version}-${index}`}
              className={`flex items-center gap-2 rounded px-2 py-1 text-xs ${
                isCurrent ? "bg-white shadow-sm" : ""
              }`}
              title={step.successCriteria || undefined}
            >
              <span className="w-4 text-center text-gray-400">{index + 1}</span>
              <span className="min-w-0 flex-1 truncate text-gray-700">
                {step.title}
              </span>
              {isCurrent && <span className="text-blue-600">当前</span>}
              <Tag color={status.color} icon={status.icon}>
                {status.label}
              </Tag>
            </div>
          );
        })}
      </div>
    </div>
  );
};

// 工具调用展示组件（简化版，用于 assistant 消息内）
const ToolCallDisplay: React.FC<{ toolCall: ToolCall }> = ({ toolCall }) => {
  let parsedArgs: Record<string, unknown> = {};
  try {
    parsedArgs = JSON.parse(toolCall.arguments) as Record<string, unknown>;
  } catch {
    // 如果解析失败，使用原始字符串
  }

  const argCount = Object.keys(parsedArgs).length;
  const argPreview = argCount > 0 
    ? Object.keys(parsedArgs).slice(0, 2).join(", ") + (argCount > 2 ? "..." : "")
    : toolCall.arguments.slice(0, 50) + (toolCall.arguments.length > 50 ? "..." : "");

  return (
    <div className="text-xs text-gray-500 flex items-center gap-1.5">
      <ToolOutlined className="text-blue-500" />
      <span className="font-mono text-blue-600">{toolCall.name}</span>
      {argPreview && (
        <>
          <span className="text-gray-400">·</span>
          <span className="text-gray-500 truncate max-w-[200px]">{argPreview}</span>
        </>
      )}
    </div>
  );
};

// 工具响应展示组件（可折叠）
const ToolResponseDisplay: React.FC<{ toolResponse: ToolResponse }> = ({
  toolResponse,
}) => {
  const [expanded, setExpanded] = useState(false);
  
  let parsedData: unknown = null;
  let isJson = false;
  let dataPreview = "";
  
  try {
    parsedData = JSON.parse(toolResponse.responseData);
    isJson = true;
    const jsonStr = JSON.stringify(parsedData);
    dataPreview = jsonStr.length > 100 ? jsonStr.slice(0, 100) + "..." : jsonStr;
  } catch {
    dataPreview = toolResponse.responseData.length > 100 
      ? toolResponse.responseData.slice(0, 100) + "..." 
      : toolResponse.responseData;
  }

  return (
    <div className="my-1.5 text-xs">
      <div 
        className="flex items-center gap-2 text-gray-500 cursor-pointer hover:text-gray-700 transition-colors"
        onClick={() => setExpanded(!expanded)}
      >
        {expanded ? (
          <DownOutlined className="text-gray-400" />
        ) : (
          <RightOutlined className="text-gray-400" />
        )}
        <CheckCircleOutlined className="text-green-500" />
        <span className="font-mono text-green-600">{toolResponse.name}</span>
        <span className="text-gray-400">·</span>
        <span className="text-gray-500 truncate flex-1">{dataPreview}</span>
      </div>
      {expanded && (
        <div className="ml-5 mt-1.5 p-2 bg-gray-50 rounded border border-gray-200">
          <div className="text-xs text-gray-600 font-mono">
            {isJson ? (
              <pre className="whitespace-pre-wrap break-words overflow-x-auto max-h-60 overflow-y-auto">
                {JSON.stringify(parsedData, null, 2)}
              </pre>
            ) : (
              <div className="whitespace-pre-wrap break-words max-h-60 overflow-y-auto">
                {toolResponse.responseData}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};

const AgentChatHistory: React.FC<AgentChatHistoryProps> = ({
  messages,
  displayAgentStatus = false,
  agentStatusText = "",
  agentStatusType,
  agentErrorText,
  agentErrorCode,
  plan,
}) => {
  // 滚动容器引用
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  // 是否允许自动滚动（用户是否接近底部）
  const [isNearBottom, setIsNearBottom] = useState(true);
  // 容错阈值（像素）
  const SCROLL_THRESHOLD = 20;
  // 上一次消息数量，用于检测新消息
  const prevMessagesLengthRef = useRef(messages.length);

  // 检查是否接近底部
  const checkIfNearBottom = useCallback(() => {
    const container = scrollContainerRef.current;
    if (!container) return false;

    const { scrollTop, clientHeight, scrollHeight } = container;
    const distanceFromBottom = scrollHeight - scrollTop - clientHeight;
    return distanceFromBottom <= SCROLL_THRESHOLD;
  }, []);

  // 滚动到底部
  const scrollToBottom = useCallback(() => {
    const container = scrollContainerRef.current;
    if (!container) return;

    // 使用 requestAnimationFrame 确保 DOM 更新完成后再滚动
    requestAnimationFrame(() => {
      if (container) {
        container.scrollTop = container.scrollHeight;
      }
    });
  }, []);

  // 处理滚动事件，实时更新是否接近底部的状态
  const handleScroll = useCallback(() => {
    const nearBottom = checkIfNearBottom();
    setIsNearBottom(nearBottom);
  }, [checkIfNearBottom]);

  // 监听滚动事件
  useEffect(() => {
    const container = scrollContainerRef.current;
    if (!container) return;

    // 初始化时检查是否在底部（延迟执行以避免同步 setState）
    const initTimer = setTimeout(() => {
      setIsNearBottom(checkIfNearBottom());
    }, 0);

    container.addEventListener("scroll", handleScroll, { passive: true });

    return () => {
      clearTimeout(initTimer);
      container.removeEventListener("scroll", handleScroll);
    };
  }, [handleScroll, checkIfNearBottom]);

  // 监听消息变化，决定是否自动滚动
  useEffect(() => {
    const hasNewMessage = messages.length > prevMessagesLengthRef.current;
    prevMessagesLengthRef.current = messages.length;

    // 如果有新消息且用户接近底部，则自动滚动
    if (hasNewMessage && isNearBottom) {
      scrollToBottom();
    }
  }, [messages, isNearBottom, scrollToBottom]);

  // 当 displayAgentStatus 变化时，如果用户接近底部，也自动滚动
  useEffect(() => {
    if (displayAgentStatus && isNearBottom) {
      scrollToBottom();
    }
  }, [displayAgentStatus, isNearBottom, scrollToBottom]);

  // 获取状态标签
  const getStatusLabel = () => {
    switch (agentStatusType) {
      case "AI_PLANNING":
        return "规划中";
      case "AI_THINKING":
        return "思考中";
      case "AI_EXECUTING":
        return "执行中";
      default:
        return "处理中";
    }
  };

  return (
    <div 
      ref={scrollContainerRef}
      className="flex-1 px-16 pt-4 overflow-y-scroll"
    >
      {plan && <PlanProgress plan={plan} />}
      {messages.map((message) => {
        return (
          <div className="mb-4" key={message.id}>
            {/* Assistant 消息 */}
            {message.role === "assistant" && (
              <Bubble
                content={
                  <div className="w-full">
                    {/* 工具调用展示 */}
                    {message.metadata?.toolCalls &&
                      message.metadata.toolCalls.length > 0 && (
                        <div className="mb-2 flex flex-wrap gap-2">
                          {message.metadata.toolCalls.map((toolCall) => (
                            <ToolCallDisplay key={toolCall.id} toolCall={toolCall} />
                          ))}
                        </div>
                      )}
                    {/* 消息内容 */}
                    {message.content && (
                      <div>
                        <XMarkdown
                          streaming={{ enableAnimation: false, hasNextChunk: true }}
                        >
                          {message.content}
                        </XMarkdown>
                      </div>
                    )}
                  </div>
                }
                placement="start"
              />
            )}

            {/* Tool 消息 - 简洁展示，不使用气泡 */}
            {message.role === "tool" && message.metadata?.toolResponse && (
              <div className="flex justify-start">
                <div className="max-w-[85%]">
                  <ToolResponseDisplay toolResponse={message.metadata.toolResponse} />
                </div>
              </div>
            )}

            {/* User 消息 */}
            {message.role === "user" && (
              <Bubble content={message.content} placement="end" />
            )}

            {/* System 消息 */}
            {message.role === "system" && (
              <div className="flex justify-center">
                <div className="px-3 py-1 bg-gray-100 text-gray-600 text-xs rounded-full flex items-center gap-1">
                  <RobotOutlined />
                  <span>{message.content}</span>
                </div>
              </div>
            )}
          </div>
        );
      })}
      {displayAgentStatus && (
        <div className="mb-3">
          <div
            className="animate-pulse"
            style={{
              animation: "pulse 0.8s cubic-bezier(0.4, 0, 0.6, 1) infinite",
              filter: "brightness(1.15)",
            }}
          >
            <Bubble
              content={
                <span className="flex items-center gap-2">
                  <span
                    className="font-semibold text-blue-600"
                    style={{
                      animation:
                        "pulse 0.7s cubic-bezier(0.4, 0, 0.6, 1) infinite",
                      textShadow:
                        "0 0 10px rgba(37, 99, 235, 1), 0 0 20px rgba(37, 99, 235, 0.8), 0 0 30px rgba(37, 99, 235, 0.5)",
                      filter: "brightness(1.3)",
                    }}
                  >
                    ✨ {getStatusLabel()}
                  </span>
                  <span className="text-gray-400">·</span>
                  <span className="text-gray-600">{agentStatusText}</span>
                </span>
              }
              placement="start"
            />
          </div>
        </div>
      )}
      {agentErrorText && (
        <div className="mb-3" role="alert">
          <Bubble
            content={
              <span className="flex items-center gap-2 font-medium text-red-600">
                <span>{agentErrorText}</span>
                {agentErrorCode && (
                  <Tag color="error">
                    {errorCodeLabels[agentErrorCode] ?? agentErrorCode}
                  </Tag>
                )}
              </span>
            }
            placement="start"
          />
        </div>
      )}
    </div>
  );
};

export default AgentChatHistory;
