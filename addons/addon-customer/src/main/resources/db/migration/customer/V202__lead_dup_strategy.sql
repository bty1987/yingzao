-- V202:客源撞单策略(B 类#1,customer+market 双缺位收口)——组织级配置 + 客源表手机号索引。
-- 老系统口径(rules/customer.md §53/market.md §46):撞单=同录单人静默合并硬编码不可配置
-- (390007/330103/330102 三异常全部注释化)【方林嫌疑】;行业共性=防撞单,策略应组织自定。
-- 策略档位 dup_strategy:0 允许重复(缺省=多渠道各自录各自的行业常见形态)/
-- 1 同手机号合并(命中未转化客源幂等回显已有 leadId,老「静默合并」的可配置化泛化)/
-- 2 同手机号拒绝(命中未转化客源报 210003,提示已存在)。
-- 只拦「未转化」客源:已转化说明客源已进后续流程,新客源属新一轮获客。
create table customer_config (
    org_id       bigint not null,
    dup_strategy tinyint not null default 0 comment '撞单策略 0允许重复/1合并回显/2拒绝',
    updated_at   datetime not null default current_timestamp on update current_timestamp,
    primary key (org_id)
) engine=innodb default charset=utf8mb4 comment='客源组织级配置(撞单策略)';

alter table customer_lead
    add key idx_lead_org_phone (org_id, phone, status);
