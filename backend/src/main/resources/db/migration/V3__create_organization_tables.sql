CREATE TABLE organization (
    id BIGINT NOT NULL COMMENT '组织ID',
    name VARCHAR(128) NOT NULL COMMENT '组织名称',
    description VARCHAR(1000) NULL COMMENT '组织介绍',
    status VARCHAR(32) NOT NULL COMMENT '组织状态',
    created_by BIGINT NOT NULL COMMENT '创建人用户ID',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_organization_created_by (created_by),
    KEY idx_organization_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='志愿组织';

CREATE TABLE organization_invite (
    id BIGINT NOT NULL COMMENT '组织邀请码ID',
    organization_id BIGINT NOT NULL COMMENT '组织ID',
    default_role_id BIGINT NOT NULL COMMENT '默认角色ID',
    code_hash CHAR(64) NOT NULL COMMENT '邀请码SHA-256摘要',
    max_uses INT NOT NULL COMMENT '最大使用次数',
    used_count INT NOT NULL DEFAULT 0 COMMENT '已使用次数',
    expires_at DATETIME(6) NOT NULL COMMENT '过期时间',
    status VARCHAR(32) NOT NULL COMMENT '邀请码状态',
    created_by BIGINT NOT NULL COMMENT '创建人用户ID',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_organization_invite_code_hash (code_hash),
    KEY idx_organization_invite_organization_id (organization_id),
    KEY idx_organization_invite_default_role_id (default_role_id),
    KEY idx_organization_invite_created_by (created_by),
    KEY idx_organization_invite_status_expiry (organization_id, status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='组织邀请码';

CREATE TABLE organization_member (
    id BIGINT NOT NULL COMMENT '组织成员ID',
    organization_id BIGINT NOT NULL COMMENT '组织ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role_id BIGINT NOT NULL COMMENT '当前角色ID',
    status VARCHAR(32) NOT NULL COMMENT '成员状态',
    joined_at DATETIME(6) NOT NULL COMMENT '加入时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_organization_member_org_user (organization_id, user_id),
    KEY idx_organization_member_user_id (user_id),
    KEY idx_organization_member_role_id (role_id),
    KEY idx_organization_member_org_status (organization_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='组织成员';
