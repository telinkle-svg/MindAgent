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
  | "MAX_STEPS_EXCEEDED";

export type AgentEventType =
  | "AI_GENERATED_CONTENT"
  | "AI_PLANNING"
  | "AI_THINKING"
  | "AI_EXECUTING"
  | "AI_ERROR"
  | "AI_DONE";

export interface AgentEventPayload {
  message?: ChatMessageVO;
  statusText?: string;
  done?: boolean;
  errorCode?: AgentErrorCode;
}

export interface AgentEventMetadata {
  chatMessageId?: string;
}

export interface AgentEvent {
  type: AgentEventType;
  payload: AgentEventPayload;
  metadata?: AgentEventMetadata;
}
