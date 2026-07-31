-- 文档元数据表
CREATE TABLE IF NOT EXISTS documents (
    id                BIGSERIAL PRIMARY KEY,
    document_id       VARCHAR(36)  NOT NULL UNIQUE,
    title             VARCHAR(500) NOT NULL,
    source            VARCHAR(20)  NOT NULL DEFAULT 'MANUAL',
    chunk_count       INT          NOT NULL DEFAULT 0,
    chunk_size        INT          NOT NULL DEFAULT 500,
    overlap_size      INT          NOT NULL DEFAULT 0,
    chunk_mode        VARCHAR(20)  NOT NULL DEFAULT 'SENTENCE',
    minio_path        VARCHAR(500),
    original_filename VARCHAR(255),
    file_size         BIGINT       DEFAULT 0,
    status            VARCHAR(20)  DEFAULT 'active',
    sync_batch_id     VARCHAR(36),
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_documents_source ON documents(source);
CREATE INDEX IF NOT EXISTS idx_documents_sync_batch ON documents(sync_batch_id);

-- 切块表
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

-- 用户反馈表
CREATE TABLE IF NOT EXISTS feedback (
    id          BIGSERIAL    PRIMARY KEY,
    question    TEXT         NOT NULL,
    answer      TEXT         NOT NULL,
    rating      VARCHAR(10)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);
