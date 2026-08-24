package com.kama.mindagent.message;

import com.kama.mindagent.model.vo.ChatMessageVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class AgentEvent {

    private Type type;
    private Payload payload;
    private Metadata metadata;

    @Data
    @AllArgsConstructor
    @Builder
    public static class Payload {
        private ChatMessageVO message;
        private String statusText;
        private Boolean done;
        private String errorCode;
    }

    @Data
    @AllArgsConstructor
    @Builder
    public static class Metadata {
        private String chatMessageId;
    }

    // 自定义消息类型
    // 1. AI 生成
    // 2. AI 规划中
    // 3. AI 思考中
    // 4. AI 执行中
    // 5. AI 完成
    public enum Type {
        AI_GENERATED_CONTENT,
        AI_PLANNING,
        AI_THINKING,
        AI_EXECUTING,
        AI_ERROR,
        AI_DONE,
    }
}
