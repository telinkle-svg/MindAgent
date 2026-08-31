package com.kama.mindagent.agent.planning;

import java.util.List;

public record PlanCommand(
        PlanAction action,
        String planId,
        int version,
        List<PlanStep> steps,
        String currentTaskId,
        String observation
) {
    public PlanCommand {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
