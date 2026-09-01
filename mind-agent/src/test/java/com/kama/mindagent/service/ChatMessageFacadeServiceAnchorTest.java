package com.kama.mindagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.mindagent.converter.ChatMessageConverter;
import com.kama.mindagent.mapper.AgentMapper;
import com.kama.mindagent.mapper.ChatMessageMapper;
import com.kama.mindagent.mapper.ChatSessionMapper;
import com.kama.mindagent.model.request.ChatHistoryAnchor;
import com.kama.mindagent.service.impl.ChatMessageFacadeServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMessageFacadeServiceAnchorTest {

    @Test
    void loadsRecentMessagesThroughAnchorAwareMapperQuery() {
        ChatMessageMapper mapper = mock(ChatMessageMapper.class);
        ChatMessageConverter converter = new ChatMessageConverter(new ObjectMapper());
        ChatMessageFacadeServiceImpl service = new ChatMessageFacadeServiceImpl(
                mapper,
                converter,
                mock(ApplicationEventPublisher.class),
                mock(AgentMapper.class),
                mock(ChatSessionMapper.class)
        );
        ChatHistoryAnchor anchor = new ChatHistoryAnchor(
                "11111111-1111-1111-1111-111111111111",
                LocalDateTime.of(2026, 8, 31, 12, 0)
        );
        when(mapper.selectBySessionIdRecentlyBefore("session-1", anchor.createdAt(), anchor.messageId(), 7))
                .thenReturn(List.of());

        service.getChatMessagesBySessionIdRecently("session-1", 7, anchor);

        verify(mapper).selectBySessionIdRecentlyBefore(
                "session-1", anchor.createdAt(), anchor.messageId(), 7);
    }
}
