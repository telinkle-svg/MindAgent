package com.kama.mindagent.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolJsonContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesRenamedToolMethodsUsingLegacyHttpFieldsAndValues() throws Exception {
        AgentTool tool = new AgentTool() {
            @Override
            public String name() {
                return "sampleTool";
            }

            @Override
            public String description() {
                return "sample description";
            }

            @Override
            public ToolCategory category() {
                return ToolCategory.REQUIRED;
            }
        };

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(tool));

        assertThat(json.get("name").asText()).isEqualTo("sampleTool");
        assertThat(json.get("description").asText()).isEqualTo("sample description");
        assertThat(json.get("type").asText()).isEqualTo("FIXED");
        assertThat(json.has("category")).isFalse();
    }
}
