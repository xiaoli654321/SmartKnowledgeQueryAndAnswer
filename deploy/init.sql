-- ─────────────────────────────────────────────────────────────
-- 项目7 建库建表脚本（幂等，可重复执行）
-- 执行：mysql -uroot -proot < deploy/init.sql
-- 说明：doc_db 的两张表和讲义第 22 页一致；
--       search_db.doc_chunk 多加了 embedding / embedding_model / 两个索引和一个 ngram 全文索引，
--       这是 M2 混合检索要用的（讲义说"向量字段简化，生产用向量库"，这里先用 JSON 文本存 1024 维向量）
-- ─────────────────────────────────────────────────────────────

-- ══════════════ 文档服务库 ══════════════
CREATE DATABASE IF NOT EXISTS doc_db DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE doc_db;

-- 知识库文档表：一篇文档一行，content 存当前版本全文
CREATE TABLE IF NOT EXISTS kb_document (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id   BIGINT NOT NULL COMMENT '租户ID（多租户隔离的第一道闸）',
  title       VARCHAR(255) NOT NULL,
  content     LONGTEXT,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_tenant (tenant_id)
) COMMENT='知识库文档表';

-- 文档版本表：追加式版本链，永不 UPDATE，回滚=把老版本内容写回主表并追加新版本
CREATE TABLE IF NOT EXISTS kb_document_version (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  document_id BIGINT NOT NULL,
  version     INT NOT NULL,
  content     LONGTEXT,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_doc_version (document_id, version)   -- 同一文档版本号唯一，防并发插重
) COMMENT='文档版本表';

-- ══════════════ 检索服务库 ══════════════
CREATE DATABASE IF NOT EXISTS search_db DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE search_db;

-- 文档分块表：一篇文档切多块，每块一条
CREATE TABLE IF NOT EXISTS doc_chunk (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  document_id     BIGINT NOT NULL,
  tenant_id       BIGINT NOT NULL,
  chunk_index     INT DEFAULT 0,
  content         LONGTEXT,
  embedding       LONGTEXT COMMENT '向量：1024 维 float 的 JSON 数组，bge-m3 输出',
  embedding_model VARCHAR(64) DEFAULT 'BAAI/bge-m3',
  create_time     DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_doc (document_id),
  KEY idx_tenant (tenant_id),
  -- 关键词那一路靠它：ngram 分词器专治中文（默认分词器会把整句当一个词，中文搜不到）
  FULLTEXT KEY ft_content (content) WITH PARSER ngram
) COMMENT='文档分块表（含向量列）';
