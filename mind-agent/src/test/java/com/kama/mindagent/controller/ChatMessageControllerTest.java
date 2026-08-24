package com.kama.mindagent.controller;

import com.kama.mindagent.exception.BizException;
import com.kama.mindagent.model.dto.ChatMessageDTO;
import com.kama.mindagent.model.response.GetChatMessagesResponse;
import com.kama.mindagent.model.vo.ChatMessageVO;
import com.kama.mindagent.service.ChatMessageFacadeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatMessageController.class)
@Import(com.kama.mindagent.exception.GlobalExceptionHandler.class)
class ChatMessageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatMessageFacadeService chatMessageFacadeService;

    @Test
    void getMessages_returnsStandardSuccessEnvelope() throws Exception {
        when(chatMessageFacadeService.getChatMessagesBySessionId("session-1"))
                .thenReturn(GetChatMessagesResponse.builder()
                        .chatMessages(new ChatMessageVO[]{
                                ChatMessageVO.builder()
                                        .id("message-1")
                                        .sessionId("session-1")
                                        .role(ChatMessageDTO.RoleType.USER)
                                        .content("hello")
                                        .build()
                        })
                        .build());

        mockMvc.perform(get("/api/chat-messages/session/session-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.chatMessages[0].id").value("message-1"))
                .andExpect(jsonPath("$.data.chatMessages[0].role").value("user"))
                .andExpect(jsonPath("$.data.chatMessages[0].content").value("hello"));
    }

    @Test
    void getMessages_returnsNotFoundEnvelopeForMissingSession() throws Exception {
        when(chatMessageFacadeService.getChatMessagesBySessionId("missing-session"))
                .thenThrow(BizException.notFound("聊天会话不存在: missing-session"));

        mockMvc.perform(get("/api/chat-messages/session/missing-session"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("聊天会话不存在: missing-session"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void getMessages_hidesUnexpectedExceptionDetails() throws Exception {
        when(chatMessageFacadeService.getChatMessagesBySessionId("broken-session"))
                .thenThrow(new IllegalStateException("database detail"));

        mockMvc.perform(get("/api/chat-messages/session/broken-session"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("服务器内部错误"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void getMessages_hidesInternalBusinessExceptionDetails() throws Exception {
        when(chatMessageFacadeService.getChatMessagesBySessionId("failed-session"))
                .thenThrow(BizException.internalServerError("序列化失败: internal detail"));

        mockMvc.perform(get("/api/chat-messages/session/failed-session"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("服务器内部错误"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void unsupportedMethod_preservesMethodNotAllowedStatus() throws Exception {
        mockMvc.perform(post("/api/chat-messages/session/session-1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(405))
                .andExpect(jsonPath("$.message").value("请求方法不支持"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void unknownApiRoute_returnsNotFoundEnvelope() throws Exception {
        mockMvc.perform(get("/api/not-mapped"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("请求资源不存在"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
