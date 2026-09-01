package com.kama.mindagent.event.listener;

import com.kama.mindagent.agent.AgentRunRequest;
import com.kama.mindagent.agent.AgentRuntime;
import com.kama.mindagent.agent.AgentRuntimeFactory;
import com.kama.mindagent.event.ChatEvent;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@AllArgsConstructor
public class ChatEventListener {

    private static final Logger log = LoggerFactory.getLogger(ChatEventListener.class);

    private final AgentRuntimeFactory agentRuntimeFactory;
    private final ConcurrentMap<String, CompletableFuture<Void>> sessionTails = new ConcurrentHashMap<>();

    @Async
    @EventListener
    public void handle(ChatEvent event) {
        String sessionId = event.getSessionId();
        CompletableFuture<Void> next = sessionTails.compute(sessionId, (key, previous) -> {
            CompletableFuture<Void> ready = previous == null
                    ? CompletableFuture.completedFuture(null)
                    : previous.exceptionally(error -> null);
            return ready.thenRun(() -> execute(event));
        });
        next.whenComplete((ignored, error) -> {
            if (error != null) {
                log.error("Agent execution failed for session {}", sessionId, error);
            }
            sessionTails.remove(sessionId, next);
        });
    }

    private void execute(ChatEvent event) {
        AgentRuntime agentRuntime;
        if (event.getUserMessageId() == null
                && event.getUserMessageCreatedAt() == null
                && event.getPlanningMode() == com.kama.mindagent.agent.planning.PlanningMode.AUTO) {
            // Preserve the legacy factory path for callers using the compatibility event constructor.
            agentRuntime = agentRuntimeFactory.createRuntime(event.getAgentId(), event.getSessionId());
        } else {
            agentRuntime = agentRuntimeFactory.createRuntime(new AgentRunRequest(
                    event.getAgentId(),
                    event.getSessionId(),
                    event.getUserMessageId(),
                    event.getUserMessageCreatedAt(),
                    event.getPlanningMode(),
                    event.getUserInput()
            ));
        }
        agentRuntime.execute();
    }
}
