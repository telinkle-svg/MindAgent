package com.kama.mindagent.service;

import com.kama.mindagent.model.request.CreateAgentRequest;
import com.kama.mindagent.model.request.UpdateAgentRequest;
import com.kama.mindagent.model.response.CreateAgentResponse;
import com.kama.mindagent.model.response.GetAgentsResponse;

public interface AgentFacadeService {
    GetAgentsResponse getAgents();

    CreateAgentResponse createAgent(CreateAgentRequest request);

    void deleteAgent(String agentId);

    void updateAgent(String agentId, UpdateAgentRequest request);
}
