package com.kama.mindagent.service;

import com.kama.mindagent.agent.context.ConversationSummary;
import com.kama.mindagent.model.dto.ChatSessionDTO;
import com.kama.mindagent.model.request.CreateChatSessionRequest;
import com.kama.mindagent.model.request.UpdateChatSessionRequest;
import com.kama.mindagent.model.response.CreateChatSessionResponse;
import com.kama.mindagent.model.response.GetChatSessionResponse;
import com.kama.mindagent.model.response.GetChatSessionsResponse;

public interface ChatSessionFacadeService {
    GetChatSessionsResponse getChatSessions();

    GetChatSessionResponse getChatSession(String chatSessionId);

    GetChatSessionsResponse getChatSessionsByAgentId(String agentId);

    CreateChatSessionResponse createChatSession(CreateChatSessionRequest request);

    void deleteChatSession(String chatSessionId);

    void updateChatSession(String chatSessionId, UpdateChatSessionRequest request);

    /**
     * Reads optional context metadata without exposing JSONB details to callers.
     */
    default ChatSessionDTO.MetaData getChatSessionMetadata(String chatSessionId) {
        return null;
    }

    /**
     * Atomically replaces the persisted incremental summary fields.
     */
    default void updateChatSessionSummary(String chatSessionId, ConversationSummary summary) {
    }
}
