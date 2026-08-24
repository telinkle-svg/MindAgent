package com.kama.mindagent.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.kama.mindagent.converter.DocumentConverter;
import com.kama.mindagent.exception.BizException;
import com.kama.mindagent.mapper.DocumentMapper;
import com.kama.mindagent.mapper.KnowledgeBaseMapper;
import com.kama.mindagent.model.dto.DocumentDTO;
import com.kama.mindagent.model.entity.Document;
import com.kama.mindagent.model.request.CreateDocumentRequest;
import com.kama.mindagent.model.request.UpdateDocumentRequest;
import com.kama.mindagent.model.response.CreateDocumentResponse;
import com.kama.mindagent.model.response.GetDocumentsResponse;
import com.kama.mindagent.model.vo.DocumentVO;
import com.kama.mindagent.mapper.ChunkBgeM3Mapper;
import com.kama.mindagent.model.entity.ChunkBgeM3;
import com.kama.mindagent.service.DocumentFacadeService;
import com.kama.mindagent.service.DocumentStorageService;
import com.kama.mindagent.service.MarkdownParserService;
import com.kama.mindagent.service.RagService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@AllArgsConstructor
@Slf4j
public class DocumentFacadeServiceImpl implements DocumentFacadeService {

    private final DocumentMapper documentMapper;
    private final DocumentConverter documentConverter;
    private final DocumentStorageService documentStorageService;
    private final MarkdownParserService markdownParserService;
    private final RagService ragService;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    @Override
    public GetDocumentsResponse getDocuments() {
        List<Document> documents = documentMapper.selectAll();
        List<DocumentVO> result = new ArrayList<>();
        for (Document document : documents) {
            try {
                DocumentVO vo = documentConverter.toVO(document);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetDocumentsResponse.builder()
                .documents(result.toArray(new DocumentVO[0]))
                .build();
    }

    @Override
    public GetDocumentsResponse getDocumentsByKbId(String kbId) {
        requireKnowledgeBase(kbId);
        List<Document> documents = documentMapper.selectByKbId(kbId);
        List<DocumentVO> result = new ArrayList<>();
        for (Document document : documents) {
            try {
                DocumentVO vo = documentConverter.toVO(document);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetDocumentsResponse.builder()
                .documents(result.toArray(new DocumentVO[0]))
                .build();
    }

    @Override
    public CreateDocumentResponse createDocument(CreateDocumentRequest request) {
        requireKnowledgeBase(request.getKbId());
        try {
            // 将 CreateDocumentRequest 转换为 DocumentDTO
            DocumentDTO documentDTO = documentConverter.toDTO(request);

            // 将 DocumentDTO 转换为 Document 实体
            Document document = documentConverter.toEntity(documentDTO);

            // 设置创建时间和更新时间
            LocalDateTime now = LocalDateTime.now();
            document.setCreatedAt(now);
            document.setUpdatedAt(now);

            // 插入数据库，ID 由数据库自动生成
            int result = documentMapper.insert(document);
            if (result <= 0) {
                throw BizException.internalServerError("创建文档失败");
            }

            // 返回生成的 documentId
            return CreateDocumentResponse.builder()
                    .documentId(document.getId())
                    .build();
        } catch (JsonProcessingException e) {
            throw BizException.internalServerError("创建文档时发生序列化错误: " + e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CreateDocumentResponse uploadDocument(String kbId, MultipartFile file) {
        String filePath = null;
        try {
            if (file.isEmpty()) {
                throw BizException.badRequest("上传的文件为空");
            }

            // 提取文件信息
            String originalFilename = file.getOriginalFilename();
            String filetype = getFileType(originalFilename);
            if (!isSupportedMarkdown(filetype)) {
                throw BizException.badRequest("仅支持 Markdown 文件（.md、.markdown）");
            }

            if (!hasNonWhitespaceMarkdownContent(file)) {
                throw BizException.badRequest("Markdown 文档内容不能为空");
            }

            requireKnowledgeBase(kbId);

            long fileSize = file.getSize();

            // 创建文档记录（先创建记录，获取 documentId）
            DocumentDTO documentDTO = DocumentDTO.builder()
                    .kbId(kbId)
                    .filename(originalFilename)
                    .filetype(filetype)
                    .size(fileSize)
                    .build();

            Document document = documentConverter.toEntity(documentDTO);
            LocalDateTime now = LocalDateTime.now();
            document.setCreatedAt(now);
            document.setUpdatedAt(now);

            // 插入数据库，获取生成的 documentId
            int result = documentMapper.insert(document);
            if (result <= 0) {
                throw BizException.internalServerError("创建文档记录失败");
            }

            String documentId = document.getId();

            // 保存文件
            filePath = documentStorageService.saveFile(kbId, documentId, file);

            // 更新文档记录，保存文件路径到 metadata
            DocumentDTO.MetaData metadata = new DocumentDTO.MetaData();
            metadata.setFilePath(filePath);
            documentDTO.setMetadata(metadata);
            documentDTO.setId(documentId);
            documentDTO.setCreatedAt(now);
            documentDTO.setUpdatedAt(now);

            Document updatedDocument = documentConverter.toEntity(documentDTO);
            updatedDocument.setId(documentId);
            updatedDocument.setCreatedAt(now);
            updatedDocument.setUpdatedAt(now);

            int updateResult = documentMapper.updateById(updatedDocument);
            if (updateResult <= 0) {
                throw BizException.internalServerError("更新文档记录失败");
            }

            processMarkdownDocument(kbId, documentId, filePath);

            log.info("文档上传成功: kbId={}, documentId={}, filename={}", kbId, documentId, originalFilename);

            return CreateDocumentResponse.builder()
                    .documentId(documentId)
                    .build();
        } catch (Exception exception) {
            cleanupUploadedFile(filePath);
            if (exception instanceof BizException) {
                throw (BizException) exception;
            }
            log.error("文档上传处理失败", exception);
            throw BizException.internalServerError("文档上传处理失败");
        }
    }

    private void cleanupUploadedFile(String filePath) {
        if (!StringUtils.hasText(filePath)) {
            return;
        }
        try {
            documentStorageService.deleteFile(filePath);
        } catch (Exception cleanupException) {
            log.error("文档上传失败后清理文件失败: filePath={}", filePath, cleanupException);
        }
    }

    @Override
    public void deleteDocument(String documentId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            throw BizException.notFound("文档不存在: " + documentId);
        }

        // 删除文件
        try {
            DocumentDTO documentDTO = documentConverter.toDTO(document);
            if (documentDTO.getMetadata() != null && documentDTO.getMetadata().getFilePath() != null) {
                String filePath = documentDTO.getMetadata().getFilePath();
                documentStorageService.deleteFile(filePath);
            }
        } catch (Exception e) {
            log.warn("删除文件失败，继续删除文档记录: documentId={}, error={}", documentId, e.getMessage());
            // 即使文件删除失败，也继续删除数据库记录
        }

        // 删除数据库记录
        int result = documentMapper.deleteById(documentId);
        if (result <= 0) {
            throw BizException.internalServerError("删除文档失败");
        }
    }

    private String buildEmbeddingText(String title, String content) {
        String normalizedTitle = title == null ? "" : title.trim();
        String normalizedContent = content == null ? "" : content.trim();

        if (!StringUtils.hasText(normalizedTitle)) {
            return normalizedContent;
        }
        if (!StringUtils.hasText(normalizedContent)) {
            return normalizedTitle;
        }
        return normalizedTitle + "\n" + normalizedContent;
    }

    /**
     * 处理 Markdown 文档，解析并生成 chunks
     */
    private void processMarkdownDocument(String kbId, String documentId, String filePath) throws IOException {
        log.info("开始处理 Markdown 文档: kbId={}, documentId={}, filePath={}", kbId, documentId, filePath);

        // 从保存的文件路径读取文件
        Path path = documentStorageService.getFilePath(filePath);
        try (InputStream inputStream = Files.newInputStream(path)) {
            // 解析 Markdown 文件
            List<MarkdownParserService.MarkdownSection> sections = markdownParserService.parseMarkdown(inputStream);

            if (sections.isEmpty()) {
                throw BizException.internalServerError("Markdown 文档解析后没有找到任何章节");
            }

            LocalDateTime now = LocalDateTime.now();
            int chunkCount = 0;

            // 为每个章节生成 chunk
            for (MarkdownParserService.MarkdownSection section : sections) {
                String title = section.getTitle();
                String content = section.getContent();

                if (!StringUtils.hasText(title) && !StringUtils.hasText(content)) {
                    continue;
                }

                float[] embedding = ragService.embed(buildEmbeddingText(title, content));
                if (embedding == null || embedding.length == 0) {
                    throw BizException.internalServerError("生成文档向量失败");
                }

                // 创建 ChunkBgeM3 实体
                ChunkBgeM3 chunk = ChunkBgeM3.builder()
                        .kbId(kbId)
                        .docId(documentId)
                        .content(content != null ? content : "")
                        .metadata(null) // 可以存储标题信息到 metadata
                        .embedding(embedding)
                        .createdAt(now)
                        .updatedAt(now)
                        .build();

                // 插入数据库
                int result = chunkBgeM3Mapper.insert(chunk);
                if (result <= 0) {
                    throw BizException.internalServerError("创建文档分块失败");
                }

                chunkCount++;
                log.debug("创建 chunk 成功: title={}, chunkId={}", title, chunk.getId());
            }

            if (chunkCount == 0) {
                throw BizException.internalServerError("Markdown 文档没有可处理的章节");
            }

            log.info("Markdown 文档处理完成: documentId={}, 共生成 {} 个 chunks", documentId, chunkCount);
        }
    }

    /**
     * 判断 Markdown 文档是否包含非空白正文。
     */
    private boolean hasNonWhitespaceMarkdownContent(MultipartFile file) throws IOException {
        try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                for (int index = 0; index < read; index++) {
                    char character = buffer[index];
                    if (character != '\uFEFF'
                            && !Character.isWhitespace(character)
                            && !Character.isSpaceChar(character)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private boolean isSupportedMarkdown(String filetype) {
        return "md".equals(filetype) || "markdown".equals(filetype);
    }

    /**
     * 从文件名提取文件类型
     */
    private String getFileType(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "unknown";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }

    @Override
    public void updateDocument(String documentId, UpdateDocumentRequest request) {
        try {
            // 查询现有的文档
            Document existingDocument = documentMapper.selectById(documentId);
            if (existingDocument == null) {
                throw BizException.notFound("文档不存在: " + documentId);
            }

            // 将现有 Document 转换为 DocumentDTO
            DocumentDTO documentDTO = documentConverter.toDTO(existingDocument);

            // 使用 UpdateDocumentRequest 更新 DocumentDTO
            documentConverter.updateDTOFromRequest(documentDTO, request);

            // 将更新后的 DocumentDTO 转换回 Document 实体
            Document updatedDocument = documentConverter.toEntity(documentDTO);

            // 保留原有的 ID、kbId 和创建时间
            updatedDocument.setId(existingDocument.getId());
            updatedDocument.setKbId(existingDocument.getKbId());
            updatedDocument.setCreatedAt(existingDocument.getCreatedAt());
            updatedDocument.setUpdatedAt(LocalDateTime.now());

            // 更新数据库
            int result = documentMapper.updateById(updatedDocument);
            if (result <= 0) {
                throw BizException.internalServerError("更新文档失败");
            }
        } catch (JsonProcessingException e) {
            throw BizException.internalServerError("更新文档时发生序列化错误: " + e.getMessage());
        }
    }

    private void requireKnowledgeBase(String kbId) {
        if (!StringUtils.hasText(kbId)) {
            throw BizException.badRequest("知识库 ID 不能为空");
        }
        if (knowledgeBaseMapper.selectById(kbId) == null) {
            throw BizException.notFound("知识库不存在: " + kbId);
        }
    }
}
