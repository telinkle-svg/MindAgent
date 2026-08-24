package com.kama.mindagent.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

final class SpringAiResponseGateway implements ModelResponseGateway {

    private final ChatClient chatClient;

    SpringAiResponseGateway(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public ChatResponse request(Prompt prompt, String systemPrompt, List<ToolCallback> tools) {
        return chatClient.prompt(prompt)
                .system(systemPrompt)
                .toolCallbacks(tools.toArray(new ToolCallback[0]))
                .call()
                .chatClientResponse()
                .chatResponse();
    }
}
