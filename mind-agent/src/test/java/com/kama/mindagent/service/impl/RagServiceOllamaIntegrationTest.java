package com.kama.mindagent.service.impl;

import com.kama.mindagent.mapper.ChunkBgeM3Mapper;
import com.kama.mindagent.model.entity.ChunkBgeM3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@EnabledIfSystemProperty(named = "mindagent.ollama.integration", matches = "true")
class RagServiceOllamaIntegrationTest {

    @Test
    void embedCallsBgeM3AndReturns1024DimensionVector() {
        ChunkBgeM3Mapper mapper = mock(ChunkBgeM3Mapper.class);
        RagServiceImpl ragService = new RagServiceImpl(WebClient.builder(), mapper);

        float[] embedding = ragService.embed("JChatMind Ollama 集成测试");

        assertThat(embedding).hasSize(1024);
        assertThat(hasNonZeroValue(embedding)).isTrue();
        verifyNoInteractions(mapper);
    }

    @Test
    void similaritySearchUsesRealEmbeddingAndBuilds1024DimensionQuery() {
        ChunkBgeM3Mapper mapper = mock(ChunkBgeM3Mapper.class);
        when(mapper.similaritySearch(eq("kb-ollama-test"), anyString(), eq(3)))
                .thenReturn(List.of(
                        ChunkBgeM3.builder().content("nearest chunk").build(),
                        ChunkBgeM3.builder().content("second chunk").build()
                ));
        RagServiceImpl ragService = new RagServiceImpl(WebClient.builder(), mapper);

        List<String> result = ragService.similaritySearch("kb-ollama-test", "蓝牙耳机产品介绍");

        assertThat(result).containsExactly("nearest chunk", "second chunk");
        var vectorLiteralCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mapper).similaritySearch(eq("kb-ollama-test"), vectorLiteralCaptor.capture(), eq(3));
        assertThat(vectorLiteralCaptor.getValue().split(",")).hasSize(1024);
        assertThat(vectorLiteralCaptor.getValue()).startsWith("[").endsWith("]");
    }

    private static boolean hasNonZeroValue(float[] embedding) {
        for (float value : embedding) {
            if (value != 0.0f) {
                return true;
            }
        }
        return false;
    }
}
