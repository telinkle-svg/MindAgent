# MindAgent 评测集

本目录保存可版本化的 Agent 评测数据，与生产配置、Maven 测试资源、运行上传目录和 `docs/` 隔离。当前版本为 `0.1.0`，样本均为项目内编写的合成数据，不包含真实用户对话、数据库主键、模型密钥或运行结果。

## 目录

```text
evaluation/
├── score_agent_evaluation.py
├── test_score_agent_evaluation.py
├── ecom_retrieval_adapter.py
├── test_ecom_retrieval_adapter.py
├── run_ecom_vector_baseline.py
├── test_ecom_vector_baseline.py
├── public/
│   └── ecom-retrieval/
│       ├── README.md
│       └── manifest.json
├── rag/
│   ├── README.md
│   ├── corpus.jsonl
│   └── queries.jsonl
└── tool-selection/
    ├── README.md
    └── cases.jsonl
```

`rag/corpus.jsonl` 和 `rag/queries.jsonl` 使用稳定的逻辑 ID；评测执行时再把逻辑文档导入临时知识库，不把 PostgreSQL UUID 写入数据集。`tool-selection/cases.jsonl` 使用模型可见的工具函数名，例如 `databaseQuery`，而不是 Agent 工具注册对象的内部名称 `dataBaseTool`。

## 版本和治理

- 每行是一个独立 JSON 对象，文件编码为 UTF-8，禁止在 JSONL 中加入注释行或尾随逗号。
- 修改样本时保留稳定 ID；删除或重命名样本需要在对应 README 记录变更原因。
- 评测结果、提示词全文、Authorization Header、API Key、数据库密码和真实业务数据不得提交到本目录。
- 该目录只定义输入数据和评分契约；真实模型调用、向量库写入和结果导出由后续评测运行器负责。

## 离线评分器

`score_agent_evaluation.py` 只读取 gold JSONL 和运行器生成的脱敏 JSONL，不启动模型或写入数据库。运行结果文件应放在仓库外的临时目录或 CI artifact 中，不要提交到 `evaluation/`。

RAG 结果每行格式为：

```json
{"queryId":"rag-q-001","returnedChunkIds":["rag-doc-001-ch-001"]}
```

工具选择结果每行格式为：

```json
{"caseId":"tool-direct-001","actualCalls":[],"outcome":"final_answer"}
```

示例：

```powershell
python evaluation/score_agent_evaluation.py --mode rag `
  --gold evaluation/rag/queries.jsonl --results C:\temp\rag-results.jsonl --pretty
python evaluation/score_agent_evaluation.py --mode tool-selection `
  --gold evaluation/tool-selection/cases.jsonl --results C:\temp\tool-results.jsonl --pretty
```

## 公共 RAG 基线

`public/ecom-retrieval/` 记录公开的 `mteb/EcomRetrieval` 数据集来源和运行契约。原始数据及结果保存在仓库外，由 `ecom_retrieval_adapter.py` 转换为与本评分器兼容的 gold/corpus JSONL；该目录不提交下载缓存。具体的快速（100 查询）和完整（1,000 查询）命令、revision 固定方式及候选集限制说明见其 [README](public/ecom-retrieval/README.md)。

完整 1,000 查询 profile 仅作为需要更高统计覆盖时的可选参考，不是当前项目的必跑回归门禁；默认门禁使用下方固定采样 profile。

当前工程回归默认使用固定的 100 条查询和 5,000 条语料哈希采样，并强制保留这些查询的正例文档：

```powershell
python evaluation/ecom_retrieval_adapter.py `
  --output C:\temp\mindagent-ecom-retrieval\sample-5000 `
  --query-limit 100 `
  --corpus-sample-size 5000 `
  --corpus-sample-seed mindagent-ecom-v1 `
  --revision 1855a4f1bee3a64e11e439f15f129b4cb30cdb9d
```

`run_ecom_vector_baseline.py` 默认调用项目当前使用的 Ollama `/api/embeddings`、保留原始向量并按 L2 距离排序；结果和 embedding 缓存必须放在仓库外。`--metric cosine` 和 `--endpoint-mode embed` 仅用于同一采样集上的对照实验，不得与生产基线混报。

缺少结果的 gold 用例会计为未命中/不正确，并在 `missingCaseIds` 中列出；格式错误或重复 ID 会以非零退出码终止。评分器不会把未执行用例误报为通过。

工具评分逐用例会输出 `toolNameExact`、`orderExact`、`argumentsValid`、`outcomeValid` 和 `caseCorrect`；汇总则输出对应的准确率。`allowAdditionalCalls=true` 时，额外调用不会破坏工具名和顺序判定，但 gold 调用仍须按声明顺序匹配并满足参数断言。

## 当前边界

本提交建立第一版金标准和结构校验基础，不把固定样本的结果误报为线上指标。RAG 评测后续按 `Hit@K`、MRR 统计，工具选择后续按工具集合、顺序和参数断言统计。
