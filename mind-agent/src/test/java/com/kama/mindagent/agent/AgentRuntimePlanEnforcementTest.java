package com.kama.mindagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.mindagent.agent.planning.PlanControlTool;
import com.kama.mindagent.agent.planning.PlanningMode;
import com.kama.mindagent.agent.support.AgentTestMessages;
import com.kama.mindagent.agent.support.InMemoryChatMessageFacadeService;
import com.kama.mindagent.agent.support.RecordingAgentEventStream;
import com.kama.mindagent.agent.support.ScriptedModelResponseGateway;
import com.kama.mindagent.converter.ChatMessageConverter;
import com.kama.mindagent.message.AgentEvent;
import com.kama.mindagent.model.dto.ChatMessageDTO;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AgentRuntimePlanEnforcementTest {

    private static final String CREATE_ARGUMENTS = "{\"command\":{\"action\":\"CREATE\","
            + "\"version\":1,\"steps\":[{\"id\":\"step-1\",\"title\":\"查找资料\","
            + "\"dependsOn\":[],\"status\":\"IN_PROGRESS\","
            + "\"successCriteria\":\"找到可引用资料\"}],"
            + "\"currentTaskId\":\"step-1\",\"observation\":\"开始执行\"}}";

    @Test
    void requiredModeRejectsOrdinaryToolBeforePlanCreation() {
        ScriptedModelResponseGateway gateway = new ScriptedModelResponseGateway(List.of(
                AgentTestMessages.assistantToolCall("call-1", "databaseQuery", "{\"sql\":\"SELECT 1\"}")
        ));
        ToolCallingManager manager = mock(ToolCallingManager.class);
        RecordingAgentEventStream sse = new RecordingAgentEventStream();
        AgentRuntime runtime = createAgent(
                gateway, manager, sse, PlanningMode.REQUIRED, new PlanControlTool(), AgentLoopPolicy.defaults());

        assertFailure(runtime, sse, AgentFailureCode.PLAN_REQUIRED);

        verify(manager, never()).executeToolCalls(any(Prompt.class), any(ChatResponse.class));
        assertThat(sse.sentEvents()).extracting(AgentEvent::getType)
                .doesNotContain(AgentEvent.Type.AI_DONE);
    }

    @Test
    void disabledModeRejectsReservedPlanCallBeforeDispatch() {
        ScriptedModelResponseGateway gateway = new ScriptedModelResponseGateway(List.of(
                AgentTestMessages.assistantToolCall("plan-1", PlanControlTool.TOOL_NAME, CREATE_ARGUMENTS)
        ));
        ToolCallingManager manager = mock(ToolCallingManager.class);
        RecordingAgentEventStream sse = new RecordingAgentEventStream();
        AgentRuntime runtime = createAgent(
                gateway, manager, sse, PlanningMode.DISABLED, null, AgentLoopPolicy.defaults());

        assertFailure(runtime, sse, AgentFailureCode.PLAN_DISABLED);

        verify(manager, never()).executeToolCalls(any(Prompt.class), any(ChatResponse.class));
    }

    @Test
    void mixedPlanAndOrdinaryCallsAreRejectedWithoutSideEffects() {
        AssistantMessage.ToolCall plan = new AssistantMessage.ToolCall(
                "plan-1", "function", PlanControlTool.TOOL_NAME, CREATE_ARGUMENTS);
        AssistantMessage.ToolCall ordinary = new AssistantMessage.ToolCall(
                "tool-1", "function", "databaseQuery", "{\"sql\":\"SELECT 1\"}");
        ScriptedModelResponseGateway gateway = new ScriptedModelResponseGateway(List.of(
                AgentTestMessages.assistantToolCalls("", List.of(plan, ordinary))
        ));
        ToolCallingManager manager = mock(ToolCallingManager.class);
        RecordingAgentEventStream sse = new RecordingAgentEventStream();
        AgentRuntime runtime = createAgent(
                gateway, manager, sse, PlanningMode.AUTO, new PlanControlTool(), AgentLoopPolicy.defaults());

        assertFailure(runtime, sse, AgentFailureCode.PLAN_PROTOCOL_ERROR);

        verify(manager, never()).executeToolCalls(any(Prompt.class), any(ChatResponse.class));
    }

    @Test
    void acceptedPlanEmitsBoundedPlanEventAndAllowsFinalAnswer() {
        ScriptedModelResponseGateway gateway = new ScriptedModelResponseGateway(List.of(
                AgentTestMessages.assistantToolCall("plan-1", PlanControlTool.TOOL_NAME, CREATE_ARGUMENTS),
                AgentTestMessages.assistantText("资料已找到")
        ));
        ToolCallingManager manager = mock(ToolCallingManager.class);
        RecordingAgentEventStream sse = new RecordingAgentEventStream();
        InMemoryChatMessageFacadeService messages = new InMemoryChatMessageFacadeService();
        AgentRuntime runtime = createAgent(
                gateway, manager, sse, PlanningMode.AUTO, new PlanControlTool(), AgentLoopPolicy.defaults(), messages);

        runtime.execute();

        assertThat(gateway.calls()).hasSize(2);
        assertThat(messages.messages()).extracting(ChatMessageDTO::getRole)
                .containsExactly(ChatMessageDTO.RoleType.ASSISTANT,
                        ChatMessageDTO.RoleType.TOOL,
                        ChatMessageDTO.RoleType.ASSISTANT);
        AgentEvent planEvent = sse.sentEvents().stream()
                .filter(event -> event.getType() == AgentEvent.Type.PLAN_CREATED)
                .findFirst()
                .orElseThrow();
        assertThat(planEvent.getPayload().getPlan()).isNotNull();
        assertThat(planEvent.getPayload().getPlan().steps()).hasSize(1);
        assertThat(sse.sentEvents()).extracting(AgentEvent::getType)
                .last().isEqualTo(AgentEvent.Type.AI_DONE);
    }

    @Test
    void planRevisionBudgetStopsBeforeApplyingExcessRevision() {
        String updateArguments = "{\"command\":{\"action\":\"UPDATE\","
                + "\"planId\":\"plan-does-not-match\",\"version\":2,"
                + "\"steps\":[{\"id\":\"step-1\",\"title\":\"查找资料\","
                + "\"dependsOn\":[],\"status\":\"COMPLETED\","
                + "\"successCriteria\":\"找到可引用资料\"}],"
                + "\"currentTaskId\":\"step-1\"}}";
        ScriptedModelResponseGateway gateway = new ScriptedModelResponseGateway(List.of(
                AgentTestMessages.assistantToolCall("plan-1", PlanControlTool.TOOL_NAME, CREATE_ARGUMENTS),
                AgentTestMessages.assistantToolCall("plan-2", PlanControlTool.TOOL_NAME, updateArguments)
        ));
        ToolCallingManager manager = mock(ToolCallingManager.class);
        RecordingAgentEventStream sse = new RecordingAgentEventStream();
        AgentRuntime runtime = createAgent(
                gateway,
                manager,
                sse,
                PlanningMode.AUTO,
                new PlanControlTool(),
                new AgentLoopPolicy(20, 20, 1, 40, Duration.ofMinutes(2)));

        assertFailure(runtime, sse, AgentFailureCode.MAX_PLAN_REVISIONS_EXCEEDED);

        verify(manager, never()).executeToolCalls(any(Prompt.class), any(ChatResponse.class));
        assertThat(sse.sentEvents()).extracting(AgentEvent::getType)
                .contains(AgentEvent.Type.PLAN_CREATED)
                .doesNotContain(AgentEvent.Type.PLAN_UPDATED)
                .doesNotContain(AgentEvent.Type.AI_DONE);
    }

    private AgentRuntime createAgent(
            ScriptedModelResponseGateway gateway,
            ToolCallingManager manager,
            RecordingAgentEventStream sse,
            PlanningMode mode,
            PlanControlTool planTool,
            AgentLoopPolicy policy
    ) {
        return createAgent(gateway, manager, sse, mode, planTool, policy, new InMemoryChatMessageFacadeService());
    }

    private AgentRuntime createAgent(
            ScriptedModelResponseGateway gateway,
            ToolCallingManager manager,
            RecordingAgentEventStream sse,
            PlanningMode mode,
            PlanControlTool planTool,
            AgentLoopPolicy policy,
            InMemoryChatMessageFacadeService messages
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
                manager,
                mode,
                planTool,
                policy
        );
    }

    private void assertFailure(
            AgentRuntime runtime,
            RecordingAgentEventStream sse,
            AgentFailureCode expected
    ) {
        assertThatThrownBy(runtime::execute)
                .isInstanceOf(AgentExecutionException.class)
                .extracting(exception -> ((AgentExecutionException) exception).getErrorCode())
                .isEqualTo(expected);
        assertThat(sse.sentEvents()).extracting(AgentEvent::getType)
                .contains(AgentEvent.Type.AI_ERROR)
                .doesNotContain(AgentEvent.Type.AI_DONE);
    }
}
