CREATE TABLE outbox_event (
    id BIGINT NOT NULL COMMENT 'Outbox记录ID',
    organization_id BIGINT NULL COMMENT '组织ID，平台事件为空',
    event_id CHAR(36) NOT NULL COMMENT '事件唯一编号',
    aggregate_type VARCHAR(64) NOT NULL COMMENT '聚合类型',
    aggregate_id VARCHAR(64) NOT NULL COMMENT '聚合ID',
    event_type VARCHAR(96) NOT NULL COMMENT '事件类型',
    payload_json JSON NOT NULL COMMENT '事件载荷',
    status VARCHAR(32) NOT NULL COMMENT '发布状态',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '重试次数',
    next_retry_at DATETIME(6) NULL COMMENT '下次重试时间',
    published_at DATETIME(6) NULL COMMENT '发布时间',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event_event_id (event_id),
    KEY idx_outbox_event_organization_id (organization_id),
    KEY idx_outbox_event_status_retry (status, next_retry_at),
    KEY idx_outbox_event_aggregate (aggregate_type, aggregate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='事务消息发件箱';
