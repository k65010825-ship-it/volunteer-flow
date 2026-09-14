CREATE TABLE checkin_session (
    id BIGINT NOT NULL COMMENT '签到场次ID',
    organization_id BIGINT NOT NULL COMMENT '组织ID',
    activity_id BIGINT NOT NULL COMMENT '活动ID',
    session_type VARCHAR(32) NOT NULL COMMENT '签到场次类型',
    static_code_hash CHAR(64) NULL COMMENT '静态签到码SHA-256摘要',
    status VARCHAR(32) NOT NULL COMMENT '签到场次状态',
    opens_at DATETIME(6) NOT NULL COMMENT '开放时间',
    closes_at DATETIME(6) NULL COMMENT '关闭时间',
    created_by BIGINT NOT NULL COMMENT '创建人用户ID',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_checkin_session_organization_id (organization_id),
    KEY idx_checkin_session_activity_id (activity_id),
    KEY idx_checkin_session_activity_status (activity_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='活动签到场次';

CREATE TABLE checkin_record (
    id BIGINT NOT NULL COMMENT '签到记录ID',
    organization_id BIGINT NOT NULL COMMENT '组织ID',
    activity_id BIGINT NOT NULL COMMENT '活动ID',
    checkin_session_id BIGINT NOT NULL COMMENT '签到场次ID',
    registration_cycle_id BIGINT NOT NULL COMMENT '报名周期ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    checkin_method VARCHAR(32) NOT NULL COMMENT '签到方式',
    checked_in_at DATETIME(6) NOT NULL COMMENT '签到时间',
    operator_id BIGINT NULL COMMENT '人工操作人用户ID',
    manual_reason VARCHAR(500) NULL COMMENT '人工补签原因',
    status VARCHAR(32) NOT NULL COMMENT '签到记录状态',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_checkin_record_activity_user (activity_id, user_id),
    KEY idx_checkin_record_organization_id (organization_id),
    KEY idx_checkin_record_session_id (checkin_session_id),
    KEY idx_checkin_record_cycle_id (registration_cycle_id),
    KEY idx_checkin_record_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='活动签到记录';

CREATE TABLE notification (
    id BIGINT NOT NULL COMMENT '通知ID',
    organization_id BIGINT NULL COMMENT '组织ID，平台通知为空',
    user_id BIGINT NOT NULL COMMENT '接收用户ID',
    notification_type VARCHAR(64) NOT NULL COMMENT '通知类型',
    title VARCHAR(160) NOT NULL COMMENT '通知标题',
    content TEXT NOT NULL COMMENT '通知内容',
    business_type VARCHAR(64) NULL COMMENT '关联业务类型',
    business_id BIGINT NULL COMMENT '关联业务ID',
    deduplication_key VARCHAR(128) NOT NULL COMMENT '业务去重键',
    read_at DATETIME(6) NULL COMMENT '已读时间',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_user_deduplication (user_id, deduplication_key),
    KEY idx_notification_organization_id (organization_id),
    KEY idx_notification_business_id (business_id),
    KEY idx_notification_user_read_time (user_id, read_at, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站内通知';

CREATE TABLE audit_log (
    id BIGINT NOT NULL COMMENT '审计日志ID',
    organization_id BIGINT NULL COMMENT '组织ID，平台审计为空',
    actor_user_id BIGINT NULL COMMENT '操作人用户ID',
    action VARCHAR(96) NOT NULL COMMENT '操作编码',
    resource_type VARCHAR(64) NOT NULL COMMENT '资源类型',
    resource_id BIGINT NULL COMMENT '资源ID',
    before_json JSON NULL COMMENT '操作前快照',
    after_json JSON NULL COMMENT '操作后快照',
    reason VARCHAR(500) NULL COMMENT '操作原因',
    request_id VARCHAR(64) NULL COMMENT '请求ID',
    ip_address VARCHAR(64) NULL COMMENT '客户端IP',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_audit_log_organization_id (organization_id),
    KEY idx_audit_log_actor_user_id (actor_user_id),
    KEY idx_audit_log_resource (resource_type, resource_id),
    KEY idx_audit_log_request_id (request_id),
    KEY idx_audit_log_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='只追加关键操作审计';
