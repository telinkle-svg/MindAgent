package com.kama.mindagent.agent.tools;

import com.kama.mindagent.service.RagService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class KnowledgeSearchTool implements AgentTool {

    private final RagService ragService;

    public KnowledgeSearchTool(RagService ragService) {
        this.ragService = ragService;
    }

    @Override
    public String name() {
        return "KnowledgeTool";
    }

    @Override
    public String description() {
        return "用于从知识库执行语义检索（RAG）。输入知识库 ID 和查询文本，返回与查询最相关的内容片段。";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.REQUIRED;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "KnowledgeTool",
            description = "从指定知识库中执行相似性检索（RAG）。参数为知识库 ID（kbsId）和查询文本（query），返回与查询最相关的知识片段。"
    )
    public String search(String kbsId, String query) {
        List<String> strings = ragService.similaritySearch(kbsId, query);
        return String.join("\n", strings);
    }
}
