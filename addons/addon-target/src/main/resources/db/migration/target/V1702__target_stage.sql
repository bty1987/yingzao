-- V1702:阶段目标(addon-target V1.2,第 76 次;docs/07 BattleBoardTargetStage 模型)——
-- 把"月"切成时间阶段分别立标,适配装企"月初淡、月末冲"节律;
-- 阶段挂在月目标(target_plan)之下,月目标独立于阶段目标(两套并行各自达成率,docs/07 定案);
-- 实绩口径与父目标一致:阶段日期区间内的签约拆分款应收合计(经 DecorationApi 契约,按日切片)。
create table target_stage (
    id         bigint not null auto_increment,
    plan_id    bigint not null comment '所属月目标(target_plan.id)',
    org_id     bigint not null comment '登记组织',
    stage_key  varchar(32) not null comment '阶段标识(如 月上旬/月末冲刺,自由命名)',
    sort_no    int not null default 1 comment '顺序',
    begin_day  date not null comment '起始日(含)',
    end_day    date not null comment '截止日(含)',
    amount     bigint not null comment '阶段目标额(厘;渠道线=条)',
    remark     varchar(200) null,
    status     tinyint not null default 0 comment '0 启用 / 1 停用',
    created_by bigint null,
    created_at datetime not null default current_timestamp,
    primary key (id),
    unique key uk_stage (plan_id, stage_key),
    key idx_stage_plan (plan_id)
) engine=innodb default charset=utf8mb4 comment='阶段目标(docs/07 阶段模型:月内切阶段分别立标)';

-- 守卫约定(服务层,DDL 无法表达):
--   起止日必须落在父目标的 ym 月份内;同 plan 内 stage_key 唯一(uk);区间重叠允许
--   (fl-yy 语义:阶段可并列考核不同业绩线,单线同月重叠=录入错误,服务层对同 plan+同父 line 重叠拒绝);
--   阶段目标额合计不做强制=月目标(允许留空隙/超额,业务自定),达成看板分列展示。
