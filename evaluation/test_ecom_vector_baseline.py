import json
import tempfile
import unittest
from pathlib import Path

from run_ecom_vector_baseline import (
    BaselineFormatError,
    EmbeddingCache,
    OllamaEmbeddingClient,
    rank_candidates,
    run_baseline,
)


class FakeTransport:
    def __init__(self, vectors):
        self.vectors = vectors
        self.calls = []

    def __call__(self, url, payload, timeout):
        self.calls.append((url, payload, timeout))
        if url.endswith("/api/embeddings"):
            return {"embedding": self.vectors[payload["prompt"]]}
        return {"embeddings": [self.vectors[text] for text in payload["input"]]}


class EcomVectorBaselineTest(unittest.TestCase):

    def test_legacy_client_sends_single_prompt_and_parses_embedding(self):
        transport = FakeTransport({"query": [1.0, 0.0]})
        client = OllamaEmbeddingClient(
            base_url="http://ollama.test",
            model="bge-m3",
            endpoint_mode="legacy",
            transport=transport,
        )

        self.assertEqual(client.embed(["query"]), [[1.0, 0.0]])
        self.assertEqual(
            transport.calls,
            [("http://ollama.test/api/embeddings", {"model": "bge-m3", "prompt": "query"}, 120.0)],
        )

    def test_embed_client_sends_batch_input_and_parses_embeddings(self):
        transport = FakeTransport({"first": [1.0, 0.0], "second": [0.0, 1.0]})
        client = OllamaEmbeddingClient(
            base_url="http://ollama.test/",
            model="bge-m3",
            endpoint_mode="embed",
            transport=transport,
        )

        self.assertEqual(client.embed(["first", "second"]), [[1.0, 0.0], [0.0, 1.0]])
        self.assertEqual(
            transport.calls,
            [("http://ollama.test/api/embed", {"model": "bge-m3", "input": ["first", "second"]}, 120.0)],
        )

    def test_l2_and_cosine_can_produce_different_orderings(self):
        records = [
            {"chunkId": "unit-ish", "content": "unit-ish"},
            {"chunkId": "large-aligned", "content": "large-aligned"},
        ]
        vectors = {
            "unit-ish": [0.7, 0.7],
            "large-aligned": [2.0, 0.0],
        }

        l2 = rank_candidates([1.0, 0.0], records, vectors, 2, metric="l2")
        cosine = rank_candidates([1.0, 0.0], records, vectors, 2, metric="cosine")

        self.assertEqual(l2[0]["chunkId"], "unit-ish")
        self.assertEqual(cosine[0]["chunkId"], "large-aligned")

    def test_rank_ties_are_broken_by_chunk_id(self):
        records = [
            {"chunkId": "chunk-b", "content": "b"},
            {"chunkId": "chunk-a", "content": "a"},
        ]
        vectors = {"chunk-a": [1.0, 0.0], "chunk-b": [1.0, 0.0]}

        ranked = rank_candidates([0.0, 0.0], records, vectors, 2, metric="l2")

        self.assertEqual([record["chunkId"] for record in ranked], ["chunk-a", "chunk-b"])

    def test_run_baseline_reuses_cache_and_writes_metrics(self):
        vectors = {
            "first document": [1.0, 0.0],
            "second document": [0.0, 1.0],
            "first query": [1.0, 0.0],
        }
        transport = FakeTransport(vectors)
        corpus = [
            {"chunkId": "chunk-1", "content": "first document"},
            {"chunkId": "chunk-2", "content": "second document"},
        ]
        queries = [
            {
                "queryId": "query-1",
                "query": "first query",
                "relevantChunkIds": ["chunk-1"],
                "k": 2,
            }
        ]

        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            corpus_path = root / "corpus.jsonl"
            queries_path = root / "queries.jsonl"
            output_path = root / "output"
            cache_path = root / "embeddings.jsonl"
            self.write_jsonl(corpus_path, corpus)
            self.write_jsonl(queries_path, queries)

            first = run_baseline(
                corpus_path,
                queries_path,
                output_path,
                base_url="http://ollama.test",
                endpoint_mode="legacy",
                metric="l2",
                top_k=10,
                model_digest="test-digest",
                embedding_cache=cache_path,
                transport=transport,
            )
            call_count = len(transport.calls)
            second = run_baseline(
                corpus_path,
                queries_path,
                output_path,
                base_url="http://ollama.test",
                endpoint_mode="legacy",
                metric="l2",
                top_k=10,
                model_digest="test-digest",
                embedding_cache=cache_path,
                transport=transport,
            )

            self.assertEqual(first["cache"]["misses"], 3)
            self.assertEqual(first["cache"]["queryHits"], 0)
            self.assertEqual(first["cache"]["queryMisses"], 1)
            self.assertEqual(first["queryLatencyScope"], "embedding+ranking")
            self.assertEqual(second["cache"]["hits"], 3)
            self.assertEqual(second["cache"]["queryHits"], 1)
            self.assertEqual(second["cache"]["queryMisses"], 0)
            self.assertEqual(second["queryLatencyScope"], "ranking-only")
            self.assertEqual(second["embeddingDimension"], 2)
            self.assertEqual(len(transport.calls), call_count)
            results = self.read_jsonl(output_path / "results.jsonl")
            self.assertEqual(results[0]["returnedChunkIds"], ["chunk-1", "chunk-2"])
            metrics = json.loads((output_path / "metrics.json").read_text(encoding="utf-8"))
            self.assertEqual(metrics["metric"], "l2")
            self.assertEqual(metrics["candidateCount"], 2)
            self.assertEqual(metrics["hitAt1"], 1.0)
            self.assertEqual(metrics["mrrAt10"], 1.0)

    def test_run_baseline_requires_top_k_of_at_least_ten(self):
        with self.assertRaisesRegex(BaselineFormatError, "at least 10"):
            run_baseline(Path("missing-corpus.jsonl"), Path("missing-queries.jsonl"), Path("output"), top_k=9)

    def test_embedding_cache_requires_model_digest(self):
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(BaselineFormatError, "model digest is required"):
                EmbeddingCache(Path(temp) / "embeddings.jsonl", "bge-m3", "legacy")

    def test_embedding_cache_namespace_includes_base_url_and_digest(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "embeddings.jsonl"
            first = EmbeddingCache(
                path,
                "bge-m3",
                "legacy",
                base_url="http://ollama-a.test/",
                model_digest="digest-a",
            )
            first.put(first.key_for("text"), [1.0, 0.0])
            first.flush()

            different_server = EmbeddingCache(
                path,
                "bge-m3",
                "legacy",
                base_url="http://ollama-b.test",
                model_digest="digest-a",
            )
            self.assertIsNone(different_server.get(different_server.key_for("text")))
            different_model = EmbeddingCache(
                path,
                "bge-m3",
                "legacy",
                base_url="http://ollama-a.test",
                model_digest="digest-b",
            )
            self.assertIsNone(different_model.get(different_model.key_for("text")))

    def test_run_baseline_rejects_embedding_dimension_mismatch(self):
        vectors = {"first document": [1.0, 0.0], "query": [1.0, 0.0, 0.0]}
        transport = FakeTransport(vectors)
        corpus = [{"chunkId": "chunk-1", "content": "first document"}]
        queries = [{"queryId": "query-1", "query": "query", "relevantChunkIds": ["chunk-1"]}]

        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            corpus_path = root / "corpus.jsonl"
            queries_path = root / "queries.jsonl"
            self.write_jsonl(corpus_path, corpus)
            self.write_jsonl(queries_path, queries)

            with self.assertRaisesRegex(BaselineFormatError, "dimension"):
                run_baseline(
                    corpus_path,
                    queries_path,
                    root / "output",
                    endpoint_mode="legacy",
                    transport=transport,
                )

    @staticmethod
    def write_jsonl(path, records):
        path.write_text(
            "".join(json.dumps(record, ensure_ascii=False) + "\n" for record in records),
            encoding="utf-8",
        )

    @staticmethod
    def read_jsonl(path):
        return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


if __name__ == "__main__":
    unittest.main()
