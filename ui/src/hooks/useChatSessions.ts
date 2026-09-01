import { useContext } from "react";
import { ChatSessionsContext } from "../contexts/ChatSessionsContextValue.ts";

export function useChatSessionsContext() {
  const context = useContext(ChatSessionsContext);
  if (context === undefined) {
    throw new Error(
      "useChatSessionsContext must be used within a ChatSessionsProvider"
    );
  }
  return context;
}

export function useChatSessions() {
  return useChatSessionsContext();
}
