-- V1203:采购单厂商引用化(外辅 outsourcing V1202 同款收口)——废「自由名称登记」,
-- 建单必传 supplier_id,经 SupplierApi 校验存在且启用(不存在 310065/停用 310066,
-- 与外辅 310058/310059 同构:缺失与停用是不同运营动作,错误信息可指导行动);
-- supplier_name 语义收窄为「厂商官方名称快照」(落单时经契约取,禁前端传入);
-- 存量自由登记行 supplier_id 为 NULL(历史单据只读保留,不回填)。
alter table inventory_purchase
    add column supplier_id bigint null comment '厂商(supplier_supplier.id,经 SupplierApi 校验;NULL=存量自由登记)',
    add key idx_pu_supplier (org_id, supplier_id);
