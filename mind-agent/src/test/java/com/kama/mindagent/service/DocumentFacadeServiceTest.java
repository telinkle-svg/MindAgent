package com.kama.mindagent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.kama.mindagent.converter.DocumentConverter;
import com.kama.mindagent.exception.BizException;
import com.kama.mindagent.mapper.ChunkBgeM3Mapper;
import com.kama.mindagent.mapper.DocumentMapper;
import com.kama.mindagent.mapper.KnowledgeBaseMapper;
import com.kama.mindagent.model.dto.DocumentDTO;
import com.kama.mindagent.model.entity.ChunkBgeM3;
import com.kama.mindagent.model.entity.Document;
import com.kama.mindagent.model.entity.KnowledgeBase;
import com.kama.mindagent.service.impl.DocumentFacadeServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentFacadeServiceTest {

    private final DocumentMapper documentMapper = mock(DocumentMapper.class);
    private final KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
    private final DocumentConverter documentConverter = mock(DocumentConverter.class);
    private final DocumentStorageService documentStorageService = mock(DocumentStorageService.class);
    private final MarkdownParserService markdownParserService = mock(MarkdownParserService.class);
    private final RagService ragService = mock(RagService.class);
    private final ChunkBgeM3Mapper chunkBgeM3Mapper = mock(ChunkBgeM3Mapper.class);
    @TempDir
    Path temporaryDirectory;
    private final DocumentFacadeServiceImpl service = new DocumentFacadeServiceImpl(
            documentMapper,
            documentConverter,
            documentStorageService,
            markdownParserService,
            ragService,
            chunkBgeM3Mapper,
            knowledgeBaseMapper
    );

    @Test
    void getDocumentsForMissingKnowledgeBase_raisesNotFoundBeforeQueryingDocuments() {
        when(knowledgeBaseMapper.selectById("missing-kb")).thenReturn(null);

        BizException exception = catchThrowableOfType(
                () -> service.getDocumentsByKbId("missing-kb"),
                BizException.class
        );

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exception).hasMessage("知识库不存在: missing-kb");
        verify(documentMapper, never()).selectByKbId("missing-kb");
    }

    @Test
    void uploadEmptyFile_raisesBadRequest() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(true);

        BizException exception = catchThrowableOfType(
                () -> service.uploadDocument("kb-1", file),
                BizException.class
        );

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exception).hasMessage("上传的文件为空");
        verify(documentMapper, never()).insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void uploadUnsupportedFile_raisesBadRequestBeforeAccessingDependencies() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("notes.txt");

        BizException exception = catchThrowableOfType(
                () -> service.uploadDocument("missing-kb", file),
                BizException.class
        );

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exception).hasMessage("仅支持 Markdown 文件（.md、.markdown）");
        verifyNoInteractions(
                knowledgeBaseMapper,
                documentMapper,
                documentConverter,
                documentStorageService,
                markdownParserService,
                ragService,
                chunkBgeM3Mapper
        );
    }

    @Test
    void uploadWhitespaceOnlyMarkdown_raisesBadRequestBeforeAccessingDependencies() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "blank.md",
                "text/markdown",
                "\uFEFF \n\t\u3000\u00A0".getBytes(StandardCharsets.UTF_8)
        );

        BizException exception = catchThrowableOfType(
                () -> service.uploadDocument("missing-kb", file),
                BizException.class
        );

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exception).hasMessage("Markdown 文档内容不能为空");
        verifyNoInteractions(
                knowledgeBaseMapper,
                documentMapper,
                documentConverter,
                documentStorageService,
                markdownParserService,
                ragService,
                chunkBgeM3Mapper
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"guide.md", "guide.MARKDOWN"})
    void uploadMarkdownExtensions_reachKnowledgeBaseValidation(String filename) {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                filename,
                "text/markdown",
                "content".getBytes(StandardCharsets.UTF_8)
        );
        when(knowledgeBaseMapper.selectById("missing-kb")).thenReturn(null);

        BizException exception = catchThrowableOfType(
                () -> service.uploadDocument("missing-kb", file),
                BizException.class
        );

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exception).hasMessage("知识库不存在: missing-kb");
        verify(knowledgeBaseMapper).selectById("missing-kb");
        verify(documentMapper, never()).insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void uploadMarkdown_usesContentAwareEmbeddingsWithoutFilenameFallback()
            throws IOException, JsonProcessingException {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "新建Markdown文件.md",
                "text/markdown",
                "fixture".getBytes(StandardCharsets.UTF_8)
        );
        Path storedFile = Files.writeString(
                temporaryDirectory.resolve("fixture.md"),
                "fixture",
                StandardCharsets.UTF_8
        );

        stubSuccessfulMarkdownUpload(file, storedFile, List.of(
                new MarkdownParserService.MarkdownSection("售后政策", "七天无理由退换"),
                new MarkdownParserService.MarkdownSection(null, "无标题正文内容"),
                new MarkdownParserService.MarkdownSection("仅标题", "")
        ));
        when(ragService.embed(any(String.class))).thenReturn(new float[]{0.1f, 0.2f});

        service.uploadDocument("kb-1", file);

        ArgumentCaptor<String> embeddingCaptor = ArgumentCaptor.forClass(String.class);
        verify(ragService, times(3)).embed(embeddingCaptor.capture());
        assertThat(embeddingCaptor.getAllValues())
                .containsExactly("售后政策\n七天无理由退换", "无标题正文内容", "仅标题")
                .doesNotContain("新建Markdown文件");

        ArgumentCaptor<ChunkBgeM3> chunkCaptor = ArgumentCaptor.forClass(ChunkBgeM3.class);
        verify(chunkBgeM3Mapper, times(3)).insert(chunkCaptor.capture());
        assertThat(chunkCaptor.getAllValues())
                .extracting(ChunkBgeM3::getContent)
                .containsExactly("七天无理由退换", "无标题正文内容", "");
        verify(documentStorageService, never()).deleteFile(any());
    }

    @Test
    void uploadMarkdown_documentInsertFailure_raisesInternalErrorWithoutSavingOrProcessing()
            throws IOException, JsonProcessingException {
        MockMultipartFile file = markdownFile();
        stubSuccessfulMarkdownUpload(file, storedMarkdownFile(), List.of(
                new MarkdownParserService.MarkdownSection("标题", "正文")
        ));
        when(documentMapper.insert(any(Document.class))).thenReturn(0);

        BizException exception = catchThrowableOfType(
                () -> service.uploadDocument("kb-1", file),
                BizException.class
        );

        assertInternalServerError(exception);
        verify(documentStorageService, never()).saveFile(any(), any(), any());
        verify(documentMapper, never()).updateById(any(Document.class));
        verifyNoInteractions(markdownParserService, ragService, chunkBgeM3Mapper);
    }

    @Test
    void uploadMarkdown_fileSaveFailure_raisesInternalErrorWithoutCleanupOrProcessing()
            throws IOException, JsonProcessingException {
        MockMultipartFile file = markdownFile();
        stubSuccessfulMarkdownUpload(file, storedMarkdownFile(), List.of(
                new MarkdownParserService.MarkdownSection("标题", "正文")
        ));
        doThrow(new IOException("copy failed"))
                .when(documentStorageService)
                .saveFile(eq("kb-1"), eq("doc-1"), eq(file));

        BizException exception = catchThrowableOfType(
                () -> service.uploadDocument("kb-1", file),
                BizException.class
        );

        assertInternalServerError(exception);
        verify(documentStorageService, never()).deleteFile(any());
        verify(documentMapper, never()).updateById(any(Document.class));
        verifyNoInteractions(markdownParserService, ragService, chunkBgeM3Mapper);
    }

    @Test
    void uploadMarkdown_documentUpdateFailure_raisesInternalErrorAndCleansUpWithoutProcessing()
            throws IOException, JsonProcessingException {
        MockMultipartFile file = markdownFile();
        stubSuccessfulMarkdownUpload(file, storedMarkdownFile(), List.of(
                new MarkdownParserService.MarkdownSection("标题", "正文")
        ));
        when(documentMapper.updateById(any(Document.class))).thenReturn(0);

        BizException exception = catchThrowableOfType(
                () -> service.uploadDocument("kb-1", file),
                BizException.class
        );

        assertInternalServerError(exception);
        verify(documentStorageService).deleteFile("stored/fixture.md");
        verifyNoInteractions(markdownParserService, ragService, chunkBgeM3Mapper);
    }

    @Test
    void uploadMarkdown_emptyParsedSections_raisesInternalErrorAndCleansUpWithoutEmbedding()
            throws IOException, JsonProcessingException {
        MockMultipartFile file = markdownFile();
        stubSuccessfulMarkdownUpload(file, storedMarkdownFile(), List.of(
                new MarkdownParserService.MarkdownSection("标题", "正文")
        ));
        when(markdownParserService.parseMarkdown(any(InputStream.class))).thenReturn(List.of());

        BizException exception = catchThrowableOfType(
                () -> service.uploadDocument("kb-1", file),
                BizException.class
        );

        assertInternalServerError(exception);
        verify(documentStorageService).deleteFile("stored/fixture.md");
        verifyNoInteractions(ragService, chunkBgeM3Mapper);
    }

    @Test
    void uploadMarkdown_parserFailure_raisesInternalErrorAndCleansUpWithoutEmbedding()
            throws IOException, JsonProcessingException {
        MockMultipartFile file = markdownFile();
        stubSuccessfulMarkdownUpload(file, storedMarkdownFile(), List.of(
                new MarkdownParserService.MarkdownSection("标题", "正文")
        ));
        when(markdownParserService.parseMarkdown(any(InputStream.class)))
                .thenThrow(new IllegalStateException("parse failed"));

        BizException exception = catchThrowableOfType(
                () -> service.uploadDocument("kb-1", file),
                BizException.class
        );

        assertInternalServerError(exception);
        verify(documentStorageService).deleteFile("stored/fixture.md");
        verifyNoInteractions(ragService, chunkBgeM3Mapper);
    }

    @Test
    void uploadMarkdown_blankParsedSection_raisesInternalErrorAndCleansUpWithoutEmbedding()
            throws IOException, JsonProcessingException {
        MockMultipartFile file = markdownFile();
        stubSuccessfulMarkdownUpload(file, storedMarkdownFile(), List.of(
                new MarkdownParserService.MarkdownSection("", "")
        ));

        BizException exception = catchThrowableOfType(
                () -> service.uploadDocument("kb-1", file),
                BizException.class
        );

        assertInternalServerError(exception);
        verify(documentStorageService).deleteFile("stored/fixture.md");
        verifyNoInteractions(ragService, chunkBgeM3Mapper);
    }

    @Test
    void uploadMarkdown_embeddingFailure_stopsAfterFirstSectionAndCleansUp()
            throws IOException, JsonProcessingException {
        MockMultipartFile file = markdownFile();
        stubSuccessfulMarkdownUpload(file, storedMarkdownFile(), List.of(
                new MarkdownParserService.MarkdownSection("标题一", "正文一"),
                new MarkdownParserService.MarkdownSection("标题二", "正文二")
        ));
        when(ragService.embed(any(String.class)))
                .thenThrow(new IllegalStateException("embedding failed"));

        BizException exception = catchThrowableOfType(
                () -> service.uploadDocument("kb-1", file),
                BizException.class
        );

        assertInternalServerError(exception);
        verify(ragService, times(1)).embed(any(String.class));
        verifyNoInteractions(chunkBgeM3Mapper);
        verify(documentStorageService).deleteFile("stored/fixture.md");
    }

    @Test
    void uploadMarkdown_nullEmbedding_raisesInternalErrorAndCleansUpWithoutWritingChunks()
            throws IOException, JsonProcessingException {
        MockMultipartFile file = markdownFile();
        stubSuccessfulMarkdownUpload(file, storedMarkdownFile(), List.of(
                new MarkdownParserService.MarkdownSection("标题", "正文")
        ));
        when(ragService.embed(any(String.class))).thenReturn((float[]) null);

        BizException exception = catchThrowableOfType(
                () -> service.uploadDocument("kb-1", file),
                BizException.class
        );

        assertInternalServerError(exception);
        verify(ragService, times(1)).embed(any(String.class));
        verifyNoInteractions(chunkBgeM3Mapper);
        verify(documentStorageService).deleteFile("stored/fixture.md");
    }

    @Test
    void uploadMarkdown_emptyEmbedding_raisesInternalErrorAndCleansUpWithoutWritingChunks()
            throws IOException, JsonProcessingException {
        MockMultipartFile file = markdownFile();
        stubSuccessfulMarkdownUpload(file, storedMarkdownFile(), List.of(
                new MarkdownParserService.MarkdownSection("标题", "正文")
        ));
        when(ragService.embed(any(String.class))).thenReturn(new float[0]);

        BizException exception = catchThrowableOfType(
                () -> service.uploadDocument("kb-1", file),
                BizException.class
        );

        assertInternalServerError(exception);
        verify(ragService, times(1)).embed(any(String.class));
        verifyNoInteractions(chunkBgeM3Mapper);
        verify(documentStorageService).deleteFile("stored/fixture.md");
    }

    @Test
    void uploadMarkdown_chunkInsertFailure_stopsAfterFirstSectionAndCleansUp()
            throws IOException, JsonProcessingException {
        MockMultipartFile file = markdownFile();
        stubSuccessfulMarkdownUpload(file, storedMarkdownFile(), List.of(
                new MarkdownParserService.MarkdownSection("标题一", "正文一"),
                new MarkdownParserService.MarkdownSection("标题二", "正文二")
        ));
        when(ragService.embed(any(String.class))).thenReturn(new float[]{0.1f});
        when(chunkBgeM3Mapper.insert(any(ChunkBgeM3.class))).thenReturn(0);

        BizException exception = catchThrowableOfType(
                () -> service.uploadDocument("kb-1", file),
                BizException.class
        );

        assertInternalServerError(exception);
        verify(ragService, times(1)).embed(any(String.class));
        verify(chunkBgeM3Mapper, times(1)).insert(any(ChunkBgeM3.class));
        verify(documentStorageService).deleteFile("stored/fixture.md");
    }

    @Test
    void uploadMarkdown_chunkInsertException_stopsAfterFirstSectionAndCleansUp()
            throws IOException, JsonProcessingException {
        MockMultipartFile file = markdownFile();
        stubSuccessfulMarkdownUpload(file, storedMarkdownFile(), List.of(
                new MarkdownParserService.MarkdownSection("标题一", "正文一"),
                new MarkdownParserService.MarkdownSection("标题二", "正文二")
        ));
        when(ragService.embed(any(String.class))).thenReturn(new float[]{0.1f});
        when(chunkBgeM3Mapper.insert(any(ChunkBgeM3.class)))
                .thenThrow(new IllegalStateException("chunk insert failed"));

        BizException exception = catchThrowableOfType(
                () -> service.uploadDocument("kb-1", file),
                BizException.class
        );

        assertInternalServerError(exception);
        verify(ragService, times(1)).embed(any(String.class));
        verify(chunkBgeM3Mapper, times(1)).insert(any(ChunkBgeM3.class));
        verify(documentStorageService).deleteFile("stored/fixture.md");
    }

    @Test
    void uploadMarkdown_cleanupFailure_preservesInternalErrorOutcome()
            throws IOException, JsonProcessingException {
        MockMultipartFile file = markdownFile();
        stubSuccessfulMarkdownUpload(file, storedMarkdownFile(), List.of(
                new MarkdownParserService.MarkdownSection("标题", "正文")
        ));
        when(markdownParserService.parseMarkdown(any(InputStream.class))).thenReturn(List.of());
        doThrow(new IOException("delete failed"))
                .when(documentStorageService)
                .deleteFile("stored/fixture.md");

        BizException exception = catchThrowableOfType(
                () -> service.uploadDocument("kb-1", file),
                BizException.class
        );

        assertInternalServerError(exception);
        verify(documentStorageService, times(1)).deleteFile("stored/fixture.md");
        verifyNoInteractions(ragService, chunkBgeM3Mapper);
    }

    private MockMultipartFile markdownFile() {
        return new MockMultipartFile(
                "file", "fixture.md", "text/markdown",
                "# 标题\n正文".getBytes(StandardCharsets.UTF_8)
        );
    }

    private Path storedMarkdownFile() throws IOException {
        Path file = Files.createTempFile(temporaryDirectory, "stored-", ".md");
        return Files.writeString(file, "# 标题\n正文", StandardCharsets.UTF_8);
    }

    private void assertInternalServerError(BizException exception) {
        assertThat(exception).isNotNull();
        assertThat(exception.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private void stubSuccessfulMarkdownUpload(
            MockMultipartFile file,
            Path storedFile,
            List<MarkdownParserService.MarkdownSection> sections
    ) throws IOException, JsonProcessingException {
        when(knowledgeBaseMapper.selectById("kb-1"))
                .thenReturn(KnowledgeBase.builder().id("kb-1").build());
        when(documentConverter.toEntity(any(DocumentDTO.class))).thenAnswer(invocation -> {
            DocumentDTO dto = invocation.getArgument(0);
            return Document.builder()
                    .id(dto.getId())
                    .kbId(dto.getKbId())
                    .filename(dto.getFilename())
                    .filetype(dto.getFiletype())
                    .size(dto.getSize())
                    .createdAt(dto.getCreatedAt())
                    .updatedAt(dto.getUpdatedAt())
                    .build();
        });
        when(documentMapper.insert(any(Document.class))).thenAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            document.setId("doc-1");
            return 1;
        });
        when(documentMapper.updateById(any(Document.class))).thenReturn(1);
        when(documentStorageService.saveFile(eq("kb-1"), eq("doc-1"), eq(file)))
                .thenReturn("stored/fixture.md");
        when(documentStorageService.getFilePath("stored/fixture.md")).thenReturn(storedFile);
        when(markdownParserService.parseMarkdown(any(InputStream.class))).thenReturn(sections);
        when(chunkBgeM3Mapper.insert(any(ChunkBgeM3.class))).thenReturn(1);
    }
}
