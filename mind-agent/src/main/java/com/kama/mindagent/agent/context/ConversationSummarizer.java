package com.kama.mindagent.agent.context;

import com.kama.mindagent.agent.ModelResponseGateway;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Generates an incremental session summary through the configured model.
 *
 * <p>The summarizer deliberately sends no tool callbacks. Summary generation
 * is a context-maintenance operation and must never execute an external side
 * effect.</p>
 */
public final class ConversationSummarizer {

    private static final String SUMMARY_SYSTEM_PROMPT = """
            你是会话记忆整理器。请把给定的历史对话压缩成可供后续 Agent 使用的事实摘要。
            只保留用户目标、已确认事实、工具结果中的关键结论、未完成事项和约束。
            不要输出思考过程、工具调用 JSON 或无关修辞，直接输出摘要。
            """;

    private final ModelResponseGateway gateway;
    private final int maxSummaryChars;

    public ConversationSummarizer(ModelResponseGateway gateway) {
        this(gateway, ConversationSummary.MAX_TEXT_CHARS);
    }

    public ConversationSummarizer(ModelResponseGateway gateway, int maxSummaryChars) {
        this.gateway = Objects.requireNonNull(gateway, "gateway cannot be null");
        if (maxSummaryChars < 1 || maxSummaryChars > ConversationSummary.MAX_TEXT_CHARS) {
            throw new IllegalArgumentException("maxSummaryChars must be between 1 and "
                    + ConversationSummary.MAX_TEXT_CHARS);
        }
        this.maxSummaryChars = maxSummaryChars;
    }

    public ConversationSummary summarize(
            List<Message> messages,
            ConversationSummary previous,
            String lastSummarizedMessageId
    ) {
        return summarize(messages, previous, lastSummarizedMessageId, response -> {
        });
    }

    /**
     * Generates a summary and reports provider usage metadata to the caller.
     * Summary text remains the only value returned by this class.
     */
    public ConversationSummary summarize(
            List<Message> messages,
            ConversationSummary previous,
            String lastSummarizedMessageId,
            Consumer<Usage> usageObserver
    ) {
        Objects.requireNonNull(messages, "messages cannot be null");
        Objects.requireNonNull(usageObserver, "usageObserver cannot be null");
        if (messages.isEmpty() && previous == null) {
            throw new IllegalArgumentException("messages must not be empty for an initial summary");
        }

        StringBuilder request = new StringBuilder();
        if (previous != null) {
            request.append("已有摘要（请在其基础上增量合并）：\n")
                    .append(previous.text())
                    .append("\n\n");
        }
        request.append("需要纳入摘要的历史消息：\n");
        for (Message message : messages) {
            appendMessage(request, message);
        }

        AssistantMessage output;
        try {
            ChatResponse response = gateway.request(
                    new Prompt(List.of(new UserMessage(request.toString()))),
                    SUMMARY_SYSTEM_PROMPT,
                    List.<ToolCallback>of()
            );
            if (response == null || response.getResult() == null) {
                throw new IllegalStateException("summary model returned no response");
            }
            usageObserver.accept(response.getMetadata() == null
                    ? null
                    : response.getMetadata().getUsage());
            output = response.getResult().getOutput();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("summary model call failed", exception);
        }
        if (output == null || !StringUtils.hasText(output.getText())) {
            throw new IllegalStateException("summary model returned blank content");
        }

        String text = output.getText().strip();
        if (text.length() > maxSummaryChars) {
            text = text.substring(0, maxSummaryChars);
        }
        int nextVersion = previous == null ? 1 : previous.version() + 1;
        String anchor = StringUtils.hasText(lastSummarizedMessageId)
                ? lastSummarizedMessageId
                : previous == null ? null : previous.lastSummarizedMessageId();
        return new ConversationSummary(text, nextVersion, anchor);
    }

    private void appendMessage(StringBuilder request, Message message) {
        if (message == null) {
            return;
        }
        if (message instanceof ToolResponseMessage toolResponse) {
            for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                request.append("[工具 ")
                        .append(response.name())
                        .append("] ")
                        .append(response.responseData())
                        .append('\n');
            }
            return;
        }
        if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
            request.append("[assistant 工具调用] ")
                    .append(assistant.getText())
                    .append('\n');
            return;
        }
        request.append('[')
                .append(message.getMessageType())
                .append("] ")
                .append(message.getText())
                .append('\n');
    }
}
