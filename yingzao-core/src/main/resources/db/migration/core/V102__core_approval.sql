-- 轻量审批(内核 100-199 版本段)
-- 内核平台设施,零业务知识:插件提交审批单时携带 biz_addon/biz_type/biz_id 溯源三元组,
-- 内核不解释其含义;办结结果经 ApprovalFinishedEvent 回插件,由插件回写自身业务状态。
create table yz_approval (
    id           bigint       not null auto_increment,
    org_id       bigint       not null comment '组织(单层)',
    title        varchar(200) not null,
    biz_addon    varchar(50)  not null comment '提交方插件标识',
    biz_type     varchar(50)  not null comment '业务类型(如 decoration.transition)',
    biz_id       varchar(64)  not null comment '业务主键(字符串透传)',
    applicant_id bigint       not null comment '发起人',
    assignee_id  bigint       not null comment '当前处理人(转交即改写,状态不变)',
    status       tinyint      not null default 0 comment '0审批中 1通过 2驳回 3撤回',
    created_at   datetime     not null default current_timestamp,
    finished_at  datetime              comment '办结时间(通过/驳回/撤回)',
    primary key (id),
    key idx_yz_approval_assignee (assignee_id, status, id),
    key idx_yz_approval_applicant (applicant_id, status, id),
    key idx_yz_approval_biz (biz_addon, biz_type, biz_id)
) engine=innodb default charset=utf8mb4 comment='轻量审批单';

-- 审批流水:每次动作一行,append-only,审计口径
create table yz_approval_record (
    id          bigint      not null auto_increment,
    approval_id bigint      not null,
    action      tinyint     not null comment '1提交 2通过 3驳回 4转交 5撤回',
    operator_id bigint      not null,
    assignee_id bigint               comment '转交时=新处理人',
    comment     varchar(500),
    created_at  datetime    not null default current_timestamp,
    primary key (id),
    key idx_yz_approval_record_approval (approval_id, id)
) engine=innodb default charset=utf8mb4 comment='审批流水';
