package com.kama.mindagent.agent.tools;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class ReadOnlySqlToolTest {

    @Test
    void query_rejectsNonSelectBeforeCallingJdbcTemplate() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ReadOnlySqlTool tool = new ReadOnlySqlTool(jdbcTemplate, new ReadOnlySqlPolicy());

        String result = tool.executeQuery("  UPDATE t_system_kv SET value = value WHERE 1 = 0");

        assertThat(result).isEqualTo("错误：SQL 查询不符合只读访问策略。");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void query_returnsSafeMessageWhenJdbcTemplateFails() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(
                anyString(),
                org.mockito.ArgumentMatchers.<ResultSetExtractor<List<String>>>any()
        )).thenThrow(new DataAccessResourceFailureException("secret database details"));
        ReadOnlySqlTool tool = new ReadOnlySqlTool(jdbcTemplate, new ReadOnlySqlPolicy());

        String result = tool.executeQuery("SELECT id FROM agent_tool.knowledge_base_summary");

        assertThat(result).isEqualTo("错误：查询执行失败，请检查查询条件后重试。");
        assertThat(result).doesNotContain("secret database details", "agent_tool.knowledge_base_summary");
    }
}
