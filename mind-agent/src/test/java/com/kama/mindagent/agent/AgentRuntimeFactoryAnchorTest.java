package com.kama.mindagent.agent;

import com.kama.mindagent.config.ChatClientRegistry;
import com.kama.mindagent.converter.AgentConverter;
import com.kama.mindagent.converter.ChatMessageConverter;
import com.kama.mindagent.converter.KnowledgeBaseConverter;
import com.kama.mindagent.mapper.AgentMapper;
import com.kama.mindagent.mapper.KnowledgeBaseMapper;
import com.kama.mindagent.model.dto.AgentDTO;
import com.kama.mindagent.model.entity.Agent;
import com.kama.mindagent.model.request.ChatHistoryAnchor;
import com.kama.mindagent.service.AgentEventStream;
import com.kama.mindagent.service.AgentToolRegistry;
import com.kama.mindagent.service.ChatMessageFacadeService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRuntimeFactoryAnchorTest {

    @Test
    void anchoredRunDoesNotLoadMessagesCreatedAfterTriggeringUserMessage() throws Exception {
        ChatClientRegistry chatClientRegistry = mock(ChatClientRegistry.class);
        AgentEventStream agentEventStream = mock(AgentEventStream.class);
        AgentMapper agentMapper = mock(AgentMapper.class);
        AgentConverter agentConverter = mock(AgentConverter.class);
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBaseConverter knowledgeBaseConverter = mock(KnowledgeBaseConverter.class);
        AgentToolRegistry agentToolRegistry = mock(AgentToolRegistry.class);
        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        ChatMessageConverter chatMessageConverter = mock(ChatMessageConverter.class);
        AgentRuntimeFactory factory = new AgentRuntimeFactory(
                chatClientRegistry,
                agentEventStream,
                agentMapper,
                agentConverter,
                knowledgeBaseMapper,
                knowledgeBaseConverter,
                agentToolRegistry,
                chatMessageFacadeService,
                chatMessageConverter
        );
        Agent agent = Agent.builder()
                .id("agent-1")
                .name("Agent 1")
                .model("deepseek-chat")
                .build();
        AgentDTO config = AgentDTO.builder()
                .id("agent-1")
                .model(AgentDTO.ModelType.DEEPSEEK_CHAT)
                .allowedTools(List.of())
                .allowedKbs(List.of())
                .chatOptions(AgentDTO.ChatOptions.builder().messageLength(7).build())
                .build();
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 31, 12, 0);
        AgentRunRequest request = new AgentRunRequest(
                "agent-1",
                "session-1",
                "11111111-1111-1111-1111-111111111111",
                createdAt,
                com.kama.mindagent.agent.planning.PlanningMode.AUTO,
                "当前问题"
        );
        ChatHistoryAnchor anchor = new ChatHistoryAnchor(request.userMessageId(), createdAt);

        when(agentMapper.selectById("agent-1")).thenReturn(agent);
        when(agentConverter.toDTO(agent)).thenReturn(config);
        when(chatClientRegistry.get("deepseek-chat")).thenReturn(mock(ChatClient.class));
        when(agentToolRegistry.listRequired()).thenReturn(List.of());
        when(chatMessageFacadeService.getChatMessagesBySessionIdRecently(
                "session-1", 7, anchor)).thenReturn(List.of());

        factory.createRuntime(request);

        verify(chatMessageFacadeService).getChatMessagesBySessionIdRecently(
                "session-1", 7, anchor);
        verify(chatMessageFacadeService, never()).getChatMessagesBySessionIdRecently("session-1", 7);
    }
}
