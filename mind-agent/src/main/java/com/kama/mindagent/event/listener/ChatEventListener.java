package com.kama.mindagent.event.listener;

import com.kama.mindagent.agent.AgentRuntime;
import com.kama.mindagent.agent.AgentRuntimeFactory;
import com.kama.mindagent.event.ChatEvent;
import lombok.AllArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class ChatEventListener {

    private final AgentRuntimeFactory agentRuntimeFactory;

    @Async
    @EventListener
    public void handle(ChatEvent event) {
        // 创建一个 Agent 实例处理聊天事件
        AgentRuntime agentRuntime = agentRuntimeFactory.createRuntime(event.getAgentId(), event.getSessionId());
        agentRuntime.execute();
    }
}
