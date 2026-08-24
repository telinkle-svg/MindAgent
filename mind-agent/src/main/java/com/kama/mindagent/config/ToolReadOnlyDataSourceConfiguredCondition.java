package com.kama.mindagent.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

public final class ToolReadOnlyDataSourceConfiguredCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return StringUtils.hasText(context.getEnvironment().getProperty(
                "mindagent.tool.sql.datasource.url"
        )) && StringUtils.hasText(context.getEnvironment().getProperty(
                "mindagent.tool.sql.datasource.username"
        )) && StringUtils.hasText(context.getEnvironment().getProperty(
                "mindagent.tool.sql.datasource.password"
        ));
    }
}
