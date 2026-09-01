package com.kama.mindagent.event;

import com.kama.mindagent.agent.planning.PlanningMode;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ChatEventTest {

    @Test
    void compatibilityConstructorDefaultsToAutoWithoutAnchor() {
        ChatEvent event = new ChatEvent("agent-1", "session-1", "question");

        assertThat(event.getPlanningMode()).isEqualTo(PlanningMode.AUTO);
        assertThat(event.getUserMessageId()).isNull();
        assertThat(event.getUserMessageCreatedAt()).isNull();
    }

    @Test
    void fullEventRetainsMessageAnchorAndPlanningMode() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 31, 22, 0);
        ChatEvent event = new ChatEvent(
                "agent-1",
                "session-1",
                "message-1",
                createdAt,
                PlanningMode.REQUIRED,
                "question"
        );

        assertThat(event.getUserMessageId()).isEqualTo("message-1");
        assertThat(event.getUserMessageCreatedAt()).isEqualTo(createdAt);
        assertThat(event.getPlanningMode()).isEqualTo(PlanningMode.REQUIRED);
        assertThat(event.getUserInput()).isEqualTo("question");
    }
}
