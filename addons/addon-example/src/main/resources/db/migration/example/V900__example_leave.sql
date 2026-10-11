-- 请假示例表(addon-example 示范工程,错误码段 290000;迁移段 900+,与业务 addon 段 200-600 保持距离)
-- 本 addon 演示「业务插件 + 内核轻量审批」标准组合:同事务建业务单+审批单,
-- 办结结果经 ApprovalFinishedEvent 回写 status(0审批中 1已通过 2已驳回 3已撤回)。
create table example_leave (
    id           bigint       not null auto_increment,
    org_id       bigint       not null comment '组织(单层)',
    applicant_id bigint       not null comment '申请人(yz_user.id)',
    assignee_id  bigint       not null comment '审批人(yz_user.id)',
    approval_id  bigint                comment '内核审批单(yz_approval.id,提交后回填)',
    reason       varchar(500) not null comment '请假事由',
    days         int          not null comment '请假天数(天)',
    status       tinyint      not null default 0 comment '0审批中 1已通过 2已驳回 3已撤回',
    created_at   datetime     not null default current_timestamp,
    finished_at  datetime              comment '办结时间(通过/驳回/撤回)',
    primary key (id),
    key idx_example_leave_applicant (applicant_id, id),
    key idx_example_leave_org (org_id)
) engine=innodb default charset=utf8mb4 comment='请假示例(示范工程)';
