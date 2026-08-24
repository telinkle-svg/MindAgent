package com.kama.mindagent.service;

import com.kama.mindagent.agent.tools.AgentTool;

import java.util.List;

public interface AgentToolRegistry {
    List<AgentTool> listAll();

    List<AgentTool> listOptional();

    List<AgentTool> listRequired();
}
