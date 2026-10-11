-- 基础数据 V1 真表(addon-basedata,错误码段 260000;迁移段 700+)
-- 设计口径:docs/rules/material.md §6-§7/§13 + docs/prd/addon-basedata.md 商品档案章节
--   查重全部下沉 DB 唯一约束(修老系统 goods no/barcode 全程无查重/addFormat 无查重/price 无查重的
--   check-then-act 竞态);品牌名快照双存(bid+brand)不迁,砍四级定长品类挂列(zerokind~thrkind)改单表树;
--   金额口径 = 厘(×1000 定点整数);倍率 = 千分位(1000 = 1.0);
--   表前缀 basedata_(军规 3);MySQL 5.6 兼容:索引列宽 ≤767 字节。

-- 品类单表树(平台共享 org_id=0 + 组织私有两层;根节点 parent_id=0 而非 NULL——
-- MySQL 唯一索引对 NULL 不生效,用 0 保证「同组织同父同名」约束对根品类同样成立)
create table basedata_category (
    id         bigint not null auto_increment,
    org_id     bigint not null comment '组织(0=平台共享)',
    parent_id  bigint not null default 0 comment '父品类(0=根)',
    name       varchar(64) not null,
    level      int not null default 1 comment '层级(1=根)',
    status     tinyint not null default 0 comment '0启用 1停用',
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_category (org_id, parent_id, name),
    key idx_category_org (org_id, status)
) engine=innodb default charset=utf8mb4 comment='品类树';

-- 品牌(行业公共字典,无组织维度;老 BrandPO 的供应商/代理商关系归 addon-supplier 不迁)
create table basedata_brand (
    id         bigint not null auto_increment,
    no         varchar(16) not null comment '品牌编号,建档后按 id 生成(B+6位)',
    name       varchar(64) not null,
    pinyin     varchar(128) null comment '拼音检索码(调用方传入,统一格式)',
    status     tinyint not null default 0 comment '0启用 1停用',
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_brand_no (no),
    unique key uk_brand_name (name)
) engine=innodb default charset=utf8mb4 comment='品牌字典';

-- 商品主档(no/barcode/name 三路组织内唯一;条码可空——MySQL 唯一索引不约束 NULL,无条码合法;
-- unit_rate 语义化老 pieceNum:销售单位与基础单位换率,千分位定点,1000=1.0)
create table basedata_goods (
    id          bigint not null auto_increment,
    org_id      bigint not null,
    no          varchar(24) not null comment '商品编号,建档后按 id 生成(G+8位)',
    name        varchar(128) not null,
    alias       varchar(64) null,
    pinyin      varchar(256) null comment '拼音检索码(调用方传入,统一格式)',
    barcode     varchar(64) null,
    category_id bigint not null comment '品类(basedata_category.id)',
    brand_id    bigint null comment '品牌(basedata_brand.id,可空)',
    unit        varchar(16) not null comment '计量单位',
    type        tinyint not null default 0 comment '0商品 1套餐(建档后不可改)',
    purchase    bigint not null default 0 comment '参考进价(厘)',
    selling     bigint not null default 0 comment '标准售价(厘)',
    confine     bigint null comment '限价(厘),NULL=不限价',
    unit_rate   int not null default 1000 comment '销售倍率(千分位,1000=1.0;语义化老 pieceNum×1000)',
    status      tinyint not null default 0 comment '0启用 1停用',
    created_by  bigint null comment '建档人(yz_user.id)',
    created_at  datetime not null default current_timestamp,
    updated_at  datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_goods_no (org_id, no),
    unique key uk_goods_name (org_id, name),
    unique key uk_goods_barcode (org_id, barcode),
    key idx_goods_org_status (org_id, status),
    key idx_goods_category (category_id),
    key idx_goods_brand (brand_id)
) engine=innodb default charset=utf8mb4 comment='商品主档';

-- 商品 SKU 属性值(老 color/format 两表同构合并;统一查重——修 addFormat 无查重缺陷)
create table basedata_goods_sku_attr (
    id         bigint not null auto_increment,
    org_id     bigint not null,
    goods_id   bigint not null comment '商品(basedata_goods.id)',
    attr_type  tinyint not null comment '0颜色 1规格',
    value      varchar(128) not null comment '属性值文本',
    status     tinyint not null default 0 comment '0启用 1停用',
    created_at datetime not null default current_timestamp,
    primary key (id),
    unique key uk_sku_attr (goods_id, attr_type, value),
    key idx_sku_org (org_id, goods_id)
) engine=innodb default charset=utf8mb4 comment='商品 SKU 属性';

-- 多价格体系(等级价/渠道价;补老模型缺失的时间有效性;结算类型/值与 addon-sales 枚举对齐)
create table basedata_goods_price (
    id         bigint not null auto_increment,
    org_id     bigint not null,
    goods_id   bigint not null comment '商品(basedata_goods.id)',
    name       varchar(64) not null comment '价格体系名(如 一级价/渠道价)',
    purchase   bigint null comment '进价(厘)',
    selling    bigint null comment '售价(厘)',
    confine    bigint null comment '限价(厘),NULL=不限',
    ctype      tinyint null comment '结算类型(随 addon-sales 结算枚举,NULL=无)',
    cvalue     bigint null comment '结算值(厘或点位,量纲随 ctype)',
    begin_time date null comment '生效日(NULL=立即)',
    end_time   date null comment '失效日(NULL=长期)',
    status     tinyint not null default 0 comment '0启用 1停用',
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_price (goods_id, name),
    key idx_price_org (org_id, goods_id)
) engine=innodb default charset=utf8mb4 comment='商品多价格体系';

-- 商品套餐成员(老 gpid 语义正名:package_goods_id=套餐主商品;软删替代物理删)
create table basedata_goods_package (
    id               bigint not null auto_increment,
    org_id           bigint not null,
    package_goods_id bigint not null comment '套餐主商品(type=1)id(老 pid 注释与语义相反,正名)',
    member_goods_id  bigint not null comment '成员商品(type=0)id',
    num              int not null default 1 comment '成员数量',
    status           tinyint not null default 0 comment '0启用 1停用(停用=软删,替代老物理删)',
    created_at       datetime not null default current_timestamp,
    primary key (id),
    unique key uk_package_member (package_goods_id, member_goods_id),
    key idx_package_org (org_id)
) engine=innodb default charset=utf8mb4 comment='商品套餐成员';

-- 主材型号库(独立价目模型;老系统与 goods 零外键,补 goods_id 可空关联;
-- calcType 0=底价/1=点位的点位计算口径随 addon-sales 校准,V1 只存值)
create table basedata_material_model (
    id         bigint not null auto_increment,
    org_id     bigint not null,
    no         varchar(24) not null comment '型号编号,建档后按 id 生成(M+8位)',
    name       varchar(128) not null,
    goods_id   bigint null comment '关联商品(basedata_goods.id,NULL=独立型号)',
    brand_id   bigint null comment '品牌(basedata_brand.id)',
    unit       varchar(16) null comment '计量单位',
    price      bigint not null default 0 comment '单价(厘)',
    purchase   bigint null comment '进价(厘)',
    discount   int null comment '折扣(千分位,1000=原价)',
    calc_type  tinyint not null default 0 comment '0底价 1点位',
    base_value bigint null comment '底价(厘,calc_type=0 时用)',
    point_value int null comment '点位(千分位,calc_type=1 时用)',
    status     tinyint not null default 0 comment '0启用 1停用',
    created_by bigint null,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_model_no (org_id, no),
    key idx_model_org_status (org_id, status),
    key idx_model_brand (brand_id),
    key idx_model_goods (goods_id)
) engine=innodb default charset=utf8mb4 comment='主材型号库';

-- 整装定材包(老 noods;两级引用:pnid=0 自建主数据行 / >0 引用行存快照、仅浮动量可改;
-- 老 company=0 即集团行的隐式约定改显式 owner_org_id=0 平台级;包成员明细挂报价项,归 addon-quotation 写侧)
create table basedata_material_package (
    id           bigint not null auto_increment,
    pnid         bigint not null default 0 comment '上级定材包:0=自建主数据行,>0=引用行(指向主数据 id)',
    owner_org_id bigint not null comment '归属组织(0=平台级主数据;主数据行=建单组织,引用行=主数据归属)',
    org_id       bigint not null comment '可见组织(主数据行=owner_org_id,引用行=引用方组织)',
    name         varchar(128) not null,
    selling      bigint not null default 0 comment '包销售价(厘)',
    balance      bigint not null default 0 comment '包结算额(厘)',
    surplus      bigint not null default 0 comment '浮动量(厘;引用行唯一可改字段)',
    calc         tinyint not null default 0 comment '计算方式(老 calc 透传,口径随 addon-quotation 校准)',
    status       tinyint not null default 0 comment '0启用 1停用',
    created_by   bigint null,
    created_at   datetime not null default current_timestamp,
    updated_at   datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    key idx_mpackage_org (org_id, status),
    key idx_mpackage_pnid (pnid)
) engine=innodb default charset=utf8mb4 comment='整装定材包';
