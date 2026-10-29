-- V605 addon-construction:工程维工单日达成聚合表(D4 北极星,V1 唯一新建 ads 表)
-- 口径(ads-reference/01_ads_wo_task_daily.sql 照搬收敛,源表=本域 construction_schedule 节点排期快照):
--   粒度: stat_date × org_id(营造无时效档/接单概念,urgency/accept 不迁)
--   分母(due_cnt) = plan_end 落在 stat_date 当日的排期行数(节点排期快照,含竣工单——其行分子=分母无扰动)
--   分子(done_cnt) = 其中 end_time 非空且 end_time <= plan_end(当日达成)
--   done_rate = done/due 万分比(全局比例制式 ppm,A6 定案);due=0 时行仍落(done_rate=0,provider 层转 no_data)
-- 刷新: yz_plan 链式日刷(handler=construction-ads-wo-task-daily,03:00,biz_key 按日幂等)
--       REPLACE INTO 近 90 天滚动全量重刷(模式 2);批次时点落 batch_time=provider asOf 来源。
-- 校验: 某日全组织达成率应与明细对账一致——
--   SELECT stat_date, SUM(due_cnt) due, SUM(done_cnt) done FROM construction_ads_wo_task_daily
--    WHERE stat_date = CURDATE() - INTERVAL 1 DAY GROUP BY stat_date;
create table construction_ads_wo_task_daily (
    stat_date  date not null,
    org_id     bigint not null,
    due_cnt    int not null default 0 comment '应完工节点数(plan_end 落当日)',
    done_cnt   int not null default 0 comment '当日达成数(end_time<=plan_end)',
    done_rate  bigint not null default 0 comment '达成率(万分比;due=0 恒 0,provider 转 no_data)',
    batch_time datetime not null comment '批次时点(provider asOf 来源)',
    primary key (stat_date, org_id),
    key idx_ads_org (org_id, stat_date)
) engine=innodb default charset=utf8mb4 comment='工单日达成(驾驶舱工程维 ads,T+1 日刷近90天滚动)';
