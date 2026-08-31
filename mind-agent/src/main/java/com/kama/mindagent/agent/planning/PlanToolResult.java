package com.kama.mindagent.agent.planning;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded, model-facing result returned by the per-run plan control tool.
 */
public record PlanToolResult(
        boolean accepted,
        String planId,
        int version,
        List<PlanStep> steps,
        String currentTaskId,
        String message
) {

    public static final int MAX_MESSAGE_LENGTH = 500;

    public PlanToolResult {
        planId = bound(planId, 80);
        currentTaskId = bound(currentTaskId, 80);
        message = bound(message, MAX_MESSAGE_LENGTH);
        steps = normalizeSteps(steps);
    }

    public static PlanToolResult accepted(PlanSnapshot snapshot, String message) {
        return fromSnapshot(true, snapshot, message);
    }

    public static PlanToolResult rejected(PlanSnapshot snapshot, String message) {
        return fromSnapshot(false, snapshot, message);
    }

    private static PlanToolResult fromSnapshot(boolean accepted, PlanSnapshot snapshot, String message) {
        PlanSnapshot safeSnapshot = snapshot == null ? PlanSnapshot.empty() : snapshot;
        return new PlanToolResult(
                accepted,
                safeSnapshot.planId(),
                safeSnapshot.version(),
                safeSnapshot.steps(),
                safeSnapshot.currentTaskId(),
                message
        );
    }

    private static List<PlanStep> normalizeSteps(List<PlanStep> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<PlanStep> bounded = new ArrayList<>();
        for (PlanStep step : source) {
            if (step == null) {
                continue;
            }
            List<String> dependencies = step.dependsOn() == null
                    ? List.of()
                    : step.dependsOn().stream()
                    .filter(dependency -> dependency != null)
                    .map(dependency -> bound(dependency, 80))
                    .limit(20)
                    .toList();
            bounded.add(new PlanStep(
                    bound(step.id(), 80),
                    bound(step.title(), 200),
                    dependencies,
                    step.status(),
                    bound(step.successCriteria(), 500)
            ));
            if (bounded.size() == PlanValidator.MAX_STEPS) {
                break;
            }
        }
        return List.copyOf(bounded);
    }

    private static String bound(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
