package com.kama.mindagent.config;

import com.kama.mindagent.agent.tools.ReadOnlySqlTool;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class ToolReadOnlyDataSourceConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ToolReadOnlyDataSourceConfig.class);

    @Test
    void missingToolDatasourceProperties_doNotRegisterDatabaseToolOrFallbackTemplate() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(ReadOnlySqlTool.class);
            assertThat(context).doesNotHaveBean("toolReadOnlyJdbcTemplate");
        });
    }

    @Test
    void partialToolDatasourceProperties_doNotRegisterDatabaseTool() {
        contextRunner
                .withPropertyValues(
                        "mindagent.tool.sql.datasource.url=jdbc:postgresql://127.0.0.1:1/isolated",
                        "mindagent.tool.sql.datasource.username=agent_reader"
                )
                .run(context -> assertThat(context).doesNotHaveBean(ReadOnlySqlTool.class));
    }

    @Test
    void completeToolDatasourceProperties_registerBoundedDedicatedTool() {
        contextRunner
                .withPropertyValues(
                        "mindagent.tool.sql.datasource.url=jdbc:postgresql://127.0.0.1:1/isolated",
                        "mindagent.tool.sql.datasource.username=agent_reader",
                        "mindagent.tool.sql.datasource.password=reader-password"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ReadOnlySqlTool.class);
                    JdbcTemplate jdbcTemplate = context.getBean(
                            "toolReadOnlyJdbcTemplate",
                            JdbcTemplate.class
                    );
                    assertThat(jdbcTemplate.getMaxRows()).isEqualTo(100);
                    assertThat(jdbcTemplate.getQueryTimeout()).isEqualTo(3);
                });
    }
}
