package com.kama.mindagent.service.impl;

import com.kama.mindagent.exception.BizException;
import com.kama.mindagent.service.DocumentFacadeService;
import com.kama.mindagent.service.RagService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
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
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "mindagent.document.integration", matches = "true")
class DocumentFacadePostgresRollbackIntegrationTest {

    @Autowired
    private DocumentFacadeService documentFacadeService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${document.storage.base-path:./data/documents}")
    private String baseStoragePath;

    @MockBean
    private RagService ragService;

    private String kbId;

    @AfterEach
    void cleanUpFixture() throws IOException {
        if (kbId == null) {
            return;
        }
        try {
            jdbcTemplate.update("DELETE FROM chunk_bge_m3 WHERE kb_id = CAST(? AS uuid)", kbId);
            jdbcTemplate.update("DELETE FROM document WHERE kb_id = CAST(? AS uuid)", kbId);
            jdbcTemplate.update("DELETE FROM knowledge_base WHERE id = CAST(? AS uuid)", kbId);
        } finally {
            deleteFixtureDirectory();
        }
    }

    @Test
    void uploadWhenEmbeddingFails_rollsBackRowsAndDeletesSavedFile() throws IOException {
        kbId = UUID.randomUUID().toString();
        createKnowledgeBaseFixture();
        when(ragService.embed(anyString())).thenThrow(new IllegalStateException("embedding unavailable"));

        MockMultipartFile file = new MockMultipartFile(
                "file", "rollback.md", "text/markdown",
                "# 标题\n正文".getBytes(StandardCharsets.UTF_8)
        );

        BizException exception = catchThrowableOfType(
                () -> documentFacadeService.uploadDocument(kbId, file), BizException.class);

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(countRows("SELECT count(*) FROM document WHERE kb_id = CAST(? AS uuid)")).isZero();
        assertThat(countRows("SELECT count(*) FROM chunk_bge_m3 WHERE kb_id = CAST(? AS uuid)")).isZero();
        assertThat(containsRegularFile(Paths.get(baseStoragePath).toAbsolutePath().normalize().resolve(kbId))).isFalse();
    }

    private int countRows(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class, kbId);
    }

    private boolean containsRegularFile(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return false;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.anyMatch(Files::isRegularFile);
        }
    }

    private void createKnowledgeBaseFixture() {
        jdbcTemplate.update(
                """
                        INSERT INTO knowledge_base
                            (id, name, description, metadata, created_at, updated_at)
                        VALUES (CAST(? AS uuid), ?, ?, CAST(? AS jsonb), NOW(), NOW())
                        """,
                kbId,
                "rollback-kb-" + kbId,
                "document rollback fixture",
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
