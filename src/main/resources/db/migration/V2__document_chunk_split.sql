-- V2: Document-Chunk separation + MinIO
-- 将原来的 clause-level 模型重构为 document-level + chunk-level

-- 1. 扩展 documents 表
ALTER TABLE documents ADD COLUMN IF NOT EXISTS minio_path VARCHAR(500);
ALTER TABLE documents ADD COLUMN IF NOT EXISTS original_filename VARCHAR(255);
ALTER TABLE documents ADD COLUMN IF NOT EXISTS file_size BIGINT DEFAULT 0;
ALTER TABLE documents ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'active';

-- 2. 新建 chunks 表
CREATE TABLE IF NOT EXISTS chunks (
    id             BIGSERIAL PRIMARY KEY,
    document_id    VARCHAR(36)  NOT NULL REFERENCES documents(document_id) ON DELETE CASCADE,
    chunk_index    INT          NOT NULL,
    text           TEXT         NOT NULL,
    clause_number  VARCHAR(50),
    parent_title   VARCHAR(500),
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_chunks_document_id ON chunks(document_id);
