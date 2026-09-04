package com.kama.mindagent.agent.support;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;

import java.util.List;

public final class AgentTestMessages {

    private AgentTestMessages() {
    }

    public static ChatResponse assistantText(String content) {
        return response(AssistantMessage.builder()
                .content(content)
                .toolCalls(List.of())
                .build());
    }

    public static ChatResponse assistantTextWithUsage(String content, Usage usage) {
        return response(
                AssistantMessage.builder()
                        .content(content)
                        .toolCalls(List.of())
                        .build(),
                usage
        );
    }

    public static ChatResponse assistantToolCall(String id, String name, String arguments) {
        return assistantToolCallWithText("", id, name, arguments);
    }

    public static ChatResponse assistantToolCallWithText(
            String content,
            String id,
            String name,
            String arguments
    ) {
        return assistantToolCalls(content, List.of(new AssistantMessage.ToolCall(
                id,
                "function",
                name,
                arguments
        )));
    }

    public static ChatResponse assistantToolCalls(
            String content,
            List<AssistantMessage.ToolCall> toolCalls
    ) {
        return response(AssistantMessage.builder()
                .content(content)
                .toolCalls(toolCalls)
                .build());
    }

    public static ToolResponseMessage toolResponse(String id, String name, String responseData) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, name, responseData)))
                .build();
    }

    private static ChatResponse response(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static ChatResponse response(AssistantMessage message, Usage usage) {
        return new ChatResponse(
                List.of(new Generation(message)),
                ChatResponseMetadata.builder().usage(usage).build()
        );
    }
}
