#!/usr/bin/env python3
"""Prepare the public MTEB EcomRetrieval set for MindAgent RAG evaluation.

The adapter deliberately keeps downloaded data and runtime traces outside the
repository.  It accepts normalized JSONL/TSV exports for offline operation and
can optionally load the three MTEB dataset configurations through the
``datasets`` package.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any, Iterable, Mapping
from urllib.parse import quote


DATASET_NAME = "mteb/EcomRetrieval"
DATASET_REVISION = "1855a4f1bee3a64e11e439f15f129b4cb30cdb9d"
DATASET_SPLIT = "dev"
DEFAULT_QUERY_LIMIT = 100
DEFAULT_K = 10
DEFAULT_CORPUS_SAMPLE_SIZE = 5000
DEFAULT_CORPUS_SAMPLE_SEED = "mindagent-ecom-v1"
ID_PREFIX = "ecom-"


class DatasetFormatError(ValueError):
    """Raised when a public dataset export violates the adapter contract."""


def _first(record: Mapping[str, Any], aliases: tuple[str, ...], field: str) -> Any:
    for alias in aliases:
        if alias in record:
            return record[alias]
    raise DatasetFormatError(f"{field} is missing; expected one of {', '.join(aliases)}")


def _identifier(record: Mapping[str, Any], aliases: tuple[str, ...], field: str) -> str:
    value = _first(record, aliases, field)
    if value is None or isinstance(value, bool):
        raise DatasetFormatError(f"{field} must be a non-blank identifier")
    normalized = str(value).strip()
    if not normalized:
        raise DatasetFormatError(f"{field} must be a non-blank identifier")
    return normalized


def _text(record: Mapping[str, Any], aliases: tuple[str, ...], field: str) -> str:
    value = _first(record, aliases, field)
    if not isinstance(value, str) or not value.strip():
        raise DatasetFormatError(f"{field} must be a non-blank string")
    return value.strip()


def _stable_key(value: str) -> tuple[int, int | str]:
    try:
        return 0, int(value)
    except ValueError:
        return 1, value


def _prefixed(source_id: str) -> str:
    return f"{ID_PREFIX}{source_id}"


def _source_id(prefixed_id: str) -> str:
    return prefixed_id[len(ID_PREFIX):] if prefixed_id.startswith(ID_PREFIX) else prefixed_id


def _ensure_unique(records: Iterable[Mapping[str, Any]], aliases: tuple[str, ...], field: str) -> dict[str, Mapping[str, Any]]:
    indexed: dict[str, Mapping[str, Any]] = {}
    for record in records:
        identifier = _identifier(record, aliases, field)
        if identifier in indexed:
            raise DatasetFormatError(f"duplicate {field} {identifier}")
        indexed[identifier] = record
    return indexed


def _normalize_corpus(records: Iterable[Mapping[str, Any]]) -> list[dict[str, Any]]:
    indexed = _ensure_unique(records, ("sourceCorpusId", "_id", "id", "corpus-id", "corpusId", "pid"), "corpus ID")
    normalized: list[dict[str, Any]] = []
    for source_id, record in sorted(indexed.items(), key=lambda item: _stable_key(item[0])):
        normalized.append(
            {
                "chunkId": _prefixed(source_id),
                "documentId": f"{ID_PREFIX}doc-{source_id}",
                "content": _text(record, ("text", "content", "passage"), "corpus text"),
                "sourceCorpusId": source_id,
            }
        )
    return normalized


def _normalize_queries(records: Iterable[Mapping[str, Any]]) -> dict[str, dict[str, Any]]:
    indexed = _ensure_unique(records, ("_id", "id", "query-id", "queryId", "qid"), "query ID")
    return {
        source_id: {
            "queryId": _prefixed(source_id),
            "query": _text(record, ("text", "query", "question"), "query text"),
            "sourceQueryId": source_id,
        }
        for source_id, record in indexed.items()
    }


def _positive_qrels(records: Iterable[Mapping[str, Any]]) -> dict[str, list[str]]:
    qrels: dict[str, list[str]] = defaultdict(list)
    seen_pairs: set[tuple[str, str]] = set()
    for record in records:
        query_id = _identifier(record, ("query-id", "queryId", "qid"), "qrel query ID")
        corpus_id = _identifier(record, ("corpus-id", "corpusId", "pid"), "qrel corpus ID")
        raw_score = _first(record, ("score", "relevance", "label"), "qrel score")
        try:
            score = float(raw_score)
        except (TypeError, ValueError) as exception:
            raise DatasetFormatError(f"qrel score for {query_id}/{corpus_id} must be numeric") from exception
        if not math.isfinite(score):
            raise DatasetFormatError(f"qrel score for {query_id}/{corpus_id} must be finite")
        if score <= 0:
            continue
        pair = query_id, corpus_id
        if pair in seen_pairs:
            raise DatasetFormatError(f"duplicate qrel pair {query_id}/{corpus_id}")
        seen_pairs.add(pair)
        qrels[query_id].append(corpus_id)
    return {
        query_id: sorted(corpus_ids, key=_stable_key)
        for query_id, corpus_ids in qrels.items()
    }


def _numeric_score(value: str) -> int | float:
    try:
        score = float(value)
    except (TypeError, ValueError) as exception:
        raise DatasetFormatError(f"qrel score must be numeric: {value}") from exception
    if not math.isfinite(score):
        raise DatasetFormatError(f"qrel score must be finite: {value}")
    return int(score) if score.is_integer() else score


def select_corpus_records(
    corpus_records: Iterable[Mapping[str, Any]],
    selected_positive_corpus_ids: set[str],
    limit: int | None = None,
) -> list[dict[str, Any]]:
    """Return a deterministic corpus slice while retaining all selected positives."""

    if limit is not None and limit < 1:
        raise DatasetFormatError("corpus limit must be positive when provided")
    normalized = _normalize_corpus(corpus_records)
    positive_ids = {_source_id(value) for value in selected_positive_corpus_ids}
    if limit is None or limit >= len(normalized):
        return normalized

    selected = normalized[:limit]
    selected_ids = {record["sourceCorpusId"] for record in selected}
    selected.extend(
        record
        for record in normalized[limit:]
        if record["sourceCorpusId"] in positive_ids and record["sourceCorpusId"] not in selected_ids
    )
    return selected


def _sample_key(seed: str, source_corpus_id: str) -> tuple[str, tuple[int, int | str]]:
    digest = hashlib.sha256(f"{seed}\0{source_corpus_id}".encode("utf-8")).hexdigest()
    return digest, _stable_key(source_corpus_id)


def select_corpus_sample_records(
    corpus_records: Iterable[Mapping[str, Any]],
    selected_positive_corpus_ids: set[str],
    sample_size: int,
    seed: str = DEFAULT_CORPUS_SAMPLE_SEED,
) -> list[dict[str, Any]]:
    """Return a deterministic hash-ranked sample while retaining all positives."""

    if sample_size < 1:
        raise DatasetFormatError("corpus sample size must be positive")
    if not isinstance(seed, str) or not seed.strip():
        raise DatasetFormatError("corpus sample seed must be a non-blank string")

    normalized = _normalize_corpus(corpus_records)
    positive_ids = {_source_id(value) for value in selected_positive_corpus_ids}
    if sample_size >= len(normalized):
        return normalized

    ranked = sorted(
        normalized,
        key=lambda record: _sample_key(seed, record["sourceCorpusId"]),
    )
    selected = ranked[:sample_size]
    selected_ids = {record["sourceCorpusId"] for record in selected}
    selected.extend(
        record
        for record in ranked[sample_size:]
        if record["sourceCorpusId"] in positive_ids
        and record["sourceCorpusId"] not in selected_ids
    )
    return selected


def _write_jsonl(path: Path, records: Iterable[Mapping[str, Any]]) -> None:
    with path.open("w", encoding="utf-8", newline="\n") as output:
        for record in records:
            output.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")


def prepare_from_records(
    corpus_records: Iterable[Mapping[str, Any]],
    query_records: Iterable[Mapping[str, Any]],
    qrel_records: Iterable[Mapping[str, Any]],
    output_dir: Path,
    *,
    query_limit: int | None = DEFAULT_QUERY_LIMIT,
    k: int = DEFAULT_K,
    corpus_limit: int | None = None,
    corpus_sample_size: int | None = None,
    corpus_sample_seed: str = DEFAULT_CORPUS_SAMPLE_SEED,
    dataset_name: str = DATASET_NAME,
    split: str = DATASET_SPLIT,
    revision: str = DATASET_REVISION,
) -> dict[str, Any]:
    """Normalize records and write a scorer-compatible corpus and gold file."""

    if query_limit is not None and query_limit < 1:
        raise DatasetFormatError("query limit must be positive when provided")
    if k < 1:
        raise DatasetFormatError("k must be positive")
    if corpus_limit is not None and corpus_sample_size is not None:
        raise DatasetFormatError("corpus limit and corpus sample size are mutually exclusive")
    if corpus_sample_size is not None:
        if corpus_sample_size < 1:
            raise DatasetFormatError("corpus sample size must be positive")
        if not isinstance(corpus_sample_seed, str) or not corpus_sample_seed.strip():
            raise DatasetFormatError("corpus sample seed must be a non-blank string")
    if not isinstance(dataset_name, str) or not dataset_name.strip():
        raise DatasetFormatError("dataset name must be non-blank")
    if not isinstance(split, str) or not split.strip():
        raise DatasetFormatError("dataset split must be non-blank")
    if not isinstance(revision, str) or not revision.strip():
        raise DatasetFormatError("dataset revision must be non-blank")

    normalized_corpus = _normalize_corpus(corpus_records)
    corpus_ids = {record["sourceCorpusId"] for record in normalized_corpus}
    normalized_queries = _normalize_queries(query_records)
    qrels = _positive_qrels(qrel_records)

    ordered_query_ids = sorted(normalized_queries, key=_stable_key)
    selected_query_ids = ordered_query_ids if query_limit is None else ordered_query_ids[:query_limit]
    gold_queries: list[dict[str, Any]] = []
    selected_positive_ids: set[str] = set()
    for query_id in selected_query_ids:
        positive_ids = qrels.get(query_id, [])
        if not positive_ids:
            raise DatasetFormatError(f"query {query_id} has no positive qrel")
        missing_corpus_ids = sorted(set(positive_ids) - corpus_ids, key=_stable_key)
        if missing_corpus_ids:
            raise DatasetFormatError(
                f"query {query_id} references missing corpus IDs: {', '.join(missing_corpus_ids)}"
            )
        selected_positive_ids.update(positive_ids)
        query = normalized_queries[query_id]
        gold_queries.append(
            {
                "queryId": query["queryId"],
                "query": query["query"],
                "relevantChunkIds": [_prefixed(value) for value in positive_ids],
                "k": k,
                "dataset": dataset_name,
                "split": split,
                "sourceQueryId": query_id,
            }
        )

    selected_corpus = normalized_corpus
    if corpus_limit is not None:
        selected_corpus = select_corpus_records(normalized_corpus, selected_positive_ids, corpus_limit)
    elif corpus_sample_size is not None:
        selected_corpus = select_corpus_sample_records(
            normalized_corpus,
            selected_positive_ids,
            corpus_sample_size,
            corpus_sample_seed,
        )

    output_dir.mkdir(parents=True, exist_ok=True)
    _write_jsonl(output_dir / "corpus.jsonl", selected_corpus)
    _write_jsonl(output_dir / "queries.jsonl", gold_queries)
    manifest = {
        "schemaVersion": 1,
        "dataset": dataset_name,
        "revision": revision,
        "split": split,
        "profile": "full" if query_limit is None else "quick",
        "queryLimit": query_limit,
        "queryCount": len(gold_queries),
        "corpusLimit": corpus_limit,
        "corpusSampleSize": corpus_sample_size,
        "corpusSampleSeed": corpus_sample_seed if corpus_sample_size is not None else None,
        "corpusSamplingStrategy": (
            "sha256" if corpus_sample_size is not None
            else "prefix" if corpus_limit is not None
            else "full"
        ),
        "corpusCount": len(selected_corpus),
        "k": k,
        "rawDataStored": False,
        "goldFile": "queries.jsonl",
        "corpusFile": "corpus.jsonl",
    }
    (output_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return manifest


def read_records(path: Path, kind: str) -> list[dict[str, Any]]:
    """Read a JSONL or TSV export without requiring third-party packages."""

    if kind not in {"corpus", "queries", "qrels"}:
        raise DatasetFormatError(f"unsupported record kind: {kind}")
    if path.suffix.lower() in {".jsonl", ".json"}:
        records: list[dict[str, Any]] = []
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip():
                continue
            try:
                value = json.loads(line)
            except json.JSONDecodeError as exception:
                raise DatasetFormatError(f"invalid JSON at {path}:{line_number}") from exception
            if not isinstance(value, dict):
                raise DatasetFormatError(f"JSON record at {path}:{line_number} must be an object")
            records.append(value)
        return records

    records = []
    with path.open("r", encoding="utf-8", newline="") as source:
        for line_number, row in enumerate(csv.reader(source, delimiter="\t"), 1):
            if not row or not any(cell.strip() for cell in row):
                continue
            if line_number == 1 and _looks_like_header(row, kind):
                continue
            if kind == "qrels":
                if len(row) == 4:
                    records.append({"query-id": row[0], "corpus-id": row[2], "score": _numeric_score(row[3])})
                elif len(row) == 3:
                    records.append({"query-id": row[0], "corpus-id": row[1], "score": _numeric_score(row[2])})
                else:
                    raise DatasetFormatError(f"qrels row {path}:{line_number} must have 3 or 4 columns")
            else:
                if len(row) < 2:
                    raise DatasetFormatError(f"{kind} row {path}:{line_number} must have at least 2 columns")
                records.append({"id": row[0], "text": "\t".join(row[1:])})
    return records


def _looks_like_header(row: list[str], kind: str) -> bool:
    first = row[0].strip().lower()
    second = row[1].strip().lower() if len(row) > 1 else ""
    if kind == "qrels":
        return first in {"qid", "query-id", "query_id"} and second in {"q0", "corpus-id", "pid"}
    return first in {"id", "_id", "qid", "query-id", "corpus-id", "pid"} and second in {"text", "content", "passage", "query"}


def load_huggingface_records(
    dataset_name: str = DATASET_NAME,
    revision: str = DATASET_REVISION,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    """Load the three MTEB configurations when ``datasets`` is installed."""

    try:
        from datasets import load_dataset
    except ImportError as exception:
        raise DatasetFormatError(
            "Hugging Face loading requires the optional 'datasets' package; "
            "install it outside the repository or pass --corpus/--queries/--qrels exports"
        ) from exception

    try:
        corpus = load_dataset(dataset_name, "corpus", split=DATASET_SPLIT, revision=revision)
        queries = load_dataset(dataset_name, "queries", split=DATASET_SPLIT, revision=revision)
        qrels = load_dataset(dataset_name, "default", split=DATASET_SPLIT, revision=revision)
    except Exception as named_config_error:
        # The converted dataset currently exposes one default builder config;
        # load its three Parquet directories explicitly when named configs are
        # unavailable in the installed datasets version.
        encoded_revision = quote(revision, safe="")

        def load_parquet_config(config: str) -> Any:
            url = (
                f"https://huggingface.co/datasets/{dataset_name}/resolve/"
                f"{encoded_revision}/{config}/{DATASET_SPLIT}/0000.parquet"
            )
            return load_dataset(
                "parquet",
                data_files={DATASET_SPLIT: url},
                split=DATASET_SPLIT,
            )

        try:
            corpus = load_parquet_config("corpus")
            queries = load_parquet_config("queries")
            qrels = load_parquet_config("default")
        except Exception as parquet_error:
            raise DatasetFormatError(
                f"failed to load {dataset_name} revision {revision}: {parquet_error}"
            ) from named_config_error
    return list(corpus), list(queries), list(qrels)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="external output directory")
    parser.add_argument("--query-limit", type=int, default=DEFAULT_QUERY_LIMIT)
    parser.add_argument("--full", action="store_true", help="include all available queries")
    corpus_selection = parser.add_mutually_exclusive_group()
    corpus_selection.add_argument(
        "--corpus-limit",
        type=int,
        default=0,
        help="legacy deterministic prefix limit",
    )
    corpus_selection.add_argument(
        "--corpus-sample-size",
        type=int,
        help=f"deterministic SHA-256 corpus sample size (default profile: {DEFAULT_CORPUS_SAMPLE_SIZE})",
    )
    parser.add_argument("--corpus-sample-seed", default=DEFAULT_CORPUS_SAMPLE_SEED)
    parser.add_argument("--k", type=int, default=DEFAULT_K)
    parser.add_argument("--dataset", default=DATASET_NAME)
    parser.add_argument("--revision", default=DATASET_REVISION, help="Hugging Face dataset revision or commit")
    parser.add_argument("--corpus", type=Path)
    parser.add_argument("--queries", type=Path)
    parser.add_argument("--qrels", type=Path)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        paths = (args.corpus, args.queries, args.qrels)
        if any(path is not None for path in paths) and not all(path is not None for path in paths):
            raise DatasetFormatError("--corpus, --queries and --qrels must be supplied together")
        if all(path is not None for path in paths):
            corpus = read_records(args.corpus, "corpus")
            queries = read_records(args.queries, "queries")
            qrels = read_records(args.qrels, "qrels")
        else:
            corpus, queries, qrels = load_huggingface_records(args.dataset, args.revision)

        manifest = prepare_from_records(
            corpus,
            queries,
            qrels,
            args.output,
            query_limit=None if args.full else args.query_limit,
            k=args.k,
            corpus_limit=None if args.corpus_limit == 0 else args.corpus_limit,
            corpus_sample_size=args.corpus_sample_size,
            corpus_sample_seed=args.corpus_sample_seed,
            dataset_name=args.dataset,
            revision=args.revision,
        )
    except DatasetFormatError as exception:
        print(f"dataset error: {exception}", file=sys.stderr)
        return 2
    print(json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
