#!/usr/bin/env python3
"""Score offline MindAgent evaluation traces against the versioned gold data.

The scorer is deliberately independent from Spring Boot, PostgreSQL, Ollama,
and model providers.  It consumes JSONL produced by a separate runner and
prints a deterministic JSON report, so credentials and runtime data never need
to be stored with the evaluation set.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path
from typing import Any, Iterable


class EvaluationError(ValueError):
    """Raised when a gold or result JSONL file violates the scoring schema."""


_MISSING = object()


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    """Read non-blank JSONL records and require each record to be an object."""

    if not path.is_file():
        raise EvaluationError(f"JSONL file does not exist: {path}")
    records: list[dict[str, Any]] = []
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw_line.strip():
            continue
        try:
            value = json.loads(raw_line)
        except json.JSONDecodeError as exc:
            raise EvaluationError(f"invalid JSON at {path}:{line_number}: {exc.msg}") from exc
        if not isinstance(value, dict):
            raise EvaluationError(f"JSONL record at {path}:{line_number} must be an object")
        records.append(value)
    return records


def index_records(records: Iterable[dict[str, Any]], field: str, path: Path) -> dict[str, dict[str, Any]]:
    """Index records by a non-blank string ID and reject duplicates."""

    indexed: dict[str, dict[str, Any]] = {}
    for line_number, record in enumerate(records, 1):
        identifier = record.get(field)
        if not isinstance(identifier, str) or not identifier.strip():
            raise EvaluationError(f"{path}:{line_number} requires a non-blank {field}")
        if identifier in indexed:
            raise EvaluationError(f"duplicate {field} {identifier!r} in {path}")
        indexed[identifier] = record
    return indexed


def string_list(value: Any, field: str, path: Path) -> list[str]:
    if not isinstance(value, list) or not all(isinstance(item, str) and item.strip() for item in value):
        raise EvaluationError(f"{path}: {field} must be an array of non-blank strings")
    return list(value)


def score_rag(gold_path: Path, results_path: Path) -> dict[str, Any]:
    """Score RAG result traces with the query-specific K and gold chunks."""

    gold = index_records(read_jsonl(gold_path), "queryId", gold_path)
    results = index_records(read_jsonl(results_path), "queryId", results_path)
    missing = sorted(set(gold) - set(results))
    unexpected = sorted(set(results) - set(gold))
    case_reports: list[dict[str, Any]] = []
    hit_sum = 0.0
    mrr_sum = 0.0

    for query_id, query in gold.items():
        relevant = set(string_list(query.get("relevantChunkIds"), "relevantChunkIds", gold_path))
        k = query.get("k")
        if not isinstance(k, int) or isinstance(k, bool) or k < 1:
            raise EvaluationError(f"{gold_path}: query {query_id} requires positive integer k")

        result = results.get(query_id)
        if result is None:
            returned: list[str] = []
            status = "missing"
        else:
            returned = string_list(result.get("returnedChunkIds"), "returnedChunkIds", results_path)
            if len(set(returned)) != len(returned):
                raise EvaluationError(f"{results_path}: query {query_id} returned duplicate chunk IDs")
            status = "ok"

        first_rank = next(
            (index + 1 for index, chunk_id in enumerate(returned[:k]) if chunk_id in relevant),
            None,
        )
        hit = 1.0 if first_rank is not None else 0.0
        reciprocal_rank = 1.0 / first_rank if first_rank is not None else 0.0
        hit_sum += hit
        mrr_sum += reciprocal_rank
        case_reports.append(
            {
                "queryId": query_id,
                "k": k,
                "returnedChunkIds": returned,
                "relevantChunkIds": sorted(relevant),
                "firstRelevantRank": first_rank,
                "hitAtK": hit,
                "mrr": reciprocal_rank,
                "status": status,
            }
        )

    count = len(gold)
    return {
        "schemaVersion": 1,
        "mode": "rag",
        "goldCases": count,
        "resultsProvided": len(results),
        "missingCaseIds": missing,
        "unexpectedCaseIds": unexpected,
        "hitAtK": round(hit_sum / count, 6) if count else 0.0,
        "mrr": round(mrr_sum / count, 6) if count else 0.0,
        "cases": case_reports,
    }


def score_tool_selection(gold_path: Path, results_path: Path) -> dict[str, Any]:
    """Score tool names, order, arguments, and outcome for each gold case."""

    gold = index_records(read_jsonl(gold_path), "caseId", gold_path)
    results = index_records(read_jsonl(results_path), "caseId", results_path)
    missing = sorted(set(gold) - set(results))
    unexpected = sorted(set(results) - set(gold))
    reports: list[dict[str, Any]] = []

    for case_id, test_case in gold.items():
        result = results.get(case_id)
        expected_calls = test_case.get("expectedCalls")
        if not isinstance(expected_calls, list):
            raise EvaluationError(f"{gold_path}: case {case_id} requires expectedCalls array")
        expected_names = [expected_call_name(item, gold_path, case_id) for item in expected_calls]
        allow_additional = test_case.get("allowAdditionalCalls")
        order_matters = test_case.get("orderMatters")
        if not isinstance(allow_additional, bool) or not isinstance(order_matters, bool):
            raise EvaluationError(
                f"{gold_path}: case {case_id} requires boolean allowAdditionalCalls and orderMatters"
            )

        if result is None:
            actual_calls: list[dict[str, Any]] = []
            actual_outcome: Any = _MISSING
            status = "missing"
        else:
            actual_calls = result.get("actualCalls")
            if not isinstance(actual_calls, list) or not all(isinstance(call, dict) for call in actual_calls):
                raise EvaluationError(f"{results_path}: case {case_id} requires actualCalls object array")
            actual_outcome = result.get("outcome", _MISSING)
            status = "ok"

        actual_names = [call.get("name") for call in actual_calls]
        if not all(isinstance(name, str) and name.strip() for name in actual_names):
            raise EvaluationError(f"{results_path}: case {case_id} actualCalls require non-blank names")

        name_valid = names_match(expected_names, actual_names, allow_additional, order_matters)
        order_valid = order_match(expected_names, actual_names, order_matters, allow_additional)
        pairs = align_calls(expected_names, actual_calls, allow_additional, order_matters)
        arguments_valid = arguments_match(expected_calls, pairs)
        outcome_valid = actual_outcome is not _MISSING and actual_outcome == test_case.get("expectedOutcome")
        case_correct = name_valid and order_valid and arguments_valid and outcome_valid
        reports.append(
            {
                "caseId": case_id,
                "toolNameExact": name_valid,
                "orderExact": order_valid,
                "argumentsValid": arguments_valid,
                "outcomeValid": outcome_valid,
                "caseCorrect": case_correct,
                "expectedTools": expected_names,
                "actualTools": actual_names,
                "status": status,
            }
        )

    count = len(gold)
    return {
        "schemaVersion": 1,
        "mode": "tool-selection",
        "goldCases": count,
        "resultsProvided": len(results),
        "missingCaseIds": missing,
        "unexpectedCaseIds": unexpected,
        "caseCorrectRate": rate(reports, "caseCorrect", count),
        "toolNameAccuracy": rate(reports, "toolNameExact", count),
        "orderAccuracy": rate(reports, "orderExact", count),
        "argumentAccuracy": rate(reports, "argumentsValid", count),
        "outcomeAccuracy": rate(reports, "outcomeValid", count),
        "cases": reports,
    }


def expected_call_name(expected_call: Any, path: Path, case_id: str) -> str:
    if not isinstance(expected_call, dict):
        raise EvaluationError(f"{path}: case {case_id} expectedCalls entries must be objects")
    name = expected_call.get("name")
    if not isinstance(name, str) or not name.strip():
        raise EvaluationError(f"{path}: case {case_id} expected call requires non-blank name")
    return name


def names_match(expected: list[str], actual: list[str], allow_additional: bool, order_matters: bool) -> bool:
    if not allow_additional:
        return actual == expected
    if order_matters:
        return is_subsequence(expected, actual)
    return not (Counter(expected) - Counter(actual))


def order_match(
    expected: list[str],
    actual: list[str],
    order_matters: bool,
    allow_additional: bool = False,
) -> bool:
    if order_matters:
        return is_subsequence(expected, actual) if allow_additional else actual == expected
    return not (Counter(expected) - Counter(actual)) if allow_additional else Counter(actual) == Counter(expected)


def is_subsequence(expected: list[str], actual: list[str]) -> bool:
    position = 0
    for item in actual:
        if position < len(expected) and expected[position] == item:
            position += 1
    return position == len(expected)


def align_calls(
    expected_names: list[str],
    actual_calls: list[dict[str, Any]],
    allow_additional: bool,
    order_matters: bool,
) -> list[tuple[int, dict[str, Any] | None]]:
    """Pair gold calls with actual calls for argument checks."""

    if not allow_additional:
        return [(index, actual_calls[index] if index < len(actual_calls) else None)
                for index in range(len(expected_names))]
    if order_matters:
        pairs: list[tuple[int, dict[str, Any] | None]] = []
        cursor = 0
        for expected_index, expected_name in enumerate(expected_names):
            while cursor < len(actual_calls) and actual_calls[cursor].get("name") != expected_name:
                cursor += 1
            pairs.append((expected_index, actual_calls[cursor] if cursor < len(actual_calls) else None))
            cursor += 1
        return pairs

    remaining = list(actual_calls)
    pairs = []
    for expected_index, expected_name in enumerate(expected_names):
        match_index = next(
            (index for index, call in enumerate(remaining) if call.get("name") == expected_name),
            None,
        )
        if match_index is None:
            pairs.append((expected_index, None))
        else:
            pairs.append((expected_index, remaining.pop(match_index)))
    return pairs


def arguments_match(expected_calls: list[Any], pairs: list[tuple[int, dict[str, Any] | None]]) -> bool:
    for expected_index, actual_call in pairs:
        expected_call = expected_calls[expected_index]
        if not isinstance(expected_call, dict):
            return False
        assertions = expected_call.get("argumentAssertions", {})
        if assertions is None:
            assertions = {}
        if not isinstance(assertions, dict) or actual_call is None:
            return False if assertions else actual_call is not None
        arguments = actual_call.get("arguments", {})
        if isinstance(arguments, str):
            try:
                arguments = json.loads(arguments)
            except json.JSONDecodeError:
                pass
        for field_path, assertion in assertions.items():
            actual_value = get_path(arguments, field_path)
            if actual_value is _MISSING or not assertion_matches(actual_value, assertion):
                return False
    return True


def get_path(value: Any, path: str) -> Any:
    current = value
    for component in path.split("."):
        if isinstance(current, dict) and component in current:
            current = current[component]
        elif isinstance(current, list) and component.isdigit() and int(component) < len(current):
            current = current[int(component)]
        else:
            return _MISSING
    return current


def assertion_matches(actual: Any, assertion: Any) -> bool:
    if not isinstance(assertion, dict):
        return False
    if not assertion or any(operator not in {"equals", "contains", "containsAny", "regex", "minItems"}
                            for operator in assertion):
        return False
    for operator, expected in assertion.items():
        if operator == "equals" and actual != expected:
            return False
        if operator == "contains" and not contains(actual, expected):
            return False
        if operator == "containsAny":
            if not isinstance(expected, list) or not any(contains(actual, candidate) for candidate in expected):
                return False
        if operator == "regex":
            if not isinstance(expected, str) or not isinstance(actual, str) or re.search(expected, actual) is None:
                return False
        if operator == "minItems":
            if not isinstance(expected, int) or isinstance(expected, bool) or not has_size(actual, expected):
                return False
    return True


def contains(actual: Any, expected: Any) -> bool:
    if isinstance(actual, str):
        return str(expected) in actual
    if isinstance(actual, (list, tuple, set)):
        return expected in actual
    if isinstance(actual, dict):
        return expected in actual or expected in actual.values()
    return False


def has_size(value: Any, minimum: int) -> bool:
    try:
        return len(value) >= minimum
    except TypeError:
        return False


def rate(reports: list[dict[str, Any]], field: str, count: int) -> float:
    if not count:
        return 0.0
    return round(sum(1 for report in reports if report[field]) / count, 6)


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("rag", "tool-selection"), required=True)
    parser.add_argument("--gold", type=Path, required=True, help="versioned gold JSONL")
    parser.add_argument("--results", type=Path, required=True, help="de-identified runtime result JSONL")
    parser.add_argument("--pretty", action="store_true", help="pretty-print the JSON report")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    try:
        if args.mode == "rag":
            report = score_rag(args.gold, args.results)
        else:
            report = score_tool_selection(args.gold, args.results)
    except EvaluationError as exc:
        print(f"evaluation error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(report, ensure_ascii=False, indent=2 if args.pretty else None, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
