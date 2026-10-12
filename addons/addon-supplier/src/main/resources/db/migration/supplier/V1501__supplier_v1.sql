-- 厂商主数据 V1 真表(addon-supplier,错误码段 340000;迁移段 1500+,customerprofile 1400+ 之后)
-- 设计口径:docs/rules/supplier.md + docs/prd/addon-supplier.md
--   厂商=独立主数据实体(废老系统"厂商即用户"user.type=1 寄生);账号是账号,档案是档案;
--   查重全部下沉 DB 唯一键(修老 addSupplier 应用层零查重):name org 内 / ext_no 全局 / creditCode org 内可空唯一
--     (MySQL 唯一索引不约束 NULL,无信用代码合法);
--   ext_no = 对外系统编号一等公民(老 oldUserId 语义,金蝶/外部门户/外部同步统一走它);
--   编号 no = 建档后按 id 生成(S+8位,无计数器读改写竞态;老 counting type=47 号段不迁);
--   停用=不可新挂单,存量单不受影响(老借员工离职语义不迁);无物理删(消费面外辅/结算/选材全挂);
--   结算条款三层(老合同七件套"五从表"收敛):品牌级(底价/点位)→品类细化→商品供货价/结算价;
--     条款与合同签署流显式分实体(老七件套混装不迁,电子签 Provider 扩展点排期 V2+);
--   金额量纲:底价/违约金/供货价/结算价一律厘(全域红线);点位=万分比(1% = 100);
--   表前缀 supplier_(军规 3 插件自有表);MySQL 5.6 兼容:索引列宽 ≤767 字节。

-- 厂商主数据
create table supplier_supplier (
    id          bigint not null auto_increment,
    org_id      bigint not null,
    no          varchar(16) not null comment '厂商编号,建档后按 id 生成(S+8位)',
    name        varchar(64) not null comment '厂商简称(组织内唯一)',
    full_name   varchar(128) null comment '企业全称',
    contact     varchar(32) null comment '联系人',
    phone       varchar(32) null comment '联系电话',
    address     varchar(256) null,
    credit_code varchar(32) null comment '统一社会信用代码(可空,唯一索引允许多 NULL)',
    ext_no      varchar(64) null comment '外部系统编号(全局唯一,SRM/金蝶类同步幂等键)',
    status      tinyint not null default 0 comment '0启用 1停用(停用=拒新挂单,存量不受影响)',
    remark      varchar(255) null,
    created_by  bigint null comment '建档人(yz_user.id)',
    created_at  datetime not null default current_timestamp,
    updated_at  datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_supplier_no (no),
    unique key uk_supplier_org_name (org_id, name),
    unique key uk_supplier_ext (ext_no),
    unique key uk_supplier_credit (org_id, credit_code),
    key idx_supplier_org_status (org_id, status)
) engine=innodb default charset=utf8mb4 comment='厂商主数据';

-- 品牌×厂商多对多(is_primary 直属位:每品牌至多一个,服务层事务切换;修老 brand.ocuid 单列与关联表双轨并存)
create table supplier_brand (
    id          bigint not null auto_increment,
    org_id      bigint not null,
    supplier_id bigint not null comment '厂商(supplier_supplier.id)',
    brand_id    bigint not null comment '品牌(basedata_brand.id,全局公共字典)',
    is_primary  tinyint not null default 0 comment '1=直属厂商(每品牌至多一个,切换语义)',
    created_at  datetime not null default current_timestamp,
    primary key (id),
    unique key uk_sbrand (org_id, supplier_id, brand_id),
    key idx_sbrand_brand (brand_id)
) engine=innodb default charset=utf8mb4 comment='厂商品牌关联';

-- 品牌级结算条款(底价/点位;老合同从表 supplierContractBrand 收敛独立实体)
create table supplier_term_brand (
    id               bigint not null auto_increment,
    org_id           bigint not null,
    supplier_id      bigint not null comment '厂商(supplier_supplier.id)',
    brand_id         bigint not null comment '品牌(basedata_brand.id)',
    settle_type      tinyint not null default 0 comment '0底价 1点位',
    settle_value     bigint not null comment '底价:厘;点位:万分比(1% = 100)',
    penalty          bigint null comment '违约金(厘)',
    warranty_months  int null comment '质保期(月)',
    service_fee_json varchar(512) null comment '服务费 JSON(自由结构快照,老 supplierContractBrand.servicePrice JSON 语义)',
    created_at       datetime not null default current_timestamp,
    updated_at       datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_sterm_brand (org_id, supplier_id, brand_id),
    key idx_sterm_supplier (supplier_id)
) engine=innodb default charset=utf8mb4 comment='品牌级结算条款';

-- 品类级结算细化(老 supplierContractCategory 收敛;挂品牌条款行,品类引用 basedata 品类树)
create table supplier_term_category (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    term_brand_id bigint not null comment '品牌条款(supplier_term_brand.id)',
    category_id   bigint not null comment '品类(basedata_category.id)',
    settle_type   tinyint not null default 0 comment '0底价 1点位(细化覆盖品牌级)',
    settle_value  bigint not null comment '底价:厘;点位:万分比',
    created_at    datetime not null default current_timestamp,
    primary key (id),
    unique key uk_sterm_cat (term_brand_id, category_id)
) engine=innodb default charset=utf8mb4 comment='品类级结算细化';

-- 商品级供货价/结算价(老 suppliercontractgoods 收敛;商品须经 GoodsApi 校验品牌归属)
create table supplier_term_goods (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    term_brand_id bigint not null comment '品牌条款(supplier_term_brand.id)',
    goods_id      bigint not null comment '商品(basedata_goods.id)',
    price         bigint not null comment '供货价(厘)',
    balance       bigint not null comment '结算价(厘)',
    created_at    datetime not null default current_timestamp,
    primary key (id),
    unique key uk_sterm_goods (term_brand_id, goods_id)
) engine=innodb default charset=utf8mb4 comment='商品级供货价/结算价';
