-- V1202:外辅采购单厂商引用化——废「自由名称登记」,挂 supplier_supplier 主数据。
-- 契约:SupplierApi(addon-supplier 实现)querySupplier/checkActive,写点校验存在且启用;
-- supplier_name 语义收窄为「厂商官方名称快照」(落单时经契约取,禁前端传入),
-- 存量自由登记行 supplier_id 为 NULL(历史单据只读保留,不再补挂)。
alter table inventory_outsourcing
    add column supplier_id bigint null comment '厂商(supplier_supplier.id,经 SupplierApi 校验;NULL=存量自由登记)',
    add key idx_os_supplier (org_id, supplier_id);
