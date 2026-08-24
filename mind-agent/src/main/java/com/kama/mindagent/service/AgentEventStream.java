package com.kama.mindagent.service;

import com.kama.mindagent.message.AgentEvent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface AgentEventStream {
    // 没有用户系统，使用 chatSessionId 作为连接标识
    SseEmitter open(String chatSessionId);

    void publish(String chatSessionId, AgentEvent event);
}
