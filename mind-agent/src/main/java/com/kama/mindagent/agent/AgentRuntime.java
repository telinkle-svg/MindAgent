package com.kama.mindagent.agent;

import com.kama.mindagent.converter.ChatMessageConverter;
import com.kama.mindagent.message.AgentEvent;
import com.kama.mindagent.model.dto.ChatMessageDTO;
import com.kama.mindagent.model.dto.KnowledgeBaseDTO;
import com.kama.mindagent.model.response.CreateChatMessageResponse;
import com.kama.mindagent.model.vo.ChatMessageVO;
import com.kama.mindagent.service.ChatMessageFacadeService;
import com.kama.mindagent.service.AgentEventStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
public class AgentRuntime {
    // 智能体 ID
    private String agentId;

    // 名称
    private String name;

    // 描述
    private String description;

    // 默认系统提示词
    private String systemPrompt;

    // 交互实例
    private ChatClient chatClient;

    // 状态
    private AgentLifecycleState agentState;

    // 可用的工具
    private List<ToolCallback> availableTools;

    // 可访问的知识库
    private List<KnowledgeBaseDTO> availableKbs;

    // 工具调用管理器
    private ToolCallingManager toolCallingManager;

    // 模型的聊天记录
    private ChatMemory chatMemory;

    // 模型调用网关
    private ModelResponseGateway agentChatGateway;

    // 模型的聊天会话 ID
    private String chatSessionId;

    // 最多循环次数
    private static final Integer MAX_STEPS = 20;

    private static final Integer DEFAULT_MAX_MESSAGES = 20;

    // SpringAI 自带的 ChatOptions, 不是 AgentDTO.ChatOptions
    private ChatOptions chatOptions;

    // SSE 服务, 用于发送消息给前端
    private AgentEventStream agentEventStream;

    private ChatMessageConverter chatMessageConverter;

    private ChatMessageFacadeService chatMessageFacadeService;

    // 最后一次的 ChatResponse
    private ChatResponse lastChatResponse;

    // AI 返回的，已经持久化，但是需要 sse 发给前端的消息
    private final List<ChatMessageDTO> pendingChatMessages = new ArrayList<>();

    public AgentRuntime() {
    }

    public AgentRuntime(String agentId,
                     String name,
                     String description,
                     String systemPrompt,
                     ChatClient chatClient,
                     Integer maxMessages,
                     List<Message> memory,
                     List<ToolCallback> availableTools,
                     List<KnowledgeBaseDTO> availableKbs,
                     String chatSessionId,
                     AgentEventStream agentEventStream,
                     ChatMessageFacadeService chatMessageFacadeService,
                     ChatMessageConverter chatMessageConverter
    ) {
        this(
                agentId,
                name,
                description,
                systemPrompt,
                new SpringAiResponseGateway(chatClient),
                maxMessages,
                memory,
                availableTools,
                availableKbs,
                chatSessionId,
                agentEventStream,
                chatMessageFacadeService,
                chatMessageConverter,
                ToolCallingManager.builder().build()
        );
    }

    AgentRuntime(String agentId,
              String name,
              String description,
              String systemPrompt,
              ModelResponseGateway agentChatGateway,
              Integer maxMessages,
              List<Message> memory,
              List<ToolCallback> availableTools,
              List<KnowledgeBaseDTO> availableKbs,
              String chatSessionId,
              AgentEventStream agentEventStream,
              ChatMessageFacadeService chatMessageFacadeService,
              ChatMessageConverter chatMessageConverter,
              ToolCallingManager toolCallingManager
    ) {
        this.agentId = agentId;
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;

        this.agentChatGateway = agentChatGateway;

        this.availableTools = availableTools;
        this.availableKbs = availableKbs;

        this.chatSessionId = chatSessionId;
        this.agentEventStream = agentEventStream;

        this.chatMessageFacadeService = chatMessageFacadeService;
        this.chatMessageConverter = chatMessageConverter;

        this.agentState = AgentLifecycleState.IDLE;

        // 保存聊天记录
        this.chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(maxMessages == null ? DEFAULT_MAX_MESSAGES : maxMessages)
                .build();
        this.chatMemory.add(chatSessionId, memory);

        // 添加系统提示
        if (StringUtils.hasLength(systemPrompt)) {
            this.chatMemory.add(chatSessionId, new SystemMessage(systemPrompt));
        }

        // 关闭 SpringAI 自带的内部的工具调用自动执行功能
        this.chatOptions = DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();

        // 工具调用管理器
        this.toolCallingManager = toolCallingManager;
    }

    // 打印工具调用信息
    private void logToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            log.info("\n\n[ToolCalling] 无工具调用");
            return;
        }
        String logMessage = IntStream.range(0, toolCalls.size())
                .mapToObj(i -> {
                    AssistantMessage.ToolCall call = toolCalls.get(i);
                    return String.format(
                            "[ToolCalling #%d]\n- name      : %s\n- arguments : %s",
                            i + 1,
                            call.name(),
                            call.arguments()
                    );
                })
                .collect(Collectors.joining("\n\n"));
        log.info("\n\n========== Tool Calling ==========\n{}\n=================================\n", logMessage);
    }

    // 持久化 Message, 返回 chatMessageId
    // 需要 Agent 持久化的 Message 子类有以下两类
    // AssistantMessage
    // ToolResponseMessage

    // SystemMessage 不需要持久化
    // UserMessage 在每次用户发送问题之间就已经持久化过了
    private void persistMessage(Message message) {
        ChatMessageDTO.ChatMessageDTOBuilder builder = ChatMessageDTO.builder();
        if (message instanceof AssistantMessage assistantMessage) {
            ChatMessageDTO chatMessageDTO = builder.role(ChatMessageDTO.RoleType.ASSISTANT)
                    .content(assistantMessage.getText())
                    .sessionId(this.chatSessionId)
                    .metadata(ChatMessageDTO.MetaData.builder()
                            .toolCalls(assistantMessage.getToolCalls())
                            .build())
                    .build();
            CreateChatMessageResponse chatMessage = chatMessageFacadeService.createChatMessage(chatMessageDTO);
            chatMessageDTO.setId(chatMessage.getChatMessageId());
            pendingChatMessages.add(chatMessageDTO);
        } else if (message instanceof ToolResponseMessage toolResponseMessage) {
            // 持久化 ToolResponseMessage
            for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()) {
                ChatMessageDTO chatMessageDTO = builder.role(ChatMessageDTO.RoleType.TOOL)
                        .content(toolResponse.responseData())
                        .sessionId(this.chatSessionId)
                        .metadata(ChatMessageDTO.MetaData.builder()
                                .toolResponse(toolResponse)
                                .build())
                        .build();
                CreateChatMessageResponse chatMessage = chatMessageFacadeService.createChatMessage(chatMessageDTO);
                chatMessageDTO.setId(chatMessage.getChatMessageId());
                pendingChatMessages.add(chatMessageDTO);
            }
        } else {
            throw new IllegalArgumentException("不支持的 Message 类型: " + message.getClass().getName());
        }
    }

    // 刷新 pendingMessages, 将数据通过 sse 发送给前端
    private void publishPendingMessages() {
        for (ChatMessageDTO message : pendingChatMessages) {
            ChatMessageVO vo = chatMessageConverter.toVO(message);
            AgentEvent event = AgentEvent.builder()
                    .type(AgentEvent.Type.AI_GENERATED_CONTENT)
                    .payload(AgentEvent.Payload.builder()
                            .message(vo)
                            .build())
                    .metadata(AgentEvent.Metadata.builder()
                            .chatMessageId(message.getId())
                            .build())
                    .build();
            agentEventStream.publish(this.chatSessionId, event);
        }
        pendingChatMessages.clear();
    }

    // 通过 SSE 发送 Agent 状态事件（规划/思考/执行/完成）
    private void publishStatus(AgentEvent.Type type, String statusText) {
        AgentEvent event = AgentEvent.builder()
                .type(type)
                .payload(AgentEvent.Payload.builder()
                        .statusText(statusText)
                        .build())
                .build();
        agentEventStream.publish(this.chatSessionId, event);
    }

    private void publishFailure(AgentFailureCode errorCode) {
        AgentEvent event = AgentEvent.builder()
                .type(AgentEvent.Type.AI_ERROR)
                .payload(AgentEvent.Payload.builder()
                        .statusText("执行失败，请重试")
                        .errorCode(errorCode.name())
                        .build())
                .build();
        agentEventStream.publish(this.chatSessionId, event);
    }

    private List<AssistantMessage.ToolCall> extractToolCalls(AssistantMessage output) {
        return output.getToolCalls() == null ? List.of() : output.getToolCalls();
    }

    private void validateModelOutput(AssistantMessage output) {
        if (output == null) {
            throw new AgentExecutionException(AgentFailureCode.MODEL_CALL_FAILED);
        }

        List<AssistantMessage.ToolCall> toolCalls = extractToolCalls(output);
        boolean hasText = StringUtils.hasText(output.getText());
        boolean hasTerminate = toolCalls.stream()
                .anyMatch(toolCall -> "terminate".equals(toolCall.name()));

        if (hasTerminate && (!hasText || toolCalls.size() != 1)) {
            throw new AgentExecutionException(AgentFailureCode.AGENT_PROTOCOL_ERROR);
        }
        if (!hasText && toolCalls.isEmpty()) {
            throw new AgentExecutionException(AgentFailureCode.FINAL_ANSWER_MISSING);
        }
    }

    // thinkPrompt 应该放到 system 中还是
    private boolean decideNextAction() {
        publishStatus(AgentEvent.Type.AI_THINKING, "思考中：正在分析问题并决定下一步行动...");

        String thinkPrompt = """
                现在你是一个智能的的具体「决策模块」
                请根据当前对话上下文，决定下一步的动作。
                                \s
                【额外信息】
                - 你目前拥有的知识库列表以及描述：%s
                - 如果有缺失的上下文时，优先从知识库中进行搜索
                """.formatted(this.availableKbs);

        // 将 thinkPrompt 通过 .user(thinkPrompt) 的方式构造进入 chatClient 中
        // 既能让每次 messageList 的最后一条是 本条提示词，
        // 又能够避免将 thinkPrompt 加入到聊天记录中
        Prompt prompt = Prompt.builder()
                .chatOptions(this.chatOptions)
                .messages(this.chatMemory.get(this.chatSessionId))
                .build();

        AssistantMessage output;
        try {
            this.lastChatResponse = this.agentChatGateway.request(
                    prompt,
                    thinkPrompt,
                    this.availableTools
            );
            Assert.notNull(lastChatResponse, "Last chat client response cannot be null");
            output = this.lastChatResponse
                    .getResult()
                    .getOutput();
        } catch (AgentExecutionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AgentExecutionException(AgentFailureCode.MODEL_CALL_FAILED, exception);
        }

        validateModelOutput(output);

        List<AssistantMessage.ToolCall> toolCalls = extractToolCalls(output);

        // 保存
        persistMessage(output);
        publishPendingMessages();

        // 打印工具调用
        logToolCalls(toolCalls);

        // 如果工具调用不为空，则进入执行阶段
        return !toolCalls.isEmpty();
    }

    // 执行
    private void executeToolCalls() {
        Assert.notNull(this.lastChatResponse, "Last chat client response cannot be null");

        if (!this.lastChatResponse.hasToolCalls()) {
            return;
        }

        publishStatus(AgentEvent.Type.AI_EXECUTING, "执行中：正在调用工具处理...");

        Prompt prompt = Prompt.builder()
                .messages(this.chatMemory.get(this.chatSessionId))
                .chatOptions(this.chatOptions)
                .build();

        ToolExecutionResult toolExecutionResult;
        try {
            toolExecutionResult = toolCallingManager.executeToolCalls(prompt, this.lastChatResponse);
        } catch (Exception exception) {
            throw new AgentExecutionException(AgentFailureCode.TOOL_EXECUTION_FAILED, exception);
        }

        if (toolExecutionResult == null || toolExecutionResult.conversationHistory() == null
                || toolExecutionResult.conversationHistory().isEmpty()) {
            throw new AgentExecutionException(AgentFailureCode.TOOL_EXECUTION_FAILED);
        }

        // 合并记忆：保留原有上下文，追加 AI 工具调用消息与工具执行结果，避免丢失系统提示词和早期对话
        List<Message> mergedMemory = new ArrayList<>(this.chatMemory.get(this.chatSessionId));
        mergedMemory.add(this.lastChatResponse.getResult().getOutput());
        for (Message message : toolExecutionResult.conversationHistory()) {
            if (message instanceof ToolResponseMessage toolMessage) {
                mergedMemory.add(toolMessage);
            }
        }
        this.chatMemory.clear(this.chatSessionId);
        this.chatMemory.add(this.chatSessionId, mergedMemory);

        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) toolExecutionResult
                .conversationHistory()
                .get(toolExecutionResult.conversationHistory().size() - 1);

        String collect = toolResponseMessage.getResponses()
                .stream()
                .map(resp -> "工具" + resp.name() + "的返回结果为：" + resp.responseData())
                .collect(Collectors.joining("\n"));

        log.info("工具调用结果：{}", collect);

        // 保存工具调用
        persistMessage(toolResponseMessage);
        publishPendingMessages();

        if (toolResponseMessage.getResponses()
                .stream()
                .anyMatch(resp -> resp.name().equals("terminate"))) {
            this.agentState = AgentLifecycleState.FINISHED;
            log.info("任务结束");
        }
    }

    // 单个步骤模板
    private void advanceOneStep() {
        if (decideNextAction()) {
            executeToolCalls();
        } else { // 没有工具调用
            agentState = AgentLifecycleState.FINISHED;
        }
    }

    // 运行
    public void execute() {
        if (agentState != AgentLifecycleState.IDLE) {
            throw new IllegalStateException("Agent is not idle");
        }

        try {
            publishStatus(AgentEvent.Type.AI_PLANNING, "规划中：正在拆解任务并制定执行计划...");
            for (int i = 0; i < MAX_STEPS && agentState != AgentLifecycleState.FINISHED; i++) {
                // 当前步骤，用于实现 Agent Loop
                advanceOneStep();
            }
            if (agentState != AgentLifecycleState.FINISHED) {
                throw new AgentExecutionException(AgentFailureCode.MAX_STEPS_EXCEEDED);
            }
        } catch (Exception e) {
            agentState = AgentLifecycleState.ERROR;
            AgentExecutionException agentException = e instanceof AgentExecutionException
                    ? (AgentExecutionException) e
                    : new AgentExecutionException(AgentFailureCode.AGENT_PROTOCOL_ERROR, e);
            log.error("Error running agent, errorCode={}",
                    agentException.getErrorCode(), e);
            publishFailure(agentException.getErrorCode());
            throw agentException;
        } finally {
            publishStatus(AgentEvent.Type.AI_DONE, "任务完成");
        }
    }

    @Override
    public String toString() {
        return "AgentRuntime {" +
                "name = " + name + ",\n" +
                "description = " + description + ",\n" +
                "agentId = " + agentId + ",\n" +
                "systemPrompt = " + systemPrompt + "}";
    }
}
