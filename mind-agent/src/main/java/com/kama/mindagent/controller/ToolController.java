package com.kama.mindagent.controller;

import com.kama.mindagent.agent.tools.AgentTool;
import com.kama.mindagent.model.common.ApiResponse;
import com.kama.mindagent.service.AgentToolRegistry;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class ToolController {

    private final AgentToolRegistry agentToolRegistry;

    // 给前端提供的可选的工具列表
    @GetMapping("/tools")
    public ApiResponse<List<AgentTool>> listOptionalTools() {
        return ApiResponse.success(agentToolRegistry.listOptional());
    }
}
