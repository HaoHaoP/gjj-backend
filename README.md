# rag-api — 后端 API（Spring Boot）

基于 RAG 与知识图谱的南宁住房公积金政策智能问答系统。

## 技术栈

Spring Boot 3.3 + Java 21 + PostgreSQL + Milvus 2.4 + MinIO + Neo4j 5 + BGE-M3

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
| Controller | 6 个 REST 控制器 |
| Service | 11 个服务（RAG、KG、嵌入、切块、同步等） |
| Entity | Document + Chunk |
| Model | IngestRequest, QueryRequest, QueryResponse 等 |

## API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/documents/ingest` | 文本入库（Pipeline 直接调用） |
| POST | `/api/documents/sync` | 触发同步 |
| GET | `/api/documents/sync/{tid}` | 查询进度 |
| POST | `/api/rag/query` | RAG 问答 |
| GET | `/api/graph/data` | 知识图谱数据 |
| GET | `/api/experiments/results` | 实验数据 |

## Docker

| 容器 | 端口 | 用途 |
|------|------|------|
| gjj-postgres | 5433 | 元数据 |
| gjj-neo4j | 7474/7687 | 知识图谱 |
| milvus-standalone | 19530 | 向量 |
| minio | 9000 | 文件 |

## 许可证

Copyright (C) 2026 Tang Longhao — GNU AGPL v3. [LICENSE](LICENSE)
