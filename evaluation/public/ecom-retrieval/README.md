# EcomRetrieval 公共 RAG 基线

本目录只保存公开数据集的来源、格式和可重复运行说明，不保存原始语料、下载缓存或模型运行结果。当前选择的是 MTEB 的 `mteb/EcomRetrieval`，它来自 Multi-CPR 的中文电商检索数据，适合验证 MindAgent 当前的向量召回链路。

## 数据集来源

- 数据集主页：[mteb/EcomRetrieval](https://huggingface.co/datasets/mteb/EcomRetrieval)
- 上游项目：[Alibaba-NLP/Multi-CPR](https://github.com/Alibaba-NLP/Multi-CPR)
- 论文：[Multi-CPR: A Multi-domain Benchmark for Chinese Product Retrieval](https://arxiv.org/abs/2203.03367)
- 语言：中文（`cmn`）
- MTEB `dev`：1,000 条查询、100,902 条语料、每条查询一个正例，页面标注总下载量约 8.49 MB
- Hugging Face 配置目录：`corpus/dev`、`queries/dev`、`default/dev`（适配器通过 Parquet 文件加载）

适配器默认固定到已验证的 Parquet 转换 commit `1855a4f1bee3a64e11e439f15f129b4cb30cdb9d`。如果需要更新数据，必须显式传入新的 commit SHA，并保留命令输出的 `manifest.json`；不要用会漂移的 `main` 作为正式基线版本。

## 生成评测输入

适配器位于 `evaluation/ecom_retrieval_adapter.py`，仅依赖 Python 标准库即可处理已经导出的 JSONL/TSV；直接从 Hugging Face 加载时才需要可选的 `datasets` 依赖。输出目录必须位于仓库外，例如 `C:\temp\mindagent-ecom-retrieval`。

快速回归使用稳定排序的前 100 条查询，默认保留完整语料：

```powershell
python evaluation/ecom_retrieval_adapter.py `
  --output C:\temp\mindagent-ecom-retrieval\quick `
  --revision <dataset-commit-sha>
```

完整基线使用全部 1,000 条查询：

```powershell
python evaluation/ecom_retrieval_adapter.py `
  --output C:\temp\mindagent-ecom-retrieval\full `
  --full `
  --revision <dataset-commit-sha>
```

如果运行环境不能安装 `datasets`，可以先把公开数据导出为三份文件，再走离线转换：

```powershell
python evaluation/ecom_retrieval_adapter.py `
  --corpus C:\data\ecom\corpus.jsonl `
  --queries C:\data\ecom\queries.jsonl `
  --qrels C:\data\ecom\qrels.tsv `
  --output C:\temp\mindagent-ecom-retrieval\quick `
  --revision <dataset-commit-sha>
```

`--corpus-limit N` 可生成受限候选集，但适配器会强制保留所选查询的正例文档；使用该选项时必须在结果报告中记录候选集大小，不能与完整语料结果直接混比。

## 与 MindAgent 评分器衔接

转换器生成：

- `corpus.jsonl`：`chunkId`、`documentId`、`content`，可导入临时知识库；
- `queries.jsonl`：与 `evaluation/score_agent_evaluation.py --mode rag` 兼容的 gold，字段包括 `queryId`、`query`、`relevantChunkIds` 和 `k`；
- `manifest.json`：数据集、revision、profile、查询数、语料数和候选限制，作为运行元数据。

Agent 运行器应把每条查询实际返回的有序 chunk ID 脱敏后写到仓库外，例如：

```json
{"queryId":"ecom-q-1","returnedChunkIds":["ecom-c-1"]}
```

然后使用现有离线评分器统计 Hit@1/5/10 和 MRR@10：

```powershell
python evaluation/score_agent_evaluation.py --mode rag `
  --gold C:\temp\mindagent-ecom-retrieval\quick\queries.jsonl `
  --results C:\temp\mindagent-ecom-retrieval\quick-results.jsonl `
  --pretty
```

该数据集每条查询只有一个标注正例，适合做确定性检索回归；它主要衡量召回排序，不覆盖 Markdown 切分、生成式答案忠实度或长期记忆，因此这些能力仍由 `evaluation/rag/` 中的项目内数据补充。

## 数据治理

- 原始下载文件、向量库快照、提示词、API Key、数据库凭据和运行结果禁止提交到仓库。
- 评测集和运行输出与生产配置、Maven 测试资源、上传目录及 `docs/` 隔离。
- 若数据集字段或上游 revision 变化，应先更新 `manifest.json` 和本说明，再重新生成外部结果；不要覆盖历史基线。
