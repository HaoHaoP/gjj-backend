# rag-api — 后端 API（Spring Boot）

基于 RAG 与知识图谱的南宁住房公积金政策智能问答系统。

## 技术栈

Spring Boot 3.3 + Java 21 + PostgreSQL + Milvus 2.4 + MinIO + Neo4j 5 + BGE-M3

## 快速开始

### 1. 启动基础设施

```bash
cd rag-api
docker compose up -d  # gjj-postgres, gjj-neo4j, milvus-standalone, minio
```

### 2. 构建

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
mvn package -DskipTests
```

### 3. 启动

```bash
set -o allexport && source .env && set +o allexport
java -jar target/rag-api-0.0.1-SNAPSHOT.jar
```

### 4. 启动 Pipeline 服务

```bash
cd ../rag-pipeline
python -m gjj_pipeline.main
```

## 模块

| 模块 | 路径 | 职责 |
|------|------|------|
| Controller | `controller/` | REST API (5 个控制器) |
| Service | `service/` | 业务逻辑 (10 个服务) |
| Entity | `entity/` | JPA 实体 |
| Config | `config/` | 应用配置 |

## API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/health` | 健康检查 |
| GET | `/api/documents?page=&size=` | 文档列表 |
| POST | `/api/documents/upload` | 上传文档 |
| DELETE | `/api/documents/{id}` | 删除文档 |
| POST | `/api/documents/sync` | 触发同步 |
| GET | `/api/documents/sync/{tid}` | 查询进度 |
| POST | `/api/rag/query` | RAG 问答 |
| GET | `/api/experiments/results` | 实验数据 |

## 数据模型

- **PostgreSQL**: `documents` 表 + `chunks` 表
- **Milvus**: `rag_knowledge` 集合（1024 维向量）
- **Neo4j**: `Policy` 节点 + `REFERENCES/REVISES/ABOLISHES` 关系
- **MinIO**: `gjj-documents` bucket，存储结构化 Markdown

## Docker 容器

| 容器 | 端口 | 用途 |
|------|------|------|
| gjj-postgres | 5433 | 元数据存储 |
| gjj-neo4j | 7474/7687 | 知识图谱 |
| milvus-standalone | 19530 | 向量存储 |
| minio | 9000 | 文件存储 |

## 许可证

Copyright (C) 2026 Tang Longhao

GNU Affero General Public License v3. 详见 [LICENSE](LICENSE)。
