package com.kama.mindagent.agent.planning;

import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PlanValidator {

    public static final int MAX_STEPS = 20;
    private static final int MAX_ID_LENGTH = 80;
    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_CRITERIA_LENGTH = 500;
    private static final int MAX_OBSERVATION_LENGTH = 1_000;

    public void validate(PlanSnapshot current, PlanCommand command) {
        if (command == null || command.action() == null) {
            throw invalid("action must be provided");
        }

        PlanSnapshot safeCurrent = current == null ? PlanSnapshot.empty() : current;
        switch (command.action()) {
            case CREATE -> validateCreate(safeCurrent, command);
            case UPDATE -> validateUpdate(safeCurrent, command);
            case COMPLETE -> validateComplete(safeCurrent, command);
        }
    }

    private void validateCreate(PlanSnapshot current, PlanCommand command) {
        if (current.exists()) {
            throw invalid("plan already exists");
        }
        if (command.version() != 1) {
            throw invalid("CREATE version must be 1");
        }
        if (StringUtils.hasText(command.planId())) {
            throw invalid("CREATE planId must be blank");
        }
        validateSteps(command.steps());
        requireText(command.currentTaskId(), "currentTaskId");
        Map<String, PlanStep> steps = index(command.steps());
        PlanStep currentStep = steps.get(command.currentTaskId());
        if (currentStep == null) {
            throw invalid("currentTaskId does not identify a step");
        }
        if (currentStep.status() != PlanStepStatus.READY
                && currentStep.status() != PlanStepStatus.IN_PROGRESS) {
            throw invalid("currentTaskId must be READY or IN_PROGRESS");
        }
        validateDependenciesAreComplete(command.steps());
        validateObservation(command.observation());
    }

    private void validateUpdate(PlanSnapshot current, PlanCommand command) {
        if (!current.exists()) {
            throw invalid("cannot UPDATE an empty plan");
        }
        if (!current.planId().equals(command.planId())) {
            throw invalid("planId does not match current plan");
        }
        if (command.version() != current.version() + 1) {
            throw invalid("version must be current version plus one");
        }
        validateSteps(command.steps());
        Map<String, PlanStep> oldSteps = index(current.steps());
        Map<String, PlanStep> newSteps = index(command.steps());
        for (PlanStep oldStep : current.steps()) {
            PlanStep newStep = newSteps.get(oldStep.id());
            if (newStep == null) {
                throw invalid("UPDATE cannot remove existing step " + oldStep.id());
            }
            if (!isAllowedTransition(oldStep.status(), newStep.status())) {
                throw invalid("illegal status transition for " + oldStep.id());
            }
        }
        for (PlanStep newStep : command.steps()) {
            if (!oldSteps.containsKey(newStep.id())
                    && newStep.status() != PlanStepStatus.BLOCKED
                    && newStep.status() != PlanStepStatus.READY) {
                throw invalid("new steps must start as BLOCKED or READY");
            }
        }
        if (StringUtils.hasText(command.currentTaskId())
                && !newSteps.containsKey(command.currentTaskId())) {
            throw invalid("currentTaskId does not identify a step");
        }
        validateDependenciesAreComplete(command.steps());
        validateObservation(command.observation());
    }

    private void validateComplete(PlanSnapshot current, PlanCommand command) {
        if (!current.exists()) {
            throw invalid("cannot COMPLETE an empty plan");
        }
        if (!current.planId().equals(command.planId())) {
            throw invalid("planId does not match current plan");
        }
        if (command.version() != current.version() + 1) {
            throw invalid("version must be current version plus one");
        }
        for (PlanStep step : current.steps()) {
            if (step.status() != PlanStepStatus.COMPLETED
                    && step.status() != PlanStepStatus.SKIPPED) {
                throw invalid("plan has unfinished steps");
            }
        }
        validateObservation(command.observation());
    }

    private void validateSteps(List<PlanStep> steps) {
        if (steps == null || steps.isEmpty()) {
            throw invalid("steps must not be empty");
        }
        if (steps.size() > MAX_STEPS) {
            throw invalid("steps must contain at most " + MAX_STEPS + " items");
        }

        Map<String, PlanStep> indexed = new HashMap<>();
        for (PlanStep step : steps) {
            if (step == null) {
                throw invalid("step must not be null");
            }
            requireText(step.id(), "step id");
            if (step.id().length() > MAX_ID_LENGTH) {
                throw invalid("step id is too long");
            }
            requireText(step.title(), "step title");
            if (step.title().length() > MAX_TITLE_LENGTH) {
                throw invalid("step title is too long");
            }
            if (step.status() == null) {
                throw invalid("step status must be provided");
            }
            if (step.successCriteria() != null
                    && step.successCriteria().length() > MAX_CRITERIA_LENGTH) {
                throw invalid("success criteria is too long");
            }
            if (indexed.put(step.id(), step) != null) {
                throw invalid("duplicate step id: " + step.id());
            }
        }

        for (PlanStep step : steps) {
            Set<String> uniqueDependencies = new HashSet<>();
            for (String dependency : step.dependsOn()) {
                requireText(dependency, "dependency id");
                if (!uniqueDependencies.add(dependency)) {
                    throw invalid("duplicate dependency: " + dependency);
                }
                if (!indexed.containsKey(dependency)) {
                    throw invalid("unknown dependency: " + dependency);
                }
            }
        }
        rejectCycles(indexed);
    }

    private void validateDependenciesAreComplete(List<PlanStep> steps) {
        Map<String, PlanStep> indexed = index(steps);
        for (PlanStep step : steps) {
            if (step.status() != PlanStepStatus.READY
                    && step.status() != PlanStepStatus.IN_PROGRESS) {
                continue;
            }
            for (String dependency : step.dependsOn()) {
                PlanStep dependencyStep = indexed.get(dependency);
                if (dependencyStep.status() != PlanStepStatus.COMPLETED
                        && dependencyStep.status() != PlanStepStatus.SKIPPED) {
                    throw invalid("dependencies must be complete before " + step.id());
                }
            }
        }
    }

    private Map<String, PlanStep> index(List<PlanStep> steps) {
        Map<String, PlanStep> indexed = new HashMap<>();
        if (steps != null) {
            for (PlanStep step : steps) {
                if (step != null) {
                    indexed.put(step.id(), step);
                }
            }
        }
        return indexed;
    }

    private void rejectCycles(Map<String, PlanStep> steps) {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String id : steps.keySet()) {
            visit(id, steps, visiting, visited);
        }
    }

    private void visit(
            String id,
            Map<String, PlanStep> steps,
            Set<String> visiting,
            Set<String> visited
    ) {
        if (visited.contains(id)) {
            return;
        }
        if (!visiting.add(id)) {
            throw invalid("dependency cycle detected");
        }
        for (String dependency : steps.get(id).dependsOn()) {
            visit(dependency, steps, visiting, visited);
        }
        visiting.remove(id);
        visited.add(id);
    }

    private boolean isAllowedTransition(PlanStepStatus from, PlanStepStatus to) {
        if (from == to) {
            return true;
        }
        return switch (from) {
            case BLOCKED -> to == PlanStepStatus.READY || to == PlanStepStatus.SKIPPED;
            case READY -> to == PlanStepStatus.IN_PROGRESS || to == PlanStepStatus.SKIPPED;
            case IN_PROGRESS -> to == PlanStepStatus.COMPLETED
                    || to == PlanStepStatus.FAILED
                    || to == PlanStepStatus.SKIPPED;
            case FAILED -> to == PlanStepStatus.READY || to == PlanStepStatus.SKIPPED;
            case COMPLETED, SKIPPED -> false;
        };
    }

    private void validateObservation(String observation) {
        if (observation != null && observation.length() > MAX_OBSERVATION_LENGTH) {
            throw invalid("observation is too long");
        }
    }

    private void requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw invalid(field + " must not be blank");
        }
    }

    private PlanValidationException invalid(String message) {
        return new PlanValidationException(message);
    }
}
