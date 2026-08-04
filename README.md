# rag-api — 后端 API（Spring Boot）

基于 RAG 与知识图谱的南宁住房公积金政策智能问答系统。

## 技术栈

Spring Boot 3.3 + Java 21 + PostgreSQL + Milvus 2.4 + MinIO + Neo4j 5 + BGE-M3 + OkHttp

## 快速开始

```bash
# 1. 启动基础设施
cd rag-api && docker compose up -d

# 2. 构建
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
mvn package -DskipTests

# 3. 启动
set -o allexport && source .env
java -jar target/rag-api-0.0.1-SNAPSHOT.jar
```

## 模块

| 层 | 说明 |
|------|------|
| Controller | 5 个（Document/Rag/Graph/Chunk/Health） |
| Service | 12 个（RAG、KG、Feedback、嵌入、切块、同步等） |
| Entity | Document + Chunk + Feedback |
| Model | 11 个 DTO（QueryRequest/QueryResponse/IngestRequest...） |

## API 速览

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/documents/ingest` | 文本入库（Pipeline 调用） |
| POST | `/api/documents/sync` | 触发同步 |
| POST | `/api/rag/query` | RAG 问答 |
| POST | `/api/rag/query/stream` | SSE 流式问答 |
| POST | `/api/rag/feedback` | 用户反馈 |
| GET | `/api/graph` | 知识图谱数据 |
| DELETE | `/api/documents/batch` | 批量删除 |

## Docker

| 容器 | 端口 | 用途 |
|------|------|------|
| gjj-postgres | 5433 | 元数据 + 反馈 |
| gjj-neo4j | 7474/7687 | 知识图谱 |
| milvus-standalone | 19530 | 向量 |
| minio | 9000 | 文件 |

## 许可证

Copyright (C) 2026 Tang Longhao — GNU AGPL v3. [LICENSE](LICENSE)
