package com.kama.mindagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.mindagent.agent.context.ContextBudgetPolicy;
import com.kama.mindagent.agent.support.AgentTestMessages;
import com.kama.mindagent.agent.support.InMemoryChatMessageFacadeService;
import com.kama.mindagent.agent.support.RecordingAgentEventStream;
import com.kama.mindagent.agent.support.ScriptedModelResponseGateway;
import com.kama.mindagent.converter.ChatMessageConverter;
import com.kama.mindagent.model.dto.ChatMessageDTO;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRuntimeContextIntegrationTest {

    @Test
    void boundsToolResultForNextModelButPersistsTheFullResult() {
        String original = "result-" + "x".repeat(80);
        ScriptedModelResponseGateway gateway = new ScriptedModelResponseGateway(List.of(
                AgentTestMessages.assistantToolCall("call-1", "databaseQuery", "{\"sql\":\"SELECT 1\"}"),
                AgentTestMessages.assistantText("最终回答")
        ));
        ToolCallingManager toolCallingManager = mock(ToolCallingManager.class);
        ToolExecutionResult executionResult = mock(ToolExecutionResult.class);
        when(executionResult.conversationHistory()).thenReturn(List.of(
                AgentTestMessages.toolResponse("call-1", "databaseQuery", original)
        ));
        when(toolCallingManager.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenReturn(executionResult);
        InMemoryChatMessageFacadeService messages = new InMemoryChatMessageFacadeService();

        AgentRuntime runtime = new AgentRuntime(
                "test-agent",
                "Test Agent",
                "context test",
                "system",
                gateway,
                20,
                List.of(),
                List.of(),
                List.of(),
                "session",
                new RecordingAgentEventStream(),
                messages,
                new ChatMessageConverter(new ObjectMapper()),
                toolCallingManager,
                com.kama.mindagent.agent.planning.PlanningMode.AUTO,
                null,
                AgentLoopPolicy.defaults(),
                new ContextBudgetPolicy(24, 10)
        );

        runtime.execute();

        assertThat(messages.messages()).extracting(ChatMessageDTO::getRole)
                .containsExactly(ChatMessageDTO.RoleType.ASSISTANT, ChatMessageDTO.RoleType.TOOL,
                        ChatMessageDTO.RoleType.ASSISTANT);
        assertThat(messages.messages().get(1).getContent()).isEqualTo(original);

        List<Message> secondPrompt = gateway.calls().get(1).prompt().getInstructions();
        ToolResponseMessage bounded = secondPrompt.stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(bounded.getResponses().get(0).responseData())
                .hasSizeLessThanOrEqualTo(24)
                .contains("truncated");
    }
}
