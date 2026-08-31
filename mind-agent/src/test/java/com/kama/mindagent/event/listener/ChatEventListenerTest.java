package com.kama.mindagent.event.listener;

import com.kama.mindagent.agent.AgentRuntime;
import com.kama.mindagent.agent.AgentRuntimeFactory;
import com.kama.mindagent.event.ChatEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatEventListenerTest {

    @Test
    void eventsForTheSameSessionAreExecutedSequentially() throws Exception {
        AgentRuntimeFactory factory = mock(AgentRuntimeFactory.class);
        AgentRuntime firstRuntime = mock(AgentRuntime.class);
        AgentRuntime secondRuntime = mock(AgentRuntime.class);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        List<String> executionOrder = new CopyOnWriteArrayList<>();

        when(factory.createRuntime("agent-1", "session-1"))
                .thenReturn(firstRuntime);
        when(factory.createRuntime("agent-2", "session-1"))
                .thenReturn(secondRuntime);
        doAnswer(invocation -> {
            firstStarted.countDown();
            assertThat(releaseFirst.await(2, TimeUnit.SECONDS)).isTrue();
            executionOrder.add("first");
            return null;
        }).when(firstRuntime).execute();
        doAnswer(invocation -> {
            secondStarted.countDown();
            executionOrder.add("second");
            return null;
        }).when(secondRuntime).execute();

        ChatEventListener listener = new ChatEventListener(factory);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = callers.submit(() -> listener.handle(new ChatEvent("agent-1", "session-1", "first")));
            assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();

            Future<?> second = callers.submit(() -> listener.handle(new ChatEvent("agent-2", "session-1", "second")));
            assertThat(secondStarted.await(200, TimeUnit.MILLISECONDS)).isFalse();

            releaseFirst.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
        } finally {
            callers.shutdownNow();
        }

        assertThat(executionOrder).containsExactly("first", "second");
        verify(factory).createRuntime("agent-1", "session-1");
        verify(factory).createRuntime("agent-2", "session-1");
    }
}
