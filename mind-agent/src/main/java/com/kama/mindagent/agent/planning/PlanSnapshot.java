package com.kama.mindagent.agent.planning;

import java.util.List;

public record PlanSnapshot(
        String planId,
        int version,
        List<PlanStep> steps,
        String currentTaskId,
        int revisionCount,
        boolean completed
) {
    public PlanSnapshot {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public static PlanSnapshot empty() {
        return new PlanSnapshot(null, 0, List.of(), null, 0, false);
    }

    public boolean exists() {
        return planId != null && !planId.isBlank();
    }
}
