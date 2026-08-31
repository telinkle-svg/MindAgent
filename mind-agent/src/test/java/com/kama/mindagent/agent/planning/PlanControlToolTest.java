package com.kama.mindagent.agent.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanControlToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createReturnsGeneratedPlanIdAndNormalizedSnapshot() {
        PlanToolResult result = new PlanControlTool().managePlan(createCommand());

        assertThat(result.accepted()).isTrue();
        assertThat(result.planId()).startsWith("plan-");
        assertThat(result.version()).isEqualTo(1);
        assertThat(result.steps()).hasSize(3);
        assertThat(result.currentTaskId()).isEqualTo("step-1");
        assertThat(result.message()).contains("created");
    }

    @Test
    void updateIncrementsVersionAndRevision() {
        PlanControlTool tool = new PlanControlTool();
        PlanToolResult created = tool.managePlan(createCommand());

        PlanCommand update = new PlanCommand(
                PlanAction.UPDATE,
                created.planId(),
                2,
                List.of(
                        step("step-1", PlanStepStatus.IN_PROGRESS),
                        step("step-2", PlanStepStatus.BLOCKED, "step-1"),
                        step("step-3", PlanStepStatus.BLOCKED, "step-2")
                ),
                "step-1",
                "started step-1"
        );
        PlanToolResult result = tool.managePlan(update);

        assertThat(result.accepted()).isTrue();
        assertThat(result.version()).isEqualTo(2);
        assertThat(result.steps()).extracting(PlanStep::status)
                .containsExactly(PlanStepStatus.IN_PROGRESS, PlanStepStatus.BLOCKED, PlanStepStatus.BLOCKED);
    }

    @Test
    void completeIsRejectedUntilEveryStepIsTerminal() {
        PlanControlTool tool = new PlanControlTool();
        PlanToolResult created = tool.managePlan(createCommand());

        PlanToolResult result = tool.managePlan(new PlanCommand(
                PlanAction.COMPLETE,
                created.planId(),
                2,
                List.of(),
                null,
                "attempted too early"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.version()).isEqualTo(1);
        assertThat(result.message()).contains("unfinished");
    }

    @Test
    void instancesDoNotSharePlanState() {
        PlanControlTool first = new PlanControlTool();
        PlanControlTool second = new PlanControlTool();

        PlanToolResult firstResult = first.managePlan(createCommand());
        PlanToolResult secondResult = second.managePlan(createCommand());

        assertThat(firstResult.accepted()).isTrue();
        assertThat(secondResult.accepted()).isTrue();
        assertThat(firstResult.planId()).isNotEqualTo(secondResult.planId());
        assertThat(first.currentSnapshot().version()).isEqualTo(1);
        assertThat(second.currentSnapshot().version()).isEqualTo(1);
    }

    @Test
    void callbackExposesManagePlanWithStructuredResult() throws Exception {
        PlanControlTool tool = new PlanControlTool();
        ToolCallback callback = Arrays.stream(MethodToolCallbackProvider.builder()
                        .toolObjects(tool)
                        .build()
                        .getToolCallbacks())
                .filter(candidate -> candidate.getToolDefinition().name().equals("manage_plan"))
                .findFirst()
                .orElseThrow();

        JsonNode result = objectMapper.readTree(callback.call(
                objectMapper.writeValueAsString(java.util.Map.of("command", createCommand()))));

        assertThat(result.get("accepted").asBoolean()).isTrue();
        assertThat(result.get("planId").asText()).startsWith("plan-");
    }

    private PlanCommand createCommand() {
        return new PlanCommand(
                PlanAction.CREATE,
                null,
                1,
                List.of(
                        step("step-1", PlanStepStatus.READY),
                        step("step-2", PlanStepStatus.BLOCKED, "step-1"),
                        step("step-3", PlanStepStatus.BLOCKED, "step-2")
                ),
                "step-1",
                "created by test"
        );
    }

    private PlanStep step(String id, PlanStepStatus status, String... dependsOn) {
        return new PlanStep(id, id, List.of(dependsOn), status, "done");
    }
}
