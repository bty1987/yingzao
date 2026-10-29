-- 外销子域 V2(D6a 精简版,docs/prd/addon-sales-oales.md 按推荐拍板 2026-10-28;码段 300100–300150)
-- 老四表(oales/oalesitem/oalespay/oalesrecord)收敛两表一留痕:
--   状态机老 7 态收敛 6 态(驳回态消亡=驳回回草稿留痕;复核档消亡=收款事件回程置 3);
--   确认建款翻转(confirm 时 PaymentApi.createBill typeCode=sales_oales)——修老「先手工付款登记
--   再确认」双入口可堆款根源;oalespay 不迁,收款唯一入口=addon-payment 款项单;
--   明细 oid 修老恒空断链,total=price×num÷1000 HALF_UP 服务端算(修老千倍虚增);
--   结算接入零新基建:status→2 发 SettlementItemReadyEvent(sourceAddon=sales,sourceType=oales,
--   balance V1301 settlement_type 协议键 sales:oales 本就预留)。
-- atttype 进货方向位/award 部门分成/cost+threshold 保密分级/isScale 并入装修增减——均不迁(PRD 划界)。
-- 表前缀 sales_oales_(军规 3);MySQL 5.6 兼容。

create table sales_oales (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    order_id      bigint not null comment '装修单(decoration_order.id,归属挂载;活单守卫经契约)',
    no            varchar(32) not null comment '单号:WX购货/WR退货+yyMMdd+4位流水(sales_counter 同族)',
    foid          bigint null comment '退货单回挂原单 id(type=1 必填)',
    brand_id      bigint not null,
    brand_name    varchar(64) not null comment '品牌名快照',
    supplier_id   bigint not null comment '品牌默认厂商(建单经 SupplierApi.queryBrandSuppliers 取首家,快照落列禁前端传名)',
    supplier_name varchar(64) not null comment '厂商名快照',
    type          tinyint not null default 0 comment '0购货/1退货(退货跳态出生=2,利润聚合按 type 分支符号)',
    total         bigint not null default 0 comment '货款合计(厘,服务端按明细重算,拒前端)',
    profit        bigint not null default 0 comment '利润(厘,0<=profit<=total;成本列不建利润直接展示——开放问题3)',
    dp_id         bigint null comment '款项单 id(confirm 建款回写)',
    status        tinyint not null default 0 comment '0草稿/1待确认/2已确认/3已收款/4已完结/8已废弃(仅0/1/2可废)',
    abate_reason  varchar(200) null,
    creater       bigint not null,
    created_at    datetime not null default current_timestamp,
    confirm_time  datetime null,
    finish_time   datetime null,
    updated_at    datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_oales_no (org_id, no),
    key idx_oales_order (org_id, order_id, status),
    key idx_oales_brand (org_id, brand_id, status)
) engine=innodb default charset=utf8mb4 comment='外销单头(平行于销售主单的品牌外销线)';

create table sales_oales_item (
    id     bigint not null auto_increment,
    org_id bigint not null,
    oid    bigint not null comment '外销单(sales_oales.id)——修老 oid 恒空明细断链',
    name   varchar(128) not null,
    format varchar(64) null comment '规格',
    color  varchar(64) null,
    model  varchar(64) null,
    price  bigint not null comment '售价(厘,>0)',
    num    bigint not null comment '数量(千分位,>0)',
    total  bigint not null comment '=price×num÷1000 HALF_UP(服务端算)',
    sort   int not null default 0,
    primary key (id),
    key idx_oitem_oid (oid, sort)
) engine=innodb default charset=utf8mb4 comment='外销明细';

create table sales_oales_track (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    oid           bigint not null,
    action        varchar(32) not null comment 'create/update/delete/submit/cancel/confirm/reject/collect/finish/abate/refund',
    before_status int null,
    after_status  int null,
    detail        varchar(500) null comment '驳回/废弃原因等(老 oalesrecord 15 type 收敛 track 惯例)',
    operator_id   bigint not null,
    operator_name varchar(64) not null,
    created_at    datetime not null default current_timestamp,
    primary key (id),
    key idx_otrack_oid (oid, id)
) engine=innodb default charset=utf8mb4 comment='外销操作留痕(与单据同事务,修老先删单再补记录孤儿)';
