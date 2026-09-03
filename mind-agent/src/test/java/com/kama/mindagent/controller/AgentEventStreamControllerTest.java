package com.kama.mindagent.controller;

import com.kama.mindagent.service.AgentEventStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentEventStreamController.class)
class AgentEventStreamControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgentEventStream agentEventStream;

    private SseEmitter emitter;

    @Test
    void connectReturnsAsyncTextEventStreamResponse() throws Exception {
        emitter = new SseEmitter();
        when(agentEventStream.open("session-1")).thenReturn(emitter);

        MvcResult result = mockMvc.perform(get("/sse/connect/session-1"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        emitter.complete();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));
    }

    @Test
    void reconnectPassesLastEventIdToStream() throws Exception {
        emitter = new SseEmitter();
        when(agentEventStream.open("session-1", "7")).thenReturn(emitter);

        MvcResult result = mockMvc.perform(get("/sse/connect/session-1")
                        .header("Last-Event-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        emitter.complete();
        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));

        verify(agentEventStream).open("session-1", "7");
    }
}
