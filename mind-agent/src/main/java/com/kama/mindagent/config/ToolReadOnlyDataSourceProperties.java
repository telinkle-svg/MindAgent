package com.kama.mindagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "mindagent.tool.sql.datasource")
public class ToolReadOnlyDataSourceProperties {
    private String url;
    private String username;
    private String password;
}
