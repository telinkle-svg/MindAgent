package com.kama.mindagent.agent;

import com.kama.mindagent.agent.planning.PlanControlTool;
import com.kama.mindagent.agent.planning.PlanningMode;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRuntimeFactoryPlanningTest {

    @Test
    void createsOnePlanToolPerEnabledRunAndOmitsItWhenDisabled() throws Exception {
        ChatClientRegistry chatClientRegistry = mock(ChatClientRegistry.class);
        AgentEventStream eventStream = mock(AgentEventStream.class);
        AgentMapper agentMapper = mock(AgentMapper.class);
        AgentConverter agentConverter = mock(AgentConverter.class);
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBaseConverter knowledgeBaseConverter = mock(KnowledgeBaseConverter.class);
        AgentToolRegistry toolRegistry = mock(AgentToolRegistry.class);
        ChatMessageFacadeService messageService = mock(ChatMessageFacadeService.class);
        ChatMessageConverter messageConverter = mock(ChatMessageConverter.class);
        AgentRuntimeFactory factory = new AgentRuntimeFactory(
                chatClientRegistry,
                eventStream,
                agentMapper,
                agentConverter,
                knowledgeBaseMapper,
                knowledgeBaseConverter,
                toolRegistry,
                messageService,
                messageConverter
        );
        Agent agent = Agent.builder().id("agent-1").name("Agent").model("deepseek-chat").build();
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
        when(toolRegistry.listRequired()).thenReturn(List.of());
        when(messageService.getChatMessagesBySessionIdRecently("session-1", 7)).thenReturn(List.of());

        AgentRuntime first = factory.createRuntime(new AgentRunRequest(
                "agent-1", "session-1", "message-1", null, PlanningMode.AUTO, "question"
        ));
        AgentRuntime second = factory.createRuntime(new AgentRunRequest(
                "agent-1", "session-1", "message-2", null, PlanningMode.REQUIRED, "question 2"
        ));
        AgentRuntime disabled = factory.createRuntime(new AgentRunRequest(
                "agent-1", "session-1", "message-3", null, PlanningMode.DISABLED, "question 3"
        ));

        assertThat(first.planControlTool()).isInstanceOf(PlanControlTool.class);
        assertThat(second.planControlTool()).isInstanceOf(PlanControlTool.class);
        assertThat(first.planControlTool()).isNotSameAs(second.planControlTool());
        assertThat(disabled.planControlTool()).isNull();
    }
}
