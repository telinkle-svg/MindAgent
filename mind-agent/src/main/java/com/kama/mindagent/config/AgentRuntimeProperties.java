package com.kama.mindagent.config;

import com.kama.mindagent.agent.AgentLoopPolicy;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Externalized safety limits for the Agent execution loop. */
@Getter
@Setter
@ConfigurationProperties(prefix = "mindagent.agent.loop")
public class AgentRuntimeProperties {

    private int maxIterations = AgentLoopPolicy.DEFAULT_MAX_ITERATIONS;
    private int maxModelCalls = AgentLoopPolicy.DEFAULT_MAX_MODEL_CALLS;
    private int maxPlanRevisions = AgentLoopPolicy.DEFAULT_MAX_PLAN_REVISIONS;
    private int maxToolCalls = AgentLoopPolicy.DEFAULT_MAX_TOOL_CALLS;
    private Duration maxRunDuration = AgentLoopPolicy.DEFAULT_MAX_RUN_DURATION;

    public AgentLoopPolicy toPolicy() {
        return new AgentLoopPolicy(
                maxIterations,
                maxModelCalls,
                maxPlanRevisions,
                maxToolCalls,
                maxRunDuration
        );
    }
}
