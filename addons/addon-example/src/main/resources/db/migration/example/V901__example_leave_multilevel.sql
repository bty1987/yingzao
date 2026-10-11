-- 请假示例多级审批(示范工程,迁移段 900+)
-- RFC §6.8 组合方式示范:多级审批 = 插件串多个审批单,内核审批单零改动。
-- 规则:days ≥ 3 为长请假,须二级审批——L1(直属审批人)通过后,插件监听 Finished 事件派生 L2(二级审批人),
-- L2 通过才算业务通过;任一级驳回/撤回即业务终态。
alter table example_leave
    add column manager_id bigint null comment '二级审批人(days≥3 长请假必填)' after assignee_id,
    add column approval_level tinyint not null default 1 comment '当前审批级(1=一级 2=二级)' after approval_id;
