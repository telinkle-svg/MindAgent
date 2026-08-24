package com.kama.mindagent.agent;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

@FunctionalInterface
public interface ModelResponseGateway {

    ChatResponse request(Prompt prompt, String systemPrompt, List<ToolCallback> tools);
}
