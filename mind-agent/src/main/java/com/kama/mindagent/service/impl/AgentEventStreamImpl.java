package com.kama.mindagent.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.mindagent.message.AgentEvent;
import com.kama.mindagent.service.AgentEventStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@Slf4j
public class AgentEventStreamImpl implements AgentEventStream {

    private static final long EMITTER_TIMEOUT_MILLIS = 30 * 60 * 1000L;
    private static final int MAX_REPLAY_EVENTS = 256;
    private static final long REPLAY_RETENTION_MILLIS = EMITTER_TIMEOUT_MILLIS;

    private final ConcurrentMap<String, SseEmitter> clients = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, SessionReplayBuffer> replayBuffers = new ConcurrentHashMap<>();
    private final Object replayBufferRegistryLock = new Object();
    private final ObjectMapper objectMapper;
    private final LongFunction<SseEmitter> emitterFactory;
    private final LongSupplier clock;

    @Autowired
    public AgentEventStreamImpl(ObjectMapper objectMapper) {
        this(objectMapper, SseEmitter::new, System::currentTimeMillis);
    }

    AgentEventStreamImpl(ObjectMapper objectMapper, LongFunction<SseEmitter> emitterFactory) {
        this(objectMapper, emitterFactory, System::currentTimeMillis);
    }

    AgentEventStreamImpl(
            ObjectMapper objectMapper,
            LongFunction<SseEmitter> emitterFactory,
            LongSupplier clock
    ) {
        this.objectMapper = objectMapper;
        this.emitterFactory = emitterFactory;
        this.clock = clock;
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

    private SessionReplayBuffer replayBuffer(String chatSessionId) {
        long now = clock.getAsLong();
        synchronized (replayBufferRegistryLock) {
            cleanupExpiredReplayBuffers(now);
            SessionReplayBuffer buffer = replayBuffers.computeIfAbsent(
                    chatSessionId,
                    ignored -> new SessionReplayBuffer()
            );
            // Mark the buffer as recently used before returning it. This closes
            // the small race where scheduled cleanup could remove an idle
            // buffer between lookup and open/publish registration.
            buffer.touch(now);
            return buffer;
        }
    }

    @Scheduled(fixedDelay = 5 * 60 * 1000L)
    private void cleanupExpiredReplayBuffers() {
        synchronized (replayBufferRegistryLock) {
            cleanupExpiredReplayBuffers(clock.getAsLong());
        }
    }

    private void cleanupExpiredReplayBuffers(long now) {
        replayBuffers.forEach((chatSessionId, buffer) -> {
            synchronized (buffer) {
                buffer.pruneExpired(now);
            }
            if (buffer.isExpired(now) && !clients.containsKey(chatSessionId)) {
                replayBuffers.remove(chatSessionId, buffer);
            }
        });
    }

    private Long parseLastEventId(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(lastEventId.trim());
            return parsed >= 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            log.debug("Ignoring invalid SSE Last-Event-ID={}", lastEventId);
            return null;
        }
    }

    private SseEmitter.SseEventBuilder messageEvent(ReplayEvent event) {
        return SseEmitter.event()
                .name("message")
                .id(event.id())
                .data(event.data());
    }

    @Override
    public SseEmitter open(String chatSessionId) {
        return open(chatSessionId, null);
    }

    @Override
    public SseEmitter open(String chatSessionId, String lastEventId) {
        SessionReplayBuffer buffer = replayBuffer(chatSessionId);
        Long cursor = parseLastEventId(lastEventId);
        SseEmitter emitter = emitterFactory.apply(EMITTER_TIMEOUT_MILLIS);
        registerLifecycleCallbacks(chatSessionId, emitter);

        SseEmitter previousEmitter;
        SseEmitter failedEmitter = null;
        synchronized (buffer) {
            previousEmitter = clients.put(chatSessionId, emitter);
            buffer.pruneExpiredAndTouch(clock.getAsLong());
            try {
                // init is a transport event and deliberately does not advance
                // the replay cursor. Agent events below carry their own IDs.
                emitter.send(SseEmitter.event()
                        .name("init")
                        .data("connected")
                );
                if (cursor != null) {
                    for (ReplayEvent event : buffer.after(cursor, clock.getAsLong())) {
                        emitter.send(messageEvent(event));
                    }
                }
            } catch (IOException | IllegalStateException exception) {
                log.info("SSE stream initialization/replay failed for chatSessionId={}",
                        chatSessionId, exception);
                clients.remove(chatSessionId, emitter);
                failedEmitter = emitter;
            }
        }

        if (previousEmitter != null && previousEmitter != emitter) {
            completeQuietly(previousEmitter);
        }
        if (failedEmitter != null) {
            completeQuietly(failedEmitter);
        }
        return emitter;
    }

    @Override
    public void publish(String chatSessionId, AgentEvent event) {
        final String serializedMessage;
        try {
            serializedMessage = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            log.error("Unable to serialize SSE message type={} for chatSessionId={}",
                    event.getType(), chatSessionId, exception);
            return;
        }

        SessionReplayBuffer buffer = replayBuffer(chatSessionId);
        SseEmitter emitter;
        SseEmitter failedEmitter = null;
        boolean noClient;
        synchronized (buffer) {
            ReplayEvent replayEvent = buffer.append(serializedMessage, clock.getAsLong());
            emitter = clients.get(chatSessionId);
            noClient = emitter == null;
            if (!noClient) {
                try {
                    emitter.send(messageEvent(replayEvent));
                } catch (IOException | IllegalStateException exception) {
                    log.info("SSE transport send failed for chatSessionId={}",
                            chatSessionId, exception);
                    clients.remove(chatSessionId, emitter);
                    failedEmitter = emitter;
                }
            }
        }

        if (failedEmitter != null) {
            completeQuietly(failedEmitter);
        }
        if (noClient) {
            log.debug("No SSE client connected for chatSessionId: {}, buffered event for replay: {}",
                    chatSessionId, event.getType());
        }
    }

    private record ReplayEvent(String id, String data, long createdAtMillis) {
    }

    private static final class SessionReplayBuffer {
        private final Deque<ReplayEvent> events = new ArrayDeque<>();
        private long nextSequence = 1;
        private volatile long lastTouchedAt = System.currentTimeMillis();

        private ReplayEvent append(String data, long now) {
            pruneExpired(now);
            ReplayEvent event = new ReplayEvent(Long.toString(nextSequence++), data, now);
            events.addLast(event);
            while (events.size() > MAX_REPLAY_EVENTS) {
                events.removeFirst();
            }
            touch(now);
            return event;
        }

        private List<ReplayEvent> after(long cursor, long now) {
            pruneExpired(now);
            List<ReplayEvent> result = new ArrayList<>();
            for (ReplayEvent event : events) {
                if (Long.parseLong(event.id()) > cursor) {
                    result.add(event);
                }
            }
            touch(now);
            return List.copyOf(result);
        }

        private void pruneExpiredAndTouch(long now) {
            pruneExpired(now);
            touch(now);
        }

        private void pruneExpired(long now) {
            while (!events.isEmpty()
                    && now - events.peekFirst().createdAtMillis() > REPLAY_RETENTION_MILLIS) {
                events.removeFirst();
            }
        }

        private void touch(long now) {
            lastTouchedAt = now;
        }

        private boolean isExpired(long now) {
            return now - lastTouchedAt > REPLAY_RETENTION_MILLIS;
        }
    }
}
