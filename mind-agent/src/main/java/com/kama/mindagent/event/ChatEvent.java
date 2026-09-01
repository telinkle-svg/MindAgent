package com.kama.mindagent.event;

import com.kama.mindagent.agent.planning.PlanningMode;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ChatEvent {
    private final String agentId;
    private final String sessionId;
    private final String userMessageId;
    private final LocalDateTime userMessageCreatedAt;
    private final PlanningMode planningMode;
    private final String userInput;

    /**
     * Compatibility constructor for callers that do not yet provide a
     * message anchor or planning mode.
     */
    public ChatEvent(String agentId, String sessionId, String userInput) {
        this(agentId, sessionId, null, null, PlanningMode.AUTO, userInput);
    }

    public ChatEvent(
            String agentId,
            String sessionId,
            String userMessageId,
            LocalDateTime userMessageCreatedAt,
            PlanningMode planningMode,
            String userInput
    ) {
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.userMessageId = userMessageId;
        this.userMessageCreatedAt = userMessageCreatedAt;
        this.planningMode = PlanningMode.fromNullable(planningMode);
        this.userInput = userInput;
    }
}
