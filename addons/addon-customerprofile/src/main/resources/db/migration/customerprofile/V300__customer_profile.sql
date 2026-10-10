-- 客户档案表(addon-customerprofile,错误码段 220000;由客源转化事件自动建档,lead_id 唯一保证幂等)
create table customer_profile (
    id          bigint not null auto_increment,
    org_id      bigint not null comment '组织(单层)',
    lead_id     bigint not null comment '来源客源 id',
    name        varchar(64)  not null,
    phone       varchar(32)  not null,
    created_at  datetime     not null default current_timestamp,
    primary key (id),
    unique key uk_customer_profile_lead (lead_id),
    key idx_customer_profile_org (org_id)
) engine=innodb default charset=utf8mb4 comment='客户档案';
