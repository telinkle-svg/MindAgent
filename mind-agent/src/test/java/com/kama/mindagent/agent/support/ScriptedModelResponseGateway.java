package com.kama.mindagent.agent.support;

import com.kama.mindagent.agent.ModelResponseGateway;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class ScriptedModelResponseGateway implements ModelResponseGateway {

    public record Call(Prompt prompt, String systemPrompt, List<ToolCallback> tools) {
    }

    private final Deque<ChatResponse> responses;
    private final List<Call> calls = new ArrayList<>();

    public ScriptedModelResponseGateway(List<ChatResponse> responses) {
        this.responses = new ArrayDeque<>(responses);
    }

    @Override
    public ChatResponse request(Prompt prompt, String systemPrompt, List<ToolCallback> tools) {
        calls.add(new Call(prompt, systemPrompt, List.copyOf(tools)));
        if (responses.isEmpty()) {
            throw new IllegalStateException("Scripted chat gateway received an unexpected extra call");
        }
        return responses.removeFirst();
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }
}
