package com.kama.mindagent.model.request;

import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * Immutable position of the user message that triggered an Agent run.
 *
 * <p>The message ID is used as a deterministic tie-breaker when two rows have
 * the same timestamp. An invalid/absent anchor lets compatibility callers use
 * the legacy unbounded-by-trigger query.</p>
 */
public record ChatHistoryAnchor(String messageId, LocalDateTime createdAt) {

    public boolean isValid() {
        return StringUtils.hasText(messageId) && createdAt != null;
    }
}
