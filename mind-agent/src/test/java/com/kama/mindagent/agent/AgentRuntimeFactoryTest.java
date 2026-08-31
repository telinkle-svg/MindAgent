package com.kama.mindagent.agent;

import com.kama.mindagent.config.ChatClientRegistry;
import com.kama.mindagent.converter.AgentConverter;
import com.kama.mindagent.converter.ChatMessageConverter;
import com.kama.mindagent.converter.KnowledgeBaseConverter;
import com.kama.mindagent.mapper.AgentMapper;
import com.kama.mindagent.mapper.KnowledgeBaseMapper;
import com.kama.mindagent.model.dto.AgentDTO;
import com.kama.mindagent.model.entity.Agent;
import com.kama.mindagent.service.AgentEventStream;
import com.kama.mindagent.service.AgentToolRegistry;
import com.kama.mindagent.service.ChatMessageFacadeService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRuntimeFactoryTest {

    @Test
    void doesNotKeepRequestScopedAgentConfiguration() {
        assertThat(Arrays.stream(AgentRuntimeFactory.class.getDeclaredFields())
                .map(Field::getType))
                .doesNotContain(AgentDTO.class);
    }

    @Test
    void createRuntimeLoadsMemoryUsingTheCurrentAgentsConfiguredWindow() throws Exception {
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

        when(agentMapper.selectById("agent-1")).thenReturn(agent);
        when(agentConverter.toDTO(agent)).thenReturn(config);
        when(chatClientRegistry.get("deepseek-chat")).thenReturn(mock(ChatClient.class));
        when(agentToolRegistry.listRequired()).thenReturn(List.of());
        when(chatMessageFacadeService.getChatMessagesBySessionIdRecently("session-1", 7))
                .thenReturn(List.of());

        factory.createRuntime("agent-1", "session-1");

        verify(chatMessageFacadeService)
                .getChatMessagesBySessionIdRecently("session-1", 7);
    }
}
