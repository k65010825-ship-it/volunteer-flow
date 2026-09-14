CREATE TABLE rbac_permission (
    id BIGINT NOT NULL COMMENT '权限ID',
    code VARCHAR(64) NOT NULL COMMENT '稳定权限编码',
    name VARCHAR(64) NOT NULL COMMENT '权限名称',
    description VARCHAR(255) NULL COMMENT '权限说明',
    status VARCHAR(32) NOT NULL COMMENT '权限状态',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_rbac_permission_code (code),
    KEY idx_rbac_permission_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台预置权限';

CREATE TABLE rbac_role (
    id BIGINT NOT NULL COMMENT '角色ID',
    organization_id BIGINT NOT NULL COMMENT '组织ID',
    name VARCHAR(64) NOT NULL COMMENT '角色名称',
    description VARCHAR(255) NULL COMMENT '角色说明',
    built_in_type VARCHAR(32) NOT NULL COMMENT '内置角色类型',
    protected_role TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否受保护',
    status VARCHAR(32) NOT NULL COMMENT '角色状态',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_rbac_role_org_name (organization_id, name),
    KEY idx_rbac_role_org_status (organization_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='组织角色';

CREATE TABLE rbac_role_permission (
    id BIGINT NOT NULL COMMENT '角色权限关系ID',
    organization_id BIGINT NOT NULL COMMENT '组织ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    permission_id BIGINT NOT NULL COMMENT '权限ID',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_rbac_role_permission_role_permission (role_id, permission_id),
    KEY idx_rbac_role_permission_organization_id (organization_id),
    KEY idx_rbac_role_permission_permission_id (permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色权限关系';

INSERT INTO rbac_permission (id, code, name, description, status) VALUES
    (1001, 'organization:read', '查看组织', '查看组织基本信息', 'ACTIVE'),
    (1002, 'organization:manage', '管理组织', '修改组织信息和状态', 'ACTIVE'),
    (1003, 'member:read', '查看成员', '查看组织成员列表', 'ACTIVE'),
    (1004, 'member:invite', '邀请成员', '创建和停用组织邀请码', 'ACTIVE'),
    (1005, 'member:role_assign', '分配角色', '调整组织成员角色', 'ACTIVE'),
    (1006, 'role:read', '查看角色', '查看角色和权限配置', 'ACTIVE'),
    (1007, 'role:create', '创建角色', '创建组织自定义角色', 'ACTIVE'),
    (1008, 'role:update', '修改角色', '修改组织角色及其权限', 'ACTIVE'),
    (1009, 'activity:read', '查看活动', '查看组织活动及岗位', 'ACTIVE'),
    (1010, 'activity:create', '创建活动', '创建活动和岗位草稿', 'ACTIVE'),
    (1011, 'activity:publish', '发布活动', '发布满足条件的活动', 'ACTIVE'),
    (1012, 'activity:change', '变更活动', '变更已发布活动和岗位', 'ACTIVE'),
    (1013, 'activity:cancel', '取消活动', '取消组织活动', 'ACTIVE'),
    (1014, 'registration:create', '提交报名', '报名组织活动岗位', 'ACTIVE'),
    (1015, 'registration:cancel', '取消报名', '取消自己的活动报名', 'ACTIVE'),
    (1016, 'registration:review', '审核报名', '审核人工审核岗位报名', 'ACTIVE'),
    (1017, 'registration:promote', '处理递补', '创建和处理递补邀请', 'ACTIVE'),
    (1018, 'checkin:create', '活动签到', '使用有效签到码签到', 'ACTIVE'),
    (1019, 'checkin:manage', '管理签到', '开启签到和执行人工补签', 'ACTIVE'),
    (1020, 'notification:read', '查看通知', '查看自己的站内通知', 'ACTIVE'),
    (1021, 'audit:read', '查看审计', '查看组织审计日志', 'ACTIVE');
