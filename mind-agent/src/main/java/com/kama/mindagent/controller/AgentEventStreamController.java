package com.kama.mindagent.controller;

import com.kama.mindagent.service.AgentEventStream;
import lombok.AllArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/sse")
@AllArgsConstructor
public class AgentEventStreamController {

    private final AgentEventStream agentEventStream;

    // 处理 sse 连接
    @RequestMapping(value = "/connect/{chatSessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connect(@PathVariable String chatSessionId) {
        return agentEventStream.open(chatSessionId);
    }
}
