-- V1205:领料审核线 V1.1(docs/11 A2,第 82 次)——
-- 建单带 assigneeId 即走内核轻量审批:status=7 审核中(通过回 0 初始可预出/出库,驳回置 4+对称 refund 额度);
-- 不带 assigneeId=现行为零变化。老 5 死状态/7 无守卫态不迁的注释兑现为显式审核线。
alter table inventory_requisition add column approval_id bigint null
    comment '领料审批单(yz_approval.id;status=7 审核中,通过回 0/驳回置 4)'
    after close_at;
