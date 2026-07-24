# gjj-backend — 南宁公积金政策智能问答后端

Spring Boot 3.3.x RAG 服务（仓库名 `gjj-backend`），提供文档入库、向量检索、DeepSeek 生成回答、文件上传、同步管理等完整 API。

## 前置依赖

- Java 17+
- Maven 3.8+
- [Milvus](https://milvus.io/) v2.4+（`localhost:19530`）
- BGE-M3 Embedding 服务（`http://localhost:8002`，`POST /encode`）
- DeepSeek API key
- [Neo4j](https://neo4j.com/)（可选，知识图谱）

## 环境变量

| 变量 | 必须 | 默认值 | 说明 |
|------|------|--------|------|
| `DEEPSEEK_API_KEY` | 是 | — | DeepSeek API 密钥 |

## 配置文件

复制模板文件并填写实际值：

```bash
cp src/main/resources/application-example.yml src/main/resources/application.yml
```

`application.yml` 已加入 `.gitignore`，不会提交到仓库。

关键配置项：

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `server.port` | `8080` | API 端口 |
| `milvus.host` | `localhost` | Milvus 地址 |
| `milvus.port` | `19530` | Milvus 端口 |
| `embedding.url` | `http://localhost:8002` | BGE-M3 服务地址 |
| `neo4j.uri` | `bolt://localhost:7687` | Neo4j 连接 |
| `neo4j.password` | — | Neo4j 密码 |
| `rag.similarity-threshold` | `0.5` | 相似度过滤阈值 |
| `spring.servlet.multipart.max-file-size` | `50MB` | 上传文件大小上限 |

## 构建与运行

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export DEEPSEEK_API_KEY=your_key_here

# 构建
./mvnw clean package -DskipTests -DfinalName=gjj-backend

# 运行
java -jar target/gjj-backend.jar

# 或直接用 Maven
./mvnw spring-boot:run
```

## API 端点

### 文档管理

**Ingest（文本入库）**

```bash
curl -X POST http://localhost:8080/api/documents/ingest \
  -H "Content-Type: application/json" \
  -d '{"title":"文档标题","content":"文档正文..."}'
```

**分页列表**

```bash
# 第 1 页，每页 20 条
curl "http://localhost:8080/api/documents?page=1&size=20"

# 关键字搜索
curl "http://localhost:8080/api/documents?page=1&size=20&keyword=公积金"
```

返回格式：`{"items":[...], "total":767, "page":1, "size":20}`

**按 ID 查询**

```bash
curl http://localhost:8080/api/documents/1
```

**单条删除**

```bash
curl -X DELETE http://localhost:8080/api/documents/1
```

**批量删除**

```bash
curl -X DELETE http://localhost:8080/api/documents/batch \
  -H "Content-Type: application/json" \
  -d '{"ids":[1,2,3]}'
```

**文件上传**

```bash
curl -X POST http://localhost:8080/api/documents/upload \
  -F "file=@policy.pdf" \
  -F "title=南宁住房公积金管理办法"
```

支持格式：`.txt`、`.html`、`.docx`、`.doc`、`.pdf`（通过 Apache Tika 解析）

**同步更新**

```bash
# 触发同步（异步执行爬虫 + 提取脚本）
curl -X POST http://localhost:8080/api/documents/sync
# → {"taskId":"...","status":"running"}

# 查询同步状态
curl http://localhost:8080/api/documents/sync/{taskId}
# → {"status":"done"}
```

### 智能问答

```bash
curl -X POST http://localhost:8080/api/rag/query \
  -H "Content-Type: application/json" \
  -d '{"question":"商转公贷款需要什么条件？"}'
```

### 回答反馈

```bash
curl -X POST http://localhost:8080/api/rag/feedback \
  -H "Content-Type: application/json" \
  -d '{"question":"...","answer":"...","rating":"up","timestamp":1700000000000}'
```

## Swagger UI

`http://localhost:8080/swagger-ui/index.html`

## 架构

```
文档入库: 文本 → 分块(ChunkingService) → 编码(EmbeddingService/BGE-M3) → 存入 Milvus
问答检索: 问题 → 编码 → Milvus 余弦相似度检索(Top 5) → DeepSeek 生成回答
文档管理: Milvus query/delete + 内存分页 + Tika 文件解析
同步: ProcessBuilder 调用 Python 爬虫脚本
知识图谱: Neo4j 存储政策引用关系
```
