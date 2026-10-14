-- V3601:C 端验证码表(手机验证码登录;匿名发码,登录消费)。
-- 限流口径:同手机号 60s 内一条(查最近 created_at)+ 单日 ≤10 条(按 created_at 计数);
-- 验证码 6 位,5 分钟有效,单次消费(used_at)。
create table portal_sms_code (
    id          bigint not null auto_increment,
    org_id      bigint not null,
    mobile      varchar(20) not null,
    code        varchar(10) not null,
    expire_at   datetime not null,
    used_at     datetime null,
    created_at  datetime not null default current_timestamp,
    primary key (id),
    key idx_portal_code (org_id, mobile, created_at)
) engine=innodb default charset=utf8mb4 comment='C端登录验证码';
