import json
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

from ecom_retrieval_adapter import (
    DatasetFormatError,
    load_huggingface_records,
    prepare_from_records,
    read_records,
    select_corpus_sample_records,
    select_corpus_records,
)


class EcomRetrievalAdapterTest(unittest.TestCase):

    def test_quick_profile_selects_stable_queries_and_maps_relevant_chunks(self):
        corpus = [
            {"id": "c-3", "text": "third product"},
            {"id": "c-1", "text": "first product"},
            {"id": "c-2", "text": "second product"},
        ]
        queries = [
            {"_id": "q-2", "text": "second"},
            {"_id": "q-1", "text": "first"},
        ]
        qrels = [
            {"query-id": "q-2", "corpus-id": "c-2", "score": 1},
            {"query-id": "q-1", "corpus-id": "c-1", "score": 1},
        ]

        with tempfile.TemporaryDirectory() as temp:
            manifest = prepare_from_records(
                corpus,
                queries,
                qrels,
                Path(temp),
                query_limit=1,
                k=10,
            )

            self.assertEqual(manifest["profile"], "quick")
            self.assertEqual(manifest["queryCount"], 1)
            self.assertEqual(manifest["corpusCount"], 3)
            gold = self.read_json_records(Path(temp) / "queries.jsonl")
            self.assertEqual(gold[0]["queryId"], "ecom-q-1")
            self.assertEqual(gold[0]["relevantChunkIds"], ["ecom-c-1"])
            self.assertEqual(gold[0]["k"], 10)

    def test_full_profile_keeps_all_queries_and_positive_corpus_records(self):
        corpus = [{"id": f"c-{index}", "text": f"product {index}"} for index in range(4)]
        queries = [{"id": f"q-{index}", "text": f"query {index}"} for index in range(3)]
        qrels = [
            {"query-id": "q-0", "corpus-id": "c-0", "score": 1},
            {"query-id": "q-1", "corpus-id": "c-1", "score": 1},
            {"query-id": "q-2", "corpus-id": "c-2", "score": 1},
        ]

        with tempfile.TemporaryDirectory() as temp:
            manifest = prepare_from_records(corpus, queries, qrels, Path(temp), query_limit=None, k=5)

            self.assertEqual(manifest["profile"], "full")
            self.assertEqual(manifest["queryCount"], 3)
            self.assertEqual(len(self.read_json_records(Path(temp) / "queries.jsonl")), 3)

    def test_corpus_limit_always_keeps_selected_positive_documents(self):
        corpus = [{"id": f"c-{index}", "text": f"product {index}"} for index in range(5)]

        selected = select_corpus_records(corpus, {"c-4"}, limit=2)

        self.assertEqual([record["chunkId"] for record in selected], ["ecom-c-0", "ecom-c-1", "ecom-c-4"])

    def test_corpus_sample_is_input_order_independent_and_retains_positive_documents(self):
        corpus = [{"id": f"c-{index}", "text": f"product {index}"} for index in range(10)]
        initial = select_corpus_sample_records(corpus, set(), sample_size=2, seed="test-seed")
        initial_ids = {record["sourceCorpusId"] for record in initial}
        positive_id = next(
            record["id"]
            for record in corpus
            if record["id"] not in initial_ids
        )

        selected = select_corpus_sample_records(
            list(reversed(corpus)),
            {positive_id},
            sample_size=2,
            seed="test-seed",
        )
        repeated = select_corpus_sample_records(
            corpus,
            {positive_id},
            sample_size=2,
            seed="test-seed",
        )

        self.assertEqual(
            [record["sourceCorpusId"] for record in selected],
            [record["sourceCorpusId"] for record in repeated],
        )
        self.assertIn(positive_id, {record["sourceCorpusId"] for record in selected})
        self.assertEqual(len(selected), 3)

    def test_corpus_sample_rejects_invalid_size_and_seed(self):
        corpus = [{"id": "c-1", "text": "product"}]

        with self.assertRaisesRegex(DatasetFormatError, "sample size must be positive"):
            select_corpus_sample_records(corpus, set(), sample_size=0)
        with self.assertRaisesRegex(DatasetFormatError, "sample seed must be a non-blank"):
            select_corpus_sample_records(corpus, set(), sample_size=1, seed=" ")

    def test_prepare_rejects_prefix_limit_and_sample_size_together(self):
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(DatasetFormatError, "mutually exclusive"):
                prepare_from_records(
                    [{"id": "c-1", "text": "product"}],
                    [{"id": "q-1", "text": "query"}],
                    [{"query-id": "q-1", "corpus-id": "c-1", "score": 1}],
                    Path(temp),
                    corpus_limit=1,
                    corpus_sample_size=1,
                )

    def test_sample_manifest_records_strategy_seed_and_actual_count(self):
        corpus = [{"id": f"c-{index}", "text": f"product {index}"} for index in range(4)]
        queries = [{"id": "q-1", "text": "query"}]
        qrels = [{"query-id": "q-1", "corpus-id": "c-3", "score": 1}]

        with tempfile.TemporaryDirectory() as temp:
            manifest = prepare_from_records(
                corpus,
                queries,
                qrels,
                Path(temp),
                corpus_sample_size=2,
                corpus_sample_seed="release-1",
            )

            self.assertEqual(manifest["corpusSamplingStrategy"], "sha256")
            self.assertEqual(manifest["corpusSampleSize"], 2)
            self.assertEqual(manifest["corpusSampleSeed"], "release-1")
            self.assertEqual(manifest["corpusCount"], 2)
            self.assertEqual(manifest["selectedPositiveCount"], 1)
            selected = self.read_json_records(Path(temp) / "corpus.jsonl")
            self.assertIn("ecom-c-3", [record["chunkId"] for record in selected])

    def test_rejects_duplicate_query_ids(self):
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(DatasetFormatError, "duplicate query ID"):
                prepare_from_records(
                    [{"id": "c-1", "text": "product"}],
                    [{"id": "q-1", "text": "same"}, {"id": "q-1", "text": "duplicate"}],
                    [{"query-id": "q-1", "corpus-id": "c-1", "score": 1}],
                    Path(temp),
                    query_limit=1,
                    k=5,
                )

    def test_rejects_selected_query_without_positive_qrel(self):
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(DatasetFormatError, "positive qrel"):
                prepare_from_records(
                    [{"id": "c-1", "text": "product"}],
                    [{"id": "q-1", "text": "query"}],
                    [{"query-id": "q-1", "corpus-id": "c-1", "score": 0}],
                    Path(temp),
                    query_limit=1,
                    k=5,
                )

    def test_rejects_non_finite_qrel_score(self):
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(DatasetFormatError, "finite"):
                prepare_from_records(
                    [{"id": "c-1", "text": "product"}],
                    [{"id": "q-1", "text": "query"}],
                    [{"query-id": "q-1", "corpus-id": "c-1", "score": "NaN"}],
                    Path(temp),
                    query_limit=1,
                    k=5,
                )

    def test_reads_tsv_corpus_queries_and_qrels(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            corpus_path = root / "corpus.tsv"
            queries_path = root / "queries.tsv"
            qrels_path = root / "qrels.tsv"
            corpus_path.write_text("c-1\tfirst product\nc-2\tsecond product\n", encoding="utf-8")
            queries_path.write_text("q-1\tfirst\n", encoding="utf-8")
            qrels_path.write_text("q-1\tQ0\tc-1\t1\n", encoding="utf-8")

            self.assertEqual(read_records(corpus_path, "corpus")[0]["text"], "first product")
            self.assertEqual(read_records(queries_path, "queries")[0]["text"], "first")
            self.assertEqual(read_records(qrels_path, "qrels")[0]["score"], 1.0)

    def test_huggingface_loader_uses_dev_split_for_each_config(self):
        calls = []

        def fake_load_dataset(dataset, config, split, revision):
            calls.append((dataset, config, split, revision))
            return [{"id": config, "text": split}]

        fake_datasets = types.SimpleNamespace(load_dataset=fake_load_dataset)
        with patch.dict(sys.modules, {"datasets": fake_datasets}):
            records = load_huggingface_records("mteb/EcomRetrieval", "1855a4f")

        self.assertEqual(
            calls,
            [
                ("mteb/EcomRetrieval", "corpus", "dev", "1855a4f"),
                ("mteb/EcomRetrieval", "queries", "dev", "1855a4f"),
                ("mteb/EcomRetrieval", "default", "dev", "1855a4f"),
            ],
        )
        self.assertEqual([record["id"] for record in records[0]], ["corpus"])

    def test_huggingface_loader_falls_back_to_parquet_layout(self):
        calls = []

        def fake_load_dataset(dataset, *args, **kwargs):
            calls.append((dataset, args, kwargs))
            if dataset != "parquet":
                raise ValueError("BuilderConfig 'corpus' not found")
            url = kwargs["data_files"]["dev"]
            config = url.split("/")[-3]
            return [{"id": config, "text": "dev"}]

        fake_datasets = types.SimpleNamespace(load_dataset=fake_load_dataset)
        with patch.dict(sys.modules, {"datasets": fake_datasets}):
            records = load_huggingface_records("mteb/EcomRetrieval", "refs/convert/parquet")

        self.assertEqual(len(calls), 4)
        self.assertEqual([call[0] for call in calls], ["mteb/EcomRetrieval", "parquet", "parquet", "parquet"])
        parquet_calls = calls[1:]
        for call in parquet_calls:
            url = call[2]["data_files"]["dev"]
            self.assertIn("refs%2Fconvert%2Fparquet", url)
            self.assertTrue(url.endswith("/dev/0000.parquet"))
        self.assertEqual(
            [record_group[0]["id"] for record_group in records],
            ["corpus", "queries", "default"],
        )

    @staticmethod
    def read_json_records(path: Path):
        return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


if __name__ == "__main__":
    unittest.main()
