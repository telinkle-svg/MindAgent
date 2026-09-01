package com.kama.mindagent.agent.context;

import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;

/**
 * Creates bounded copies of tool results for model context assembly.
 *
 * <p>Persisted tool messages are intentionally not modified. This keeps the
 * audit trail complete while preventing a large result from consuming the
 * next model request's context window.</p>
 */
public final class ToolResultTruncator {

    private static final String MARKER_PREFIX = "...[truncated:";
    private static final String MARKER_SUFFIX = "]...";

    private final int maxChars;

    public ToolResultTruncator() {
        this(ContextBudgetPolicy.DEFAULT_MAX_TOOL_RESULT_CHARS);
    }

    public ToolResultTruncator(int maxChars) {
        if (maxChars < 1) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        this.maxChars = maxChars;
    }

    public String truncate(String value) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }

        String marker = MARKER_PREFIX + value.length() + MARKER_SUFFIX;
        if (marker.length() >= maxChars) {
            return marker.substring(0, maxChars);
        }

        int remaining = maxChars - marker.length();
        int headLength = (remaining + 1) / 2;
        int tailLength = remaining - headLength;
        String head = value.substring(0, headLength);
        String tail = tailLength == 0
                ? ""
                : value.substring(value.length() - tailLength);
        return head + marker + tail;
    }

    public ToolResponseMessage truncate(ToolResponseMessage message) {
        if (message == null || message.getResponses() == null || message.getResponses().isEmpty()) {
            return message;
        }

        boolean changed = false;
        List<ToolResponseMessage.ToolResponse> boundedResponses =
                new java.util.ArrayList<>(message.getResponses().size());
        for (ToolResponseMessage.ToolResponse response : message.getResponses()) {
            String boundedData = truncate(response.responseData());
            changed |= !java.util.Objects.equals(response.responseData(), boundedData);
            boundedResponses.add(new ToolResponseMessage.ToolResponse(
                    response.id(),
                    response.name(),
                    boundedData
            ));
        }
        if (!changed) {
            return message;
        }
        return ToolResponseMessage.builder()
                .responses(List.copyOf(boundedResponses))
                .metadata(message.getMetadata())
                .build();
    }
}
