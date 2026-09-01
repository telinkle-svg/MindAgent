package com.kama.mindagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.mindagent.agent.context.ConversationSummary;
import com.kama.mindagent.converter.ChatSessionConverter;
import com.kama.mindagent.mapper.AgentMapper;
import com.kama.mindagent.mapper.ChatSessionMapper;
import com.kama.mindagent.model.entity.ChatSession;
import com.kama.mindagent.service.impl.ChatSessionFacadeServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatSessionSummaryPersistenceTest {

    @Test
    void mergesSummaryIntoExistingJsonMetadata() throws Exception {
        ChatSessionMapper mapper = mock(ChatSessionMapper.class);
        ChatSession session = ChatSession.builder()
                .id("session-1")
                .metadata("{\"legacy\":\"value\",\"summary\":\"old\",\"summaryVersion\":1}")
                .build();
        when(mapper.selectById("session-1")).thenReturn(session);
        when(mapper.updateMetadataById(anyString(), anyString())).thenReturn(1);
        ChatSessionFacadeServiceImpl service = new ChatSessionFacadeServiceImpl(
                mapper,
                new ChatSessionConverter(new ObjectMapper()),
                mock(AgentMapper.class)
        );

        service.updateChatSessionSummary(
                "session-1",
                new ConversationSummary("new facts", 2, "message-2")
        );

        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(mapper).updateMetadataById(org.mockito.ArgumentMatchers.eq("session-1"), metadataCaptor.capture());
        JsonNode json = new ObjectMapper().readTree(metadataCaptor.getValue());
        assertThat(json.get("summary").asText()).isEqualTo("new facts");
        assertThat(json.get("summaryVersion").asInt()).isEqualTo(2);
        assertThat(json.get("lastSummarizedMessageId").asText()).isEqualTo("message-2");
        assertThat(json.get("legacy").asText()).isEqualTo("value");
    }
}
