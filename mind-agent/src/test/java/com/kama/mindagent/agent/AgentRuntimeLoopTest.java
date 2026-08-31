package com.kama.mindagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.mindagent.agent.support.AgentTestMessages;
import com.kama.mindagent.agent.support.InMemoryChatMessageFacadeService;
import com.kama.mindagent.agent.support.RecordingAgentEventStream;
import com.kama.mindagent.agent.support.ScriptedModelResponseGateway;
import com.kama.mindagent.converter.ChatMessageConverter;
import com.kama.mindagent.message.AgentEvent;
import com.kama.mindagent.model.dto.ChatMessageDTO;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRuntimeLoopTest {

    @Test
    void run_withoutToolCall_finishesAfterOneModelCall() {
        ScriptedModelResponseGateway gateway = new ScriptedModelResponseGateway(List.of(
                AgentTestMessages.assistantText("普通回答")
        ));
        ToolCallingManager toolCallingManager = mock(ToolCallingManager.class);
        InMemoryChatMessageFacadeService messages = new InMemoryChatMessageFacadeService();
        RecordingAgentEventStream sse = new RecordingAgentEventStream();

        createAgent(gateway, toolCallingManager, messages, sse).execute();

        assertThat(gateway.calls()).hasSize(1);
        verify(toolCallingManager, never()).executeToolCalls(any(Prompt.class), any(ChatResponse.class));
        assertThat(messages.messages()).extracting(ChatMessageDTO::getRole)
                .containsExactly(ChatMessageDTO.RoleType.ASSISTANT);
        assertThat(sse.sentEvents()).extracting(AgentEvent::getType)
                .containsExactly(
                        AgentEvent.Type.AI_PLANNING,
                        AgentEvent.Type.AI_THINKING,
                        AgentEvent.Type.AI_GENERATED_CONTENT,
                        AgentEvent.Type.AI_DONE
                );
    }

    @Test
    void run_withOneToolCall_continuesToFinalAnswer() {
        ScriptedModelResponseGateway gateway = new ScriptedModelResponseGateway(List.of(
                AgentTestMessages.assistantToolCall("call-1", "databaseQuery", "{\"sql\":\"SELECT 1\"}"),
                AgentTestMessages.assistantText("工具结果的最终回答")
        ));
        ToolCallingManager toolCallingManager = mock(ToolCallingManager.class);
        ToolExecutionResult toolExecutionResult = toolExecutionResult(
                AgentTestMessages.toolResponse("call-1", "databaseQuery", "查询结果: 1")
        );
        when(toolCallingManager.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenReturn(toolExecutionResult);
        InMemoryChatMessageFacadeService messages = new InMemoryChatMessageFacadeService();
        RecordingAgentEventStream sse = new RecordingAgentEventStream();

        createAgent(gateway, toolCallingManager, messages, sse).execute();

        assertThat(gateway.calls()).hasSize(2);
        verify(toolCallingManager).executeToolCalls(any(Prompt.class), any(ChatResponse.class));
        assertThat(messages.messages()).extracting(ChatMessageDTO::getRole)
                .containsExactly(
                        ChatMessageDTO.RoleType.ASSISTANT,
                        ChatMessageDTO.RoleType.TOOL,
                        ChatMessageDTO.RoleType.ASSISTANT
                );
        assertThat(sse.sentEvents()).extracting(AgentEvent::getType)
                .containsExactly(
                        AgentEvent.Type.AI_PLANNING,
                        AgentEvent.Type.AI_THINKING,
                        AgentEvent.Type.AI_GENERATED_CONTENT,
                        AgentEvent.Type.AI_EXECUTING,
                        AgentEvent.Type.AI_GENERATED_CONTENT,
                        AgentEvent.Type.AI_THINKING,
                        AgentEvent.Type.AI_GENERATED_CONTENT,
                        AgentEvent.Type.AI_DONE
                );
    }

    @Test
    void run_withTerminateTool_stopsWithoutSecondModelCall() {
        ScriptedModelResponseGateway gateway = new ScriptedModelResponseGateway(List.of(
                AgentTestMessages.assistantToolCallWithText(
                        "任务已完成",
                        "call-terminate",
                        "terminate",
                        "{}"
                )
        ));
        ToolCallingManager toolCallingManager = mock(ToolCallingManager.class);
        ToolExecutionResult toolExecutionResult = toolExecutionResult(
                AgentTestMessages.toolResponse("call-terminate", "terminate", "Done")
        );
        when(toolCallingManager.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenReturn(toolExecutionResult);
        InMemoryChatMessageFacadeService messages = new InMemoryChatMessageFacadeService();
        RecordingAgentEventStream sse = new RecordingAgentEventStream();

        createAgent(gateway, toolCallingManager, messages, sse).execute();

        assertThat(gateway.calls()).hasSize(1);
        assertThat(messages.messages()).extracting(ChatMessageDTO::getRole)
                .containsExactly(ChatMessageDTO.RoleType.ASSISTANT, ChatMessageDTO.RoleType.TOOL);
        assertThat(sse.sentEvents()).extracting(AgentEvent::getType)
                .last().isEqualTo(AgentEvent.Type.AI_DONE);
    }

    @Test
    void run_withBlankTerminate_rejectsBeforeToolExecutionOrPersistence() {
        ScriptedModelResponseGateway gateway = new ScriptedModelResponseGateway(List.of(
                AgentTestMessages.assistantToolCall("call-terminate", "terminate", "{}")
        ));
        ToolCallingManager toolCallingManager = mock(ToolCallingManager.class);
        InMemoryChatMessageFacadeService messages = new InMemoryChatMessageFacadeService();
        RecordingAgentEventStream sse = new RecordingAgentEventStream();

        assertFailure(
                createAgent(gateway, toolCallingManager, messages, sse),
                sse,
                AgentFailureCode.AGENT_PROTOCOL_ERROR
        );

        verify(toolCallingManager, never()).executeToolCalls(any(Prompt.class), any(ChatResponse.class));
        assertThat(messages.messages()).isEmpty();
        assertThat(sse.sentEvents()).extracting(AgentEvent::getType)
                .containsExactly(
                        AgentEvent.Type.AI_PLANNING,
                        AgentEvent.Type.AI_THINKING,
                        AgentEvent.Type.AI_ERROR
                );
    }

    @Test
    void run_withMixedTerminateTools_rejectsBeforeToolExecutionOrPersistence() {
        AssistantMessage.ToolCall terminate = new AssistantMessage.ToolCall(
                "call-terminate", "function", "terminate", "{}"
        );
        AssistantMessage.ToolCall database = new AssistantMessage.ToolCall(
                "call-database", "function", "databaseQuery", "{\"sql\":\"SELECT 1\"}"
        );
        ScriptedModelResponseGateway gateway = new ScriptedModelResponseGateway(List.of(
                AgentTestMessages.assistantToolCalls("任务已完成", List.of(terminate, database))
        ));
        ToolCallingManager toolCallingManager = mock(ToolCallingManager.class);
        InMemoryChatMessageFacadeService messages = new InMemoryChatMessageFacadeService();
        RecordingAgentEventStream sse = new RecordingAgentEventStream();

        assertFailure(
                createAgent(gateway, toolCallingManager, messages, sse),
                sse,
                AgentFailureCode.AGENT_PROTOCOL_ERROR
        );

        verify(toolCallingManager, never()).executeToolCalls(any(Prompt.class), any(ChatResponse.class));
        assertThat(messages.messages()).isEmpty();
    }

    @Test
    void run_withBlankFinalAnswerAfterTool_rejectsWithoutPersistingBlankAnswer() {
        ScriptedModelResponseGateway gateway = new ScriptedModelResponseGateway(List.of(
                AgentTestMessages.assistantToolCall("call-1", "databaseQuery", "{\"sql\":\"SELECT 1\"}"),
                AgentTestMessages.assistantText("   ")
        ));
        ToolCallingManager toolCallingManager = mock(ToolCallingManager.class);
        ToolExecutionResult toolExecutionResult = toolExecutionResult(
                AgentTestMessages.toolResponse("call-1", "databaseQuery", "查询结果: 1")
        );
        when(toolCallingManager.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenReturn(toolExecutionResult);
        InMemoryChatMessageFacadeService messages = new InMemoryChatMessageFacadeService();
        RecordingAgentEventStream sse = new RecordingAgentEventStream();

        assertFailure(
                createAgent(gateway, toolCallingManager, messages, sse),
                sse,
                AgentFailureCode.FINAL_ANSWER_MISSING
        );

        assertThat(messages.messages()).extracting(ChatMessageDTO::getRole)
                .containsExactly(ChatMessageDTO.RoleType.ASSISTANT, ChatMessageDTO.RoleType.TOOL);
    }

    @Test
    void run_withBlankInitialAnswer_rejectsAsMissingFinalAnswer() {
        ScriptedModelResponseGateway gateway = new ScriptedModelResponseGateway(List.of(
                AgentTestMessages.assistantText("\t")
        ));
        ToolCallingManager toolCallingManager = mock(ToolCallingManager.class);
        InMemoryChatMessageFacadeService messages = new InMemoryChatMessageFacadeService();
        RecordingAgentEventStream sse = new RecordingAgentEventStream();

        assertFailure(
                createAgent(gateway, toolCallingManager, messages, sse),
                sse,
                AgentFailureCode.FINAL_ANSWER_MISSING
        );

        assertThat(messages.messages()).isEmpty();
    }

    @Test
    void run_whenModelCallFails_emitsSafeModelErrorOnly() {
        ModelResponseGateway gateway = mock(ModelResponseGateway.class);
        when(gateway.request(any(Prompt.class), any(String.class), any()))
                .thenThrow(new IllegalStateException("model internals"));
        ToolCallingManager toolCallingManager = mock(ToolCallingManager.class);
        InMemoryChatMessageFacadeService messages = new InMemoryChatMessageFacadeService();
        RecordingAgentEventStream sse = new RecordingAgentEventStream();

        assertFailure(
                createAgent(gateway, toolCallingManager, messages, sse),
                sse,
                AgentFailureCode.MODEL_CALL_FAILED
        );

        assertThat(messages.messages()).isEmpty();
    }

    @Test
    void run_withContinuousToolCalls_stopsAtTwentySteps() {
        List<ChatResponse> responses = IntStream.range(0, 20)
                .mapToObj(step -> AgentTestMessages.assistantToolCall(
                        "call-" + step,
                        "databaseQuery",
                        "{\"sql\":\"SELECT " + step + "\"}"
                ))
                .toList();
        ScriptedModelResponseGateway gateway = new ScriptedModelResponseGateway(responses);
        ToolCallingManager toolCallingManager = mock(ToolCallingManager.class);
        ToolExecutionResult toolExecutionResult = toolExecutionResult(
                AgentTestMessages.toolResponse("call", "databaseQuery", "查询结果")
        );
        when(toolCallingManager.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenReturn(toolExecutionResult);
        InMemoryChatMessageFacadeService messages = new InMemoryChatMessageFacadeService();
        RecordingAgentEventStream sse = new RecordingAgentEventStream();

        assertFailure(
                createAgent(gateway, toolCallingManager, messages, sse),
                sse,
                AgentFailureCode.MAX_STEPS_EXCEEDED
        );

        assertThat(gateway.calls()).hasSize(20);
        assertThat(messages.messages()).hasSize(40);
        assertThat(sse.sentEvents()).extracting(AgentEvent::getType)
                .contains(AgentEvent.Type.AI_ERROR)
                .doesNotContain(AgentEvent.Type.AI_DONE);
    }

    @Test
    void run_whenToolExecutionFails_emitsErrorOnly() {
        ScriptedModelResponseGateway gateway = new ScriptedModelResponseGateway(List.of(
                AgentTestMessages.assistantToolCall("call-failure", "databaseQuery", "{\"sql\":\"SELECT 1\"}")
        ));
        ToolCallingManager toolCallingManager = mock(ToolCallingManager.class);
        when(toolCallingManager.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenThrow(new IllegalStateException("tool failed"));
        InMemoryChatMessageFacadeService messages = new InMemoryChatMessageFacadeService();
        RecordingAgentEventStream sse = new RecordingAgentEventStream();

        assertFailure(
                createAgent(gateway, toolCallingManager, messages, sse),
                sse,
                AgentFailureCode.TOOL_EXECUTION_FAILED
        );

        assertThat(messages.messages()).extracting(ChatMessageDTO::getRole)
                .containsExactly(ChatMessageDTO.RoleType.ASSISTANT);
        assertThat(sse.sentEvents()).extracting(AgentEvent::getType)
                .contains(AgentEvent.Type.AI_ERROR)
                .doesNotContain(AgentEvent.Type.AI_DONE);
    }

    private void assertFailure(
            AgentRuntime agent,
            RecordingAgentEventStream sse,
            AgentFailureCode expectedErrorCode
    ) {
        Throwable thrown = catchThrowable(agent::execute);

        assertThat(thrown).isInstanceOf(AgentExecutionException.class);
        AgentExecutionException agentException = (AgentExecutionException) thrown;
        assertThat(agentException.getErrorCode()).isEqualTo(expectedErrorCode);

        List<AgentEvent> sentEvents = sse.sentEvents();
        assertThat(sentEvents).extracting(AgentEvent::getType)
                .contains(AgentEvent.Type.AI_ERROR)
                .doesNotContain(AgentEvent.Type.AI_DONE);
        AgentEvent errorMessage = sentEvents.stream()
                .filter(event -> event.getType() == AgentEvent.Type.AI_ERROR)
                .findFirst()
                .orElseThrow();
        assertThat(errorMessage.getPayload().getStatusText()).isEqualTo("执行失败，请重试");
        assertThat(errorMessage.getPayload().getErrorCode()).isEqualTo(expectedErrorCode.name());
    }

    private AgentRuntime createAgent(
            ModelResponseGateway gateway,
            ToolCallingManager toolCallingManager,
            InMemoryChatMessageFacadeService messages,
            RecordingAgentEventStream sse
    ) {
        return new AgentRuntime(
                "test-agent",
                "Test Agent",
                "Agent Loop test fixture",
                "你是测试 Agent。",
                gateway,
                20,
                List.of(),
                List.of(),
                List.of(),
                "test-session",
                sse,
                messages,
                new ChatMessageConverter(new ObjectMapper()),
                toolCallingManager
        );
    }

    private ToolExecutionResult toolExecutionResult(ToolResponseMessage toolResponseMessage) {
        ToolExecutionResult result = mock(ToolExecutionResult.class);
        when(result.conversationHistory()).thenReturn(List.of(toolResponseMessage));
        return result;
    }
}
