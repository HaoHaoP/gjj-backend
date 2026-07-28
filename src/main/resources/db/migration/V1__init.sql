-- 字典表：统一管理枚举值
CREATE TABLE IF NOT EXISTS dict_type (
    id          BIGSERIAL PRIMARY KEY,
    dict_code   VARCHAR(50)  NOT NULL UNIQUE,  -- e.g. 'SOURCE', 'CHUNK_MODE'
    dict_name   VARCHAR(100) NOT NULL,
    description VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS dict_item (
    id          BIGSERIAL PRIMARY KEY,
    dict_code   VARCHAR(50)  NOT NULL REFERENCES dict_type(dict_code),
    item_code   VARCHAR(50)  NOT NULL,          -- e.g. 'SYNC', 'UPLOAD', 'MANUAL'
    item_name   VARCHAR(100) NOT NULL,
    sort_order  INT DEFAULT 0,
    UNIQUE (dict_code, item_code)
);

-- 字典初始数据
INSERT INTO dict_type (dict_code, dict_name, description) VALUES
    ('SOURCE', '文档来源', '标识文档的入库来源'),
    ('CHUNK_MODE', '切块模式', '文档切块的策略');

INSERT INTO dict_item (dict_code, item_code, item_name, sort_order) VALUES
    ('SOURCE', 'SYNC',   '同步',   1),
    ('SOURCE', 'UPLOAD', '上传',   2),
    ('SOURCE', 'MANUAL', '手动录入', 3),
    ('CHUNK_MODE', 'SENTENCE', '句子边界对齐', 1),
    ('CHUNK_MODE', 'FIXED',    '固定长度',    2);

-- 文档元数据表
CREATE TABLE IF NOT EXISTS documents (
    id              BIGSERIAL PRIMARY KEY,
    document_id     VARCHAR(36)  NOT NULL UNIQUE,  -- UUID, 对应 Milvus document_id
    title           VARCHAR(500) NOT NULL,
    source          VARCHAR(20)  NOT NULL DEFAULT 'MANUAL',
    chunk_count     INT          NOT NULL DEFAULT 0,
    chunk_size      INT          NOT NULL DEFAULT 500,
    overlap_size    INT          NOT NULL DEFAULT 0,
    chunk_mode      VARCHAR(20)  NOT NULL DEFAULT 'SENTENCE',
    minio_path      VARCHAR(500),                   -- MinIO 原文路径
    sync_batch_id   VARCHAR(36),                    -- 同步批次 ID
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_documents_source ON documents(source);
CREATE INDEX idx_documents_sync_batch ON documents(sync_batch_id);
