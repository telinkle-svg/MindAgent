package com.kama.mindagent.agent.tools;

import org.springframework.stereotype.Component;

@Component
public class CompletionSignalTool implements AgentTool {

    @Override
    public String name() {
        return "terminate";
    }

    @Override
    public String description() {
        return "跳出 Agent Loop 的工具";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.REQUIRED;
    }

    @org.springframework.ai.tool.annotation.Tool(name = "terminate", description = "如果你觉得当前所有的任务已经执行完毕了，就执行这个工具调用")
    public void signalCompletion() {}
}
