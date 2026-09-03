package com.kama.mindagent.service;

import com.kama.mindagent.message.AgentEvent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface AgentEventStream {
    // 没有用户系统，使用 chatSessionId 作为连接标识
    SseEmitter open(String chatSessionId);

    /**
     * Opens a stream and, when the client supplies an SSE cursor, replays events
     * published after that cursor. Implementations that do not support replay
     * can keep the compatibility behaviour of opening a normal stream.
     */
    default SseEmitter open(String chatSessionId, String lastEventId) {
        return open(chatSessionId);
    }

    void publish(String chatSessionId, AgentEvent event);
}
