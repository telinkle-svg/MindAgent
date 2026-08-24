package com.kama.mindagent.agent.tools;

import com.kama.mindagent.service.RagService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeSearchToolTest {

    @Test
    void knowledgeQuery_joinsRetrievedChunksInOrder() {
        RagService ragService = mock(RagService.class);
        when(ragService.similaritySearch("kb-1", "return policy"))
                .thenReturn(List.of("chunk one", "chunk two"));

        String result = new KnowledgeSearchTool(ragService)
                .search("kb-1", "return policy");

        assertThat(result).isEqualTo("chunk one\nchunk two");
    }
}
