package com.kama.mindagent.agent.context;

import com.kama.mindagent.agent.ModelResponseGateway;
import com.kama.mindagent.agent.support.AgentTestMessages;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationSummarizerTest {

    @Test
    void createsIncrementalSummaryWithoutExposingToolCallbacks() {
        ModelResponseGateway gateway = mock(ModelResponseGateway.class);
        when(gateway.request(any(Prompt.class), any(String.class), any()))
                .thenReturn(AgentTestMessages.assistantText("用户要完成数据迁移；查询已确认 42 条记录。"));
        ConversationSummary previous = new ConversationSummary("用户正在处理迁移任务。", 2, "message-2");
        ConversationSummarizer summarizer = new ConversationSummarizer(gateway);

        ConversationSummary summary = summarizer.summarize(List.of(
                new UserMessage("继续检查迁移结果"),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                "call-3", "databaseQuery", "42 rows")))
                        .build()
        ), previous, "message-3");

        assertThat(summary.version()).isEqualTo(3);
        assertThat(summary.lastSummarizedMessageId()).isEqualTo("message-3");
        assertThat(summary.text()).contains("42");

        ArgumentCaptor<List<ToolCallback>> toolsCaptor = ArgumentCaptor.forClass(List.class);
        verify(gateway).request(any(Prompt.class), any(String.class), toolsCaptor.capture());
        assertThat(toolsCaptor.getValue()).isEmpty();
    }

    @Test
    void summaryFailureDoesNotMutatePreviousSummary() {
        ModelResponseGateway gateway = mock(ModelResponseGateway.class);
        when(gateway.request(any(Prompt.class), any(String.class), any()))
                .thenThrow(new IllegalStateException("provider unavailable"));
        ConversationSummary previous = new ConversationSummary("keep this", 4, "message-4");
        ConversationSummarizer summarizer = new ConversationSummarizer(gateway);

        assertThatThrownBy(() -> summarizer.summarize(
                List.of(new UserMessage("older turn")), previous, "message-5"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(previous.text()).isEqualTo("keep this");
        assertThat(previous.version()).isEqualTo(4);
    }
}
