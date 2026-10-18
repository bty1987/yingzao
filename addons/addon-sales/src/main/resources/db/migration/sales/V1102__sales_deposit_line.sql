-- V1102:销售定金款线(第 65 次,PRD 款项轴 V2 兑现)——
-- 老系统 depositpayStatus/fullpayStatus 双轴的正统形态回归:开单可约定定金(deposit_amount),
-- 录款时拆 sales_deposit(定金)+ sales_full(尾款=总额-定金)两条款项单;
-- deposit_amount=0 时行为与 V1 完全一致(单全款线),零迁移成本。
-- 协议键 sales_deposit 登记于 docs/rules/payment.md §15(deposit 已归装修定金线,不得复用)。
alter table sales_order add column deposit_amount bigint not null default 0
    comment '定金金额(厘;0=不拆线,单全款线)' after total;
alter table sales_order add column deposit_status tinyint not null default -1
    comment '定金轴:-1未录/0已录/1已付/2已确认(deposit_amount=0 恒 -1)' after fullpay_status;
