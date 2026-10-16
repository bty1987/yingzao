-- V402:报价挂定材包(B 类#7 领料定材校验的定额来源唯一入口)。
-- 老口径:定额量=normood(报价项×定材×定额量)聚合;营造收敛为「报价挂定材包,
-- 包成员给定额量」(成员明细见 basedata V7002),定额量的报价项级展开留 V2。
-- 仅编辑中报价可挂/换包(requireEditable 同款守卫);空=未指定(领料闸不生效)。
alter table quotation_quotation
    add column material_package_id bigint null comment '定材包(basedata_material_package.id;领料定材校验定额来源)';
