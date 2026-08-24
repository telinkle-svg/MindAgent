package com.kama.mindagent.agent.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadOnlySqlPolicyTest {

    private final ReadOnlySqlPolicy policy = new ReadOnlySqlPolicy();

    @Test
    void acceptsSingleSelectAgainstControlledView() {
        String sql = " SELECT id, name FROM agent_tool.knowledge_base_summary ORDER BY name ";

        assertThat(policy.validate(sql))
                .isEqualTo("SELECT id, name FROM agent_tool.knowledge_base_summary ORDER BY name");
    }

    @Test
    void acceptsReadOnlyWithQueryOverControlledView() {
        String sql = "WITH recent AS ("
                + "SELECT id, name FROM agent_tool.knowledge_base_summary"
                + ") SELECT id, name FROM recent";

        assertThat(policy.validate(sql)).isEqualTo(sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "UPDATE agent_tool.document_summary SET filename = filename",
            "DELETE FROM agent_tool.document_summary",
            "INSERT INTO agent_tool.document_summary (filename) VALUES ('x')",
            "CREATE TABLE agent_tool.created_by_agent (id int)",
            "DROP TABLE agent_tool.document_summary",
            "TRUNCATE agent_tool.document_summary",
            "CALL refresh_agent_tool()",
            "SELECT id FROM agent_tool.document_summary; SELECT id FROM agent_tool.knowledge_base_summary",
            "SELECT id FROM agent_tool.document_summary FOR UPDATE",
            "SELECT id INTO agent_tool.created_by_agent FROM agent_tool.document_summary",
            "SELECT id FROM public.document",
            "SELECT id FROM agent_tool.document_summary JOIN public.document ON true",
            "SELECT 1"
    })
    void rejectsStatementsOutsideReadOnlyControlledViewPolicy(String sql) {
        assertThatThrownBy(() -> policy.validate(sql))
                .isInstanceOf(SqlPolicyViolationException.class);
    }
}
