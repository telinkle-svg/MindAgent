package com.kama.mindagent.agent.context;

import com.kama.mindagent.model.dto.ChatSessionDTO;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SessionContextMetadataTest {

    @Test
    void summaryRoundTripsAndIsPrependedAsLabeledContext() {
        ConversationSummary summary = new ConversationSummary(
                "已确认用户要迁移 42 条数据。",
                3,
                "message-9"
        );
        ChatSessionDTO.MetaData metadata = SessionContextMetadata.mergeSummary(null, summary);

        ConversationSummary restored = SessionContextMetadata.readSummary(metadata);
        List<Message> assembled = new ConversationContextAssembler(
                new ContextBudgetPolicy(100, 2), restored)
                .assemble(List.of(new UserMessage("当前问题")));

        assertThat(restored).isEqualTo(summary);
        assertThat(assembled).hasSize(2);
        assertThat(assembled.get(0).getText())
                .startsWith("【会话摘要】")
                .contains("42");
        assertThat(assembled.get(1).getText()).isEqualTo("当前问题");
    }

    @Test
    void invalidLegacyMetadataIsIgnored() {
        ChatSessionDTO.MetaData metadata = new ChatSessionDTO.MetaData();
        metadata.setSummary(" ");
        metadata.setSummaryVersion(0);

        assertThat(SessionContextMetadata.readSummary(metadata)).isNull();
    }
}
