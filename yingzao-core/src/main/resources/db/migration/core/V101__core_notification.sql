-- 站内通知(内核 100-199 版本段)
-- 内核平台设施,零业务知识:插件监听领域事件后调用 NotificationService 落通知,
-- source_addon/source_type 仅作溯源展示,内核不解释其含义。
create table yz_notification (
    id           bigint        not null auto_increment,
    org_id       bigint        not null comment '组织(单层)',
    user_id      bigint        not null comment '收件人',
    title        varchar(200)  not null,
    content      varchar(1000),
    source_addon varchar(50)   not null comment '来源插件标识',
    source_type  varchar(50)   not null comment '业务事件类型(如 lead.assigned)',
    source_id    varchar(64)            comment '业务主键(字符串透传)',
    read_flag    tinyint       not null default 0 comment '0未读 1已读',
    created_at   datetime      not null default current_timestamp,
    primary key (id),
    key idx_yz_notification_user (user_id, read_flag, id)
) engine=innodb default charset=utf8mb4 comment='站内通知';
