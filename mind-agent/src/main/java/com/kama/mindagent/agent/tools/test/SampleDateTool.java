package com.kama.mindagent.agent.tools.test;

import com.kama.mindagent.agent.tools.AgentTool;
import com.kama.mindagent.agent.tools.ToolCategory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class SampleDateTool implements AgentTool {
    @Override
    public String name() {
        return "dateTool";
    }

    @Override
    public String description() {
        return "获取当前的日期";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.REQUIRED;
    }

    @org.springframework.ai.tool.annotation.Tool(name = "getDate", description = "获取当前的日期")
    public String getDate() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }
}
