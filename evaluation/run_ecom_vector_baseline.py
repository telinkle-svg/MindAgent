#!/usr/bin/env python3
"""Run a reproducible Ollama vector-retrieval baseline on prepared JSONL data.

The default mode intentionally mirrors MindAgent's current RAG path: the
legacy ``/api/embeddings`` endpoint, raw vectors, and exact L2 ranking. Runtime
artifacts must be written outside the repository.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
import time
from pathlib import Path
from typing import Any, Callable, Mapping, Sequence
from urllib import error, request


DEFAULT_BASE_URL = "http://localhost:11434"
DEFAULT_MODEL = "bge-m3"
DEFAULT_ENDPOINT_MODE = "legacy"
DEFAULT_METRIC = "l2"
DEFAULT_BATCH_SIZE = 32
DEFAULT_TOP_K = 10
DEFAULT_TIMEOUT_SECONDS = 120.0


class BaselineFormatError(ValueError):
    """Raised when input data, embeddings, or runner settings are invalid."""


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.is_file():
        raise BaselineFormatError(f"JSONL file does not exist: {path}")

    records: list[dict[str, Any]] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError as exception:
            raise BaselineFormatError(f"invalid JSON at {path}:{line_number}") from exception
        if not isinstance(value, dict):
            raise BaselineFormatError(f"JSON record at {path}:{line_number} must be an object")
        records.append(value)
    return records


def _require_text(record: Mapping[str, Any], field: str, record_number: int) -> str:
    value = record.get(field)
    if not isinstance(value, str) or not value.strip():
        raise BaselineFormatError(f"record {record_number} field {field} must be a non-blank string")
    return value.strip()


def _validate_records(corpus: Sequence[Mapping[str, Any]], queries: Sequence[Mapping[str, Any]]) -> None:
    if not corpus:
        raise BaselineFormatError("corpus must contain at least one record")
    if not queries:
        raise BaselineFormatError("queries must contain at least one record")

    corpus_ids: set[str] = set()
    for number, record in enumerate(corpus, 1):
        chunk_id = _require_text(record, "chunkId", number)
        _require_text(record, "content", number)
        if chunk_id in corpus_ids:
            raise BaselineFormatError(f"duplicate corpus chunkId: {chunk_id}")
        corpus_ids.add(chunk_id)

    query_ids: set[str] = set()
    for number, record in enumerate(queries, 1):
        query_id = _require_text(record, "queryId", number)
        _require_text(record, "query", number)
        if query_id in query_ids:
            raise BaselineFormatError(f"duplicate queryId: {query_id}")
        query_ids.add(query_id)
        relevant = record.get("relevantChunkIds", [])
        if not isinstance(relevant, list) or any(not isinstance(value, str) or not value.strip() for value in relevant):
            raise BaselineFormatError(f"query {query_id} relevantChunkIds must be a list of non-blank strings")
        missing = sorted(set(relevant) - corpus_ids)
        if missing:
            raise BaselineFormatError(
                f"query {query_id} references corpus chunks not present in the sample: {', '.join(missing)}"
            )


def _validate_embedding(raw: Any, expected_dimension: int | None = None) -> list[float]:
    if not isinstance(raw, (list, tuple)) or not raw:
        raise BaselineFormatError("embedding must be a non-empty array")
    values: list[float] = []
    for value in raw:
        if isinstance(value, bool):
            raise BaselineFormatError("embedding values must be numeric")
        try:
            converted = float(value)
        except (TypeError, ValueError) as exception:
            raise BaselineFormatError("embedding values must be numeric") from exception
        if not math.isfinite(converted):
            raise BaselineFormatError("embedding values must be finite")
        values.append(converted)
    if expected_dimension is not None and len(values) != expected_dimension:
        raise BaselineFormatError(
            f"embedding dimension mismatch: expected {expected_dimension}, got {len(values)}"
        )
    return values


def _post_json(url: str, payload: Mapping[str, Any], timeout: float) -> Mapping[str, Any]:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    http_request = request.Request(
        url,
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with request.urlopen(http_request, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
    except error.HTTPError as exception:
        detail = exception.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Ollama request failed with HTTP {exception.code}: {detail[:500]}") from exception
    except error.URLError as exception:
        raise RuntimeError(f"Ollama request failed: {exception.reason}") from exception

    try:
        value = json.loads(raw)
    except json.JSONDecodeError as exception:
        raise RuntimeError("Ollama returned invalid JSON") from exception
    if not isinstance(value, dict):
        raise RuntimeError("Ollama response must be a JSON object")
    return value


class OllamaEmbeddingClient:
    """Small client supporting both Ollama embedding endpoint contracts."""

    def __init__(
        self,
        base_url: str = DEFAULT_BASE_URL,
        model: str = DEFAULT_MODEL,
        endpoint_mode: str = DEFAULT_ENDPOINT_MODE,
        timeout: float = DEFAULT_TIMEOUT_SECONDS,
        transport: Callable[[str, Mapping[str, Any], float], Mapping[str, Any]] | None = None,
    ) -> None:
        if endpoint_mode not in {"legacy", "embed"}:
            raise BaselineFormatError("endpoint mode must be legacy or embed")
        if timeout <= 0:
            raise BaselineFormatError("timeout must be positive")
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.endpoint_mode = endpoint_mode
        self.timeout = timeout
        self.transport = transport or _post_json

    @property
    def endpoint(self) -> str:
        return f"{self.base_url}/api/embeddings" if self.endpoint_mode == "legacy" else f"{self.base_url}/api/embed"

    def embed(self, texts: Sequence[str]) -> list[list[float]]:
        if not texts:
            return []
        if any(not isinstance(text, str) or not text.strip() for text in texts):
            raise BaselineFormatError("texts to embed must be non-blank strings")

        if self.endpoint_mode == "legacy":
            result: list[list[float]] = []
            for text in texts:
                response = self.transport(
                    self.endpoint,
                    {"model": self.model, "prompt": text},
                    self.timeout,
                )
                if not isinstance(response, Mapping) or "embedding" not in response:
                    raise BaselineFormatError("legacy Ollama response is missing embedding")
                result.append(_validate_embedding(response["embedding"]))
            return result

        response = self.transport(
            self.endpoint,
            {"model": self.model, "input": list(texts)},
            self.timeout,
        )
        if not isinstance(response, Mapping) or not isinstance(response.get("embeddings"), list):
            raise BaselineFormatError("Ollama /api/embed response is missing embeddings")
        embeddings = response["embeddings"]
        if len(embeddings) != len(texts):
            raise BaselineFormatError(
                f"Ollama returned {len(embeddings)} embeddings for {len(texts)} inputs"
            )
        return [_validate_embedding(value) for value in embeddings]


class EmbeddingCache:
    def __init__(self, path: Path | None, model: str, endpoint_mode: str) -> None:
        self.path = path
        self.model = model
        self.endpoint_mode = endpoint_mode
        self.records: dict[str, list[float]] = {}
        self.hits = 0
        self.misses = 0
        if path is not None and path.exists():
            self._load()

    def _load(self) -> None:
        assert self.path is not None
        for line_number, line in enumerate(self.path.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip():
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError as exception:
                raise BaselineFormatError(f"invalid embedding cache JSON at {self.path}:{line_number}") from exception
            if not isinstance(record, dict):
                raise BaselineFormatError(f"embedding cache record at {self.path}:{line_number} must be an object")
            key = record.get("key")
            if not isinstance(key, str) or not key:
                raise BaselineFormatError(f"embedding cache record at {self.path}:{line_number} has no key")
            if record.get("model") != self.model or record.get("endpointMode") != self.endpoint_mode:
                continue
            self.records[key] = _validate_embedding(record.get("embedding"))

    def key_for(self, text: str) -> str:
        digest = hashlib.sha256(text.encode("utf-8")).hexdigest()
        return f"{self.model}|{self.endpoint_mode}|{digest}"

    def get(self, key: str) -> list[float] | None:
        value = self.records.get(key)
        if value is None:
            self.misses += 1
            return None
        self.hits += 1
        return list(value)

    def put(self, key: str, embedding: Sequence[float]) -> None:
        self.records[key] = list(embedding)

    def flush(self) -> None:
        if self.path is None:
            return
        self.path.parent.mkdir(parents=True, exist_ok=True)
        lines = []
        for key in sorted(self.records):
            lines.append(
                json.dumps(
                    {
                        "key": key,
                        "model": self.model,
                        "endpointMode": self.endpoint_mode,
                        "embedding": self.records[key],
                    },
                    ensure_ascii=False,
                    sort_keys=True,
                )
            )
        self.path.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")


def embed_texts(
    texts: Sequence[str],
    client: OllamaEmbeddingClient,
    cache: EmbeddingCache,
    batch_size: int,
    expected_dimension: int | None = None,
) -> tuple[list[list[float]], int | None]:
    if batch_size < 1:
        raise BaselineFormatError("batch size must be positive")

    vectors: list[list[float] | None] = [None] * len(texts)
    pending: list[tuple[int, str, str]] = []
    for index, text in enumerate(texts):
        key = cache.key_for(text)
        cached = cache.get(key)
        if cached is None:
            pending.append((index, text, key))
        else:
            if expected_dimension is None:
                expected_dimension = len(cached)
            vectors[index] = cached

    if client.endpoint_mode == "legacy":
        batches = [pending[index:index + 1] for index in range(0, len(pending), 1)]
    else:
        batches = [pending[index:index + batch_size] for index in range(0, len(pending), batch_size)]

    for batch in batches:
        generated = client.embed([item[1] for item in batch])
        for item, raw_embedding in zip(batch, generated):
            embedding = _validate_embedding(raw_embedding, expected_dimension)
            if expected_dimension is None:
                expected_dimension = len(embedding)
            cache.put(item[2], embedding)
            vectors[item[0]] = embedding

    completed = [vector for vector in vectors if vector is not None]
    if len(completed) != len(texts):
        raise BaselineFormatError("embedding client did not return every requested vector")
    normalized = [_validate_embedding(vector, expected_dimension) for vector in completed]
    return normalized, expected_dimension


def _distance(query: Sequence[float], candidate: Sequence[float], metric: str) -> float:
    if len(query) != len(candidate):
        raise BaselineFormatError(
            f"embedding dimension mismatch: query has {len(query)}, candidate has {len(candidate)}"
        )
    if metric == "l2":
        return sum((left - right) ** 2 for left, right in zip(query, candidate))
    if metric == "cosine":
        query_norm = math.sqrt(sum(value * value for value in query))
        candidate_norm = math.sqrt(sum(value * value for value in candidate))
        if query_norm == 0 or candidate_norm == 0:
            raise BaselineFormatError("cosine distance is undefined for a zero vector")
        dot = sum(left * right for left, right in zip(query, candidate))
        return 1.0 - dot / (query_norm * candidate_norm)
    raise BaselineFormatError("metric must be l2 or cosine")


def rank_candidates(
    query_vector: Sequence[float],
    corpus_records: Sequence[Mapping[str, Any]],
    candidate_vectors: Mapping[str, Sequence[float]] | Sequence[Sequence[float]],
    top_k: int,
    *,
    metric: str = DEFAULT_METRIC,
) -> list[Mapping[str, Any]]:
    if top_k < 1:
        raise BaselineFormatError("top-k must be positive")
    if len(candidate_vectors) != len(corpus_records):
        raise BaselineFormatError("candidate vector count does not match corpus count")

    scored: list[tuple[float, str, Mapping[str, Any]]] = []
    for index, record in enumerate(corpus_records):
        chunk_id = _require_text(record, "chunkId", index + 1)
        vector = candidate_vectors[chunk_id] if isinstance(candidate_vectors, Mapping) else candidate_vectors[index]
        score = _distance(query_vector, vector, metric)
        scored.append((score, chunk_id, record))
    scored.sort(key=lambda item: (item[0], item[1]))
    return [item[2] for item in scored[:top_k]]


def _percentile(values: Sequence[float], percentile: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    rank = max(1, math.ceil(percentile / 100.0 * len(ordered)))
    return float(ordered[min(rank, len(ordered)) - 1])


def _score_results(queries: Sequence[Mapping[str, Any]], result_records: Sequence[Mapping[str, Any]]) -> dict[str, float]:
    result_by_id = {record["queryId"]: record for record in result_records}
    hit_at_1 = 0
    hit_at_5 = 0
    hit_at_10 = 0
    reciprocal_rank = 0.0
    for query in queries:
        query_id = query["queryId"]
        relevant = set(query.get("relevantChunkIds", []))
        returned = result_by_id[query_id]["returnedChunkIds"]
        hit_at_1 += bool(set(returned[:1]) & relevant)
        hit_at_5 += bool(set(returned[:5]) & relevant)
        hit_at_10 += bool(set(returned[:10]) & relevant)
        for rank, chunk_id in enumerate(returned[:10], 1):
            if chunk_id in relevant:
                reciprocal_rank += 1.0 / rank
                break
    count = len(queries)
    return {
        "hitAt1": hit_at_1 / count,
        "hitAt5": hit_at_5 / count,
        "hitAt10": hit_at_10 / count,
        "mrrAt10": reciprocal_rank / count,
    }


def _write_jsonl(path: Path, records: Sequence[Mapping[str, Any]]) -> None:
    path.write_text(
        "".join(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n" for record in records),
        encoding="utf-8",
    )


def run_baseline(
    corpus_path: Path,
    queries_path: Path,
    output_dir: Path,
    *,
    base_url: str = DEFAULT_BASE_URL,
    model: str = DEFAULT_MODEL,
    endpoint_mode: str = DEFAULT_ENDPOINT_MODE,
    metric: str = DEFAULT_METRIC,
    batch_size: int = DEFAULT_BATCH_SIZE,
    top_k: int = DEFAULT_TOP_K,
    timeout: float = DEFAULT_TIMEOUT_SECONDS,
    embedding_cache: Path | None = None,
    transport: Callable[[str, Mapping[str, Any], float], Mapping[str, Any]] | None = None,
) -> dict[str, Any]:
    if metric not in {"l2", "cosine"}:
        raise BaselineFormatError("metric must be l2 or cosine")
    if top_k < 1:
        raise BaselineFormatError("top-k must be positive")
    if batch_size < 1:
        raise BaselineFormatError("batch size must be positive")
    if timeout <= 0:
        raise BaselineFormatError("timeout must be positive")

    corpus = read_jsonl(corpus_path)
    queries = read_jsonl(queries_path)
    _validate_records(corpus, queries)
    client = OllamaEmbeddingClient(
        base_url=base_url,
        model=model,
        endpoint_mode=endpoint_mode,
        timeout=timeout,
        transport=transport,
    )
    cache = EmbeddingCache(embedding_cache, model, endpoint_mode)

    corpus_started = time.perf_counter()
    corpus_vectors, dimension = embed_texts(
        [record["content"] for record in corpus],
        client,
        cache,
        batch_size,
    )
    corpus_embedding_seconds = time.perf_counter() - corpus_started
    cache.flush()
    vector_by_id = {
        record["chunkId"]: vector
        for record, vector in zip(corpus, corpus_vectors)
    }

    result_records: list[dict[str, Any]] = []
    query_latencies_ms: list[float] = []
    query_embedding_seconds = 0.0
    query_vectors: list[list[float]]
    query_latency_scope = "embedding+ranking" if endpoint_mode == "legacy" else "ranking-only"

    if endpoint_mode == "legacy":
        query_vectors = []
        for query in queries:
            query_started = time.perf_counter()
            query_embedding_started = time.perf_counter()
            vectors, dimension = embed_texts(
                [query["query"]],
                client,
                cache,
                batch_size,
                expected_dimension=dimension,
            )
            query_embedding_seconds += time.perf_counter() - query_embedding_started
            ranked = rank_candidates(vectors[0], corpus, vector_by_id, top_k, metric=metric)
            query_latencies_ms.append((time.perf_counter() - query_started) * 1000.0)
            result_records.append(
                {
                    "queryId": query["queryId"],
                    "returnedChunkIds": [record["chunkId"] for record in ranked],
                }
            )
    else:
        query_embedding_started = time.perf_counter()
        query_vectors, dimension = embed_texts(
            [query["query"] for query in queries],
            client,
            cache,
            batch_size,
            expected_dimension=dimension,
        )
        query_embedding_seconds = time.perf_counter() - query_embedding_started
        for query, query_vector in zip(queries, query_vectors):
            ranking_started = time.perf_counter()
            ranked = rank_candidates(query_vector, corpus, vector_by_id, top_k, metric=metric)
            query_latencies_ms.append((time.perf_counter() - ranking_started) * 1000.0)
            result_records.append(
                {
                    "queryId": query["queryId"],
                    "returnedChunkIds": [record["chunkId"] for record in ranked],
                }
            )

    cache.flush()
    output_dir.mkdir(parents=True, exist_ok=True)
    _write_jsonl(output_dir / "results.jsonl", result_records)
    metrics: dict[str, Any] = {
        "schemaVersion": 1,
        "dataset": queries[0].get("dataset"),
        "split": queries[0].get("split"),
        "model": model,
        "endpointMode": endpoint_mode,
        "metric": metric,
        "topK": top_k,
        "candidateCount": len(corpus),
        "queryCount": len(queries),
        "embeddingDimension": dimension,
        "queryLatencyScope": query_latency_scope,
        "timingSeconds": {
            "corpusEmbedding": corpus_embedding_seconds,
            "queryEmbedding": query_embedding_seconds,
        },
        "queryLatencyMs": {
            "p50": _percentile(query_latencies_ms, 50),
            "p95": _percentile(query_latencies_ms, 95),
            "max": max(query_latencies_ms) if query_latencies_ms else 0.0,
        },
        "cache": {
            "path": str(embedding_cache) if embedding_cache is not None else None,
            "hits": cache.hits,
            "misses": cache.misses,
        },
    }
    metrics.update(_score_results(queries, result_records))
    (output_dir / "metrics.json").write_text(
        json.dumps(metrics, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return metrics


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--queries", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--ollama-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--endpoint-mode", choices=("legacy", "embed"), default=DEFAULT_ENDPOINT_MODE)
    parser.add_argument("--metric", choices=("l2", "cosine"), default=DEFAULT_METRIC)
    parser.add_argument("--batch-size", type=int, default=DEFAULT_BATCH_SIZE)
    parser.add_argument("--top-k", type=int, default=DEFAULT_TOP_K)
    parser.add_argument("--timeout-seconds", type=float, default=DEFAULT_TIMEOUT_SECONDS)
    parser.add_argument("--embedding-cache", type=Path)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        metrics = run_baseline(
            args.corpus,
            args.queries,
            args.output,
            base_url=args.ollama_url,
            model=args.model,
            endpoint_mode=args.endpoint_mode,
            metric=args.metric,
            batch_size=args.batch_size,
            top_k=args.top_k,
            timeout=args.timeout_seconds,
            embedding_cache=args.embedding_cache,
        )
    except BaselineFormatError as exception:
        print(f"baseline error: {exception}", file=sys.stderr)
        return 2
    except Exception as exception:
        print(f"baseline failed: {exception}", file=sys.stderr)
        return 1
    print(json.dumps(metrics, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
