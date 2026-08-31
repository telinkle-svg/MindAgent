package com.kama.mindagent.agent.tools;

import com.kama.mindagent.service.AgentToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ProductionToolRegistrationTest {

    @Autowired
    private AgentToolRegistry agentToolRegistry;

    @Test
    void doesNotRegisterExampleToolsInTheProductionRegistry() {
        assertThat(agentToolRegistry.listAll())
                .extracting(AgentTool::name)
                .doesNotContain("cityTool", "dateTool", "weatherTool");
    }
}
