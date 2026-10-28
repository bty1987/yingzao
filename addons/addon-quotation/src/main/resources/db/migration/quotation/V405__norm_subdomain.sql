-- V405 定额子域(addon-quotation,docs/prd/addon-quotation-norm.md 第 90 次 PRD,
-- 2026-10-28 用户拍板「按推荐」七项:quotation 子域/单表+kind 位/工时进/套餐进/
-- 聚合收口「确认即计入追加手工同权」/千分位÷1000 HALF_UP/待审徽章事件回写)——
-- 档案层=组织级主数据(挂 V404 报价项库 catalog_item_id):材料/人工/工时单表+kind 位
-- (老 data 库 Normood/Normrice/Normtime 三表收敛)+附加项档案(老 Normadd)+材料定额套餐
-- (老 Normpack/NormpackItem);实例层=一单一头+实例单表收敛老三张实例表(五分表收敛先例):
-- source_type 四分 0 报价/1 拓展预留/2 增减/3 附加;status 四态对齐老人工确认线全族唯一
-- (0 初始/1 禁用/2 确认/3 驳回——材料工时老两态 0/1 用前两位,附加行用 0/2/3);
-- 🔴附加行 uk 自引用解题(核对清单 5):重算复用锚只覆盖 0/2 来源(archive_id 非空),
-- 附加手工行 archive_id=null(MySQL uk 多 NULL 不防重,天然排除)、套餐成员行 archive_id=0
-- +pack_item_id 行级区分——uk(org,quotation,source_type,source_id,kind,archive_id,pack_item_id)
-- 三形态互不撞;冗余合计 moodTotal/mriceTotal 不落库(查询期聚合);confirmer/confirm_time 原生带
-- (老 2026-10 生产补列,营造不欠账);MySQL 5.6 兼容。

-- 档案:定额主数据(kind 0 材料/1 人工/2 工时;挂报价项库)
create table quotation_norm_archive (
    id                   bigint not null auto_increment,
    org_id               bigint not null,
    kind                 tinyint not null comment '0 材料 / 1 人工 / 2 工时',
    catalog_item_id      bigint not null comment '报价项库(quotation_item.id,V404)',
    catalog_name         varchar(128) not null comment '报价项名快照',
    name                 varchar(128) not null comment '定额名(材料名/工种名)',
    -- 材料列(kind=0;定材必填)
    goods_id             bigint null comment '商品(basedata_goods.id;材料必填,231204)',
    goods_name           varchar(128) null,
    norm_unit            varchar(16)  null comment '定额单位',
    norm_num             bigint null default 0 comment '每单位用量(千分位)',
    store_unit           varchar(16)  null comment '库房单位',
    store_num            bigint null default 0 comment '库房用量(千分位)',
    -- 人工/工时列(kind=1/2)
    construction_node_id bigint null comment '施工节点 id(快照挂接,存 id+名称不 join 他域)',
    node_name            varchar(64)  null,
    unit                 varchar(16)  null comment '计量单位',
    trannum              bigint not null default 1000 comment '转换系数(千分位,1000=1.0)',
    salary               bigint null default 0 comment '单价(厘,人工)',
    workdays             bigint null default 0 comment '工日(千分位,1000=1 工日,工时)',
    status               tinyint not null default 0 comment '0 启用 / 1 停用',
    created_at           datetime not null default current_timestamp,
    primary key (id),
    key idx_qna_org (org_id, kind, catalog_item_id)
) engine=innodb default charset=utf8mb4 comment='定额档案(单表+kind 位)';

-- 附加项档案(公司级模板:登记附加项时选模板带出或全手工;老 Normadd)
create table quotation_norm_addition (
    id                   bigint not null auto_increment,
    org_id               bigint not null,
    name                 varchar(128) not null comment '附加项名(如「砸墙」「垃圾清运」)',
    construction_node_id bigint not null comment '施工节点',
    node_name            varchar(64)  not null,
    unit                 varchar(16)  null,
    price                bigint not null default 0 comment '单价(厘)',
    status               tinyint not null default 0 comment '0 启用 / 1 停用',
    created_at           datetime not null default current_timestamp,
    primary key (id),
    key idx_qnad_org (org_id, status)
) engine=innodb default charset=utf8mb4 comment='附加项档案';

-- 材料定额套餐 + 成员(老 Normpack/NormpackItem;实例展开按包成员一次展开)
create table quotation_norm_pack (
    id         bigint not null auto_increment,
    org_id     bigint not null,
    name       varchar(128) not null comment '套餐名',
    catalog_item_id bigint not null comment '挂报价项库(明细经 catalog 引用套餐)',
    catalog_name    varchar(128) not null,
    status     tinyint not null default 0 comment '0 启用 / 1 停用',
    created_at datetime not null default current_timestamp,
    primary key (id),
    key idx_qnp_org (org_id, catalog_item_id)
) engine=innodb default charset=utf8mb4 comment='材料定额套餐';

create table quotation_norm_pack_item (
    id         bigint not null auto_increment,
    org_id     bigint not null,
    pack_id    bigint not null comment '套餐(quotation_norm_pack.id)',
    goods_id   bigint not null comment '成员商品',
    goods_name varchar(128) not null,
    norm_unit  varchar(16) null,
    norm_num   bigint not null default 0 comment '每单位用量(千分位)',
    store_unit varchar(16) null,
    store_num  bigint not null default 0 comment '库房用量(千分位)',
    primary key (id),
    unique key uk_qnpi (pack_id, goods_id)
) engine=innodb default charset=utf8mb4 comment='套餐成员';

-- 实例单头(一单唯一;冗余合计不落库,查询期聚合)
create table quotation_norm (
    id           bigint not null auto_increment,
    org_id       bigint not null,
    order_id     bigint not null comment '装修单',
    quotation_id bigint null comment '默认报价(quotation_quotation.id;首发时回填)',
    creater      bigint not null comment '建档人',
    create_time  datetime not null default current_timestamp,
    sealed       tinyint not null default 0 comment '0 生成中 / 1 完工固化(拒再生成 231206)',
    sealed_at    datetime null,
    primary key (id),
    unique key uk_qn (org_id, order_id)
) engine=innodb default charset=utf8mb4 comment='定额单头';

-- 实例行(单表+kind 位,收敛老 NormoodItem/NormriceItem/NormtimeItem 三表)
create table quotation_norm_item (
    id              bigint not null auto_increment,
    org_id          bigint not null,
    order_id        bigint not null,
    quotation_id    bigint null,
    source_type     tinyint not null comment '0 报价 / 1 拓展(预留) / 2 增减 / 3 附加',
    source_id       bigint not null default 0 comment '来源单 id:报价=功能项 id、增减=差量单 id、附加=恒 0(自引用解题)',
    kind            tinyint not null comment '0 材料 / 1 人工 / 2 工时',
    archive_id      bigint null comment '定额档案 id;套餐成员=0;附加手工行=null(uk 多 NULL 天然排除)',
    pack_item_id    bigint not null default 0 comment '套餐成员行 id(非套餐行=0)',
    area_id         bigint null comment '功能区',
    item_id         bigint null comment '报价功能项(quotation_function_item.id)',
    catalog_item_id bigint null,
    goods_id        bigint null,
    goods_name      varchar(128) null,
    node_id         bigint null comment '施工节点',
    node_name       varchar(64)  null,
    name            varchar(128) not null comment '名称快照',
    unit            varchar(16)  null,
    atttype         tinyint not null default 0 comment '0 收 / 1 支(减项反向走支)',
    num             bigint not null default 0 comment '明细量(千分位,来源明细直拷)',
    norm_num        bigint not null default 0 comment '定额量快照(千分位;实际用量=读取期 num×norm_num÷1000 HALF_UP)',
    trannum         bigint not null default 1000 comment '转换系数(千分位)',
    salary          bigint not null default 0 comment '单价(厘,人工)',
    workdays        bigint not null default 0 comment '工日(千分位,工时)',
    status          tinyint not null default 0 comment '0 初始 / 1 禁用(重算换版) / 2 确认 / 3 驳回(仅附加行 2/3)',
    confirmer       bigint null comment '确认人(附加行)',
    confirm_time    datetime null,
    created_at      datetime not null default current_timestamp,
    primary key (id),
    unique key uk_qni (org_id, quotation_id, source_type, source_id, kind, archive_id, pack_item_id),
    key idx_qni_order (org_id, order_id, status),
    key idx_qni_node (org_id, order_id, node_id)
) engine=innodb default charset=utf8mb4 comment='定额实例行(单表+kind 位)';
