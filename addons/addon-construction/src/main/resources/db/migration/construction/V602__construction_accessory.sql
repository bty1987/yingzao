-- 工程辅料额度台账(addon-construction V602;错误码段 240000,消费方 addon-inventory 经
-- ConstructionAccessoryApi 同步调用,修老系统 inventory 直写 construction 表 + 额度记账
-- 双侧口径漂移:consume/refund 双向均按服务端重算金额并落 append-only 流水)
--   总额闸 = construction_order.accessory_limit(哨兵 999999000=无上限,老 construction.accessoryLimit);
--   per-gid 闸 = construction_accessory_quota(老 ConstructionAccessory,cid×gid 登记行;
--     checkup=1 才启用数量闸,未登记商品不受 per-gid 限但受总额限,老宽松登记制语义保留);
--   售后领料跳过全部额度联动(老 type=1 语义保留,行业惯例:售后用料不占工程额度)。
-- MySQL 5.6 兼容。

alter table construction_order
    add column accessory_limit bigint not null default 999999000
        comment '辅料总额度(‰元;哨兵=无上限;老 constructionPO.accessoryLimit)' after accessory,
    add column accessory_consumed_total bigint not null default 0
        comment '已领总额(‰元,只进不出由 consume/refund 维护)' after accessory_limit;

-- per-gid 额度登记行(施工域自有数据;商品名快照,禁直查 basedata)
create table construction_accessory_quota (
    id             bigint not null auto_increment,
    org_id         bigint not null,
    order_id       bigint not null comment '装修单(construction_order.order_id)',
    goods_id       bigint not null comment '商品(basedata_goods.id,经 GoodsApi 校验)',
    goods_name     varchar(128) not null comment '商品名快照',
    checkup        tinyint not null default 0 comment '0不校验/1启用数量闸(老 CHECKUP_VERIFY)',
    num_limit      bigint not null default 0 comment '数量限额(千分位)',
    consumed_num   bigint not null default 0 comment '已领数量(千分位)',
    consumed_total bigint not null default 0 comment '已领金额(‰元)',
    created_at     datetime not null default current_timestamp,
    updated_at     datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_quota (org_id, order_id, goods_id)
) engine=innodb default charset=utf8mb4 comment='工程辅料 per-gid 额度登记';

-- 额度流水(append-only,审计面:consume/refund 双向留痕,修老无流水无审计)
create table construction_accessory_ledger (
    id             bigint not null auto_increment,
    org_id         bigint not null,
    order_id       bigint not null,
    requisition_id bigint not null comment '领料单(inventory_requisition.id)溯源',
    requisition_no varchar(32) not null comment '领料单号快照',
    action         varchar(16) not null comment 'consume 领用 / refund 回退(协议键)',
    goods_id       bigint not null,
    goods_name     varchar(128) not null,
    num            bigint not null comment '±数量(千分位,refund 为负)',
    total          bigint not null comment '±金额(‰元,refund 为负)',
    operator_id    bigint not null,
    operator_name  varchar(64) not null,
    created_at     datetime not null default current_timestamp,
    primary key (id),
    key idx_ledger_order (org_id, order_id),
    key idx_ledger_requisition (org_id, requisition_id)
) engine=innodb default charset=utf8mb4 comment='工程辅料额度流水(append-only)';
