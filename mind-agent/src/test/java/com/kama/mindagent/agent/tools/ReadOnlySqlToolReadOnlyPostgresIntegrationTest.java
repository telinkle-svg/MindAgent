package com.kama.mindagent.agent.tools;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "MINDAGENT_AGENT_TOOL_IT", matches = "true")
class ReadOnlySqlToolReadOnlyPostgresIntegrationTest {

    private static final String FIXTURE_KB_ID = "00000000-0000-0000-0000-000000000001";

    private HikariDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private ReadOnlySqlTool tool;

    @BeforeEach
    void setUp() {
        String url = System.getenv("MINDAGENT_AGENT_TOOL_IT_JDBC_URL");
        String username = System.getenv("MINDAGENT_AGENT_TOOL_IT_USERNAME");
        String password = System.getenv("MINDAGENT_AGENT_TOOL_IT_PASSWORD");
        Assumptions.assumeTrue(
                StringUtils.hasText(url)
                        && StringUtils.hasText(username)
                        && StringUtils.hasText(password),
                "isolated agent-tool integration credentials are not configured"
        );

        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setReadOnly(true);
        dataSource.setConnectionInitSql("SET statement_timeout = '3s'");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.setMaxRows(100);
        jdbcTemplate.setQueryTimeout(3);
        tool = new ReadOnlySqlTool(jdbcTemplate, new ReadOnlySqlPolicy());
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void readOnlyRoleCanReadControlledViewButCannotReadBaseTable() {
        String result = tool.executeQuery(
                "SELECT id, name FROM agent_tool.knowledge_base_summary "
                        + "WHERE id = '" + FIXTURE_KB_ID + "'"
        );

        assertThat(result).contains("agent-tool-it-kb");
        assertThatThrownBy(() -> jdbcTemplate.queryForObject(
                "SELECT name FROM public.knowledge_base WHERE id = '" + FIXTURE_KB_ID + "'",
                String.class
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    void writeAndLockingRequestsAreRejected() {
        assertThat(tool.executeQuery(
                "UPDATE agent_tool.document_summary SET filename = filename"
        )).isEqualTo("错误：SQL 查询不符合只读访问策略。");
        assertThat(tool.executeQuery(
                "SELECT id FROM agent_tool.document_summary FOR UPDATE"
        )).isEqualTo("错误：SQL 查询不符合只读访问策略。");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE public.document SET filename = filename WHERE 1 = 0"
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    void resultSetIsCappedAtOneHundredRows() {
        String result = tool.executeQuery(
                "SELECT id FROM agent_tool.document_summary "
                        + "WHERE kb_id = '" + FIXTURE_KB_ID + "' ORDER BY id"
        );

        long tableLines = result.lines()
                .filter(line -> line.startsWith("| "))
                .count();
        assertThat(tableLines).isEqualTo(101);
    }

    @Test
    void slowQueryReturnsSafeFailureWithinBoundedTime() {
        long startedAt = System.nanoTime();

        String result = tool.executeQuery(
                "SELECT pg_sleep(4), id FROM agent_tool.knowledge_base_summary "
                        + "WHERE id = '" + FIXTURE_KB_ID + "'"
        );

        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        assertThat(result).isEqualTo("错误：查询执行失败，请检查查询条件后重试。");
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
    }
}
