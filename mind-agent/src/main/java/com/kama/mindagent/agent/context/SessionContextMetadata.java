package com.kama.mindagent.agent.context;

import com.kama.mindagent.model.dto.ChatSessionDTO;
import org.springframework.util.StringUtils;

/**
 * Maps optional session-context fields kept in the existing JSONB metadata
 * column. Unknown or legacy metadata remains harmlessly compatible.
 */
public final class SessionContextMetadata {

    private SessionContextMetadata() {
    }

    public static ConversationSummary readSummary(ChatSessionDTO.MetaData metadata) {
        if (metadata == null
                || !StringUtils.hasText(metadata.getSummary())
                || metadata.getSummaryVersion() == null
                || metadata.getSummaryVersion() < 1) {
            return null;
        }
        try {
            return new ConversationSummary(
                    metadata.getSummary(),
                    metadata.getSummaryVersion(),
                    metadata.getLastSummarizedMessageId()
            );
        } catch (IllegalArgumentException ignored) {
            // Metadata is persisted session data; an invalid old value must
            // not prevent the session from being opened.
            return null;
        }
    }

    public static ChatSessionDTO.MetaData mergeSummary(
            ChatSessionDTO.MetaData metadata,
            ConversationSummary summary
    ) {
        if (summary == null) {
            return metadata;
        }
        ChatSessionDTO.MetaData target = metadata == null
                ? new ChatSessionDTO.MetaData()
                : metadata;
        target.setSummary(summary.text());
        target.setSummaryVersion(summary.version());
        target.setLastSummarizedMessageId(summary.lastSummarizedMessageId());
        return target;
    }
}
