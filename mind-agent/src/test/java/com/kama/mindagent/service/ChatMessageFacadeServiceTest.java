package com.kama.mindagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.mindagent.converter.ChatMessageConverter;
import com.kama.mindagent.event.ChatEvent;
import com.kama.mindagent.exception.BizException;
import com.kama.mindagent.mapper.AgentMapper;
import com.kama.mindagent.mapper.ChatMessageMapper;
import com.kama.mindagent.mapper.ChatSessionMapper;
import com.kama.mindagent.model.dto.ChatMessageDTO;
import com.kama.mindagent.model.entity.Agent;
import com.kama.mindagent.model.entity.ChatMessage;
import com.kama.mindagent.model.entity.ChatSession;
import com.kama.mindagent.model.request.CreateChatMessageRequest;
import com.kama.mindagent.model.request.UpdateChatMessageRequest;
import com.kama.mindagent.model.response.CreateChatMessageResponse;
import com.kama.mindagent.service.impl.ChatMessageFacadeServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMessageFacadeServiceTest {

    private final ChatMessageMapper mapper = mock(ChatMessageMapper.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
    private final ChatMessageFacadeServiceImpl service = new ChatMessageFacadeServiceImpl(
            mapper,
            new ChatMessageConverter(new ObjectMapper()),
            publisher,
            agentMapper,
            chatSessionMapper
    );

    @Test
    void createFromDto_persistsAndReturnsGeneratedIdWithoutPublishingEvent() {
        doAnswer(invocation -> {
            ChatMessage entity = invocation.getArgument(0);
            entity.setId("message-1");
            return 1;
        }).when(mapper).insert(any(ChatMessage.class));

        CreateChatMessageResponse response = service.createChatMessage(ChatMessageDTO.builder()
                .sessionId("session-1")
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .content("assistant content")
                .build());

        assertThat(response.getChatMessageId()).isEqualTo("message-1");
        verify(mapper).insert(any(ChatMessage.class));
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void createFromDto_reportsPersistenceFailureAsInternalServerError() {
        when(mapper.insert(any(ChatMessage.class))).thenReturn(0);

        BizException exception = catchThrowableOfType(
                () -> service.createChatMessage(ChatMessageDTO.builder()
                        .sessionId("session-1")
                        .role(ChatMessageDTO.RoleType.ASSISTANT)
                        .content("assistant content")
                        .build()),
                BizException.class
        );

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(exception).hasMessage("创建聊天消息失败");
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void createFromRequest_publishesChatEventWithUserContext() {
        when(agentMapper.selectById("agent-1")).thenReturn(Agent.builder().id("agent-1").build());
        when(chatSessionMapper.selectById("session-1"))
                .thenReturn(ChatSession.builder().id("session-1").agentId("agent-1").build());
        doAnswer(invocation -> {
            ChatMessage entity = invocation.getArgument(0);
            entity.setId("message-2");
            return 1;
        }).when(mapper).insert(any(ChatMessage.class));

        service.createChatMessage(CreateChatMessageRequest.builder()
                .agentId("agent-1")
                .sessionId("session-1")
                .role(ChatMessageDTO.RoleType.USER)
                .content("user question")
                .build());

        ArgumentCaptor<ChatEvent> eventCaptor = ArgumentCaptor.forClass(ChatEvent.class);
        verify(publisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getAgentId()).isEqualTo("agent-1");
        assertThat(eventCaptor.getValue().getSessionId()).isEqualTo("session-1");
        assertThat(eventCaptor.getValue().getUserInput()).isEqualTo("user question");
    }

    @Test
    void update_preservesIdentityAndRoleWhileChangingContent() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 21, 20, 0);
        when(mapper.selectById("message-3")).thenReturn(ChatMessage.builder()
                .id("message-3")
                .sessionId("session-3")
                .role("user")
                .content("old content")
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build());
        when(mapper.updateById(any(ChatMessage.class))).thenReturn(1);

        UpdateChatMessageRequest request = new UpdateChatMessageRequest();
        request.setContent("new content");
        service.updateChatMessage("message-3", request);

        ArgumentCaptor<ChatMessage> entityCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(mapper).updateById(entityCaptor.capture());
        ChatMessage updated = entityCaptor.getValue();
        assertThat(updated.getId()).isEqualTo("message-3");
        assertThat(updated.getSessionId()).isEqualTo("session-3");
        assertThat(updated.getRole()).isEqualTo("user");
        assertThat(updated.getContent()).isEqualTo("new content");
        assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
        assertThat(updated.getUpdatedAt()).isAfter(createdAt);
    }

    @Test
    void deleteMissingMessage_raisesBusinessException() {
        when(mapper.selectById("missing-message")).thenReturn(null);

        BizException exception = catchThrowableOfType(
                () -> service.deleteChatMessage("missing-message"),
                BizException.class
        );

        assertThat(exception).hasMessage("聊天消息不存在: missing-message");
        assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getMessagesForMissingSession_raisesNotFoundBeforeQueryingMessages() {
        when(chatSessionMapper.selectById("missing-session")).thenReturn(null);

        BizException exception = catchThrowableOfType(
                () -> service.getChatMessagesBySessionId("missing-session"),
                BizException.class
        );

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exception).hasMessage("聊天会话不存在: missing-session");
        verify(mapper, never()).selectBySessionId(any());
    }
}
