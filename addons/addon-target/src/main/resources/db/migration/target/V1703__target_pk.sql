-- V1703:PK 对赌子域(addon-target V2,docs/prd/addon-target-pk.md D3 按推荐拍板,第 102 次实现)——
-- fl-yy PerformancePk 域 13 表收敛为 3 表:赛事/参赛单元(模板+锁档合一,uk upsert 修老「全删重插
-- stage.id 不稳定」)/归档快照(append-only+SHA256 防口径后漂移);
-- 金额一律厘;档位码即系数(2 保底/3 冲刺/4 突破/5 挑战,锁档奖金 = pk_money × 档位码,fl-yy 实证语义);
-- 状态机五态 0 筹备→1 对赌中(首次锁档翻态)→2 进行中(confirm)→3 已归档(快照冻结,终态)+4 已作废
-- (0/1 可废留痕,修老 delete/reset 无守卫物理删);归档走 yz_plan 定时任务(target-pk-archive,biz_key 按日幂等)。
create table target_pk_event (
    id             bigint not null auto_increment,
    org_id         bigint not null comment '组织',
    cycle_type     tinyint not null comment '1 月赛 / 2 季赛',
    period         varchar(8) not null comment '周期键:月=yyyy-MM,季=yyyy-Qn',
    name           varchar(100) not null comment '赛事名',
    start_date     date not null comment '起始日(含)',
    end_date       date not null comment '截止日(含)',
    status         tinyint not null default 0 comment '0 筹备/1 对赌中/2 进行中/3 已归档/4 已作废',
    base_tier      tinyint null comment '缺省基准档(2-5,锁档默认选中;null=不预设)',
    tier_increment int null comment '超档递增系数(万分比,>5 档扩展预留,V1 不启用)',
    void_reason    varchar(200) null comment '作废原因(4 态留痕)',
    draft_data     text null comment '整包模板草稿 JSON(发布落行即清,修老草稿无身份/状态校验)',
    created_by     bigint null,
    created_at     datetime not null default current_timestamp,
    updated_at     datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_pk_event (org_id, cycle_type, period),
    key idx_pk_event_org (org_id, status)
) engine=innodb default charset=utf8mb4 comment='PK 对赌赛事(D3,fl-yy 13 表收敛 3 表之赛事)';

create table target_pk_unit (
    id                  bigint not null auto_increment,
    event_id            bigint not null comment '赛事(target_pk_event.id)',
    org_id              bigint not null comment '组织',
    org_node            bigint not null comment '参赛单元=组织树节点(organization.id,修老 LIKE %staffCode% 模糊匹配)',
    staffs              varchar(500) null comment '参赛人(JSON 字符串数组,展示用)',
    guarantee           bigint not null comment '保底目标(厘,档位码 2)',
    base                bigint not null comment '冲刺目标(厘,档位码 3)',
    breakthrough        bigint not null comment '突破目标(厘,档位码 4)',
    challenge           bigint not null comment '挑战目标(厘,档位码 5)',
    pk_money            bigint not null comment '单档对赌金(厘;锁档奖金=pk_money×档位码)',
    bonus_preset        bigint null comment '预设自定义奖金(厘,可空)',
    manual_enabled      tinyint not null default 0 comment '手工实绩开关(1=可录,source=2 单源)',
    manual_performance  bigint null comment '手工实绩(厘,快照时启用且非空则覆盖自动口径)',
    manual_remark       varchar(200) null comment '手工实绩说明',
    selected_tier       tinyint null comment '已锁档位 2/3/4/5(null=未锁)',
    target_amount       bigint null comment '锁定目标(厘=所选档直配值或自定义)',
    bonus_amount        bigint null comment '锁定奖金(厘=pk_money×档位码或自定义)',
    custom_target       bigint null comment '自定义目标(厘,选档锁档时清空)',
    custom_bonus        bigint null comment '自定义奖金(厘,选档锁档时清空)',
    lock_by             bigint null comment '锁档人',
    lock_at             datetime null comment '锁档时间',
    created_at          datetime not null default current_timestamp,
    updated_at          datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_pk_unit (event_id, org_node),
    key idx_pk_unit_event (event_id)
) engine=innodb default charset=utf8mb4 comment='PK 参赛单元(D3,模板+锁档合一 upsert,修老模板全删重插+锁档另表)';

create table target_pk_snapshot (
    id                 bigint not null auto_increment,
    event_id           bigint not null comment '赛事(target_pk_event.id)',
    org_node           bigint not null comment '参赛单元节点',
    target_amount      bigint not null comment '锁定目标(厘)',
    final_amount       bigint not null comment '最终实绩(厘;source 单源:手工启用且非空取手工,否则自动聚合)',
    performance_source tinyint not null comment '1 自动聚合 / 2 手工录入(修老 manual 与 BI 叠加靠自律)',
    final_data         text not null comment '全景明细定格 JSON(目标/奖金/实绩/达成率)',
    snapshot_hash      char(64) not null comment 'SHA256(final_data),防口径后漂移',
    created_at         datetime not null default current_timestamp,
    primary key (id),
    unique key uk_pk_snapshot (event_id, org_node),
    key idx_pk_snapshot_event (event_id)
) engine=innodb default charset=utf8mb4 comment='PK 归档快照(D3,append-only+SHA256;归档后一律读快照)';

-- 守卫约定(服务层,DDL 无法表达):
--   承诺额/奖金池 = 查询时对已锁单元聚合,不落 event 列(修老全景读接口回写 event 的读写副作用);
--   归档 = yz_plan 定时任务(target-pk-archive,biz_key 按日幂等+死亡死信+重启对账),
--   快照时点=归档时点,零宽限歧义(修老「归档后次月 2 号前宽限窗」);
--   >5 档扩展(递增公式)与 legacy 档位公式(保底=冲刺×0.9 等)不迁,四档直配置代;
--   dept_group 跨赛分组不迁,复用=「从上一届复制赛事(含单元模板)」。
