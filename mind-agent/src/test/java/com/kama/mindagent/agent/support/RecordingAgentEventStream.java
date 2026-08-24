package com.kama.mindagent.agent.support;

import com.kama.mindagent.message.AgentEvent;
import com.kama.mindagent.service.AgentEventStream;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;

public final class RecordingAgentEventStream implements AgentEventStream {

    private final List<AgentEvent> sentEvents = new ArrayList<>();

    @Override
    public SseEmitter open(String chatSessionId) {
        return new SseEmitter();
    }

    @Override
    public void publish(String chatSessionId, AgentEvent event) {
        sentEvents.add(event);
    }

    public List<AgentEvent> sentEvents() {
        return List.copyOf(sentEvents);
    }
}
