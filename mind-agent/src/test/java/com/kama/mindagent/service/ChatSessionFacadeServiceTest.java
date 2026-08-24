package com.kama.mindagent.service;

import com.kama.mindagent.converter.ChatSessionConverter;
import com.kama.mindagent.exception.BizException;
import com.kama.mindagent.mapper.AgentMapper;
import com.kama.mindagent.mapper.ChatSessionMapper;
import com.kama.mindagent.service.impl.ChatSessionFacadeServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatSessionFacadeServiceTest {

    private final ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final ChatSessionFacadeServiceImpl service = new ChatSessionFacadeServiceImpl(
            chatSessionMapper,
            mock(ChatSessionConverter.class),
            agentMapper
    );

    @Test
    void getSessionsForMissingAgent_raisesNotFoundBeforeQueryingSessions() {
        when(agentMapper.selectById("missing-agent")).thenReturn(null);

        BizException exception = catchThrowableOfType(
                () -> service.getChatSessionsByAgentId("missing-agent"),
                BizException.class
        );

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exception).hasMessage("Agent 不存在: missing-agent");
        verify(chatSessionMapper, never()).selectByAgentId("missing-agent");
    }
}
