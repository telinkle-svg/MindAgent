package com.kama.mindagent.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.kama.mindagent.converter.ChatMessageConverter;
import com.kama.mindagent.event.ChatEvent;
import com.kama.mindagent.exception.BizException;
import com.kama.mindagent.mapper.AgentMapper;
import com.kama.mindagent.mapper.ChatMessageMapper;
import com.kama.mindagent.mapper.ChatSessionMapper;
import com.kama.mindagent.model.dto.ChatMessageDTO;
import com.kama.mindagent.model.entity.ChatMessage;
import com.kama.mindagent.model.request.CreateChatMessageRequest;
import com.kama.mindagent.model.request.UpdateChatMessageRequest;
import com.kama.mindagent.model.response.CreateChatMessageResponse;
import com.kama.mindagent.model.response.GetChatMessagesResponse;
import com.kama.mindagent.model.vo.ChatMessageVO;
import com.kama.mindagent.service.ChatMessageFacadeService;
import lombok.AllArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@AllArgsConstructor
public class ChatMessageFacadeServiceImpl implements ChatMessageFacadeService {

    private final ChatMessageMapper chatMessageMapper;
    private final ChatMessageConverter chatMessageConverter;
    private final ApplicationEventPublisher publisher;
    private final AgentMapper agentMapper;
    private final ChatSessionMapper chatSessionMapper;

    @Override
    public GetChatMessagesResponse getChatMessagesBySessionId(String sessionId) {
        requireChatSession(sessionId);
        List<ChatMessage> chatMessages = chatMessageMapper.selectBySessionId(sessionId);
        List<ChatMessageVO> result = new ArrayList<>();

        for (ChatMessage chatMessage : chatMessages) {
            try {
                ChatMessageVO vo = chatMessageConverter.toVO(chatMessage);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }

        return GetChatMessagesResponse.builder()
                .chatMessages(result.toArray(new ChatMessageVO[0]))
                .build();
    }

    @Override
    public List<ChatMessageDTO> getChatMessagesBySessionIdRecently(String sessionId, int limit) {
        List<ChatMessage> chatMessages = chatMessageMapper.selectBySessionIdRecently(sessionId, limit);
        List<ChatMessageDTO> result = new ArrayList<>();
        for (ChatMessage chatMessage : chatMessages) {
            try {
                ChatMessageDTO dto = chatMessageConverter.toDTO(chatMessage);
                result.add(dto);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return result;
    }

    @Override
    public CreateChatMessageResponse createChatMessage(CreateChatMessageRequest request) {
        requireAgent(request.getAgentId());
        requireChatSession(request.getSessionId());
        ChatMessage chatMessage = doCreateChatMessage(request);
        // 发布聊天通知事件
        publisher.publishEvent(new ChatEvent(
                        request.getAgentId(),
                        chatMessage.getSessionId(),
                        chatMessage.getId(),
                        chatMessage.getCreatedAt(),
                        com.kama.mindagent.agent.planning.PlanningMode.fromNullable(request.getPlanningMode()),
                        chatMessage.getContent()
                )
        );
        // 返回生成的 chatMessageId
        return CreateChatMessageResponse.builder()
                .chatMessageId(chatMessage.getId())
                .build();
    }

    @Override
    public CreateChatMessageResponse createChatMessage(ChatMessageDTO chatMessageDTO) {
        ChatMessage chatMessage = doCreateChatMessage(chatMessageDTO);
        return CreateChatMessageResponse.builder()
                .chatMessageId(chatMessage.getId())
                .build();
    }

    @Override
    public CreateChatMessageResponse agentCreateChatMessage(CreateChatMessageRequest request) {
        ChatMessage chatMessage = doCreateChatMessage(request);
        // 和 createChatMessage 的区别在于，Agent 创建的 chatMessage 不需要发布事件
        return CreateChatMessageResponse.builder()
                .chatMessageId(chatMessage.getId())
                .build();
    }

    private ChatMessage doCreateChatMessage(CreateChatMessageRequest request) {
        // 将 CreateChatMessageRequest 转换为 ChatMessageDTO
        ChatMessageDTO chatMessageDTO = chatMessageConverter.toDTO(request);
        // 将 ChatMessageDTO 转换为 ChatMessage 实体
        return doCreateChatMessage(chatMessageDTO);
    }

    private ChatMessage doCreateChatMessage(ChatMessageDTO chatMessageDTO) {
        try {
            // 将 ChatMessageDTO 转换为 ChatMessage 实体
            ChatMessage chatMessage = chatMessageConverter.toEntity(chatMessageDTO);

            // 设置创建时间和更新时间
            LocalDateTime now = LocalDateTime.now();
            chatMessage.setCreatedAt(now);
            chatMessage.setUpdatedAt(now);
            // 插入数据库，ID 由数据库自动生成
            int result = chatMessageMapper.insert(chatMessage);
            if (result <= 0) {
                throw BizException.internalServerError("创建聊天消息失败");
            }
            return chatMessage;
        } catch (JsonProcessingException e) {
            throw BizException.internalServerError("创建聊天消息时发生序列化错误: " + e.getMessage());
        }
    }

    @Override
    public CreateChatMessageResponse appendChatMessage(String chatMessageId, String appendContent) {
        // 查询现有的聊天消息
        ChatMessage existingChatMessage = chatMessageMapper.selectById(chatMessageId);
        if (existingChatMessage == null) {
            throw BizException.notFound("聊天消息不存在: " + chatMessageId);
        }

        // 将追加内容添加到现有内容后面
        String currentContent = existingChatMessage.getContent() != null
                ? existingChatMessage.getContent()
                : "";
        String updatedContent = currentContent + appendContent;

        // 创建更新后的消息对象
        ChatMessage updatedChatMessage = ChatMessage.builder()
                .id(existingChatMessage.getId())
                .sessionId(existingChatMessage.getSessionId())
                .role(existingChatMessage.getRole())
                .content(updatedContent)
                .metadata(existingChatMessage.getMetadata())
                .createdAt(existingChatMessage.getCreatedAt())
                .updatedAt(LocalDateTime.now())
                .build();

        // 更新数据库
        int result = chatMessageMapper.updateById(updatedChatMessage);
        if (result <= 0) {
            throw BizException.internalServerError("追加聊天消息内容失败");
        }

        // 返回聊天消息ID
        return CreateChatMessageResponse.builder()
                .chatMessageId(chatMessageId)
                .build();
    }

    @Override
    public void deleteChatMessage(String chatMessageId) {
        ChatMessage chatMessage = chatMessageMapper.selectById(chatMessageId);
        if (chatMessage == null) {
            throw BizException.notFound("聊天消息不存在: " + chatMessageId);
        }

        int result = chatMessageMapper.deleteById(chatMessageId);
        if (result <= 0) {
            throw BizException.internalServerError("删除聊天消息失败");
        }
    }

    @Override
    public void updateChatMessage(String chatMessageId, UpdateChatMessageRequest request) {
        try {
            // 查询现有的聊天消息
            ChatMessage existingChatMessage = chatMessageMapper.selectById(chatMessageId);
            if (existingChatMessage == null) {
                throw BizException.notFound("聊天消息不存在: " + chatMessageId);
            }

            // 将现有 ChatMessage 转换为 ChatMessageDTO
            ChatMessageDTO chatMessageDTO = chatMessageConverter.toDTO(existingChatMessage);

            // 使用 UpdateChatMessageRequest 更新 ChatMessageDTO
            chatMessageConverter.updateDTOFromRequest(chatMessageDTO, request);

            // 将更新后的 ChatMessageDTO 转换回 ChatMessage 实体
            ChatMessage updatedChatMessage = chatMessageConverter.toEntity(chatMessageDTO);

            // 保留原有的 ID、sessionId、role 和创建时间
            updatedChatMessage.setId(existingChatMessage.getId());
            updatedChatMessage.setSessionId(existingChatMessage.getSessionId());
            updatedChatMessage.setRole(existingChatMessage.getRole());
            updatedChatMessage.setCreatedAt(existingChatMessage.getCreatedAt());
            updatedChatMessage.setUpdatedAt(LocalDateTime.now());

            // 更新数据库
            int result = chatMessageMapper.updateById(updatedChatMessage);
            if (result <= 0) {
                throw BizException.internalServerError("更新聊天消息失败");
            }
        } catch (JsonProcessingException e) {
            throw BizException.internalServerError("更新聊天消息时发生序列化错误: " + e.getMessage());
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

    private void requireChatSession(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            throw BizException.badRequest("聊天会话 ID 不能为空");
        }
        if (chatSessionMapper.selectById(sessionId) == null) {
            throw BizException.notFound("聊天会话不存在: " + sessionId);
        }
    }
}
