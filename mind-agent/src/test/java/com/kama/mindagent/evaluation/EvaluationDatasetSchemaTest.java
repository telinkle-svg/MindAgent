package com.kama.mindagent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationDatasetSchemaTest {

    private static final Set<String> KNOWN_TOOLS = Set.of(
            "KnowledgeTool",
            "databaseQuery",
            "readFile",
            "writeFile",
            "appendToFile",
            "listFiles",
            "deleteFile",
            "createDirectory",
            "sendEmail",
            "manage_plan",
            "terminate"
    );
    private static final Set<String> PLANNING_MODES = Set.of("DISABLED", "AUTO", "REQUIRED");
    private static final Set<String> OUTCOMES = Set.of("final_answer", "safe_refusal", "tool_then_answer");
    private static final Set<String> ASSERTION_OPERATORS = Set.of(
            "equals", "contains", "containsAny", "regex", "minItems"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void ragCorpusAndQueriesHaveStableReferences() throws IOException {
        Path ragRoot = evaluationRoot().resolve("rag");
        List<JsonNode> corpus = readJsonLines(ragRoot.resolve("corpus.jsonl"));
        List<JsonNode> queries = readJsonLines(ragRoot.resolve("queries.jsonl"));
        Set<String> chunkIds = uniqueRequiredValues(corpus, "chunkId");
        Set<String> documentIds = new HashSet<>();
        for (JsonNode entry : corpus) {
            documentIds.add(requiredText(entry, "documentId"));
        }

        assertThat(corpus).isNotEmpty();
        assertThat(queries).isNotEmpty();
        assertThat(documentIds).hasSizeGreaterThan(1);
        Set<String> queryIds = new HashSet<>();
        for (JsonNode query : queries) {
            String queryId = requiredText(query, "queryId");
            assertThat(queryIds.add(queryId)).as("duplicate queryId %s", queryId).isTrue();
            assertThat(requiredText(query, "query")).isNotBlank();
            int k = query.path("k").asInt(0);
            assertThat(k).as("k for %s", queryId).isBetween(1, 10);
            JsonNode relevant = query.get("relevantChunkIds");
            assertThat(relevant).as("relevantChunkIds for %s", queryId).isNotNull();
            assertThat(relevant.isArray()).isTrue();
            assertThat(relevant).isNotEmpty();
            for (JsonNode chunkId : relevant) {
                assertThat(chunkIds).contains(chunkId.asText());
            }
        }
    }

    @Test
    void toolSelectionCasesUseKnownToolsAndAssertionOperators() throws IOException {
        List<JsonNode> cases = readJsonLines(evaluationRoot()
                .resolve("tool-selection")
                .resolve("cases.jsonl"));
        Set<String> caseIds = new HashSet<>();
        assertThat(cases).isNotEmpty();
        for (JsonNode testCase : cases) {
            String caseId = requiredText(testCase, "caseId");
            assertThat(caseIds.add(caseId)).as("duplicate caseId %s", caseId).isTrue();
            assertThat(requiredText(testCase, "input")).isNotBlank();
            assertThat(PLANNING_MODES).contains(requiredText(testCase, "planningMode"));
            assertThat(OUTCOMES).contains(requiredText(testCase, "expectedOutcome"));
            assertThat(testCase.path("allowAdditionalCalls").isBoolean()).isTrue();
            assertThat(testCase.path("orderMatters").isBoolean()).isTrue();

            Set<String> availableTools = textArray(testCase, "availableTools");
            assertThat(availableTools).isNotEmpty();
            assertThat(availableTools).allMatch(KNOWN_TOOLS::contains);

            JsonNode expectedCalls = testCase.get("expectedCalls");
            assertThat(expectedCalls).isNotNull();
            assertThat(expectedCalls.isArray()).isTrue();
            for (JsonNode expectedCall : expectedCalls) {
                String toolName = requiredText(expectedCall, "name");
                assertThat(KNOWN_TOOLS).contains(toolName);
                assertThat(availableTools).contains(toolName);
                JsonNode assertions = expectedCall.get("argumentAssertions");
                if (assertions == null) {
                    continue;
                }
                assertThat(assertions.isObject()).isTrue();
                Iterator<JsonNode> values = assertions.elements();
                while (values.hasNext()) {
                    JsonNode assertion = values.next();
                    assertThat(assertion.isObject()).isTrue();
                    assertThat(assertion.fieldNames()).toIterable()
                            .allMatch(ASSERTION_OPERATORS::contains);
                }
            }
        }
    }

    private Set<String> uniqueRequiredValues(List<JsonNode> entries, String field) {
        Set<String> values = new HashSet<>();
        for (JsonNode entry : entries) {
            String value = requiredText(entry, field);
            assertThat(values.add(value)).as("duplicate %s %s", field, value).isTrue();
        }
        return values;
    }

    private Set<String> textArray(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        assertThat(value).as(field).isNotNull();
        assertThat(value.isArray()).as(field).isTrue();
        Set<String> values = new HashSet<>();
        for (JsonNode item : value) {
            assertThat(item.isTextual()).as(field).isTrue();
            values.add(item.asText());
        }
        return values;
    }

    private String requiredText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        assertThat(value).as(field).isNotNull();
        assertThat(value.isTextual()).as(field).isTrue();
        assertThat(value.asText()).as(field).isNotBlank();
        return value.asText();
    }

    private List<JsonNode> readJsonLines(Path path) throws IOException {
        assertThat(Files.exists(path)).as(path.toString()).isTrue();
        return Files.readAllLines(path).stream()
                .filter(line -> !line.isBlank())
                .map(line -> readJson(path, line))
                .toList();
    }

    private JsonNode readJson(Path path, String line) {
        try {
            JsonNode node = objectMapper.readTree(line);
            assertThat(node).as(path.toString()).isNotNull();
            assertThat(node.isObject()).as(path.toString()).isTrue();
            return node;
        } catch (IOException exception) {
            throw new AssertionError("Invalid JSONL in " + path, exception);
        }
    }

    private Path evaluationRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        for (int depth = 0; current != null && depth < 6; depth++) {
            Path candidate = current.resolve("evaluation");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new AssertionError("evaluation directory was not found from "
                + Path.of("").toAbsolutePath());
    }
}
