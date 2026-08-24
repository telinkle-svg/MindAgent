package com.kama.mindagent.agent.tools;

import com.fasterxml.jackson.annotation.JsonProperty;

public interface AgentTool {
    @JsonProperty("name")
    String name();

    @JsonProperty("description")
    String description();

    // Keep the existing HTTP field name while using a clearer internal category name.
    @JsonProperty("type")
    ToolCategory category();
}
