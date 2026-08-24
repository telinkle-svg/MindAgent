package com.kama.mindagent.agent.tools.test;

import com.kama.mindagent.agent.tools.AgentTool;
import com.kama.mindagent.agent.tools.ToolCategory;
import org.springframework.stereotype.Component;

@Component
public class SampleWeatherTool implements AgentTool {

    @Override
    public String name() {
        return "weatherTool";
    }

    @Override
    public String description() {
        return "获取天气";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.REQUIRED;
    }

    @org.springframework.ai.tool.annotation.Tool(name = "weather", description = "获取天气")
    public String getWeather(String city, String date) {
        // 模拟模拟调用天气 API
        return city + date + "的天气查询结果：晴转多云，温度 25°C，湿度 60%";
    }
}
