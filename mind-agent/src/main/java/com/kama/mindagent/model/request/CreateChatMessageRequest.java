package com.kama.mindagent.model.request;

import com.kama.mindagent.agent.planning.PlanningMode;
import com.kama.mindagent.model.dto.ChatMessageDTO;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateChatMessageRequest {
    private String agentId;
    private String sessionId;
    private ChatMessageDTO.RoleType role;
    private String content;
    private ChatMessageDTO.MetaData metadata;
    /** Optional per-run override; null is normalized to AUTO at event creation. */
    private PlanningMode planningMode;
}
