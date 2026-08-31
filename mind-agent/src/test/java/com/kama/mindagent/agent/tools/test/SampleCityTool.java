package com.kama.mindagent.agent.tools.test;

import com.kama.mindagent.agent.tools.AgentTool;
import com.kama.mindagent.agent.tools.ToolCategory;
public class SampleCityTool implements AgentTool {
    @Override
    public String name() {
        return "cityTool";
    }

    @Override
    public String description() {
        return "获取当前的城市";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.REQUIRED;
    }

    @org.springframework.ai.tool.annotation.Tool(name = "getCity", description = "获取当前的城市")
    public String getCity() {
        return "深圳";
    }
}
