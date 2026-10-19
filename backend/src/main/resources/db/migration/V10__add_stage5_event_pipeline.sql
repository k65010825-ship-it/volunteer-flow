ALTER TABLE outbox_event
    ADD COLUMN topic VARCHAR(160) NOT NULL,
    ADD COLUMN message_key VARCHAR(160) NOT NULL,
    ADD COLUMN locked_by VARCHAR(128) NULL,
    ADD COLUMN locked_until DATETIME(6) NULL,
    ADD COLUMN last_error VARCHAR(1000) NULL,
    ADD COLUMN published_partition INT NULL,
    ADD COLUMN published_offset BIGINT NULL,
    DROP INDEX idx_outbox_event_status_retry,
    ADD KEY idx_outbox_event_status_retry_id (status, next_retry_at, id),
    ADD KEY idx_outbox_event_status_lease_id (status, locked_until, id),
    ADD KEY idx_outbox_event_published_at_id (published_at, id);

CREATE TABLE consumed_event (
    id BIGINT NOT NULL,
    consumer_name VARCHAR(128) NOT NULL,
    event_id CHAR(36) NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_consumed_event_consumer_event (consumer_name, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE promotion_reconcile_task (
    position_id BIGINT NOT NULL,
    organization_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    request_version BIGINT NOT NULL DEFAULT 1,
    processed_version BIGINT NOT NULL DEFAULT 0,
    next_run_at DATETIME(6) NOT NULL,
    locked_by VARCHAR(128) NULL,
    locked_until DATETIME(6) NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1000) NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (position_id),
    KEY idx_promotion_reconcile_status_run_position (status, next_run_at, position_id),
    KEY idx_promotion_reconcile_status_lease_position (status, locked_until, position_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
