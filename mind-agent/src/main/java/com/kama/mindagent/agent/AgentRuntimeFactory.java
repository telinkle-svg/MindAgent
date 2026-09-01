package com.kama.mindagent.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.kama.mindagent.agent.context.ContextBudgetPolicy;
import com.kama.mindagent.agent.context.ConversationSummary;
import com.kama.mindagent.agent.context.SessionContextMetadata;
import com.kama.mindagent.agent.planning.PlanControlTool;
import com.kama.mindagent.agent.planning.PlanningMode;
import com.kama.mindagent.agent.tools.AgentTool;
import com.kama.mindagent.config.ChatClientRegistry;
import com.kama.mindagent.config.AgentRuntimeProperties;
import com.kama.mindagent.converter.AgentConverter;
import com.kama.mindagent.converter.ChatMessageConverter;
import com.kama.mindagent.converter.KnowledgeBaseConverter;
import com.kama.mindagent.mapper.AgentMapper;
import com.kama.mindagent.mapper.KnowledgeBaseMapper;
import com.kama.mindagent.model.dto.AgentDTO;
import com.kama.mindagent.model.dto.ChatMessageDTO;
import com.kama.mindagent.model.dto.KnowledgeBaseDTO;
import com.kama.mindagent.model.entity.Agent;
import com.kama.mindagent.model.entity.KnowledgeBase;
import com.kama.mindagent.model.request.ChatHistoryAnchor;
import com.kama.mindagent.service.ChatMessageFacadeService;
import com.kama.mindagent.service.AgentEventStream;
import com.kama.mindagent.service.AgentToolRegistry;
import com.kama.mindagent.service.ChatSessionFacadeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class AgentRuntimeFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeFactory.class);
    private final ChatClientRegistry chatClientRegistry;
    private final AgentEventStream agentEventStream;
    private final AgentMapper agentMapper;
    private final AgentConverter agentConverter;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseConverter knowledgeBaseConverter;
    private final AgentToolRegistry agentToolRegistry;
    private final ChatMessageFacadeService chatMessageFacadeService;
    private final ChatMessageConverter chatMessageConverter;
    private final ChatSessionFacadeService chatSessionFacadeService;
    private final AgentLoopPolicy loopPolicy;
    private final ContextBudgetPolicy contextBudgetPolicy;

    public AgentRuntimeFactory(
            ChatClientRegistry chatClientRegistry,
            AgentEventStream agentEventStream,
            AgentMapper agentMapper,
            AgentConverter agentConverter,
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeBaseConverter knowledgeBaseConverter,
            AgentToolRegistry agentToolRegistry,
            ChatMessageFacadeService chatMessageFacadeService,
            ChatMessageConverter chatMessageConverter
    ) {
        this(
                chatClientRegistry,
                agentEventStream,
                agentMapper,
                agentConverter,
                knowledgeBaseMapper,
                knowledgeBaseConverter,
                agentToolRegistry,
                chatMessageFacadeService,
                chatMessageConverter,
                null,
                AgentLoopPolicy.defaults(),
                ContextBudgetPolicy.defaults()
        );
    }

    @Autowired
    public AgentRuntimeFactory(
            ChatClientRegistry chatClientRegistry,
            AgentEventStream agentEventStream,
            AgentMapper agentMapper,
            AgentConverter agentConverter,
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeBaseConverter knowledgeBaseConverter,
            AgentToolRegistry agentToolRegistry,
            ChatMessageFacadeService chatMessageFacadeService,
            ChatMessageConverter chatMessageConverter,
            ChatSessionFacadeService chatSessionFacadeService,
            AgentRuntimeProperties runtimeProperties
    ) {
        this(
                chatClientRegistry,
                agentEventStream,
                agentMapper,
                agentConverter,
                knowledgeBaseMapper,
                knowledgeBaseConverter,
                agentToolRegistry,
                chatMessageFacadeService,
                chatMessageConverter,
                chatSessionFacadeService,
                runtimeProperties == null ? AgentLoopPolicy.defaults() : runtimeProperties.toPolicy(),
                runtimeProperties == null
                        ? ContextBudgetPolicy.defaults()
                        : runtimeProperties.toContextBudgetPolicy()
        );
    }

    private AgentRuntimeFactory(
            ChatClientRegistry chatClientRegistry,
            AgentEventStream agentEventStream,
            AgentMapper agentMapper,
            AgentConverter agentConverter,
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeBaseConverter knowledgeBaseConverter,
            AgentToolRegistry agentToolRegistry,
            ChatMessageFacadeService chatMessageFacadeService,
            ChatMessageConverter chatMessageConverter,
            ChatSessionFacadeService chatSessionFacadeService,
            AgentLoopPolicy loopPolicy,
            ContextBudgetPolicy contextBudgetPolicy
    ) {
        this.chatClientRegistry = chatClientRegistry;
        this.agentEventStream = agentEventStream;
        this.agentMapper = agentMapper;
        this.agentConverter = agentConverter;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeBaseConverter = knowledgeBaseConverter;
        this.agentToolRegistry = agentToolRegistry;
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.chatMessageConverter = chatMessageConverter;
        this.chatSessionFacadeService = chatSessionFacadeService;
        this.loopPolicy = loopPolicy == null ? AgentLoopPolicy.defaults() : loopPolicy;
        this.contextBudgetPolicy = contextBudgetPolicy == null
                ? ContextBudgetPolicy.defaults()
                : contextBudgetPolicy;
    }

    private Agent loadAgent(String agentId) {
        return agentMapper.selectById(agentId);
    }

    /**
     * 将数据库中存储的记忆恢复成 List<Message> 结构
     */
    private List<Message> loadMemory(AgentDTO agentConfig, String chatSessionId) {
        return loadMemory(agentConfig, chatSessionId, null);
    }

    private List<Message> loadMemory(
            AgentDTO agentConfig,
            String chatSessionId,
            ChatHistoryAnchor anchor
    ) {
        int messageLength = agentConfig.getChatOptions().getMessageLength();
        int historyLimit = anchor != null && anchor.isValid()
                ? Math.max(messageLength, contextBudgetPolicy.recentTurns() * 6 + 10)
                : messageLength;
        List<ChatMessageDTO> chatMessages = anchor == null || !anchor.isValid()
                ? chatMessageFacadeService.getChatMessagesBySessionIdRecently(chatSessionId, messageLength)
                : chatMessageFacadeService.getChatMessagesBySessionIdRecently(
                chatSessionId, historyLimit, anchor);
        List<Message> memory = new ArrayList<>();
        for (ChatMessageDTO chatMessageDTO : chatMessages) {
            switch (chatMessageDTO.getRole()) {
                case SYSTEM:
                    if (!StringUtils.hasLength(chatMessageDTO.getContent())) continue;
                    memory.add(0, new SystemMessage(chatMessageDTO.getContent()));
                    break;
                case USER:
                    if (!StringUtils.hasLength(chatMessageDTO.getContent())) continue;
                    memory.add(new UserMessage(chatMessageDTO.getContent()));
                    break;
                case ASSISTANT:
                    memory.add(AssistantMessage.builder()
                            .content(chatMessageDTO.getContent())
                            .toolCalls(chatMessageDTO.getMetadata()
                                    .getToolCalls())
                            .build());
                    break;
                case TOOL:
                    memory.add(ToolResponseMessage.builder()
                            .responses(List.of(chatMessageDTO
                                    .getMetadata()
                                    .getToolResponse()))
                            .build());
                    break;
                default:
                    log.error("不支持的 Message 类型: {}, content = {}",
                            chatMessageDTO.getRole().getRole(),
                            chatMessageDTO.getContent()
                    );
                    throw new IllegalStateException("不支持的 Message 类型");
            }
        }
        return memory;
    }

    private AgentDTO toAgentConfig(Agent agent) {
        try {
            return agentConverter.toDTO(agent);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("解析 Agent 配置失败", e);
        }
    }

    private List<KnowledgeBaseDTO> resolveRuntimeKnowledgeBases(AgentDTO agentConfig) {
        List<String> allowedKbIds = agentConfig.getAllowedKbs();
        if (allowedKbIds == null || allowedKbIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<KnowledgeBase> knowledgeBases = knowledgeBaseMapper.selectByIdBatch(allowedKbIds);
        if (knowledgeBases.isEmpty()) {
            return Collections.emptyList();
        }
        List<KnowledgeBaseDTO> kbDTOs = new ArrayList<>();
        try {
            for (KnowledgeBase knowledgeBase : knowledgeBases) {
                KnowledgeBaseDTO kbDTO = knowledgeBaseConverter.toDTO(knowledgeBase);
                kbDTOs.add(kbDTO);
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        return kbDTOs;
    }

    private List<AgentTool> resolveRuntimeTools(AgentDTO agentConfig) {
        // 固定工具（系统强制）
        List<AgentTool> runtimeTools = new ArrayList<>(agentToolRegistry.listRequired());

        // 可选工具（按 Agent 配置）
        List<String> allowedToolNames = agentConfig.getAllowedTools();
        if (allowedToolNames == null || allowedToolNames.isEmpty()) {
            return runtimeTools;
        }

        Map<String, AgentTool> optionalToolMap = agentToolRegistry.listOptional()
                .stream()
                .collect(Collectors.toMap(AgentTool::name, Function.identity()));

        for (String toolName : allowedToolNames) {
            AgentTool tool = optionalToolMap.get(toolName);
            if (tool != null) {
                runtimeTools.add(tool);
            }
        }
        return runtimeTools;
    }

    private List<ToolCallback> buildToolCallbacks(List<AgentTool> runtimeTools) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (AgentTool tool : runtimeTools) {
            Object target = resolveToolTarget(tool);
            ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
                    .toolObjects(target)
                    .build()
                    .getToolCallbacks();
            callbacks.addAll(Arrays.asList(toolCallbacks));
        }
        return callbacks;
    }

    private List<ToolCallback> buildPlanToolCallbacks(PlanControlTool planControlTool) {
        return Arrays.asList(MethodToolCallbackProvider.builder()
                .toolObjects(planControlTool)
                .build()
                .getToolCallbacks());
    }
    private Object resolveToolTarget(AgentTool tool) {
        try {
            return AopUtils.isAopProxy(tool)
                    ? AopUtils.getTargetClass(tool)
                    : tool;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "解析工具目标对象失败: " + tool.name(), e);
        }
    }

    private AgentRuntime assembleRuntime(
            Agent agent,
            AgentDTO agentConfig,
            List<Message> memory,
            List<KnowledgeBaseDTO> knowledgeBases,
            List<ToolCallback> toolCallbacks,
            String chatSessionId,
            PlanningMode planningMode,
            PlanControlTool planControlTool,
            ConversationSummary sessionSummary,
            java.util.function.Consumer<ConversationSummary> summaryPersister,
            String summaryAnchorId
    ) {
        ChatClient chatClient = chatClientRegistry.get(agent.getModel());
        if (Objects.isNull(chatClient)) {
            throw new IllegalStateException("未找到对应的 ChatClient: " + agent.getModel());
        }
        return new AgentRuntime(
                agent.getId(),
                agent.getName(),
                agent.getDescription(),
                agent.getSystemPrompt(),
                new SpringAiResponseGateway(chatClient),
                agentConfig.getChatOptions().getMessageLength(),
                memory,
                toolCallbacks,
                knowledgeBases,
                chatSessionId,
                agentEventStream,
                chatMessageFacadeService,
                chatMessageConverter,
                ToolCallingManager.builder().build(),
                planningMode,
                planControlTool,
                loopPolicy,
                contextBudgetPolicy,
                sessionSummary,
                summaryPersister,
                summaryAnchorId
        );
    }

    /**
     * 创建一个 AgentRuntime 实例
     */
    public AgentRuntime createRuntime(String agentId, String chatSessionId) {
        return createRuntime(AgentRunRequest.auto(agentId, chatSessionId));
    }

    public AgentRuntime createRuntime(AgentRunRequest runRequest) {
        Objects.requireNonNull(runRequest, "runRequest cannot be null");
        Agent agent = loadAgent(runRequest.agentId());
        AgentDTO agentConfig = toAgentConfig(agent);
        ChatHistoryAnchor anchor = new ChatHistoryAnchor(
                runRequest.userMessageId(),
                runRequest.userMessageCreatedAt()
        );
        List<Message> memory = loadMemory(agentConfig, runRequest.sessionId(), anchor);
        ConversationSummary sessionSummary = loadSessionSummary(runRequest.sessionId());
        java.util.function.Consumer<ConversationSummary> summaryPersister =
                chatSessionFacadeService == null
                        ? null
                        : summary -> chatSessionFacadeService.updateChatSessionSummary(
                        runRequest.sessionId(), summary);

        // 解析 agent 的支持的知识库
        List<KnowledgeBaseDTO> knowledgeBases = resolveRuntimeKnowledgeBases(agentConfig);
        // 解析 agent 支持的工具调用
        List<AgentTool> runtimeTools = resolveRuntimeTools(agentConfig);
        // 将普通工具调用转换成 ToolCallback 的形式
        List<ToolCallback> toolCallbacks = buildToolCallbacks(runtimeTools);

        PlanningMode planningMode = PlanningMode.fromNullable(runRequest.planningMode());
        PlanControlTool planControlTool = null;
        if (planningMode != PlanningMode.DISABLED) {
            planControlTool = new PlanControlTool();
            toolCallbacks.addAll(buildPlanToolCallbacks(planControlTool));
        }

        return assembleRuntime(
                agent,
                agentConfig,
                memory,
                knowledgeBases,
                toolCallbacks,
                runRequest.sessionId(),
                planningMode,
                planControlTool,
                sessionSummary,
                summaryPersister,
                runRequest.userMessageId()
        );
    }

    private ConversationSummary loadSessionSummary(String chatSessionId) {
        if (chatSessionFacadeService == null) {
            return null;
        }
        try {
            return SessionContextMetadata.readSummary(
                    chatSessionFacadeService.getChatSessionMetadata(chatSessionId)
            );
        } catch (RuntimeException exception) {
            log.warn("Unable to load session summary for {}", chatSessionId, exception);
            return null;
        }
    }
}
