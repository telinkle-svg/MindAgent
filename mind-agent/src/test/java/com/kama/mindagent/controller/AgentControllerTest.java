package com.kama.mindagent.controller;

import com.kama.mindagent.exception.GlobalExceptionHandler;
import com.kama.mindagent.service.AgentFacadeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentController.class)
@Import(GlobalExceptionHandler.class)
class AgentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgentFacadeService agentFacadeService;

    @Test
    void createAgent_rejectsMissingNameWithBadRequestEnvelope() throws Exception {
        mockMvc.perform(post("/api/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"deepseek-chat\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("Agent 名称不能为空"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyNoInteractions(agentFacadeService);
    }

    @Test
    void createAgent_rejectsMalformedJsonWithBadRequestEnvelope() throws Exception {
        mockMvc.perform(post("/api/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请求参数格式错误"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyNoInteractions(agentFacadeService);
    }

    @Test
    void createAgent_rejectsUnsupportedMediaTypeWithMatchingStatus() throws Exception {
        mockMvc.perform(post("/api/agents")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("name=test"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(415))
                .andExpect(jsonPath("$.message").value("不支持的媒体类型"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyNoInteractions(agentFacadeService);
    }
}
