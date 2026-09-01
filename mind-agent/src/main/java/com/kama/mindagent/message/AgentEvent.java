package com.kama.mindagent.message;

import com.kama.mindagent.agent.planning.PlanSnapshot;
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
    @Builder
    public static class Payload {
        private ChatMessageVO message;
        private String statusText;
        private Boolean done;
        private String errorCode;
        private PlanSnapshot plan;

        public Payload(
                ChatMessageVO message,
                String statusText,
                Boolean done,
                String errorCode,
                PlanSnapshot plan
        ) {
            this.message = message;
            this.statusText = statusText;
            this.done = done;
            this.errorCode = errorCode;
            this.plan = plan;
        }

        /** Compatibility constructor for clients compiled against the old payload shape. */
        public Payload(ChatMessageVO message, String statusText, Boolean done, String errorCode) {
            this(message, statusText, done, errorCode, null);
        }
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
        PLAN_CREATED,
        PLAN_UPDATED,
        AI_DONE,
    }
}
