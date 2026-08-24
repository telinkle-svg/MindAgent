package com.kama.mindagent.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.kama.mindagent.converter.ChatSessionConverter;
import com.kama.mindagent.exception.BizException;
import com.kama.mindagent.mapper.AgentMapper;
import com.kama.mindagent.mapper.ChatSessionMapper;
import com.kama.mindagent.model.dto.ChatSessionDTO;
import com.kama.mindagent.model.entity.ChatSession;
import com.kama.mindagent.model.request.CreateChatSessionRequest;
import com.kama.mindagent.model.request.UpdateChatSessionRequest;
import com.kama.mindagent.model.response.CreateChatSessionResponse;
import com.kama.mindagent.model.response.GetChatSessionResponse;
import com.kama.mindagent.model.response.GetChatSessionsResponse;
import com.kama.mindagent.model.vo.ChatSessionVO;
import com.kama.mindagent.service.ChatSessionFacadeService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@AllArgsConstructor
public class ChatSessionFacadeServiceImpl implements ChatSessionFacadeService {

    private final ChatSessionMapper chatSessionMapper;
    private final ChatSessionConverter chatSessionConverter;
    private final AgentMapper agentMapper;

    @Override
    public GetChatSessionsResponse getChatSessions() {
        List<ChatSession> chatSessions = chatSessionMapper.selectAll();
        List<ChatSessionVO> result = new ArrayList<>();
        for (ChatSession chatSession : chatSessions) {
            try {
                ChatSessionVO vo = chatSessionConverter.toVO(chatSession);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetChatSessionsResponse.builder()
                .chatSessions(result.toArray(new ChatSessionVO[0]))
                .build();
    }

    @Override
    public GetChatSessionResponse getChatSession(String chatSessionId) {
        ChatSession chatSession = chatSessionMapper.selectById(chatSessionId);
        if (chatSession != null) {
            try {
                ChatSessionVO vo = chatSessionConverter.toVO(chatSession);
                return GetChatSessionResponse.builder()
                        .chatSession(vo)
                        .build();
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        throw BizException.notFound("聊天会话不存在: " + chatSessionId);
    }

    @Override
    public GetChatSessionsResponse getChatSessionsByAgentId(String agentId) {
        requireAgent(agentId);
        List<ChatSession> chatSessions = chatSessionMapper.selectByAgentId(agentId);
        List<ChatSessionVO> result = new ArrayList<>();
        for (ChatSession chatSession : chatSessions) {
            try {
                ChatSessionVO vo = chatSessionConverter.toVO(chatSession);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetChatSessionsResponse.builder()
                .chatSessions(result.toArray(new ChatSessionVO[0]))
                .build();
    }

    @Override
    public CreateChatSessionResponse createChatSession(CreateChatSessionRequest request) {
        requireAgent(request.getAgentId());
        try {
            // 将 CreateChatSessionRequest 转换为 ChatSessionDTO
            ChatSessionDTO chatSessionDTO = chatSessionConverter.toDTO(request);
            
            // 将 ChatSessionDTO 转换为 ChatSession 实体
            ChatSession chatSession = chatSessionConverter.toEntity(chatSessionDTO);
            
            // 设置创建时间和更新时间
            LocalDateTime now = LocalDateTime.now();
            chatSession.setCreatedAt(now);
            chatSession.setUpdatedAt(now);
            
            // 插入数据库，ID 由数据库自动生成
            int result = chatSessionMapper.insert(chatSession);
            if (result <= 0) {
                throw BizException.internalServerError("创建聊天会话失败");
            }
            
            // 返回生成的 chatSessionId
            return CreateChatSessionResponse.builder()
                    .chatSessionId(chatSession.getId())
                    .build();
        } catch (JsonProcessingException e) {
            throw BizException.internalServerError("创建聊天会话时发生序列化错误: " + e.getMessage());
        }
    }

    @Override
    public void deleteChatSession(String chatSessionId) {
        ChatSession chatSession = chatSessionMapper.selectById(chatSessionId);
        if (chatSession == null) {
            throw BizException.notFound("聊天会话不存在: " + chatSessionId);
        }
        
        int result = chatSessionMapper.deleteById(chatSessionId);
        if (result <= 0) {
            throw BizException.internalServerError("删除聊天会话失败");
        }
    }

    @Override
    public void updateChatSession(String chatSessionId, UpdateChatSessionRequest request) {
        try {
            // 查询现有的聊天会话
            ChatSession existingChatSession = chatSessionMapper.selectById(chatSessionId);
            if (existingChatSession == null) {
                throw BizException.notFound("聊天会话不存在: " + chatSessionId);
            }
            
            // 将现有 ChatSession 转换为 ChatSessionDTO
            ChatSessionDTO chatSessionDTO = chatSessionConverter.toDTO(existingChatSession);
            
            // 使用 UpdateChatSessionRequest 更新 ChatSessionDTO
            chatSessionConverter.updateDTOFromRequest(chatSessionDTO, request);
            
            // 将更新后的 ChatSessionDTO 转换回 ChatSession 实体
            ChatSession updatedChatSession = chatSessionConverter.toEntity(chatSessionDTO);
            
            // 保留原有的 ID、agentId 和创建时间
            updatedChatSession.setId(existingChatSession.getId());
            updatedChatSession.setAgentId(existingChatSession.getAgentId());
            updatedChatSession.setCreatedAt(existingChatSession.getCreatedAt());
            updatedChatSession.setUpdatedAt(LocalDateTime.now());
            
            // 更新数据库
            int result = chatSessionMapper.updateById(updatedChatSession);
            if (result <= 0) {
                throw BizException.internalServerError("更新聊天会话失败");
            }
        } catch (JsonProcessingException e) {
            throw BizException.internalServerError("更新聊天会话时发生序列化错误: " + e.getMessage());
        }
    }

    private void requireAgent(String agentId) {
        if (!StringUtils.hasText(agentId)) {
            throw BizException.badRequest("Agent ID 不能为空");
        }
        if (agentMapper.selectById(agentId) == null) {
            throw BizException.notFound("Agent 不存在: " + agentId);
        }
    }
}
