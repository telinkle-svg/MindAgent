package com.kama.mindagent.controller;

import com.kama.mindagent.exception.BizException;
import com.kama.mindagent.exception.GlobalExceptionHandler;
import com.kama.mindagent.service.DocumentFacadeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DocumentController.class)
@Import(GlobalExceptionHandler.class)
class DocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DocumentFacadeService documentFacadeService;

    @Test
    void uploadWhenFacadeHasInternalFailure_returnsGenericInternalServerError() throws Exception {
        when(documentFacadeService.uploadDocument(eq("kb-1"), any()))
                .thenThrow(BizException.internalServerError("embedding unavailable"));

        mockMvc.perform(multipart("/api/documents/upload")
                        .file(new MockMultipartFile("file", "fixture.md", "text/markdown", "# title".getBytes()))
                        .param("kbId", "kb-1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("服务器内部错误"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
