-- 库存域 V1 真表(addon-inventory,错误码段 310000;迁移段 1200+)
-- 设计口径:docs/rules/inventory.md + docs/prd/addon-inventory.md
--   「单据即库存」语义保留(建单事务内即时过账,修老审核线不存在的事实不做审核前移),
--   单据 = 业务面,库存增减 = 记账面(老 manifest 12 类收敛为类型字典,手工建单仅进货/出货);
--   实时库存原子扣减保留(老系统唯一真守卫 GoodsStorehouseStockDao.decreaseStockNum 孤本)+补唯一键;
--   数量千分位(×1000)/金额厘(‰元),恒正服务端校验(修老恒假守卫 size()<0 与负数击穿);
--   单号计数器原子自增(修老读改写竞态)+ (org_id, code) 唯一键兜底;
--   全链 org_id 隔离(修老多租户半成品:库存/仓库/明细无 company)。
--   表前缀 inventory_(军规 3);MySQL 5.6 兼容。

-- 仓库档案(组织自有;老 storehouse 无 company 修为 org_id;0启用/1停用沿老语义)
create table inventory_warehouse (
    id         bigint not null auto_increment,
    org_id     bigint not null,
    code       varchar(16) not null comment '仓库编号',
    name       varchar(64) not null,
    status     tinyint not null default 0 comment '0启用/1停用',
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_wh_code (org_id, code)
) engine=innodb default charset=utf8mb4 comment='仓库档案';

-- 仓库授权(可见性闸:库存查询按授权仓库过滤,无授权=空列表;写操作 V1 不校验,显式声明沿老语义)
create table inventory_warehouse_user (
    id           bigint not null auto_increment,
    org_id       bigint not null,
    warehouse_id bigint not null comment '仓库(inventory_warehouse.id)',
    user_id      bigint not null comment '用户(yz_user.id)',
    created_at   datetime not null default current_timestamp,
    primary key (id),
    unique key uk_wh_user (warehouse_id, user_id)
) engine=innodb default charset=utf8mb4 comment='仓库可见性授权';

-- 实时库存(商品×色×规×仓库;唯一真守卫 = 原子条件扣减 UPDATE num=num-:n WHERE num>=:n;
-- 快照纪律:商品/色/规名建档快照,禁跨插件直查 basedata;0=未选色规,唯一键要求全 NOT NULL)
create table inventory_stock (
    id           bigint not null auto_increment,
    org_id       bigint not null,
    goods_id     bigint not null comment '商品(basedata_goods.id,经 GoodsApi 校验)',
    color_id     bigint not null default 0 comment '颜色属性(basedata_goods_sku_attr.id,0=无)',
    format_id    bigint not null default 0 comment '规格属性(同上,0=无)',
    goods_name   varchar(128) not null,
    color_name   varchar(128) not null default '' comment '空串=无色',
    format_name  varchar(128) not null default '' comment '空串=无规',
    warehouse_id bigint not null comment '仓库(inventory_warehouse.id)',
    num          bigint not null default 0 comment '库存数量(千分位,恒>=0)',
    created_at   datetime not null default current_timestamp,
    updated_at   datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_stock (org_id, goods_id, color_id, format_id, warehouse_id),
    key idx_stock_wh (org_id, warehouse_id)
) engine=innodb default charset=utf8mb4 comment='实时库存(唯一记账守卫对象)';

-- 单号计数器(原子 last_insert_id 自增;修老 CountingPO 读改写竞态 + 单公司模式 company=0 隐患)
create table inventory_counter (
    id       bigint not null auto_increment,
    org_id   bigint not null,
    prefix   varchar(4) not null comment '单类型前缀:CG/CR/XG/XB/DB/PU/AS/AR/CP/CL/OS/PO',
    date_key int not null comment 'yyMMdd',
    last_seq int not null default 0,
    primary key (id),
    unique key uk_counter (org_id, prefix, date_key)
) engine=innodb default charset=utf8mb4 comment='单号流水计数器';

-- 出入库单(库存事件业务面;12 类收敛:type 1进货/2进退/3出货/4出返/5调拨/6采购入库/7领料出库/
-- 8领料退回/9套合/10套拆/11盘盈/12盘亏;手工建单仅 1/3,其余系统派生落单即确认;
-- status:0初始/1已确认/2审批中(仅调拨)/3已驳回;套合/套拆(9/10)随套餐场景 V1.1)
create table inventory_manifest (
    id             bigint not null auto_increment,
    org_id         bigint not null,
    code           varchar(32) not null comment '单号:前缀+yyMMdd+4位流水',
    type           tinyint not null comment '1-12 类型字典(见 addon.yml dicts)',
    status         tinyint not null default 0 comment '0初始/1已确认/2审批中/3已驳回',
    order_id       bigint null comment '装修单(decoration_order.id,联动场景可空)',
    source_type    varchar(16) null comment '源单协议键:stockManifest冲销/requisition领料/purchase采购',
    source_id      bigint null comment '源单 id(原货单/领料单/采购单)',
    approval_id    bigint null comment '调拨审批单(yz_approval.id;驳回/撤回→3,通过→过账+1)',
    remark         varchar(200) null,
    created_by     bigint not null,
    created_by_name varchar(64) not null,
    confirmed_at   datetime null comment '确认时间(手工单确认/派生单落库即写)',
    created_at     datetime not null default current_timestamp,
    updated_at     datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_manifest_code (org_id, code),
    key idx_manifest_type (org_id, type, status),
    key idx_manifest_source (org_id, source_type, source_id),
    key idx_manifest_order (org_id, order_id)
) engine=innodb default charset=utf8mb4 comment='出入库单(单据面)';

-- 出入库单明细(surplus = 剩余可冲量,冲销线专用;siid/riid/piid 为跨域事件锚点,
-- inventory 零直写他域表,计数回写由 sales/construction 订阅事件自行完成)
create table inventory_manifest_item (
    id                  bigint not null auto_increment,
    org_id              bigint not null,
    manifest_id         bigint not null comment '出入库单(inventory_manifest.id)',
    goods_id            bigint not null,
    color_id            bigint not null default 0,
    format_id           bigint not null default 0,
    goods_name          varchar(128) not null,
    color_name          varchar(128) not null default '',
    format_name         varchar(128) not null default '',
    warehouse_in        bigint null comment '入库仓(进货/出返/调拨入/盘盈)',
    warehouse_out       bigint null comment '出库仓(出货/进退/调拨出/领料/盘亏)',
    num                 bigint not null comment '数量(千分位,恒正)',
    price               bigint not null default 0 comment '单价(厘)',
    surplus             bigint null comment '剩余可冲量(千分位;CR/XB/AR 冲销线专用,NULL=不可冲)',
    sales_item_id       bigint null comment '销售明细锚点(出货联动,事件回写用)',
    requisition_item_id bigint null comment '领料明细锚点(领料出库/退回联动)',
    purchase_item_id    bigint null comment '采购明细锚点(采购入库核销)',
    source_item_id      bigint null comment '冲销源明细(原货单/原出库明细)',
    sort                int not null default 0,
    primary key (id),
    key idx_mi_manifest (manifest_id),
    key idx_mi_source (org_id, source_item_id),
    key idx_mi_sales_item (org_id, sales_item_id)
) engine=innodb default charset=utf8mb4 comment='出入库单明细';

-- 领料单(老 accessories 正名;type 0普通/1售后,售后跳过工程额度联动;
-- status 0初始/1预出/2已出库/3已撤销/4已拒绝/6已到货——老 5退回死状态与 7审核无守卫态不迁,
-- 审核线如需走内核轻量审批 V1.1;额度双闸经 ConstructionAccessoryApi 契约,本域零直写施工表)
create table inventory_requisition (
    id             bigint not null auto_increment,
    org_id         bigint not null,
    code           varchar(32) not null comment '单号 RQ 前缀',
    order_id       bigint not null comment '装修单(construction_order.order_id,施工点火后可领)',
    order_no       varchar(32) not null comment '装修单单号快照',
    house_label    varchar(200) not null comment '房屋展示快照',
    type           tinyint not null default 0 comment '0普通/1售后',
    status         tinyint not null default 0 comment '0初始/1预出/2已出库/3已撤销/4已拒绝/6已到货',
    total          bigint not null default 0 comment '领用总额(‰元,服务端 Σselling×num 重算)',
    comment        varchar(200) null,
    fetch_time     datetime null comment '领用时间',
    created_by     bigint not null,
    created_by_name varchar(64) not null,
    outbound_at    datetime null,
    arrive_at      datetime null,
    close_at       datetime null comment '撤销/拒绝时间',
    created_at     datetime not null default current_timestamp,
    updated_at     datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_rq_code (org_id, code),
    key idx_rq_order (org_id, order_id, status)
) engine=innodb default charset=utf8mb4 comment='领料单(工程辅料领用)';

create table inventory_requisition_item (
    id             bigint not null auto_increment,
    org_id         bigint not null,
    requisition_id bigint not null comment '领料单(inventory_requisition.id)',
    goods_id       bigint not null,
    color_id       bigint not null default 0,
    format_id      bigint not null default 0,
    goods_name     varchar(128) not null,
    color_name     varchar(128) not null default '',
    format_name    varchar(128) not null default '',
    warehouse_id   bigint not null comment '领用仓(出库扣减对象)',
    warehouse_name varchar(64) not null,
    num            bigint not null comment '申请数量(千分位,恒正)',
    selling        bigint not null default 0 comment '单价(厘,服务端取商品实价快照)',
    total          bigint not null default 0 comment '=num×selling(服务端重算)',
    outbound_num   bigint not null default 0 comment '已出库数量(千分位)',
    returned_num   bigint not null default 0 comment '已退回数量(千分位)',
    sort           int not null default 0,
    primary key (id),
    key idx_ri_requisition (requisition_id)
) engine=innodb default charset=utf8mb4 comment='领料单明细';

-- 外辅采购单(老 occcessories 正名 outsourced purchase;向外部厂商定材辅料的物流+费用+结算,
-- V1 金额单据内自闭环(生产即线下钱货),厂商实体随 addon-supplier 就绪前以名称快照登记
-- (废「厂商即用户」);布尔语义全面翻转:1=是/0=否,修老 0=肯定反直觉字典)
create table inventory_outsourcing (
    id             bigint not null auto_increment,
    org_id         bigint not null,
    code           varchar(32) not null comment '单号 OS 前缀',
    order_id       bigint not null comment '装修单(工程验收后锁定 310051)',
    order_no       varchar(32) not null,
    house_label    varchar(200) not null,
    status         tinyint not null default 0 comment '0初始/1已分配/2送货中/3已到货/4已结算/5已完成/6已作废',
    supplier_name  varchar(128) null comment '厂商名称快照(addon-supplier 就绪前自由登记)',
    supplier_phone varchar(32) null,
    is_enter       tinyint not null default 0 comment '1可进园区/0否(老 0=是 翻转)',
    is_lift        tinyint not null default 0 comment '1有电梯/0无',
    is_exceed      tinyint not null default 0 comment '1超阶/0否(超阶时费用浮动额参与计价)',
    is_attic       tinyint not null default 0 comment '1阁楼/0否',
    load_floor     int not null default 0 comment '上料楼层:0一层/1二层/-1负一/-2负二',
    total          bigint not null default 0 comment '商品总额=Σ实价×num(厘,服务端重算;修老 int 溢出改 long)',
    charge         bigint not null default 0 comment '费用总额=Σ(price+超阶浮动)×num(厘)',
    rcharge        bigint not null default 0 comment '应收费用快照(结算动作写)',
    rbalance       bigint not null default 0 comment '原结算总额=Σrbalance×num(厘)',
    vbalance       bigint not null default 0 comment '结算总额(厘)',
    remark         varchar(200) null,
    created_by     bigint not null,
    created_by_name varchar(64) not null,
    assigned_at    datetime null,
    delivered_at   datetime null,
    received_at    datetime null,
    balanced_at    datetime null,
    finished_at    datetime null,
    cancelled_at   datetime null,
    created_at     datetime not null default current_timestamp,
    updated_at     datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_os_code (org_id, code),
    key idx_os_order (org_id, order_id, status)
) engine=innodb default charset=utf8mb4 comment='外辅采购单';

create table inventory_outsourcing_item (
    id             bigint not null auto_increment,
    org_id         bigint not null,
    outsourcing_id bigint not null comment '外辅单(inventory_outsourcing.id)',
    goods_id       bigint not null,
    color_id       bigint not null default 0,
    format_id      bigint not null default 0,
    goods_name     varchar(128) not null,
    color_name     varchar(128) not null default '',
    format_name    varchar(128) not null default '',
    num            bigint not null comment '申请数量(千分位)',
    sennum         bigint not null default 0 comment '送货数量(≤num,修老无校验)',
    recnum         bigint not null default 0 comment '到货数量(≤sennum)',
    rbalance       bigint not null default 0 comment '原结算单价(厘)',
    vbalance       bigint not null default 0 comment '结算单价(厘)',
    charge         bigint not null default 0 comment '费用小计(厘,公式重算)',
    sort           int not null default 0,
    primary key (id),
    key idx_oi_outsourcing (outsourcing_id)
) engine=innodb default charset=utf8mb4 comment='外辅单明细';

-- 外辅收费明细(向装修单收的运/搬/上楼费;费用公式=(price+超阶浮动)×num;V1 单据内自闭环,
-- 不进装修单款项计算(老语义保留),接 addon-payment 随 V2 评估)
create table inventory_outsourcing_charge (
    id             bigint not null auto_increment,
    org_id         bigint not null,
    outsourcing_id bigint not null,
    item_id        bigint null comment '归属明细(inventory_outsourcing_item.id,可空=整单费用)',
    name           varchar(64) not null comment '费用名(车运费/搬运费/上楼费)',
    price          bigint not null default 0 comment '单价(厘)',
    surplus        bigint not null default 0 comment '浮动额(厘,超阶时参与计价)',
    num            int not null default 0 comment '数量',
    charge         bigint not null default 0 comment '=（price+超阶?surplus:0)×num(服务端重算)',
    primary key (id),
    key idx_oc_outsourcing (outsourcing_id)
) engine=innodb default charset=utf8mb4 comment='外辅收费明细';

-- 外辅轨迹(作废/反结算/分配等留痕;修老评分无守卫——评分线不迁,轨迹面收口)
create table inventory_outsourcing_track (
    id             bigint not null auto_increment,
    org_id         bigint not null,
    outsourcing_id bigint not null,
    action         varchar(32) not null comment 'create/assign/deliver/receive/balance/rejectBalance/finish/cancel',
    detail         varchar(200) null,
    operator_id    bigint not null,
    operator_name  varchar(64) not null,
    created_at     datetime not null default current_timestamp,
    primary key (id),
    key idx_ot_outsourcing (outsourcing_id)
) engine=innodb default charset=utf8mb4 comment='外辅单轨迹';

-- 采购单(入库轴 0初始/1部分入库/2全部入库/3已取消;取消为营造新增终态,
-- 释放采购余量走 StockPurchaseChangedEvent 事件(修老 punum 永不释放);
-- 老结算轴 balanceStatus 不迁——采购结算统一 addon-payment(purchasebalance 生产 0 行不仿);
-- balanceTime/deliveryTime 死列不迁)
create table inventory_purchase (
    id             bigint not null auto_increment,
    org_id         bigint not null,
    code           varchar(32) not null comment '单号 PO 前缀',
    sales_id       bigint null comment '销售单锚点(addon-sales 就绪前可空,事件回写用)',
    status         tinyint not null default 0 comment '0初始/1部分入库/2全部入库/3已取消',
    supplier_name  varchar(128) null comment '厂商名称快照',
    remark         varchar(200) null,
    created_by     bigint not null,
    created_by_name varchar(64) not null,
    inbound_at     datetime null comment '全部入库时间',
    cancelled_at   datetime null,
    created_at     datetime not null default current_timestamp,
    updated_at     datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_po_code (org_id, code),
    key idx_po_status (org_id, status)
) engine=innodb default charset=utf8mb4 comment='采购单';

create table inventory_purchase_item (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    purchase_id   bigint not null comment '采购单(inventory_purchase.id)',
    goods_id      bigint not null,
    color_id      bigint not null default 0,
    format_id     bigint not null default 0,
    goods_name    varchar(128) not null,
    color_name    varchar(128) not null default '',
    format_name   varchar(128) not null default '',
    num           bigint not null comment '采购数量(千分位)',
    inprice       bigint not null default 0 comment '进价(厘)',
    surplus       bigint not null default 0 comment '待入库余量(千分位,原子条件扣减)',
    sales_item_id bigint null comment '销售明细锚点(释放事件回写用)',
    sort          int not null default 0,
    primary key (id),
    key idx_pi_purchase (purchase_id),
    key idx_pi_sales_item (org_id, sales_item_id)
) engine=innodb default charset=utf8mb4 comment='采购单明细';
