package com.kama.mindagent.model.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
public class ChatSessionDTO {
    private String id;

    private String agentId;

    private String title;

    private MetaData metadata;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Data
    public static class MetaData {
        /** Bounded incremental summary of older conversation turns. */
        private String summary;

        /** Monotonically increasing summary version. */
        private Integer summaryVersion;

        /** Inclusive message anchor covered by the summary. */
        private String lastSummarizedMessageId;

        /**
         * Preserve metadata written by older or newer application versions.
         */
        @JsonAnySetter
        @JsonAnyGetter
        private Map<String, Object> additionalProperties = new LinkedHashMap<>();
    }
}
