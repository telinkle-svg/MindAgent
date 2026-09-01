package com.kama.mindagent.agent.context;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultTruncatorTest {

    @Test
    void keepsHeadAndTailWithBoundedMarker() {
        ToolResultTruncator truncator = new ToolResultTruncator(32);
        String original = "HEAD-" + "x".repeat(100) + "-TAIL";

        String bounded = truncator.truncate(original);

        assertThat(bounded).hasSizeLessThanOrEqualTo(32);
        assertThat(bounded).startsWith("HEAD-");
        assertThat(bounded).endsWith("-TAIL");
        assertThat(bounded).contains("truncated");
        assertThat(bounded).contains(String.valueOf(original.length()));
    }

    @Test
    void leavesShortValuesAndNullUntouched() {
        ToolResultTruncator truncator = new ToolResultTruncator(32);

        assertThat(truncator.truncate("short")).isEqualTo("short");
        assertThat(truncator.truncate((String) null)).isNull();
    }

    @Test
    void truncatesToolMessageForContextWithoutChangingOriginalObject() {
        ToolResultTruncator truncator = new ToolResultTruncator(24);
        String original = "result-" + "y".repeat(80);
        ToolResponseMessage message = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "tool", original)))
                .build();

        ToolResponseMessage bounded = truncator.truncate(message);

        assertThat(message.getResponses().get(0).responseData()).isEqualTo(original);
        assertThat(bounded.getResponses().get(0).responseData())
                .hasSizeLessThanOrEqualTo(24)
                .contains("truncated");
    }
}
