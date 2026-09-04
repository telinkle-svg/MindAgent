# RAG 评测集

## 文件

- `corpus.jsonl`：待导入临时知识库的逻辑 chunk。每行字段：
  - `chunkId`：全局唯一的稳定 chunk ID；
  - `documentId`：逻辑文档 ID；
  - `title`：章节标题；
  - `content`：用于 embedding 和检索的正文；
  - `keywords`：便于人工审查的主题词列表；
  - `sourceRef`：对应的代码或配置契约，仅用于追溯。
- `queries.jsonl`：带 gold 标注的查询。每行字段：
  - `queryId`：全局唯一查询 ID；
  - `query`：发送给 RAG 检索的中文查询；
  - `relevantChunkIds`：相关 chunk 的稳定 ID 列表；
  - `k`：本条查询的 Top-K，当前统一为 3；
  - `category`、`difficulty`、`tags`：切片统计标签；
  - `expectedKeywords`：人工检查答案相关性的关键词，不替代 chunk gold。

## 评分口径

给定查询返回的有序 chunk ID 列表 `R` 和 gold 集合 `G`：

```text
Hit@K = 1                         if R[0:K] 与 G 有交集，否则 0
MRR    = 1 / rank(first gold)     若前 K 命中，否则 0
```

数据集级别的 Hit@K 是各查询 Hit@K 的平均值。若一条查询有多个相关 chunk，只要任一相关 chunk 出现在 Top-K 即命中；MRR 使用最靠前的相关 chunk。评分器必须保留查询顺序和 `queryId`，不能只输出一个汇总数字。

## 执行约束

1. 先将 `corpus.jsonl` 导入临时知识库，导入记录使用运行 ID，测试结束后只删除本轮创建的记录。
2. 使用与生产一致的 embedding 模型和检索 SQL；不得用 `expectedKeywords` 反向改变检索结果。
3. 记录 `k`、模型名、embedding 版本、检索耗时和返回 chunk ID；不得保存密钥或完整请求头。
4. 语料内容是当前 Agent 循环、上下文、SQL/RAG、SSE 契约的合成摘要，不代表线上业务知识。
