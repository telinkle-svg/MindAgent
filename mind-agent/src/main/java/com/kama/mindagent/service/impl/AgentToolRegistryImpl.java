package com.kama.mindagent.service.impl;

import com.kama.mindagent.agent.tools.AgentTool;
import com.kama.mindagent.agent.tools.ToolCategory;
import com.kama.mindagent.service.AgentToolRegistry;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@AllArgsConstructor
public class AgentToolRegistryImpl implements AgentToolRegistry {

    private final List<AgentTool> tools;

    @Override
    public List<AgentTool> listAll() {
        return tools;
    }

    @Override
    public List<AgentTool> listOptional() {
        return getToolsByCategory(ToolCategory.OPTIONAL);
    }

    @Override
    public List<AgentTool> listRequired() {
        return getToolsByCategory(ToolCategory.REQUIRED);
    }

    private List<AgentTool> getToolsByCategory(ToolCategory category) {
        return tools.stream()
                .filter(tool -> tool.category().equals(category))
                .toList();
    }
}
