package com.kama.mindagent.agent.planning;

public enum PlanningMode {
    AUTO,
    REQUIRED,
    DISABLED;

    public static PlanningMode fromNullable(PlanningMode mode) {
        return mode == null ? AUTO : mode;
    }
}
