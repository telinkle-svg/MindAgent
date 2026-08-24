package com.kama.mindagent.agent.support;

import com.kama.mindagent.model.dto.ChatMessageDTO;
import com.kama.mindagent.model.request.CreateChatMessageRequest;
import com.kama.mindagent.model.request.UpdateChatMessageRequest;
import com.kama.mindagent.model.response.CreateChatMessageResponse;
import com.kama.mindagent.model.response.GetChatMessagesResponse;
import com.kama.mindagent.service.ChatMessageFacadeService;

import java.util.ArrayList;
import java.util.List;

public final class InMemoryChatMessageFacadeService implements ChatMessageFacadeService {

    private final List<ChatMessageDTO> messages = new ArrayList<>();

    @Override
    public CreateChatMessageResponse createChatMessage(ChatMessageDTO chatMessageDTO) {
        String id = "test-message-" + (messages.size() + 1);
        chatMessageDTO.setId(id);
        messages.add(chatMessageDTO);
        return CreateChatMessageResponse.builder()
                .chatMessageId(id)
                .build();
    }

    @Override
    public GetChatMessagesResponse getChatMessagesBySessionId(String sessionId) {
        throw new UnsupportedOperationException("Not used by AgentRuntime loop tests");
    }

    @Override
    public List<ChatMessageDTO> getChatMessagesBySessionIdRecently(String sessionId, int limit) {
        throw new UnsupportedOperationException("Not used by AgentRuntime loop tests");
    }

    @Override
    public CreateChatMessageResponse createChatMessage(CreateChatMessageRequest request) {
        throw new UnsupportedOperationException("Not used by AgentRuntime loop tests");
    }

    @Override
    public CreateChatMessageResponse agentCreateChatMessage(CreateChatMessageRequest request) {
        throw new UnsupportedOperationException("Not used by AgentRuntime loop tests");
    }

    @Override
    public CreateChatMessageResponse appendChatMessage(String chatMessageId, String appendContent) {
        throw new UnsupportedOperationException("Not used by AgentRuntime loop tests");
    }

    @Override
    public void deleteChatMessage(String chatMessageId) {
        throw new UnsupportedOperationException("Not used by AgentRuntime loop tests");
    }

    @Override
    public void updateChatMessage(String chatMessageId, UpdateChatMessageRequest request) {
        throw new UnsupportedOperationException("Not used by AgentRuntime loop tests");
    }

    public List<ChatMessageDTO> messages() {
        return List.copyOf(messages);
    }
}
