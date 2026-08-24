package com.kama.mindagent.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.kama.mindagent.converter.KnowledgeBaseConverter;
import com.kama.mindagent.exception.BizException;
import com.kama.mindagent.mapper.KnowledgeBaseMapper;
import com.kama.mindagent.model.dto.KnowledgeBaseDTO;
import com.kama.mindagent.model.entity.KnowledgeBase;
import com.kama.mindagent.model.request.CreateKnowledgeBaseRequest;
import com.kama.mindagent.model.request.UpdateKnowledgeBaseRequest;
import com.kama.mindagent.model.response.CreateKnowledgeBaseResponse;
import com.kama.mindagent.model.response.GetKnowledgeBasesResponse;
import com.kama.mindagent.model.vo.KnowledgeBaseVO;
import com.kama.mindagent.service.KnowledgeBaseFacadeService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@AllArgsConstructor
public class KnowledgeBaseFacadeServiceImpl implements KnowledgeBaseFacadeService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseConverter knowledgeBaseConverter;

    @Override
    public GetKnowledgeBasesResponse getKnowledgeBases() {
        List<KnowledgeBase> knowledgeBases = knowledgeBaseMapper.selectAll();
        List<KnowledgeBaseVO> result = new ArrayList<>();
        for (KnowledgeBase knowledgeBase : knowledgeBases) {
            try {
                KnowledgeBaseVO vo = knowledgeBaseConverter.toVO(knowledgeBase);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetKnowledgeBasesResponse.builder()
                .knowledgeBases(result.toArray(new KnowledgeBaseVO[0]))
                .build();
    }

    @Override
    public CreateKnowledgeBaseResponse createKnowledgeBase(CreateKnowledgeBaseRequest request) {
        try {
            // 将 CreateKnowledgeBaseRequest 转换为 KnowledgeBaseDTO
            KnowledgeBaseDTO knowledgeBaseDTO = knowledgeBaseConverter.toDTO(request);
            
            // 将 KnowledgeBaseDTO 转换为 KnowledgeBase 实体
            KnowledgeBase knowledgeBase = knowledgeBaseConverter.toEntity(knowledgeBaseDTO);
            
            // 设置创建时间和更新时间
            LocalDateTime now = LocalDateTime.now();
            knowledgeBase.setCreatedAt(now);
            knowledgeBase.setUpdatedAt(now);
            
            // 插入数据库，ID 由数据库自动生成
            int result = knowledgeBaseMapper.insert(knowledgeBase);
            if (result <= 0) {
                throw BizException.internalServerError("创建知识库失败");
            }
            
            // 返回生成的 knowledgeBaseId
            return CreateKnowledgeBaseResponse.builder()
                    .knowledgeBaseId(knowledgeBase.getId())
                    .build();
        } catch (JsonProcessingException e) {
            throw BizException.internalServerError("创建知识库时发生序列化错误: " + e.getMessage());
        }
    }

    @Override
    public void deleteKnowledgeBase(String knowledgeBaseId) {
        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectById(knowledgeBaseId);
        if (knowledgeBase == null) {
            throw BizException.notFound("知识库不存在: " + knowledgeBaseId);
        }
        
        int result = knowledgeBaseMapper.deleteById(knowledgeBaseId);
        if (result <= 0) {
            throw BizException.internalServerError("删除知识库失败");
        }
    }

    @Override
    public void updateKnowledgeBase(String knowledgeBaseId, UpdateKnowledgeBaseRequest request) {
        try {
            // 查询现有的知识库
            KnowledgeBase existingKnowledgeBase = knowledgeBaseMapper.selectById(knowledgeBaseId);
            if (existingKnowledgeBase == null) {
                throw BizException.notFound("知识库不存在: " + knowledgeBaseId);
            }
            
            // 将现有 KnowledgeBase 转换为 KnowledgeBaseDTO
            KnowledgeBaseDTO knowledgeBaseDTO = knowledgeBaseConverter.toDTO(existingKnowledgeBase);
            
            // 使用 UpdateKnowledgeBaseRequest 更新 KnowledgeBaseDTO
            knowledgeBaseConverter.updateDTOFromRequest(knowledgeBaseDTO, request);
            
            // 将更新后的 KnowledgeBaseDTO 转换回 KnowledgeBase 实体
            KnowledgeBase updatedKnowledgeBase = knowledgeBaseConverter.toEntity(knowledgeBaseDTO);
            
            // 保留原有的 ID 和创建时间
            updatedKnowledgeBase.setId(existingKnowledgeBase.getId());
            updatedKnowledgeBase.setCreatedAt(existingKnowledgeBase.getCreatedAt());
            updatedKnowledgeBase.setUpdatedAt(LocalDateTime.now());
            
            // 更新数据库
            int result = knowledgeBaseMapper.updateById(updatedKnowledgeBase);
            if (result <= 0) {
                throw BizException.internalServerError("更新知识库失败");
            }
        } catch (JsonProcessingException e) {
            throw BizException.internalServerError("更新知识库时发生序列化错误: " + e.getMessage());
        }
    }
}
