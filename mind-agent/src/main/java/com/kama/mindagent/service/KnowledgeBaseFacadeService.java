package com.kama.mindagent.service;

import com.kama.mindagent.model.request.CreateKnowledgeBaseRequest;
import com.kama.mindagent.model.request.UpdateKnowledgeBaseRequest;
import com.kama.mindagent.model.response.CreateKnowledgeBaseResponse;
import com.kama.mindagent.model.response.GetKnowledgeBasesResponse;

public interface KnowledgeBaseFacadeService {
    GetKnowledgeBasesResponse getKnowledgeBases();

    CreateKnowledgeBaseResponse createKnowledgeBase(CreateKnowledgeBaseRequest request);

    void deleteKnowledgeBase(String knowledgeBaseId);

    void updateKnowledgeBase(String knowledgeBaseId, UpdateKnowledgeBaseRequest request);
}

