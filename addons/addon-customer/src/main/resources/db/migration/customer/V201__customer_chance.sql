-- 客源机会表(addon-customer V2,错误码段 210000;status: 0=已分配 3=已确认 4=已完成)
-- 每客源生命周期内唯一机会(uk lead_id);释放/重分配归 V3 公海规则
create table customer_chance (
    id           bigint not null auto_increment,
    org_id       bigint not null comment '组织(单层)',
    lead_id      bigint not null comment '客源(customer_lead.id)',
    assignee_id  bigint not null comment '责任人(yz_user.id)',
    status       tinyint     not null default 0,
    assigned_at  datetime    not null default current_timestamp,
    confirmed_at datetime    null,
    completed_at datetime    null,
    primary key (id),
    unique key uk_customer_chance_lead (lead_id),
    key idx_customer_chance_org (org_id),
    key idx_customer_chance_assignee (assignee_id)
) engine=innodb default charset=utf8mb4 comment='客源机会';
