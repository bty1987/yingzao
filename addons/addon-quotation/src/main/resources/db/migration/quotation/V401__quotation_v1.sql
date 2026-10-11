-- 报价域 V1 真表(addon-quotation,错误码段 230000;迁移段 400+)
-- 设计口径:docs/rules/quotation.md + docs/prd/addon-quotation.md
--   定稿线(status)×审核线(attstatus)双态位正交保留(老系统好设计);老 updateQuotationForStatus 裸状态位写入口不迁;
--   老五张分表化石(QuotationFunctionAreaItemPO0-4 按配置选实体类)不迁——v1 单表+org_id 索引,分表归 V3 性能专项;
--   金额一律厘;费率 rate 千分比(‰),discount 万分比(10000=不打折)——老 1e6 金额缩放与万分比黑盒在此显式文档化;
--   聚合公式(服务端计算,修老信任前端总价):
--     undis = Σ(常规项 num × price);dis = undis × discount / 10000;
--     manage = dis × manage_rate/10000 × manage_discount/10000;carry 同构;
--     tax = (dis+manage+carry) × tax_rate/10000 × tax_discount/10000(版本 tax_rate>0 才生成税项行);
--     total = dis + manage + carry + tax;除法一律 HALF_UP。
--   表前缀 quotation_(军规 3);MySQL 5.6 兼容:索引列宽 ≤767 字节。

-- 报价版本(组织级配置域:费率默认值/启税;建报价时三元组快照到主单,版本后续改价不影响已建报价)
create table quotation_version (
    id              bigint not null auto_increment,
    org_id          bigint not null,
    name            varchar(64) not null comment '版本名(组织内唯一)',
    busitype        tinyint not null default 0 comment '适用装修类型:0半包/1包干/2整装(对齐 decoration_order.type)',
    manage_rate     int not null default 0 comment '管理费率(千分比‰,50=5%)',
    manage_discount int not null default 10000 comment '管理费折上折(万分比,10000=不折)',
    carry_rate      int not null default 0 comment '搬运费率(千分比‰)',
    carry_discount  int not null default 10000 comment '搬运费折上折(万分比)',
    tax_rate        int not null default 0 comment '税率(千分比‰;0=不启税,建报价不生成税项行——老 NOTTAXDISCOUNT 语义)',
    tax_discount    int not null default 10000 comment '税折上折(万分比)',
    status          tinyint not null default 1 comment '0停用/1启用',
    remark          varchar(200) null,
    created_at      datetime not null default current_timestamp,
    updated_at      datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_version_name (org_id, name)
) engine=innodb default charset=utf8mb4 comment='报价版本(费率默认值配置域)';

-- 报价单主单(挂装修单 decoration_order.id;一单多份,同单唯一默认)
-- status 定稿线:0编辑中/1锁定(锁定后一切编辑拒绝,老 200030);attstatus 审核线:0初始/1提交/2审核/3完成
create table quotation_quotation (
    id               bigint not null auto_increment,
    org_id           bigint not null,
    order_id         bigint      not null comment '装修单(decoration_order.id,经 DecorationApi 契约校验)',
    order_no         varchar(32) not null comment '装修单号快照(展示用)',
    customer_name    varchar(64) not null comment '客户名快照(展示用)',
    house_label      varchar(200) not null comment '房屋展示快照(展示用)',
    name             varchar(64) not null comment '报价名(如 全包方案A)',
    version_id       bigint      null comment '版本来源(quotation_version.id;快照后不随版本变)',
    status           tinyint not null default 0 comment '定稿线:0编辑中/1锁定',
    attstatus        tinyint not null default 0 comment '审核线:0初始/1已提交/2已审核/3已完成',
    is_default       tinyint not null default 0 comment '同装修单唯一默认(仅锁定报价可设默认)',
    discount         int not null default 10000 comment '整单折扣(万分比,10000=不打折,9500=0.95)',
    manage_rate      int not null default 0 comment '管理费率‰(版本快照,可单报价覆盖)',
    manage_discount  int not null default 10000 comment '管理费折上折',
    carry_rate       int not null default 0 comment '搬运费率‰',
    carry_discount   int not null default 10000 comment '搬运费折上折',
    tax_rate         int not null default 0 comment '税率‰(0=未启税)',
    tax_discount     int not null default 10000 comment '税折上折',
    undis_price      bigint not null default 0 comment '未折合计(厘,Σ常规项)',
    discount_price   bigint not null default 0 comment '折后合计(厘)',
    total_price      bigint not null default 0 comment '总价(厘=折后+管理+搬运+税;服务端聚合,禁前端传入)',
    lock_time        datetime null comment '首次锁定时间(幂等不覆盖)',
    submit_time      datetime null comment '提交审核时间',
    audit_time       datetime null comment '审核时间',
    finish_time      datetime null comment '审核完成时间',
    remark           varchar(200) null,
    created_by       bigint not null,
    created_at       datetime not null default current_timestamp,
    updated_at       datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    key idx_quotation_org_order (org_id, order_id),
    key idx_quotation_status (org_id, status, attstatus)
) engine=innodb default charset=utf8mb4 comment='报价单主单';

-- 功能区(老报价明细二维结构外维;type 枚举照老;排序 sort asc)
create table quotation_function_area (
    id           bigint not null auto_increment,
    org_id       bigint not null,
    quotation_id bigint not null comment '报价单(quotation_quotation.id)',
    name         varchar(64) not null comment '功能区名(如 客厅/生成项目)',
    type         tinyint not null default 1 comment '0管理区/1工程量/2功能区总价/3不计算/7综合区(老枚举)',
    sort         int not null default 0 comment '排序(自动生成区固定 9999,老语义)',
    created_at   datetime not null default current_timestamp,
    primary key (id),
    key idx_area_quotation (quotation_id, sort)
) engine=innodb default charset=utf8mb4 comment='报价功能区';

-- 功能项(明细行;老五分表化石收敛单表;type∈{2,3,8} 为费率自动项,price 由聚合回填,禁手改)
create table quotation_function_item (
    id           bigint not null auto_increment,
    org_id       bigint not null,
    quotation_id bigint not null comment '报价单(quotation_quotation.id)',
    area_id      bigint not null comment '功能区(quotation_function_area.id)',
    name         varchar(128) not null comment '项目名',
    type         tinyint not null default 0 comment '0常规/1自定义/2搬运费/3管理费/4忽略/8税金(老枚举;5成本/6未完成不迁——成本线归报表插件)',
    subtype      tinyint not null default 0 comment '0工程/1材料(老 subtype)',
    unit         varchar(16) null comment '计量单位(m²/项/套…)',
    num          bigint not null default 1000 comment '数量(千分位×1000,与面积口径一致)',
    price        bigint not null default 0 comment '单价(厘;费率自动项由聚合回填)',
    sort         int not null default 0,
    remark       varchar(200) null,
    created_at   datetime not null default current_timestamp,
    primary key (id),
    key idx_item_area (area_id, sort),
    key idx_item_quotation (quotation_id)
) engine=innodb default charset=utf8mb4 comment='报价功能项';

-- 操作轨迹(定稿线/审核线动作留痕;append-only)
create table quotation_track (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    quotation_id  bigint       not null comment '报价单(quotation_quotation.id)',
    action        varchar(32)  not null comment '动作协议键:create/lock/unlock/submit/audit/finish/setDefault/areaSave',
    from_value    int null comment '定稿线前值',
    to_value      int null comment '定稿线后值',
    from_att      int null comment '审核线前值',
    to_att        int null comment '审核线后值',
    operator_id   bigint not null,
    operator_name varchar(64) not null comment '操作人姓名快照',
    comment       varchar(200) null,
    created_at    datetime not null default current_timestamp,
    primary key (id),
    key idx_qtrack_quotation (quotation_id, created_at)
) engine=innodb default charset=utf8mb4 comment='报价操作轨迹';

-- 组织级配置(懒初始化:首次读取无行用默认并落行;上限/保价天数老散落 systemConfig 收敛于此)
create table quotation_config (
    org_id            bigint not null,
    quotation_limit   int not null default 3 comment '同一装修单报价数量上限(老 quotationlimit 默认 3)',
    keep_price_days   int not null default 365 comment '保价窗口天数(老 KEEPRICE_DAYS_INT 默认 365;重算 V2 使用)',
    comprehensive_enabled tinyint not null default 0 comment '综合项目区自动生成开关(老 QUOTATION_CMPREH_CONFIG 公司开关→组织级)',
    updated_at        datetime not null default current_timestamp on update current_timestamp,
    primary key (org_id)
) engine=innodb default charset=utf8mb4 comment='报价组织级配置';
