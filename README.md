# rag-api — 南宁公积金政策智能问答后端

Spring Boot 3.3.x RAG 服务，提供文档入库、向量检索、DeepSeek 生成回答、文件上传、同步管理等完整 API。

## 前置依赖

| 依赖 | 说明 | 启动方式 |
|------|------|----------|
| PostgreSQL 16 | 文档元数据存储 | docker compose up -d postgres |
| Milvus v3.0-beta | 向量存储与检索 | docker compose up -d milvus |
| BGE-M3 Embedding | 文本向量化 (1024维) | docker compose up -d embedding |
| Neo4j 5 | 知识图谱（可选） | docker compose up -d neo4j |
| etcd + MinIO | Milvus 依赖 | docker compose 随 milvus 自动启动 |
| DeepSeek API key | LLM 生成回答 | 配置在 `.env` 中 |

> 以上所有 Docker 服务均通过项目内的 `docker-compose.yml` 统一编排，一条命令即可启动全部基础设施。

## 快速启动（推荐）

```bash
# 1. 启动所有基础设施（PostgreSQL + Milvus + BGE-M3 + Neo4j + etcd + MinIO）
docker compose up -d

# 2. 等待服务就绪后启动 API
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
set -o allexport && source .env && set +o allexport
./mvnw spring-boot:run
```

停止基础设施：

```bash
docker compose down
```

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

## 统一响应格式

所有 API 返回统一的 JSON 结构：

```json
{
  "code": 200,
  "msg": "success",
  "data": { ... }
}
```

### 错误码定义

| HTTP Status | code | msg | 说明 |
|---|---|---|---|
| 200 | 200 | success | 请求成功 |
| 201 | 200 | 文档入库成功 | 资源创建成功 |
| 202 | 200 | 同步任务已创建 | 异步任务已提交 |
| 400 | 400 | 参数校验失败 / 具体错误描述 | 客户端请求错误 |
| 500 | 500 | 具体错误描述 / 服务器内部错误 | 服务端异常 |

### 示例

**成功响应（带数据）**

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "items": [...],
    "total": 767,
    "page": 1,
    "size": 20
  }
}
```

**成功响应（无数据，如删除）**

```json
{
  "code": 200,
  "msg": "文档已删除",
  "data": null
}
```

**错误响应**

```json
{
  "code": 400,
  "msg": "参数校验失败",
  "data": null
}
```

## 构建与运行

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export DEEPSEEK_API_KEY=your_key_here

# 构建
./mvnw clean package -DskipTests -DfinalName=rag-api

# 运行
java -jar target/rag-api.jar

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

返回格式：`{"code":200, "msg":"success", "data":{"items":[...], "total":767, "page":1, "size":20}}`

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
文档管理: PostgreSQL 存储元数据 + Milvus query/delete + 内存分页 + Tika 文件解析
同步: ProcessBuilder 调用 Python 爬虫脚本
知识图谱: Neo4j 存储政策引用关系
```

## Docker 服务说明

| 服务 | 镜像 | 端口 | 说明 |
|------|------|------|------|
| postgres | postgres:16-alpine | 5432 | 文档元数据存储 |
| minio | minio/minio:RELEASE.2024-05-28T17-19-04Z | 9000/9001 | 对象存储 + 控制台 |
| etcd | quay.io/coreos/etcd:v3.0.25 | 2379 | Milvus 元数据协调 |
| milvus | milvusdb/milvus:v3.0-beta | 19530/9091 | 向量数据库 + 健康检查 |
| neo4j | neo4j:5-community | 7474/7687 | 知识图谱 |
| embedding | 本地构建 (Dockerfile.embedding) | 8002 | BGE-M3 文本向量化 |
