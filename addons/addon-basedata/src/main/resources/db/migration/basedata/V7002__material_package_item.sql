-- V7002:定材包成员明细(B 类#7 领料定材校验前置件;老 normood「定材×报价项」的成员面收敛)。
-- 老模型:定材包 noods(带 surplus 浮动量)是材料主数据,normood 把「报价项×定材×定额量」
-- 展开成 5 万行;营造收敛为「定材包成员(包→商品→定额量)」单层——定额量的业务来源
-- (报价项展开)随 addon-quotation 定额子域 V2 接入,本表先承载定额量与成员关系。
-- 量纲:num 与领料 num 同为千分位(×1000),两侧直接比较不换算。
create table basedata_material_package_item (
    id         bigint not null auto_increment,
    org_id     bigint not null comment '可见组织(引用行=引用方组织)',
    package_id bigint not null comment '定材包(basedata_material_package.id)',
    goods_id   bigint not null comment '商品(basedata_goods.id,同包内唯一)',
    goods_name varchar(128) not null comment '商品名快照(展示用)',
    num        bigint not null comment '成员定额量(千分位;与领料 num 同量纲)',
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_pkg_item (package_id, goods_id),
    key idx_pkg_item_org (org_id, package_id)
) engine=innodb default charset=utf8mb4 comment='定材包成员明细(定额量)';
