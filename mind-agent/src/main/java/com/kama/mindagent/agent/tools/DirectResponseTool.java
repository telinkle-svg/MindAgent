package com.kama.mindagent.agent.tools;

import org.springframework.stereotype.Component;

// 注释 @Component 注解，暂不将 DirectResponseTool 注册为 Spring Bean
// @Component
public class DirectResponseTool implements AgentTool {

    @Override
    public String name() {
        return "directAnswer";
    }

    @Override
    public String description() {
        return "当用户的请求不需要执行操作时调用此工具，用以直接返回自然语言回答。";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.REQUIRED;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "directAnswer",
            description = "用于直接回答用户问题，适用于无需生成任务计划或调用其他工具的场景。"
    )
    public void directAnswer() {}
}
