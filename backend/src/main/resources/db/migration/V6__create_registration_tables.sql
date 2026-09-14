CREATE TABLE registration (
    id BIGINT NOT NULL COMMENT '报名主记录ID',
    organization_id BIGINT NOT NULL COMMENT '组织ID',
    activity_id BIGINT NOT NULL COMMENT '活动ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    last_cycle_number INT NOT NULL DEFAULT 0 COMMENT '最后报名周期号',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_registration_activity_user (activity_id, user_id),
    KEY idx_registration_organization_id (organization_id),
    KEY idx_registration_user_id (user_id),
    KEY idx_registration_org_user (organization_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='成员与活动稳定报名关系';

CREATE TABLE registration_cycle (
    id BIGINT NOT NULL COMMENT '报名周期ID',
    organization_id BIGINT NOT NULL COMMENT '组织ID',
    registration_id BIGINT NOT NULL COMMENT '报名主记录ID',
    activity_id BIGINT NOT NULL COMMENT '活动ID',
    position_id BIGINT NOT NULL COMMENT '岗位ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    cycle_number INT NOT NULL COMMENT '报名周期号',
    status VARCHAR(32) NOT NULL COMMENT '报名周期状态',
    waitlist_sequence BIGINT NULL COMMENT '候补序号',
    submitted_at DATETIME(6) NOT NULL COMMENT '提交时间',
    reviewed_by BIGINT NULL COMMENT '审核人用户ID',
    reviewed_at DATETIME(6) NULL COMMENT '审核时间',
    review_reason VARCHAR(500) NULL COMMENT '审核原因',
    canceled_at DATETIME(6) NULL COMMENT '取消时间',
    cancel_reason VARCHAR(500) NULL COMMENT '取消原因',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_registration_cycle_number (registration_id, cycle_number),
    UNIQUE KEY uk_registration_cycle_waitlist_sequence (position_id, waitlist_sequence),
    KEY idx_registration_cycle_organization_id (organization_id),
    KEY idx_registration_cycle_activity_id (activity_id),
    KEY idx_registration_cycle_user_id (user_id),
    KEY idx_registration_cycle_position_status (position_id, status),
    KEY idx_registration_cycle_registration_status (registration_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='单次报名尝试及状态';

CREATE TABLE registration_answer (
    id BIGINT NOT NULL COMMENT '报名答案ID',
    organization_id BIGINT NOT NULL COMMENT '组织ID',
    registration_cycle_id BIGINT NOT NULL COMMENT '报名周期ID',
    question_scope VARCHAR(32) NOT NULL COMMENT '问题范围',
    question_id BIGINT NOT NULL COMMENT '问题ID',
    answer_json JSON NOT NULL COMMENT '答案JSON',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_registration_answer_cycle_scope_question
        (registration_cycle_id, question_scope, question_id),
    KEY idx_registration_answer_organization_id (organization_id),
    KEY idx_registration_answer_question_id (question_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='报名问题答案';

CREATE TABLE promotion_offer (
    id BIGINT NOT NULL COMMENT '递补邀请ID',
    organization_id BIGINT NOT NULL COMMENT '组织ID',
    activity_id BIGINT NOT NULL COMMENT '活动ID',
    position_id BIGINT NOT NULL COMMENT '岗位ID',
    registration_cycle_id BIGINT NOT NULL COMMENT '报名周期ID',
    status VARCHAR(32) NOT NULL COMMENT '邀请状态',
    expires_at DATETIME(6) NOT NULL COMMENT '邀请过期时间',
    responded_at DATETIME(6) NULL COMMENT '响应时间',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    reason VARCHAR(500) NULL COMMENT '邀请原因',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_promotion_offer_cycle (registration_cycle_id),
    KEY idx_promotion_offer_organization_id (organization_id),
    KEY idx_promotion_offer_activity_id (activity_id),
    KEY idx_promotion_offer_position_id (position_id),
    KEY idx_promotion_offer_status_expiry (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='限时递补邀请';
