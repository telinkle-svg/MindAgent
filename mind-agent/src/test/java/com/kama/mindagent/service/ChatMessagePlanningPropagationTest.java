package com.kama.mindagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.mindagent.converter.ChatMessageConverter;
import com.kama.mindagent.event.ChatEvent;
import com.kama.mindagent.mapper.AgentMapper;
import com.kama.mindagent.mapper.ChatMessageMapper;
import com.kama.mindagent.mapper.ChatSessionMapper;
import com.kama.mindagent.model.dto.ChatMessageDTO;
import com.kama.mindagent.model.entity.Agent;
import com.kama.mindagent.model.entity.ChatMessage;
import com.kama.mindagent.model.entity.ChatSession;
import com.kama.mindagent.model.request.CreateChatMessageRequest;
import com.kama.mindagent.agent.planning.PlanningMode;
import com.kama.mindagent.service.impl.ChatMessageFacadeServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMessagePlanningPropagationTest {

    @Test
    void missingPlanningModeIsNormalizedAndEventCarriesInsertedAnchor() {
        ChatMessageMapper mapper = mock(ChatMessageMapper.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        AgentMapper agentMapper = mock(AgentMapper.class);
        ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
        ChatMessageFacadeServiceImpl service = new ChatMessageFacadeServiceImpl(
                mapper,
                new ChatMessageConverter(new ObjectMapper()),
                publisher,
                agentMapper,
                sessionMapper
        );
        when(agentMapper.selectById("agent-1")).thenReturn(Agent.builder().id("agent-1").build());
        when(sessionMapper.selectById("session-1"))
                .thenReturn(ChatSession.builder().id("session-1").agentId("agent-1").build());
        doAnswer(invocation -> {
            ChatMessage entity = invocation.getArgument(0);
            entity.setId("message-1");
            return 1;
        }).when(mapper).insert(any(ChatMessage.class));

        service.createChatMessage(CreateChatMessageRequest.builder()
                .agentId("agent-1")
                .sessionId("session-1")
                .role(ChatMessageDTO.RoleType.USER)
                .content("question")
                .build());

        ArgumentCaptor<ChatEvent> captor = ArgumentCaptor.forClass(ChatEvent.class);
        verify(publisher).publishEvent(captor.capture());
        ChatEvent event = captor.getValue();
        assertThat(event.getPlanningMode()).isEqualTo(PlanningMode.AUTO);
        assertThat(event.getUserMessageId()).isEqualTo("message-1");
        assertThat(event.getUserMessageCreatedAt()).isNotNull();
    }
}
