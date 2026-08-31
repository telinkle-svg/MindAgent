package com.kama.mindagent.agent.planning;

import java.util.List;

public record PlanStep(
        String id,
        String title,
        List<String> dependsOn,
        PlanStepStatus status,
        String successCriteria
) {
    public PlanStep {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
}
