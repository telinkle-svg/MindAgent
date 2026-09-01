package com.kama.mindagent.agent.context;

import org.springframework.util.StringUtils;

/**
 * The durable summary of the older part of one chat session.
 *
 * @param text                    bounded natural-language summary
 * @param version                 monotonically increasing summary version
 * @param lastSummarizedMessageId message anchor covered by this summary
 */
public record ConversationSummary(
        String text,
        int version,
        String lastSummarizedMessageId
) {

    public static final int MAX_TEXT_CHARS = 4_000;

    public ConversationSummary {
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("summary text must not be blank");
        }
        if (text.length() > MAX_TEXT_CHARS) {
            throw new IllegalArgumentException("summary text exceeds " + MAX_TEXT_CHARS + " characters");
        }
        if (version < 1) {
            throw new IllegalArgumentException("summary version must be positive");
        }
        text = text.strip();
        if (lastSummarizedMessageId != null && lastSummarizedMessageId.isBlank()) {
            lastSummarizedMessageId = null;
        }
    }
}
