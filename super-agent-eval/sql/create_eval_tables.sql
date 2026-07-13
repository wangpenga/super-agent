-- ============================================================
-- RAG 评估模块数据库表 DDL
-- 与主服务共用 super_agent_business_chat 数据库，
-- 表名前缀 super_agent_eval_* 以示区分。
--
-- 注意：MyBatis-Plus 会自动处理 createTime/editTime/status 字段的填充，
-- 不需要在 SQL 中设置 DEFAULT。
-- ============================================================

-- 评估测试集表：一个「问题 + 期望命中的 chunk 列表」对
CREATE TABLE IF NOT EXISTS super_agent_eval_dataset (
    id                        BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键 ID',
    document_id               BIGINT          NOT NULL                 COMMENT '文档 ID',
    question                  TEXT            NOT NULL                 COMMENT '测试问题',
    source                    VARCHAR(32)     NOT NULL DEFAULT 'profile' COMMENT '问题来源：conversation_log / profile / llm_generated / manual',
    ground_truth_chunk_ids    JSON            DEFAULT '[]'             COMMENT '相关 chunk ID 列表 [1,2,3]（人工录入可为空）',
    ground_truth_parent_block_ids JSON       DEFAULT NULL              COMMENT '相关父块 ID 列表（可选）',
    difficulty                VARCHAR(16)     DEFAULT 'medium'          COMMENT '难度：easy / medium / hard',
    tags                      VARCHAR(255)    DEFAULT NULL             COMMENT '标签，逗号分隔',
    is_active                 TINYINT(1)      DEFAULT 1                COMMENT '是否激活：1=参与评估 0=跳过',
    exchange_id               BIGINT          DEFAULT NULL             COMMENT '来源对话的 exchange_id（source=conversation_log 时）',
    create_time               DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    edit_time                 DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '编辑时间',
    status                    TINYINT(1)      DEFAULT 1                COMMENT '逻辑删除：1=正常 0=删除',
    PRIMARY KEY (id),
    KEY idx_document (document_id),
    KEY idx_source (source),
    KEY idx_active (is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG 评估测试集';


-- 评估运行表：一次完整的离线评估执行
CREATE TABLE IF NOT EXISTS super_agent_eval_run (
    id                        BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键 ID',
    run_name                  VARCHAR(128)    NOT NULL                 COMMENT '运行名称，如 "2026-07-10-baseline"',
    run_type                  VARCHAR(32)     NOT NULL DEFAULT 'manual' COMMENT '运行类型：manual / scheduled / ab_test',
    config_snapshot           JSON            NOT NULL                 COMMENT '运行时的完整配置快照',
    dataset_size              INT             DEFAULT 0                COMMENT '测试集规模',
    avg_context_precision     DECIMAL(8,6)    DEFAULT NULL             COMMENT '平均 Context Precision',
    avg_context_recall        DECIMAL(8,6)    DEFAULT NULL             COMMENT '平均 Context Recall',
    avg_faithfulness          DECIMAL(8,6)    DEFAULT NULL             COMMENT '平均 Faithfulness',
    avg_answer_relevancy      DECIMAL(8,6)    DEFAULT NULL             COMMENT '平均 Answer Relevancy',
    avg_latency_ms            BIGINT          DEFAULT NULL             COMMENT '平均检索耗时（毫秒）',
    run_status                TINYINT(1)      NOT NULL DEFAULT 1       COMMENT '运行状态：1=pending 2=running 3=completed 4=failed',
    started_at                DATETIME        DEFAULT NULL             COMMENT '开始时间',
    completed_at              DATETIME        DEFAULT NULL             COMMENT '完成时间',
    error_message             TEXT            DEFAULT NULL             COMMENT '错误信息',
    create_time               DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    edit_time                 DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '编辑时间',
    status                    TINYINT(1)      DEFAULT 1                COMMENT '逻辑删除：1=正常 0=删除',
    PRIMARY KEY (id),
    KEY idx_run_created (create_time),
    KEY idx_run_status (run_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG 评估运行记录';


-- 单问题评估结果表：一次评估运行中单个问题的全部指标
CREATE TABLE IF NOT EXISTS super_agent_eval_question_result (
    id                        BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键 ID',
    run_id                    BIGINT          NOT NULL                 COMMENT '关联的 eval_run_id',
    dataset_id                BIGINT          NOT NULL                 COMMENT '关联的 eval_dataset_id',
    document_id               BIGINT          NOT NULL                 COMMENT '文档 ID',
    question                  TEXT            NOT NULL                 COMMENT '问题文本',
    context_precision         DECIMAL(8,6)    DEFAULT NULL             COMMENT 'Context Precision（0~1）',
    context_recall            DECIMAL(8,6)    DEFAULT NULL             COMMENT 'Context Recall（0~1）',
    faithfulness              DECIMAL(8,6)    DEFAULT NULL             COMMENT 'Faithfulness（0~1）',
    answer_relevancy          DECIMAL(8,6)    DEFAULT NULL             COMMENT 'Answer Relevancy（0~1）',
    retrieval_latency_ms      BIGINT          DEFAULT NULL             COMMENT '检索阶段耗时（毫秒）',
    relevance_judgments       JSON            DEFAULT NULL             COMMENT '相关性判断明细 [{"chunkId":1,"relevant":true,"method":"rerank"}]',
    answer                    TEXT            DEFAULT NULL             COMMENT '生成的答案文本（来源对话日志时有值）',
    final_top_k               INT             DEFAULT 0                COMMENT '最终选入 Prompt 的引用数',
    retrieved_chunk_ids       JSON            DEFAULT NULL             COMMENT '检索到的 chunk ID 列表',
    create_time               DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    edit_time                 DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '编辑时间',
    status                    TINYINT(1)      DEFAULT 1                COMMENT '逻辑删除：1=正常 0=删除',
    PRIMARY KEY (id),
    KEY idx_run_id (run_id),
    KEY idx_document_id (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG 评估单问题结果表';


-- 评估指标日汇总表：每天各指标的聚合统计，供 Dashboard 趋势图使用
CREATE TABLE IF NOT EXISTS super_agent_eval_metric_daily (
    id                        BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键 ID',
    metric_date               DATE            NOT NULL                 COMMENT '统计日期',
    metric_name               VARCHAR(64)     NOT NULL                 COMMENT '指标名：context_precision / context_recall / faithfulness / answer_relevancy',
    avg_value                 DECIMAL(8,6)    DEFAULT NULL             COMMENT '日均值',
    p50                       DECIMAL(8,6)    DEFAULT NULL             COMMENT 'P50 中位数',
    p90                       DECIMAL(8,6)    DEFAULT NULL             COMMENT 'P90 值',
    min_value                 DECIMAL(8,6)    DEFAULT NULL             COMMENT '最小值',
    max_value                 DECIMAL(8,6)    DEFAULT NULL             COMMENT '最大值',
    sample_count              INT             NOT NULL DEFAULT 0       COMMENT '样本数',
    run_count                 INT             NOT NULL DEFAULT 0       COMMENT '运行次数',
    create_time               DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    edit_time                 DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '编辑时间',
    status                    TINYINT(1)      DEFAULT 1                COMMENT '逻辑删除：1=正常 0=删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_metric_date_name (metric_date, metric_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG 评估指标日汇总表';


-- ============================================================
-- 人工抽检新增字段（ALTER TABLE）
-- 如果表已存在，执行以下 ALTER 语句
-- ============================================================
ALTER TABLE super_agent_eval_dataset
    ADD COLUMN reference_answer     TEXT            DEFAULT NULL     COMMENT '参考答案（人工或LLM准备的标准答案）' AFTER exchange_id,
    ADD COLUMN generated_answer     TEXT            DEFAULT NULL     COMMENT 'LLM 基于检索 chunks 生成的答案' AFTER reference_answer,
    ADD COLUMN human_score          TINYINT(1)      DEFAULT NULL     COMMENT '人工评分（1~5），null=未评' AFTER generated_answer,
    ADD COLUMN human_comment        VARCHAR(500)    DEFAULT NULL     COMMENT '人工评语' AFTER human_score,
    ADD COLUMN review_status        TINYINT(1)      DEFAULT 0        COMMENT '抽检状态：0=待处理 1=已生成答案 2=已评分' AFTER human_comment,
    ADD INDEX idx_review_status (review_status);
