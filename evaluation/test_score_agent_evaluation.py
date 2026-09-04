import json
import tempfile
import unittest
from pathlib import Path

from score_agent_evaluation import EvaluationError, score_rag, score_tool_selection


class ScoreAgentEvaluationTest(unittest.TestCase):

    def write_jsonl(self, directory: Path, name: str, records: list[dict]) -> Path:
        path = directory / name
        path.write_text("".join(json.dumps(record, ensure_ascii=False) + "\n" for record in records), encoding="utf-8")
        return path

    def test_rag_reports_query_specific_hit_and_mrr(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            gold = self.write_jsonl(directory, "gold.jsonl", [
                {"queryId": "q-1", "relevantChunkIds": ["c-1"], "k": 3},
                {"queryId": "q-2", "relevantChunkIds": ["c-4"], "k": 2},
            ])
            results = self.write_jsonl(directory, "results.jsonl", [
                {"queryId": "q-1", "returnedChunkIds": ["c-9", "c-1"]},
                {"queryId": "q-2", "returnedChunkIds": ["c-2"]},
            ])

            report = score_rag(gold, results)

            self.assertEqual(report["hitAtK"], 0.5)
            self.assertEqual(report["mrr"], 0.25)
            self.assertEqual(report["missingCaseIds"], [])
            self.assertEqual(report["cases"][0]["firstRelevantRank"], 2)

    def test_tool_selection_checks_order_arguments_and_outcome(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            gold = self.write_jsonl(directory, "gold.jsonl", [
                {
                    "caseId": "tool-1",
                    "expectedCalls": [
                        {"name": "databaseQuery", "argumentAssertions": {
                            "sql": {"regex": r"^SELECT\s+1"},
                        }},
                    ],
                    "allowAdditionalCalls": False,
                    "orderMatters": True,
                    "expectedOutcome": "tool_then_answer",
                },
                {
                    "caseId": "tool-2",
                    "expectedCalls": [
                        {"name": "manage_plan", "argumentAssertions": {
                            "steps": {"minItems": 2},
                        }},
                    ],
                    "allowAdditionalCalls": False,
                    "orderMatters": True,
                    "expectedOutcome": "final_answer",
                },
            ])
            results = self.write_jsonl(directory, "results.jsonl", [
                {
                    "caseId": "tool-1",
                    "actualCalls": [{"name": "databaseQuery", "arguments": {"sql": "SELECT 1"}}],
                    "outcome": "tool_then_answer",
                },
                {
                    "caseId": "tool-2",
                    "actualCalls": [{"name": "manage_plan", "arguments": {"steps": ["a", "b"]}}],
                    "outcome": "final_answer",
                },
            ])

            report = score_tool_selection(gold, results)

            self.assertEqual(report["caseCorrectRate"], 1.0)
            self.assertEqual(report["toolNameAccuracy"], 1.0)
            self.assertEqual(report["argumentAccuracy"], 1.0)

    def test_duplicate_result_ids_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            gold = self.write_jsonl(directory, "gold.jsonl", [
                {"queryId": "q-1", "relevantChunkIds": ["c-1"], "k": 1},
            ])
            results = self.write_jsonl(directory, "results.jsonl", [
                {"queryId": "q-1", "returnedChunkIds": ["c-1", "c-1"]},
            ])

            with self.assertRaises(EvaluationError):
                score_rag(gold, results)

    def test_additional_tool_calls_can_be_allowed_without_losing_gold_order(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            gold = self.write_jsonl(directory, "gold.jsonl", [
                {
                    "caseId": "tool-extra",
                    "expectedCalls": [{"name": "databaseQuery"}],
                    "allowAdditionalCalls": True,
                    "orderMatters": True,
                    "expectedOutcome": "tool_then_answer",
                },
            ])
            results = self.write_jsonl(directory, "results.jsonl", [
                {
                    "caseId": "tool-extra",
                    "actualCalls": [
                        {"name": "KnowledgeTool", "arguments": {}},
                        {"name": "databaseQuery", "arguments": {}},
                    ],
                    "outcome": "tool_then_answer",
                },
            ])

            report = score_tool_selection(gold, results)

            self.assertEqual(report["caseCorrectRate"], 1.0)
            self.assertTrue(report["cases"][0]["orderExact"])

    def test_additional_tool_calls_can_be_allowed_without_order_constraint(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            gold = self.write_jsonl(directory, "gold.jsonl", [
                {
                    "caseId": "tool-extra-unordered",
                    "expectedCalls": [{"name": "databaseQuery"}],
                    "allowAdditionalCalls": True,
                    "orderMatters": False,
                    "expectedOutcome": "tool_then_answer",
                },
            ])
            results = self.write_jsonl(directory, "results.jsonl", [
                {
                    "caseId": "tool-extra-unordered",
                    "actualCalls": [
                        {"name": "KnowledgeTool", "arguments": {}},
                        {"name": "databaseQuery", "arguments": {}},
                    ],
                    "outcome": "tool_then_answer",
                },
            ])

            report = score_tool_selection(gold, results)

            self.assertEqual(report["caseCorrectRate"], 1.0)
            self.assertTrue(report["cases"][0]["orderExact"])


if __name__ == "__main__":
    unittest.main()
