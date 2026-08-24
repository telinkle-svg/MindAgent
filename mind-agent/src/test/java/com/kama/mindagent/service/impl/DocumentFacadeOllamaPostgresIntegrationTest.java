package com.kama.mindagent.service.impl;

import com.kama.mindagent.model.response.CreateDocumentResponse;
import com.kama.mindagent.service.DocumentFacadeService;
import com.kama.mindagent.service.DocumentStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "mindagent.document.integration", matches = "true")
class DocumentFacadeOllamaPostgresIntegrationTest {

    private static final int VECTOR_DIMENSION = 1024;

    @Autowired
    private DocumentFacadeService documentFacadeService;

    @Autowired
    private DocumentStorageService documentStorageService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${document.storage.base-path:./data/documents}")
    private String baseStoragePath;

    private String kbId;

    @AfterEach
    void cleanUpFixture() throws IOException {
        if (kbId == null) {
            return;
        }

        try {
            jdbcTemplate.update(
                    "DELETE FROM chunk_bge_m3 WHERE kb_id = CAST(? AS uuid)",
                    kbId
            );
            jdbcTemplate.update(
                    "DELETE FROM document WHERE kb_id = CAST(? AS uuid)",
                    kbId
            );
            jdbcTemplate.update(
                    "DELETE FROM knowledge_base WHERE id = CAST(? AS uuid)",
                    kbId
            );
        } finally {
            deleteFixtureDirectory();
        }
    }

    @Test
    void uploadMarkdownPersistsDocumentChunksVectorsAndFile() throws IOException {
        kbId = UUID.randomUUID().toString();
        createKnowledgeBaseFixture();

        String markdown = """
                # 无线蓝牙耳机
                续航与降噪信息。
                # 机械键盘
                轴体与键帽信息。
                # 售后政策
                七天无理由退货。
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "document-e2e.md",
                "text/markdown",
                markdown.getBytes(StandardCharsets.UTF_8)
        );

        CreateDocumentResponse response = documentFacadeService.uploadDocument(kbId, file);

        assertThat(response).isNotNull();
        assertThat(response.getDocumentId()).isNotBlank();
        String documentId = response.getDocumentId();

        Integer documentCount = jdbcTemplate.queryForObject(
                """
                        SELECT count(*)
                        FROM document
                        WHERE id = CAST(? AS uuid) AND kb_id = CAST(? AS uuid)
                        """,
                Integer.class,
                documentId,
                kbId
        );
        assertThat(documentCount).isEqualTo(1);

        String filePath = jdbcTemplate.queryForObject(
                "SELECT metadata->>'filePath' FROM document WHERE id = CAST(? AS uuid)",
                String.class,
                documentId
        );
        assertThat(filePath).isNotBlank();
        assertThat(documentStorageService.fileExists(filePath)).isTrue();
        assertThat(Files.readString(documentStorageService.getFilePath(filePath), StandardCharsets.UTF_8))
                .isEqualTo(markdown);

        Integer chunkCount = jdbcTemplate.queryForObject(
                """
                        SELECT count(*)
                        FROM chunk_bge_m3
                        WHERE doc_id = CAST(? AS uuid) AND kb_id = CAST(? AS uuid)
                        """,
                Integer.class,
                documentId,
                kbId
        );
        assertThat(chunkCount).isEqualTo(3);

        List<Integer> vectorDimensions = jdbcTemplate.query(
                """
                        SELECT vector_dims(embedding)
                        FROM chunk_bge_m3
                        WHERE doc_id = CAST(? AS uuid)
                        ORDER BY created_at, id
                        """,
                (resultSet, rowNum) -> resultSet.getInt(1),
                documentId
        );
        assertThat(vectorDimensions)
                .hasSize(3)
                .containsOnly(VECTOR_DIMENSION);

        List<String> contents = jdbcTemplate.query(
                """
                        SELECT content
                        FROM chunk_bge_m3
                        WHERE doc_id = CAST(? AS uuid)
                        ORDER BY created_at, id
                        """,
                (resultSet, rowNum) -> resultSet.getString(1),
                documentId
        );
        assertThat(contents)
                .anyMatch(content -> content.contains("续航"))
                .anyMatch(content -> content.contains("轴体"))
                .anyMatch(content -> content.contains("七天"));
    }

    private void createKnowledgeBaseFixture() {
        jdbcTemplate.update(
                """
                        INSERT INTO knowledge_base
                            (id, name, description, metadata, created_at, updated_at)
                        VALUES (CAST(? AS uuid), ?, ?, CAST(? AS jsonb), NOW(), NOW())
                        """,
                kbId,
                "document-e2e-kb-" + kbId,
                "document upload integration fixture",
                "{}"
        );
    }

    private void deleteFixtureDirectory() throws IOException {
        Path baseDirectory = Paths.get(baseStoragePath).toAbsolutePath().normalize();
        Path fixtureDirectory = baseDirectory.resolve(kbId).normalize();

        if (!fixtureDirectory.startsWith(baseDirectory)
                || !kbId.equals(fixtureDirectory.getFileName().toString())) {
            throw new IllegalStateException("Refusing to delete a path outside the test fixture directory");
        }
        if (!Files.exists(fixtureDirectory)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(fixtureDirectory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }
}
