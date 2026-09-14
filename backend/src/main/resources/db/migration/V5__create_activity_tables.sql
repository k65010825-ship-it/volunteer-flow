CREATE TABLE activity (
    id BIGINT NOT NULL COMMENT '活动ID',
    organization_id BIGINT NOT NULL COMMENT '组织ID',
    title VARCHAR(160) NOT NULL COMMENT '活动标题',
    description TEXT NOT NULL COMMENT '活动介绍',
    location VARCHAR(255) NOT NULL COMMENT '活动地点',
    registration_start_at DATETIME(6) NOT NULL COMMENT '报名开始时间',
    registration_end_at DATETIME(6) NOT NULL COMMENT '报名截止时间',
    free_cancel_deadline_at DATETIME(6) NULL COMMENT '免费取消截止时间',
    activity_start_at DATETIME(6) NOT NULL COMMENT '活动开始时间',
    activity_end_at DATETIME(6) NOT NULL COMMENT '活动结束时间',
    status VARCHAR(32) NOT NULL COMMENT '活动状态',
    created_by BIGINT NOT NULL COMMENT '创建人用户ID',
    published_at DATETIME(6) NULL COMMENT '发布时间',
    canceled_at DATETIME(6) NULL COMMENT '取消时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_activity_organization_id (organization_id),
    KEY idx_activity_created_by (created_by),
    KEY idx_activity_org_status (organization_id, status),
    KEY idx_activity_org_start_at (organization_id, activity_start_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='志愿活动';

CREATE TABLE activity_position (
    id BIGINT NOT NULL COMMENT '活动岗位ID',
    organization_id BIGINT NOT NULL COMMENT '组织ID',
    activity_id BIGINT NOT NULL COMMENT '活动ID',
    name VARCHAR(128) NOT NULL COMMENT '岗位名称',
    description VARCHAR(1000) NOT NULL COMMENT '岗位说明',
    capacity INT NOT NULL COMMENT '招募容量',
    registration_mode VARCHAR(32) NOT NULL COMMENT '报名模式',
    promotion_timeout_minutes INT NOT NULL DEFAULT 120 COMMENT '递补确认分钟数',
    service_start_at DATETIME(6) NULL COMMENT '岗位服务开始时间',
    service_end_at DATETIME(6) NULL COMMENT '岗位服务结束时间',
    meeting_location VARCHAR(255) NULL COMMENT '岗位集合地点',
    next_waitlist_sequence BIGINT NOT NULL DEFAULT 1 COMMENT '下一个候补序号',
    status VARCHAR(32) NOT NULL COMMENT '岗位状态',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_activity_position_activity_name (activity_id, name),
    KEY idx_activity_position_organization_id (organization_id),
    KEY idx_activity_position_activity_status (activity_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='活动志愿岗位';

CREATE TABLE activity_question (
    id BIGINT NOT NULL COMMENT '活动公共问题ID',
    organization_id BIGINT NOT NULL COMMENT '组织ID',
    activity_id BIGINT NOT NULL COMMENT '活动ID',
    question_type VARCHAR(32) NOT NULL COMMENT '问题类型',
    title VARCHAR(255) NOT NULL COMMENT '问题标题',
    required_question TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否必填',
    options_json JSON NULL COMMENT '选项JSON',
    sort_order INT NOT NULL COMMENT '展示顺序',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_activity_question_activity_order (activity_id, sort_order),
    KEY idx_activity_question_organization_id (organization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='活动公共报名问题';

CREATE TABLE activity_position_question (
    id BIGINT NOT NULL COMMENT '岗位问题ID',
    organization_id BIGINT NOT NULL COMMENT '组织ID',
    activity_id BIGINT NOT NULL COMMENT '活动ID',
    position_id BIGINT NOT NULL COMMENT '岗位ID',
    question_type VARCHAR(32) NOT NULL COMMENT '问题类型',
    title VARCHAR(255) NOT NULL COMMENT '问题标题',
    required_question TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否必填',
    options_json JSON NULL COMMENT '选项JSON',
    sort_order INT NOT NULL COMMENT '展示顺序',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_position_question_position_order (position_id, sort_order),
    KEY idx_position_question_organization_id (organization_id),
    KEY idx_position_question_activity_id (activity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='岗位专属报名问题';

CREATE TABLE activity_change (
    id BIGINT NOT NULL COMMENT '活动变更ID',
    organization_id BIGINT NOT NULL COMMENT '组织ID',
    activity_id BIGINT NOT NULL COMMENT '活动ID',
    target_type VARCHAR(32) NOT NULL COMMENT '变更目标类型',
    target_id BIGINT NOT NULL COMMENT '变更目标ID',
    change_type VARCHAR(64) NOT NULL COMMENT '变更类型',
    before_json JSON NOT NULL COMMENT '变更前快照',
    after_json JSON NOT NULL COMMENT '变更后快照',
    reason VARCHAR(500) NOT NULL COMMENT '变更原因',
    changed_by BIGINT NOT NULL COMMENT '操作人用户ID',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_activity_change_organization_id (organization_id),
    KEY idx_activity_change_activity_id (activity_id),
    KEY idx_activity_change_target_id (target_id),
    KEY idx_activity_change_changed_by (changed_by),
    KEY idx_activity_change_activity_time (activity_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='活动正式变更记录';
