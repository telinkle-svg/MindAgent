# MindAgent

MindAgent 是一个面向知识库与工具调用的可扩展智能体工作台，实现了从模型调用、Agent 循环、工具调度到知识库检索的完整链路。

## 功能概览

- Agent 循环：按照规划、思考、执行和完成的状态推进任务，并限制最大执行步数。
- 工具调用：通过工具注册表管理工具，支持直接响应、任务结束、文件操作、邮件发送和只读 SQL 查询。
- SSE 事件流：向前端推送 Agent 状态、模型消息、工具结果、完成和错误事件。
- RAG 检索：解析 Markdown 文档，调用 Embedding 服务生成向量，并使用 PostgreSQL/pgvector 进行相似度检索。
- 知识库管理：提供知识库、文档和对话会话的基础管理接口。
- 前端交互：提供 Agent 配置、对话、知识库和文档管理界面。

## 技术栈

### 后端

- Java 17
- Spring Boot 3.5.8
- Spring AI 1.1.0
- MyBatis、PostgreSQL、pgvector
- JSqlParser、Flexmark
- Maven Wrapper

### 前端

- React 19
- TypeScript 5.9
- Vite、Ant Design、Tailwind CSS
- npm

### 外部服务

- DeepSeek 或智谱 AI：对话模型
- Ollama + `bge-m3`：RAG Embedding 模型
- PostgreSQL：业务数据和向量数据存储
- SMTP 服务：邮件工具使用，可选

## 项目结构

```text
MindAgent-main/
├── mind-agent/                       # Spring Boot 后端
│   ├── src/main/java/com/kama/mindagent/
│   │   ├── agent/                    # Agent 运行时和工具
│   │   ├── controller/               # HTTP / SSE 接口
│   │   ├── service/                  # 业务服务和 RAG 服务
│   │   ├── mapper/                   # MyBatis Mapper
│   │   └── model/                    # DTO、Entity、Request、Response
│   ├── src/main/resources/
│   │   ├── application.yaml          # 默认配置
│   │   └── application.example.yaml  # 配置参考
│   ├── src/test/                     # 单元测试和集成测试
│   └── pom.xml
├── ui/                               # React 前端
│   ├── src/
│   ├── package.json
│   └── vite.config.ts
├── README.md
└── .gitignore
```

## 运行前准备

请先准备以下环境：

1. JDK 17 或更高版本。
2. Node.js 20+ 和 npm。
3. 一个启用 `vector` 扩展的 PostgreSQL 数据库。
4. 至少一种对话模型的 API Key。当前配置支持 DeepSeek 和智谱 AI。
5. 如果需要使用 RAG，安装 Ollama 并准备 `bge-m3` 模型。

当前仓库不包含数据库初始化脚本。首次部署前，需要在 PostgreSQL 中准备项目所需的业务表、向量字段和 `vector` 扩展。默认数据库连接地址为：

```text
jdbc:postgresql://localhost:5432/jchatmind
```

## 配置

后端通过环境变量读取敏感配置。注意请勿将真实密码、API Key 或邮箱授权码写入 Git 仓库。

| 环境变量 | 作用 |
| --- | --- |
| `MINDAGENT_DB_URL` | 业务数据库 JDBC 地址 |
| `MINDAGENT_DB_USERNAME` | 业务数据库用户名 |
| `MINDAGENT_DB_PASSWORD` | 业务数据库密码 |
| `MINDAGENT_DEEPSEEK_API_KEY` | DeepSeek API Key |
| `MINDAGENT_ZHIPUAI_API_KEY` | 智谱 AI API Key |
| `MINDAGENT_DOCUMENT_STORAGE_PATH` | 文档文件存储目录 |
| `MINDAGENT_MAIL_USERNAME` | SMTP 用户名，可选 |
| `MINDAGENT_MAIL_PASSWORD` | SMTP 密码或授权码，可选 |
| `MINDAGENT_TOOL_SQL_DATASOURCE_URL` | 只读 SQL 工具的数据源地址，可选 |
| `MINDAGENT_TOOL_SQL_DATASOURCE_USERNAME` | 只读 SQL 工具用户名，可选 |
| `MINDAGENT_TOOL_SQL_DATASOURCE_PASSWORD` | 只读 SQL 工具密码，可选 |

只读 SQL 数据源只有在 URL、用户名和密码同时配置时才会启用。当前实现会设置只读连接、3 秒查询超时和最多 100 行结果限制；生产环境仍建议使用数据库层面的只读用户和权限隔离。

RAG 的 Embedding 服务当前固定访问 `http://localhost:11434`，使用 Ollama 的 `bge-m3` 模型。准备方式示例：

```powershell
ollama serve
ollama pull bge-m3
```

## 本地启动

### 启动后端

在项目根目录执行：

```powershell
cd mind-agent
./mvnw.cmd spring-boot:run
```

后端默认监听 `http://localhost:8080`。

Windows PowerShell 也可以使用：

```powershell
cd mind-agent
.\mvnw.cmd spring-boot:run
```

### 启动前端

打开新的终端窗口：

```powershell
cd ui
npm install
npm run dev
```

前端默认地址为 `http://localhost:5173`。当前前端 API 地址写在 `ui/src/api/http.ts`，默认指向 `http://localhost:8080/api`。如果后端部署到其他主机或端口，需要同步调整该地址，或在网关层配置转发。

## 构建和测试

后端测试：

```powershell
cd mind-agent
.\mvnw.cmd test
```

前端构建：

```powershell
cd ui
npm run build
```

涉及 PostgreSQL、模型服务或 Ollama 的集成测试，需要先准备相应的外部依赖和环境变量。

## 生产构建

构建后端并运行 Jar：

```powershell
cd mind-agent
.\mvnw.cmd clean package -DskipTests
java -jar target/mind-agent-0.0.1-SNAPSHOT.jar
```

构建前端：

```powershell
cd ui
npm run build
```

前端静态文件位于 `ui/dist`，可以交给 Nginx 或其他静态文件服务器托管。部署到非本机环境时，请先修改前端 API 地址，并配置后端 CORS 或反向代理。

## 许可证

项目许可证信息见根目录 `LICENSE` 文件。
