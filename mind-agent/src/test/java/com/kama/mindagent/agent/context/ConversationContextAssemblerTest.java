package com.kama.mindagent.agent.context;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationContextAssemblerTest {

    @Test
    void keepsSystemMessageAndNewestCompleteTurns() {
        ConversationContextAssembler assembler = new ConversationContextAssembler(
                new ContextBudgetPolicy(100, 2));
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("system"));
        for (int turn = 1; turn <= 4; turn++) {
            messages.add(new UserMessage("user-" + turn));
            messages.add(AssistantMessage.builder().content("assistant-" + turn).build());
            messages.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            "call-" + turn, "tool", "tool-" + turn)))
                    .build());
        }

        ConversationContextAssembler.AssemblyResult result = assembler.assembleWithStats(messages);

        assertThat(result.messages()).hasSize(7);
        assertThat(result.messages()).extracting(Message::getText)
                .containsExactly("system", "user-3", "assistant-3", "", "user-4", "assistant-4", "");
        assertThat(((ToolResponseMessage) result.messages().get(3)).getResponses().get(0).responseData())
                .isEqualTo("tool-3");
        assertThat(((ToolResponseMessage) result.messages().get(6)).getResponses().get(0).responseData())
                .isEqualTo("tool-4");
        assertThat(result.omittedTurns()).isEqualTo(2);
    }

    @Test
    void neverSplitsAssistantToolPairWhenWindowOverflows() {
        ConversationContextAssembler assembler = new ConversationContextAssembler(
                new ContextBudgetPolicy(100, 1));
        List<Message> messages = List.of(
                new SystemMessage("system"),
                new UserMessage("user-1"),
                AssistantMessage.builder().content("assistant-1").build(),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "tool", "tool-1")))
                        .build(),
                new UserMessage("user-2"),
                AssistantMessage.builder().content("assistant-2").build(),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse("call-2", "tool", "tool-2")))
                        .build()
        );

        List<Message> assembled = assembler.assemble(messages);

        assertThat(assembled).extracting(Message::getText)
                .containsExactly("system", "user-2", "assistant-2", "");
        assertThat(((ToolResponseMessage) assembled.get(3)).getResponses().get(0).responseData())
                .isEqualTo("tool-2");
    }

    @Test
    void truncatesToolResultsOnlyInAssembledContext() {
        ConversationContextAssembler assembler = new ConversationContextAssembler(
                new ContextBudgetPolicy(20, 10));
        String original = "z".repeat(100);
        ToolResponseMessage tool = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "tool", original)))
                .build();

        ConversationContextAssembler.AssemblyResult result = assembler.assembleWithStats(List.of(
                new UserMessage("question"),
                AssistantMessage.builder().content("calling").build(),
                tool
        ));

        assertThat(tool.getResponses().get(0).responseData()).isEqualTo(original);
        assertThat(result.messages().get(2).getText()).hasSizeLessThanOrEqualTo(20);
        assertThat(result.truncatedToolResults()).isEqualTo(1);
    }
}
