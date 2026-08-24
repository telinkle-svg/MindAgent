package com.kama.mindagent.model.request;

import com.kama.mindagent.model.dto.AgentDTO;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class CreateAgentRequest {
    @NotBlank(message = "Agent 名称不能为空")
    private String name;
    private String description;
    private String systemPrompt;
    private String model;
    private List<String> allowedTools;
    private List<String> allowedKbs;
    private AgentDTO.ChatOptions chatOptions;
}
