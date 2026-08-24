package com.kama.mindagent.service;

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
}
