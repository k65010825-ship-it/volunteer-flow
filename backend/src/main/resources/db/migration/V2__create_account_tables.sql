CREATE TABLE app_user (
    id BIGINT NOT NULL COMMENT '用户ID',
    username VARCHAR(64) NOT NULL COMMENT '登录用户名',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt密码摘要',
    real_name VARCHAR(64) NOT NULL COMMENT '真实姓名',
    student_number VARCHAR(64) NOT NULL COMMENT '学号',
    contact VARCHAR(128) NOT NULL COMMENT '联系方式',
    status VARCHAR(32) NOT NULL COMMENT '用户状态',
    platform_role VARCHAR(32) NOT NULL COMMENT '平台角色',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_user_username (username),
    UNIQUE KEY uk_app_user_student_number (student_number),
    KEY idx_app_user_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台用户';

CREATE TABLE refresh_session (
    id BIGINT NOT NULL COMMENT '刷新会话ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    token_hash CHAR(64) NOT NULL COMMENT '刷新令牌SHA-256摘要',
    device_name VARCHAR(128) NULL COMMENT '设备名称',
    expires_at DATETIME(6) NOT NULL COMMENT '过期时间',
    last_used_at DATETIME(6) NULL COMMENT '最近使用时间',
    revoked_at DATETIME(6) NULL COMMENT '撤销时间',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_refresh_session_token_hash (token_hash),
    KEY idx_refresh_session_user_id (user_id),
    KEY idx_refresh_session_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='多设备刷新会话';
