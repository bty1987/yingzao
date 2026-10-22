-- V1701:经营目标(addon-target V1,docs/08 S3 首个消费方;码段 370000;target 1700+ 版本段)——
-- 目标挂组织树上任一节点(公司/分区/部门),达成率 = 节点子树实绩 ÷ 节点目标;
-- V1 业绩线仅 contract 签约额(实绩经 DecorationApi 契约聚合,禁直查他域表);
-- 调整留痕(docs/07 提交记录模型):月中改目标强制写 old→new,解决争议。
create table target_plan (
    id         bigint not null auto_increment,
    org_id     bigint not null comment '登记组织',
    org_node   bigint not null comment '目标节点(organization.id,树上任一节点)',
    ym             char(7) not null comment '目标月 yyyy-MM',
    line       varchar(24) not null default 'contract' comment '业绩线(V1=contract 签约额)',
    amount     bigint not null comment '目标额(厘)',
    status     tinyint not null default 0 comment '0 启用 / 1 停用',
    remark     varchar(200) null,
    created_by bigint null,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_target (org_id, org_node, ym, line),
    key idx_target_node (org_node, ym)
) engine=innodb default charset=utf8mb4 comment='经营目标计划(docs/07 月目标模型)';

create table target_submission (
    id         bigint not null auto_increment,
    plan_id    bigint not null comment '目标(target_plan.id)',
    old_amount bigint not null comment '调整前(厘)',
    new_amount bigint not null comment '调整后(厘)',
    remark     varchar(200) null,
    created_by bigint null,
    created_at datetime not null default current_timestamp,
    primary key (id),
    key idx_sub_plan (plan_id)
) engine=innodb default charset=utf8mb4 comment='目标调整留痕(docs/07 提交记录模型)';
