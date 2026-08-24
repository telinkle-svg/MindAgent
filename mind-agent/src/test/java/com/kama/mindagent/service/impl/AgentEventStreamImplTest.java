package com.kama.mindagent.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.mindagent.message.AgentEvent;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentEventStreamImplTest {

    private final AgentEventStreamImpl service = new AgentEventStreamImpl(new ObjectMapper());

    @Test
    void connectQueuesInitEventAndSendAddsMessageEvent() throws Exception {
        SseEmitter emitter = service.open("session-1");
        CapturingHandler capturingHandler = initializeEmitter(emitter);

        service.publish("session-1", AgentEvent.builder()
                .type(AgentEvent.Type.AI_DONE)
                .payload(AgentEvent.Payload.builder().done(true).build())
                .metadata(AgentEvent.Metadata.builder().chatMessageId("message-1").build())
                .build());

        assertThat(capturingHandler.sentBatches).hasSize(2);
        assertThat(flattenEventData(capturingHandler.sentBatches.get(0))).contains("connected");
        assertThat(flattenEventData(capturingHandler.sentBatches.get(1))).contains("AI_DONE", "message-1");
    }

    @Test
    void sendWithoutConnectedClientIsNoOp() {
        assertThatCode(() -> service.publish("missing-session", AgentEvent.builder()
                .type(AgentEvent.Type.AI_THINKING)
                .build()))
                .doesNotThrowAnyException();
    }

    @Test
    void completingInitializedEmitterRemovesItsSession() throws Exception {
        SseEmitter emitter = service.open("session-1");
        CapturingHandler capturingHandler = initializeEmitter(emitter);

        assertThat(clients()).containsEntry("session-1", emitter);

        emitter.complete();

        assertThat(clients()).doesNotContainKey("session-1");
    }

    @Test
    void reconnectingSameSessionKeepsOnlyLatestEmitter() throws Exception {
        SseEmitter first = service.open("session-1");
        CapturingHandler firstHandler = initializeEmitter(first);
        SseEmitter second = service.open("session-1");
        initializeEmitter(second);

        assertThat(clients()).hasSize(1);
        assertThat(clients()).containsEntry("session-1", second);
        assertThat(clients()).doesNotContainValue(first);
        assertThat(firstHandler.completionCallbackRuns).isEqualTo(1);
    }

    @Test
    void staleCallbacksFromReplacedEmitterDoNotRemoveLatestEmitter() throws Exception {
        SseEmitter first = service.open("session-1");
        CapturingHandler firstHandler = initializeEmitter(first);

        SseEmitter second = service.open("session-1");
        CapturingHandler secondHandler = initializeEmitter(second);

        firstHandler.runCompletionCallbacks();
        firstHandler.runTimeoutCallbacks();
        firstHandler.runErrorCallbacks(new IOException("old connection closed"));

        assertThat(clients()).containsOnly(entry("session-1", second));
        assertThat(secondHandler.sentBatches).isNotEmpty();
    }

    @Test
    void transportSendFailureCleansCurrentEmitterWithoutThrowingToCaller() throws Exception {
        SseEmitter emitter = service.open("session-1");
        CapturingHandler handler = initializeEmitter(emitter);
        handler.failSends = true;

        assertThatCode(() -> service.publish("session-1", AgentEvent.builder()
                .type(AgentEvent.Type.AI_THINKING)
                .payload(AgentEvent.Payload.builder().statusText("思考中").build())
                .build()))
                .doesNotThrowAnyException();

        assertThat(clients()).doesNotContainKey("session-1");
    }

    @Test
    void serializationFailureDoesNotThrowAndKeepsHealthyConnectionRegistered() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("serialization failed") {
                });
        AgentEventStreamImpl failingService = new AgentEventStreamImpl(failingMapper);
        SseEmitter emitter = failingService.open("session-1");
        initializeEmitter(emitter);

        assertThatCode(() -> failingService.publish("session-1", AgentEvent.builder()
                .type(AgentEvent.Type.AI_DONE)
                .build()))
                .doesNotThrowAnyException();
        assertThat(clientsOf(failingService)).containsKey("session-1");
    }

    @Test
    void initSendFailureCleansEmitterWithoutFailingConnectionRequest() throws Exception {
        SseEmitter failingEmitter = mock(SseEmitter.class);
        doThrow(new IOException("init failed"))
                .when(failingEmitter)
                .send(any(SseEmitter.SseEventBuilder.class));
        AgentEventStreamImpl failingService = new AgentEventStreamImpl(
                new ObjectMapper(),
                ignored -> failingEmitter
        );

        assertThatCode(() -> failingService.open("session-1"))
                .doesNotThrowAnyException();
        assertThat(clientsOf(failingService)).doesNotContainKey("session-1");
    }

    @SuppressWarnings("unchecked")
    private ConcurrentMap<String, SseEmitter> clients() {
        return clientsOf(service);
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentMap<String, SseEmitter> clientsOf(AgentEventStreamImpl target) {
        try {
            var field = AgentEventStreamImpl.class.getDeclaredField("clients");
            field.setAccessible(true);
            return (ConcurrentMap<String, SseEmitter>) field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to inspect SSE client registry", e);
        }
    }

    private static CapturingHandler initializeEmitter(SseEmitter emitter) throws Exception {
        Class<?> handlerType = Class.forName(
                "org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter$Handler"
        );
        CapturingHandler capturingHandler = new CapturingHandler();
        InvocationHandler invocationHandler = (proxy, method, args) -> {
            if ("send".equals(method.getName()) && args != null && args.length > 0) {
                if (capturingHandler.failSends) {
                    throw new IOException("simulated client disconnect");
                }
                if (args[0] instanceof Set<?> set) {
                    capturingHandler.sentBatches.add(new LinkedHashSet<>(set));
                } else {
                    capturingHandler.sentBatches.add(args[0]);
                }
            } else if ("onCompletion".equals(method.getName()) && args != null && args.length > 0) {
                capturingHandler.completionCallbacks.add((Runnable) args[0]);
            } else if ("onTimeout".equals(method.getName()) && args != null && args.length > 0) {
                capturingHandler.timeoutCallbacks.add((Runnable) args[0]);
            } else if ("onError".equals(method.getName()) && args != null && args.length > 0) {
                capturingHandler.errorCallbacks.add((Consumer<Throwable>) args[0]);
            } else if ("complete".equals(method.getName())) {
                capturingHandler.runCompletionCallbacks();
            }
            return null;
        };
        Object handler = Proxy.newProxyInstance(
                handlerType.getClassLoader(),
                new Class<?>[]{handlerType},
                invocationHandler
        );
        Method initialize = ResponseBodyEmitter.class.getDeclaredMethod("initialize", handlerType);
        initialize.setAccessible(true);
        initialize.invoke(emitter, handler);
        return capturingHandler;
    }

    private static String flattenEventData(Object batch) {
        if (batch instanceof Set<?> set) {
            return set.stream()
                    .map(AgentEventStreamImplTest::dataValue)
                    .reduce("", (left, right) -> left + right);
        }
        return dataValue(batch);
    }

    private static String dataValue(Object value) {
        if (value instanceof ResponseBodyEmitter.DataWithMediaType data) {
            return String.valueOf(data.getData());
        }
        if (value instanceof Map<?, ?> map) {
            return map.toString();
        }
        return String.valueOf(value);
    }

    private static final class CapturingHandler {
        private final List<Object> sentBatches = new ArrayList<>();
        private final List<Runnable> completionCallbacks = new ArrayList<>();
        private final List<Runnable> timeoutCallbacks = new ArrayList<>();
        private final List<Consumer<Throwable>> errorCallbacks = new ArrayList<>();
        private int completionCallbackRuns;
        private boolean failSends;

        private void runCompletionCallbacks() {
            completionCallbacks.forEach(Runnable::run);
            completionCallbackRuns++;
        }

        private void runTimeoutCallbacks() {
            timeoutCallbacks.forEach(Runnable::run);
        }

        private void runErrorCallbacks(Throwable error) {
            errorCallbacks.forEach(callback -> callback.accept(error));
        }
    }
}
