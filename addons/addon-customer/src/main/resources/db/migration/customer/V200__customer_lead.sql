-- 客源表(addon-customer,错误码段 210000;status: 0=登记 1=已转化)
create table customer_lead (
    id          bigint not null auto_increment,
    org_id      bigint not null comment '组织(单层)',
    name        varchar(64)  not null,
    phone       varchar(32)  not null,
    source      varchar(32)  null comment '来源:advertisement/referral/walkin',
    note        varchar(512) null,
    status      tinyint      not null default 0,
    created_at  datetime     not null default current_timestamp,
    primary key (id),
    key idx_customer_lead_org (org_id)
) engine=innodb default charset=utf8mb4 comment='客源';
