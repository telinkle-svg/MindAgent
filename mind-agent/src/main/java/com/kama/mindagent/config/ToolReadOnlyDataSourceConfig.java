package com.kama.mindagent.config;

import com.kama.mindagent.agent.tools.ReadOnlySqlTool;
import com.kama.mindagent.agent.tools.ReadOnlySqlPolicy;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@Conditional(ToolReadOnlyDataSourceConfiguredCondition.class)
@EnableConfigurationProperties(ToolReadOnlyDataSourceProperties.class)
public class ToolReadOnlyDataSourceConfig {

    @Bean(name = "toolReadOnlyDataSource", destroyMethod = "close")
    HikariDataSource toolReadOnlyDataSource(ToolReadOnlyDataSourceProperties properties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(properties.getUrl());
        dataSource.setUsername(properties.getUsername());
        dataSource.setPassword(properties.getPassword());
        dataSource.setReadOnly(true);
        dataSource.setMaximumPoolSize(2);
        dataSource.setConnectionInitSql("SET statement_timeout = '3s'");
        return dataSource;
    }

    @Bean(name = "toolReadOnlyJdbcTemplate")
    JdbcTemplate toolReadOnlyJdbcTemplate(
            @Qualifier("toolReadOnlyDataSource") HikariDataSource dataSource
    ) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.setMaxRows(100);
        jdbcTemplate.setQueryTimeout(3);
        return jdbcTemplate;
    }

    @Bean
    ReadOnlySqlPolicy readOnlySqlPolicy() {
        return new ReadOnlySqlPolicy();
    }

    @Bean
    ReadOnlySqlTool readOnlySqlTool(
            @Qualifier("toolReadOnlyJdbcTemplate") JdbcTemplate jdbcTemplate,
            ReadOnlySqlPolicy readOnlySqlPolicy
    ) {
        return new ReadOnlySqlTool(jdbcTemplate, readOnlySqlPolicy);
    }
}
