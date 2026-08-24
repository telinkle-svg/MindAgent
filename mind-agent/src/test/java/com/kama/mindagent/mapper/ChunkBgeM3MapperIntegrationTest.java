package com.kama.mindagent.mapper;

import com.kama.mindagent.model.entity.ChunkBgeM3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "mindagent.pgvector.integration", matches = "true")
class ChunkBgeM3MapperIntegrationTest {

    private static final int VECTOR_DIMENSION = 1024;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChunkBgeM3Mapper chunkBgeM3Mapper;

    private String kbId;
    private String docId;

    @AfterEach
    void cleanUpFixture() {
        if (kbId == null) {
            return;
        }

        jdbcTemplate.update(
                "DELETE FROM chunk_bge_m3 WHERE kb_id = CAST(? AS uuid)",
                kbId
        );
        if (docId != null) {
            jdbcTemplate.update(
                    "DELETE FROM document WHERE id = CAST(? AS uuid)",
                    docId
            );
        }
        jdbcTemplate.update(
                "DELETE FROM knowledge_base WHERE id = CAST(? AS uuid)",
                kbId
        );
    }

    @Test
    void similaritySearchReturnsNearestChunksAndPreservesVectorDimension() {
        createParentFixture();

        String nearestId = insertChunk("nearest", vectorLiteral(1.0f, 0.0f));
        String secondId = insertChunk("second", vectorLiteral(0.8f, 0.6f));
        String thirdId = insertChunk("third", vectorLiteral(0.0f, 1.0f));

        List<ChunkBgeM3> result = chunkBgeM3Mapper.similaritySearch(
                kbId,
                vectorLiteral(1.0f, 0.0f),
                3
        );

        assertThat(result)
                .extracting(ChunkBgeM3::getId, ChunkBgeM3::getContent)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(nearestId, "nearest"),
                        org.assertj.core.groups.Tuple.tuple(secondId, "second"),
                        org.assertj.core.groups.Tuple.tuple(thirdId, "third")
                );
        assertThat(result.get(0).getEmbedding()).hasSize(VECTOR_DIMENSION);

        Integer dimension = jdbcTemplate.queryForObject(
                "SELECT vector_dims(embedding) FROM chunk_bge_m3 WHERE id = CAST(? AS uuid)",
                Integer.class,
                nearestId
        );
        assertThat(dimension).isEqualTo(VECTOR_DIMENSION);
    }

    @Test
    void mapperInsertUsesPgVectorTypeHandlerAndCanBeReadBack() {
        createParentFixture();
        float[] embedding = vectorValues(0.25f, 0.75f);

        ChunkBgeM3 chunk = ChunkBgeM3.builder()
                .kbId(kbId)
                .docId(docId)
                .content("inserted through mapper")
                .metadata("{\"source\":\"mapper\"}")
                .embedding(embedding)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        assertThat(chunkBgeM3Mapper.insert(chunk)).isEqualTo(1);

        String insertedId = jdbcTemplate.queryForObject(
                "SELECT id::text FROM chunk_bge_m3 WHERE kb_id = CAST(? AS uuid) AND content = ?",
                String.class,
                kbId,
                "inserted through mapper"
        );
        ChunkBgeM3 loaded = chunkBgeM3Mapper.selectById(insertedId);

        assertThat(loaded).isNotNull();
        assertThat(loaded.getKbId()).isEqualTo(kbId);
        assertThat(loaded.getDocId()).isEqualTo(docId);
        assertThat(loaded.getMetadata()).contains("source", "mapper");
        assertThat(loaded.getEmbedding()).containsExactly(embedding);
    }

    @Test
    void mapperUpdateCastsJsonMetadataAndCanBeReadBack() {
        createParentFixture();
        String chunkId = insertChunk("chunk to update", vectorLiteral(0.5f, 0.5f));

        ChunkBgeM3 update = ChunkBgeM3.builder()
                .id(chunkId)
                .metadata("{\"source\":\"updated\"}")
                .build();

        assertThat(chunkBgeM3Mapper.updateById(update)).isEqualTo(1);

        ChunkBgeM3 loaded = chunkBgeM3Mapper.selectById(chunkId);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getMetadata()).contains("source", "updated");
    }

    private void createParentFixture() {
        kbId = UUID.randomUUID().toString();
        docId = UUID.randomUUID().toString();

        jdbcTemplate.update(
                """
                        INSERT INTO knowledge_base
                            (id, name, description, metadata, created_at, updated_at)
                        VALUES (CAST(? AS uuid), ?, ?, CAST(? AS jsonb), NOW(), NOW())
                        """,
                kbId,
                "test-kb-" + kbId,
                "mapper integration fixture",
                "{}"
        );
        jdbcTemplate.update(
                """
                        INSERT INTO document
                            (id, kb_id, filename, filetype, size, metadata, created_at, updated_at)
                        VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, CAST(? AS jsonb), NOW(), NOW())
                        """,
                docId,
                kbId,
                "mapper-fixture.md",
                "md",
                1L,
                "{}"
        );
    }

    private String insertChunk(String content, String vectorLiteral) {
        String chunkId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                """
                        INSERT INTO chunk_bge_m3
                            (id, kb_id, doc_id, content, metadata, embedding, created_at, updated_at)
                        VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?,
                                CAST(? AS jsonb), CAST(? AS vector), NOW(), NOW())
                        """,
                chunkId,
                kbId,
                docId,
                content,
                "{}",
                vectorLiteral
        );
        return chunkId;
    }

    private static float[] vectorValues(float first, float second) {
        float[] values = new float[VECTOR_DIMENSION];
        values[0] = first;
        values[1] = second;
        return values;
    }

    private static String vectorLiteral(float first, float second) {
        float[] values = vectorValues(first, second);
        List<String> components = new ArrayList<>(VECTOR_DIMENSION);
        for (float value : values) {
            components.add(Float.toString(value));
        }
        return "[" + String.join(",", components) + "]";
    }
}
