package com.kama.mindagent.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.mindagent.message.AgentEvent;
import com.kama.mindagent.service.AgentEventStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.function.LongFunction;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@Slf4j
public class AgentEventStreamImpl implements AgentEventStream {

    private static final long EMITTER_TIMEOUT_MILLIS = 30 * 60 * 1000L;

    private final ConcurrentMap<String, SseEmitter> clients = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final LongFunction<SseEmitter> emitterFactory;

    @Autowired
    public AgentEventStreamImpl(ObjectMapper objectMapper) {
        this(objectMapper, SseEmitter::new);
    }

    AgentEventStreamImpl(ObjectMapper objectMapper, LongFunction<SseEmitter> emitterFactory) {
        this.objectMapper = objectMapper;
        this.emitterFactory = emitterFactory;
    }

    private void registerLifecycleCallbacks(String chatSessionId, SseEmitter emitter) {
        emitter.onCompletion(() -> removeIfCurrent(chatSessionId, emitter));
        emitter.onTimeout(() -> removeIfCurrent(chatSessionId, emitter));
        emitter.onError(error -> {
            log.info("SSE transport error for chatSessionId={}, cleaning current emitter",
                    chatSessionId, error);
            removeIfCurrent(chatSessionId, emitter);
        });
    }

    private void removeIfCurrent(String chatSessionId, SseEmitter emitter) {
        clients.remove(chatSessionId, emitter);
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception exception) {
            log.debug("SSE emitter completion failed", exception);
        }
    }

    private void removeAndCompleteIfCurrent(String chatSessionId, SseEmitter emitter) {
        if (clients.remove(chatSessionId, emitter)) {
            completeQuietly(emitter);
        }
    }

    @Override
    public SseEmitter open(String chatSessionId) {
        SseEmitter emitter = emitterFactory.apply(EMITTER_TIMEOUT_MILLIS);
        registerLifecycleCallbacks(chatSessionId, emitter);

        SseEmitter previousEmitter = clients.put(chatSessionId, emitter);
        if (previousEmitter != null && previousEmitter != emitter) {
            completeQuietly(previousEmitter);
        }

        try {
            emitter.send(SseEmitter.event()
                    .name("init")
                    .data("connected")
            );
        } catch (IOException exception) {
            log.info("SSE init send failed for chatSessionId={}", chatSessionId, exception);
            removeAndCompleteIfCurrent(chatSessionId, emitter);
        }
        return emitter;
    }

    @Override
    public void publish(String chatSessionId, AgentEvent event) {
        SseEmitter emitter = clients.get(chatSessionId);

        if (emitter != null) {
            final String serializedMessage;
            try {
                serializedMessage = objectMapper.writeValueAsString(event);
            } catch (JsonProcessingException exception) {
                log.error("Unable to serialize SSE message type={} for chatSessionId={}",
                        event.getType(), chatSessionId, exception);
                return;
            }

            try {
                emitter.send(SseEmitter.event()
                        .name("message")
                        .data(serializedMessage)
                );
            } catch (IOException | IllegalStateException exception) {
                log.info("SSE transport send failed for chatSessionId={}",
                        chatSessionId, exception);
                removeAndCompleteIfCurrent(chatSessionId, emitter);
            }
        } else {
            log.warn("No SSE client connected for chatSessionId: {}, skip push: {}", chatSessionId, event.getType());
        }
    }
}
