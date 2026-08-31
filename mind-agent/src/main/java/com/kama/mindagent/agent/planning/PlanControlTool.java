package com.kama.mindagent.agent.planning;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.util.StringUtils;

import java.util.UUID;

/**
 * Per-run control-plane tool for creating and updating a model-managed plan.
 *
 * <p>The class deliberately is not a Spring component. A new instance must be
 * created for every Agent run so that one session cannot mutate another
 * session's plan.</p>
 */
public final class PlanControlTool {

    public static final String TOOL_NAME = "manage_plan";
    private static final String TOOL_DESCRIPTION = "管理当前 Agent 运行的任务计划。"
            + "使用 CREATE 创建计划，UPDATE 更新步骤状态，COMPLETE 标记计划完成。"
            + "计划步骤必须使用唯一 ID，并遵守依赖顺序。该工具只维护本次运行的内存状态，不执行外部副作用。";

    private final PlanValidator validator;
    private PlanSnapshot snapshot = PlanSnapshot.empty();

    public PlanControlTool() {
        this(new PlanValidator());
    }

    PlanControlTool(PlanValidator validator) {
        this.validator = validator;
    }

    /**
     * Apply one plan command and return a bounded snapshot for the model.
     * Invalid commands are represented as rejected tool results so the model
     * can correct its next action without mutating the current snapshot.
     */
    @Tool(name = TOOL_NAME, description = TOOL_DESCRIPTION)
    public synchronized PlanToolResult managePlan(PlanCommand command) {
        PlanSnapshot previous = snapshot;
        try {
            validator.validate(previous, command);
            snapshot = transition(previous, command);
            return PlanToolResult.accepted(snapshot, acceptedMessage(command.action()));
        } catch (PlanValidationException exception) {
            return PlanToolResult.rejected(previous, "request rejected: " + exception.getMessage());
        }
    }

    public synchronized PlanSnapshot currentSnapshot() {
        return snapshot;
    }

    private PlanSnapshot transition(PlanSnapshot previous, PlanCommand command) {
        return switch (command.action()) {
            case CREATE -> new PlanSnapshot(
                    newPlanId(),
                    1,
                    command.steps(),
                    command.currentTaskId(),
                    1,
                    false
            );
            case UPDATE -> new PlanSnapshot(
                    previous.planId(),
                    command.version(),
                    command.steps(),
                    StringUtils.hasText(command.currentTaskId())
                            ? command.currentTaskId()
                            : previous.currentTaskId(),
                    previous.revisionCount() + 1,
                    false
            );
            case COMPLETE -> new PlanSnapshot(
                    previous.planId(),
                    command.version(),
                    previous.steps(),
                    previous.currentTaskId(),
                    previous.revisionCount() + 1,
                    true
            );
        };
    }

    private String newPlanId() {
        return "plan-" + UUID.randomUUID();
    }

    private String acceptedMessage(PlanAction action) {
        return switch (action) {
            case CREATE -> "plan created";
            case UPDATE -> "plan updated";
            case COMPLETE -> "plan completed";
        };
    }
}
