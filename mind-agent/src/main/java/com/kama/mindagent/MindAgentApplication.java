package com.kama.mindagent;

import com.kama.mindagent.config.AgentRuntimeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AgentRuntimeProperties.class)
public class MindAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(MindAgentApplication.class, args);
    }

}
