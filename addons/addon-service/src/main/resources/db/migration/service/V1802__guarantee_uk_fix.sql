-- 保修登记 uk 修正(第 89 次:作废后可重登语义与全行 uk 冲突)
-- V1801 uk_guarantee_order(org_id, order_id) 不含 status——作废行也占名额,
-- 与「作废后可重新登记」(PRD:查重只看有效行)冲突(DuplicateKeyException 实证);
-- 修 = uk 收窄为 (org_id, order_id, status):有效行(status=0)同单唯一,作废行(status=1)不占名额;
-- 极端场景(同单两轮作废)两条 status=1 撞 uk——概率极低,真发生走 V2 软删列方案;
-- 应用层查重已按 status=0(GuaranteeService.create),语义不变。回滚:逆操作重建原 uk。
alter table service_guarantee drop index uk_guarantee_order;
alter table service_guarantee add unique key uk_guarantee_order (org_id, order_id, status);
