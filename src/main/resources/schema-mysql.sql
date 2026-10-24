-- ============================================================
-- AI 教学知识库平台 —— MySQL 8 同构 DDL（可选 profile）
-- 与 schema-h2.sql 表/列/唯一约束完全一致，仅索引语法不同
-- （MySQL 8 不支持 CREATE INDEX IF NOT EXISTS，故索引写在建表语句内）。
-- ============================================================

CREATE TABLE IF NOT EXISTS documents (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  doc_id       VARCHAR(64)  NOT NULL,
  user_id      VARCHAR(64)  NOT NULL DEFAULT 'default',
  filename     VARCHAR(255) NOT NULL,
  doc_type     VARCHAR(16)  NOT NULL,
  content_md5  CHAR(32)     NOT NULL,
  storage_path VARCHAR(512) NOT NULL,
  size_bytes   BIGINT       NOT NULL DEFAULT 0,
  status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_documents_docid UNIQUE (doc_id),
  CONSTRAINT uk_documents_user_md5 UNIQUE (user_id, content_md5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chunks (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  chunk_id    VARCHAR(64) NOT NULL,
  doc_id      VARCHAR(64) NOT NULL,
  seq         INT         NOT NULL,
  content     MEDIUMTEXT  NOT NULL,
  speaker     VARCHAR(128),
  msg_time    TIMESTAMP   NULL,
  embedding   MEDIUMTEXT  NOT NULL,
  dim         INT         NOT NULL,
  token_count INT         NOT NULL DEFAULT 0,
  created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_chunks_chunkid UNIQUE (chunk_id),
  KEY idx_chunks_doc (doc_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tasks (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id     VARCHAR(64)  NOT NULL,
  task_type   VARCHAR(32)  NOT NULL,
  status      VARCHAR(16)  NOT NULL,
  doc_id      VARCHAR(64),
  payload     TEXT,
  progress    INT          NOT NULL DEFAULT 0,
  retry_count INT          NOT NULL DEFAULT 0,
  fail_reason VARCHAR(1024),
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_tasks_taskid UNIQUE (task_id),
  KEY idx_tasks_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS conversations (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  conversation_id VARCHAR(64) NOT NULL,
  user_id         VARCHAR(64) NOT NULL DEFAULT 'default',
  title           VARCHAR(255),
  created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_conv_id UNIQUE (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS messages (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  message_id        VARCHAR(64) NOT NULL,
  conversation_id   VARCHAR(64) NOT NULL,
  role              VARCHAR(16) NOT NULL,
  content           MEDIUMTEXT  NOT NULL,
  citations         TEXT,
  plugin_calls      TEXT,
  prompt_tokens     INT NOT NULL DEFAULT 0,
  completion_tokens INT NOT NULL DEFAULT 0,
  created_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_messages_msgid UNIQUE (message_id),
  KEY idx_messages_conv (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- 分片上传（断点续传）：MySQL 版（与 schema-h2.sql 同构）
-- 差异：MySQL 8 不支持 CREATE INDEX IF NOT EXISTS，索引随建表语句内联。
-- ============================================================

CREATE TABLE IF NOT EXISTS upload_sessions (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  upload_id     VARCHAR(64)  NOT NULL,
  user_id       VARCHAR(64)  NOT NULL DEFAULT 'default',
  filename      VARCHAR(255) NOT NULL,
  total_size    BIGINT       NOT NULL,
  chunk_size    INT          NOT NULL,
  total_chunks  INT          NOT NULL,
  file_md5      CHAR(32),
  status        VARCHAR(16)  NOT NULL DEFAULT 'UPLOADING',
  merged_doc_id VARCHAR(64),
  merged_task_id VARCHAR(64),
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at    TIMESTAMP    NOT NULL,
  CONSTRAINT uk_upload_session_id UNIQUE (upload_id),
  KEY idx_upload_session_status (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS upload_chunks (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  upload_id    VARCHAR(64) NOT NULL,
  seq          INT         NOT NULL,
  size_bytes   INT         NOT NULL,
  chunk_md5    CHAR(32)    NOT NULL,
  received_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_upload_chunk UNIQUE (upload_id, seq),
  KEY idx_upload_chunk_upload (upload_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
