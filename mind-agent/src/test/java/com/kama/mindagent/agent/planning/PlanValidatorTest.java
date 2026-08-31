package com.kama.mindagent.agent.planning;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanValidatorTest {

    private final PlanValidator validator = new PlanValidator();

    @Test
    void acceptsValidCreateCommand() {
        PlanCommand command = createCommand(
                step("step-1", List.of(), PlanStepStatus.READY),
                step("step-2", List.of("step-1"), PlanStepStatus.BLOCKED)
        );

        assertThatCode(() -> validator.validate(PlanSnapshot.empty(), command))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsDuplicateStepIds() {
        PlanCommand command = createCommand(
                step("step-1", List.of(), PlanStepStatus.READY),
                step("step-1", List.of(), PlanStepStatus.READY)
        );

        assertThatThrownBy(() -> validator.validate(PlanSnapshot.empty(), command))
                .isInstanceOf(PlanValidationException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void rejectsUnknownDependency() {
        PlanCommand command = createCommand(
                step("step-1", List.of("missing"), PlanStepStatus.READY)
        );

        assertThatThrownBy(() -> validator.validate(PlanSnapshot.empty(), command))
                .isInstanceOf(PlanValidationException.class)
                .hasMessageContaining("dependency");
    }

    @Test
    void rejectsDependencyCycle() {
        PlanCommand command = createCommand(
                step("step-1", List.of("step-2"), PlanStepStatus.READY),
                step("step-2", List.of("step-1"), PlanStepStatus.BLOCKED)
        );

        assertThatThrownBy(() -> validator.validate(PlanSnapshot.empty(), command))
                .isInstanceOf(PlanValidationException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void rejectsBlankTitle() {
        PlanCommand command = createCommand(
                new PlanStep("step-1", " ", List.of(), PlanStepStatus.READY, "done")
        );

        assertThatThrownBy(() -> validator.validate(PlanSnapshot.empty(), command))
                .isInstanceOf(PlanValidationException.class)
                .hasMessageContaining("title");
    }

    @Test
    void rejectsMoreThanTwentySteps() {
        List<PlanStep> steps = java.util.stream.IntStream.rangeClosed(1, 21)
                .mapToObj(index -> step("step-" + index, List.of(), PlanStepStatus.READY))
                .toList();

        assertThatThrownBy(() -> validator.validate(
                PlanSnapshot.empty(),
                new PlanCommand(PlanAction.CREATE, null, 1, steps, "step-1", null)
        )).isInstanceOf(PlanValidationException.class)
                .hasMessageContaining("20");
    }

    @Test
    void rejectsIllegalStatusTransition() {
        PlanSnapshot current = snapshot(
                step("step-1", List.of(), PlanStepStatus.READY)
        );
        PlanCommand update = new PlanCommand(
                PlanAction.UPDATE,
                current.planId(),
                current.version() + 1,
                List.of(step("step-1", List.of(), PlanStepStatus.COMPLETED)),
                "step-1",
                "completed without execution"
        );

        assertThatThrownBy(() -> validator.validate(current, update))
                .isInstanceOf(PlanValidationException.class)
                .hasMessageContaining("transition");
    }

    @Test
    void rejectsVersionMismatch() {
        PlanSnapshot current = snapshot(
                step("step-1", List.of(), PlanStepStatus.IN_PROGRESS)
        );
        PlanCommand update = new PlanCommand(
                PlanAction.UPDATE,
                current.planId(),
                current.version(),
                List.of(step("step-1", List.of(), PlanStepStatus.COMPLETED)),
                "step-1",
                "done"
        );

        assertThatThrownBy(() -> validator.validate(current, update))
                .isInstanceOf(PlanValidationException.class)
                .hasMessageContaining("version");
    }

    private static PlanCommand createCommand(PlanStep... steps) {
        return new PlanCommand(
                PlanAction.CREATE,
                null,
                1,
                List.of(steps),
                steps[0].id(),
                null
        );
    }

    private static PlanSnapshot snapshot(PlanStep... steps) {
        return new PlanSnapshot(
                "plan-1",
                1,
                List.of(steps),
                steps[0].id(),
                0,
                false
        );
    }

    private static PlanStep step(String id, List<String> dependencies, PlanStepStatus status) {
        return new PlanStep(id, id + " title", dependencies, status, "done");
    }
}
