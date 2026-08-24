package com.kama.mindagent.service;

import com.kama.mindagent.model.request.CreateDocumentRequest;
import com.kama.mindagent.model.request.UpdateDocumentRequest;
import com.kama.mindagent.model.response.CreateDocumentResponse;
import com.kama.mindagent.model.response.GetDocumentsResponse;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentFacadeService {
    GetDocumentsResponse getDocuments();

    GetDocumentsResponse getDocumentsByKbId(String kbId);

    CreateDocumentResponse createDocument(CreateDocumentRequest request);

    CreateDocumentResponse uploadDocument(String kbId, MultipartFile file);

    void deleteDocument(String documentId);

    void updateDocument(String documentId, UpdateDocumentRequest request);
}
