package com.kama.mindagent.service;

import com.kama.mindagent.model.dto.ChatMessageDTO;
import com.kama.mindagent.model.request.ChatHistoryAnchor;
import com.kama.mindagent.model.request.CreateChatMessageRequest;
import com.kama.mindagent.model.request.UpdateChatMessageRequest;
import com.kama.mindagent.model.response.CreateChatMessageResponse;
import com.kama.mindagent.model.response.GetChatMessagesResponse;

import java.util.List;

public interface ChatMessageFacadeService {
    GetChatMessagesResponse getChatMessagesBySessionId(String sessionId);

    List<ChatMessageDTO> getChatMessagesBySessionIdRecently(String sessionId, int limit);

    default List<ChatMessageDTO> getChatMessagesBySessionIdRecently(
            String sessionId, int limit, ChatHistoryAnchor anchor
    ) {
        return getChatMessagesBySessionIdRecently(sessionId, limit);
    }

    CreateChatMessageResponse createChatMessage(CreateChatMessageRequest request);

    CreateChatMessageResponse createChatMessage(ChatMessageDTO chatMessageDTO);

    CreateChatMessageResponse agentCreateChatMessage(CreateChatMessageRequest request);

    CreateChatMessageResponse appendChatMessage(String chatMessageId, String appendContent);

    void deleteChatMessage(String chatMessageId);

    void updateChatMessage(String chatMessageId, UpdateChatMessageRequest request);
}
