-- ============================================================
-- AI 教学知识库平台 —— H2 DDL（默认数据源，MODE=MySQL，文件模式）
-- 由 spring.sql.init.mode=always 在每次启动时执行，必须幂等。
-- 五表：documents / chunks / tasks / conversations / messages
-- ============================================================

CREATE TABLE IF NOT EXISTS documents (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  doc_id       VARCHAR(64)  NOT NULL,
  user_id      VARCHAR(64)  NOT NULL DEFAULT 'default',
  filename     VARCHAR(255) NOT NULL,
  doc_type     VARCHAR(16)  NOT NULL,                -- MD/TXT/DOCX/CHAT
  content_md5  CHAR(32)     NOT NULL,
  storage_path VARCHAR(512) NOT NULL,
  size_bytes   BIGINT       NOT NULL DEFAULT 0,
  status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE', -- ACTIVE/DELETED
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_documents_docid UNIQUE (doc_id),
  CONSTRAINT uk_documents_user_md5 UNIQUE (user_id, content_md5)   -- MD5 幂等
);

CREATE TABLE IF NOT EXISTS chunks (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  chunk_id    VARCHAR(64) NOT NULL,
  doc_id      VARCHAR(64) NOT NULL,
  seq         INT         NOT NULL,
  content     TEXT        NOT NULL,
  speaker     VARCHAR(128),                          -- 聊天 chunk 说话人；文档为 NULL
  msg_time    TIMESTAMP,                             -- 聊天 chunk 消息时间
  embedding   TEXT        NOT NULL,                  -- float32 数组序列化（Base64）
  dim         INT         NOT NULL,
  token_count INT         NOT NULL DEFAULT 0,
  created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_chunks_chunkid UNIQUE (chunk_id)
);
CREATE INDEX IF NOT EXISTS idx_chunks_doc ON chunks (doc_id);

CREATE TABLE IF NOT EXISTS tasks (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id     VARCHAR(64)  NOT NULL,
  task_type   VARCHAR(32)  NOT NULL,                 -- IMPORT_DOC / IMPORT_CHAT
  status      VARCHAR(16)  NOT NULL,                 -- PENDING/RUNNING/SUCCESS/FAILED
  doc_id      VARCHAR(64),
  payload     TEXT,                                  -- 任务参数 JSON
  progress    INT          NOT NULL DEFAULT 0,
  retry_count INT          NOT NULL DEFAULT 0,
  fail_reason VARCHAR(1024),
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_tasks_taskid UNIQUE (task_id)
);
CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks (status);

CREATE TABLE IF NOT EXISTS conversations (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  conversation_id VARCHAR(64) NOT NULL,
  user_id         VARCHAR(64) NOT NULL DEFAULT 'default',
  title           VARCHAR(255),
  created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_conv_id UNIQUE (conversation_id)
);

CREATE TABLE IF NOT EXISTS messages (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  message_id        VARCHAR(64) NOT NULL,
  conversation_id   VARCHAR(64) NOT NULL,
  role              VARCHAR(16) NOT NULL,            -- user/assistant
  content           TEXT        NOT NULL,
  citations         TEXT,                            -- JSON [{chunkId,docId,snippet,score}]
  plugin_calls      TEXT,                            -- JSON [{tool,args,result}]
  prompt_tokens     INT NOT NULL DEFAULT 0,
  completion_tokens INT NOT NULL DEFAULT 0,
  created_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_messages_msgid UNIQUE (message_id)
);
CREATE INDEX IF NOT EXISTS idx_messages_conv ON messages (conversation_id);

-- ============================================================
-- 分片上传（断点续传）：upload_sessions（会话）+ upload_chunks（分片元数据，权威来源）
-- 分片字节本身落盘 data/chunks/<uploadId>/<seq>.part，DB 只存"哪些分片已收"。
-- 这样续传时可按 DB 重建进度，不依赖 Redis 存活（Redis 只做加速缓存）。
-- ============================================================

CREATE TABLE IF NOT EXISTS upload_sessions (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  upload_id     VARCHAR(64)  NOT NULL,
  user_id       VARCHAR(64)  NOT NULL DEFAULT 'default',
  filename      VARCHAR(255) NOT NULL,
  total_size    BIGINT       NOT NULL,
  chunk_size    INT          NOT NULL,
  total_chunks  INT          NOT NULL,
  file_md5      CHAR(32),                            -- 客户端可选：合并后强校验 + 秒传预判
  status        VARCHAR(16)  NOT NULL DEFAULT 'UPLOADING',
  merged_doc_id VARCHAR(64),
  merged_task_id VARCHAR(64),
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at    TIMESTAMP    NOT NULL,
  CONSTRAINT uk_upload_session_id UNIQUE (upload_id)
);
CREATE INDEX IF NOT EXISTS idx_upload_session_status ON upload_sessions (status, expires_at);

CREATE TABLE IF NOT EXISTS upload_chunks (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  upload_id    VARCHAR(64) NOT NULL,
  seq          INT         NOT NULL,
  size_bytes   INT         NOT NULL,
  chunk_md5    CHAR(32)    NOT NULL,                 -- 分片级完整性校验（坏片可被单独特定重传）
  received_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_upload_chunk UNIQUE (upload_id, seq)  -- 同一分片重传 = 覆盖，天然幂等
);
