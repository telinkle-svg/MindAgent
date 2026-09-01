package com.kama.mindagent.agent;

import com.kama.mindagent.agent.planning.PlanningMode;

import java.time.LocalDateTime;

/** Immutable input captured when one user message starts an Agent run. */
public record AgentRunRequest(
        String agentId,
        String sessionId,
        String userMessageId,
        LocalDateTime userMessageCreatedAt,
        PlanningMode planningMode,
        String userInput
) {
    public AgentRunRequest {
        planningMode = PlanningMode.fromNullable(planningMode);
    }

    public static AgentRunRequest auto(String agentId, String sessionId) {
        return new AgentRunRequest(agentId, sessionId, null, null, PlanningMode.AUTO, null);
    }
}
