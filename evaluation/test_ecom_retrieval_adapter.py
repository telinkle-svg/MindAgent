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

    @staticmethod
    def read_json_records(path: Path):
        return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


if __name__ == "__main__":
    unittest.main()
