-- 主材销售域 V1 真表(addon-sales,错误码段 300000;迁移段 1100+)
-- 设计口径:docs/rules/sales.md + docs/prd/addon-sales.md
--   装修单附属主材销售链:开单(挂装修单)→明细维护(服务端算价)→客户确认→收款(addon-payment 事件)
--   →出货挂单(addon-inventory 事件回写计数)→配送安装四级→厂商结算三段式→完结/废单;
--   四轴状态机(行业共性保留):主单轴 status(老缺 1 的历史编号保留)/结算轴 balance_status/
--   款项轴 fullpay_status(V1 单全款线,老定金线随 payment 多款项能力 V2)/客户确认位 customer_confirmed;
--   金额厘(×1000)/件数千分位(×1000=1 件)/折扣千分比(1000=无折),服务端算价拒前端金额;
--   五计数(num/initnum/punum/xgnum/trnum)由 inventory 事件回写,本域零直写他域表;
--   单号 = XS+yyMMdd+4 位全局流水(计数器原子自增,修老公司级日流水可跳号可撞);
--   让利公式老三套量纲不自洽不迁:V1 balance=total(无让利源),vbalance/pbalance 双值人工修正保留;
--   全链 org_id 隔离。表前缀 sales_(军规 3);MySQL 5.6 兼容。

-- 单号计数器(原子 last_insert_id 自增)
create table sales_counter (
    id       bigint not null auto_increment,
    org_id   bigint not null,
    prefix   varchar(4) not null comment '单类型前缀:XS销售/SR退货',
    date_key int not null comment 'yyMMdd',
    last_seq int not null default 0,
    primary key (id),
    unique key uk_counter (org_id, prefix, date_key)
) engine=innodb default charset=utf8mb4 comment='单号流水计数器';

-- 销售单主单(主单轴 status 沿老编号:0初始/2全退/3倒单/4废单/5完结,缺 1 系老历史痕迹不回填;
-- 结算轴 balance_status:0无结/1预结/2厂结/3结算(老 4 完结旁路态不迁,完结只走主单轴);
-- 款项轴 fullpay_status:-1未录/0已录/1已付/2已确认;客户确认位 customer_confirmed)
create table sales_order (
    id                bigint not null auto_increment,
    org_id            bigint not null,
    code              varchar(32) not null comment '单号:XS+yyMMdd+4位流水',
    order_id          bigint not null comment '装修单(decoration_order.id)',
    order_no          varchar(32) not null comment '装修单单号快照',
    customer_name     varchar(128) not null comment '客户名快照',
    house_label       varchar(256) not null comment '房屋展示快照',
    salesman_id       bigint null comment '销售员(decoration 快照,可空)',
    status            tinyint not null default 0 comment '0初始/2全退/3倒单/4废单/5完结',
    balance_status    tinyint not null default 0 comment '0无结/1预结/2厂结/3结算',
    fullpay_status    tinyint not null default -1 comment '-1未录/0已录/1已付/2已确认',
    customer_confirmed tinyint not null default 0 comment '客户确认位 0/1',
    confirmed_at      datetime null,
    total             bigint not null default 0 comment 'Σ明细total(主项,厘,服务端重算)',
    finish_time       datetime null comment '完结时间',
    remark            varchar(200) null,
    created_by        bigint not null,
    created_by_name   varchar(64) not null,
    created_at        datetime not null default current_timestamp,
    updated_at        datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_sales_code (org_id, code),
    key idx_sales_order (org_id, order_id),
    key idx_sales_status (org_id, status)
) engine=innodb default charset=utf8mb4 comment='销售单主单';

-- 销售明细(psiid 父子收敛为 package_id:0=主项;主项计入总额,套餐子项不计(老口径保留);
-- 五计数由 inventory 事件回写:xgnum 出货扣/删行回加,punum 采购释放扣减;
-- xgnum 基线=num(开单/改单时建立,老 SalesItemServiceImpl 同语义))
create table sales_order_item (
    id           bigint not null auto_increment,
    org_id       bigint not null,
    sale_id      bigint not null comment '销售单(sales_order.id)',
    package_id   bigint not null default 0 comment '父明细 id(0=主项;套餐子项挂父)',
    goods_id     bigint not null comment '商品(basedata_goods.id,经 GoodsApi 校验)',
    color_id     bigint not null default 0,
    format_id    bigint not null default 0,
    goods_name   varchar(128) not null,
    color_name   varchar(128) not null default '',
    format_name  varchar(128) not null default '',
    brand_id     bigint not null default 0 comment '品牌快照(结算分组维度)',
    brand_name   varchar(64) not null default '',
    num          bigint not null comment '销售数(千分位,恒>0)',
    initnum      bigint not null default 0 comment '套餐子项单份件数(主项=num 本身)',
    punum        bigint not null default 0 comment '采购占用(千分位,采购释放事件扣减)',
    xgnum        bigint not null default 0 comment '可出库余量(基线=num,出货事件扣减)',
    trnum        bigint not null default 0 comment '已送货数(千分位)',
    selling      bigint not null comment '售价(厘)',
    discount     bigint not null default 1000 comment '折扣(千分比,1000=无折)',
    dis_price    bigint not null comment '折后单价 = selling×discount/1000(厘,HALF_UP)',
    total        bigint not null comment '明细额 = dis_price×num/1000(厘,HALF_UP,主项计入主单)',
    created_at   datetime not null default current_timestamp,
    primary key (id),
    key idx_sitem_sale (org_id, sale_id),
    key idx_sitem_pkg (sale_id, package_id)
) engine=innodb default charset=utf8mb4 comment='销售明细(五计数事件回写)';

-- 结算行(1 单 N 品牌行;行轴 0预结→1厂结→2结算;type 0购货/1预结退货/2结算退货/3定金;
-- onetype 镜像主单轴 0正常/2全退/3倒单;balance=结算基值(V1=total,让利源 V2);
-- vbalance 厂结值(0 前 vpost 服务端强校验>0)/pbalance 结算值;difference=total-balance;
-- rid=退货单关联(退货行);uk 兜底防同键重复生成(rid 用 0 填充非 NULL))
create table sales_settlement (
    id          bigint not null auto_increment,
    org_id      bigint not null,
    sale_id     bigint not null comment '销售单(sales_order.id)',
    brand_id    bigint not null default 0,
    brand_name  varchar(64) not null default '',
    type        tinyint not null default 0 comment '0购货/1预结退货/2结算退货/3定金',
    onetype     tinyint not null default 0 comment '0正常/2全退/3倒单(镜像主单轴/退货行)',
    rid         bigint not null default 0 comment '退货单(sales_refunds.id,购货行恒0)',
    total       bigint not null comment '应收(厘;退货行为负值)',
    balance     bigint not null default 0 comment '结算基值(让利后,V1=total)',
    vbalance    bigint not null default 0 comment '厂结值(厘;退货行为负)',
    pbalance    bigint not null default 0 comment '结算值(厘,初始=vbalance)',
    difference  bigint not null default 0 comment 'total-balance',
    status      tinyint not null default 0 comment '0预结/1厂结/2结算',
    vposted_by  bigint null,
    vposted_at  datetime null,
    posted_by   bigint null,
    posted_at   datetime null,
    created_by  bigint not null,
    created_at  datetime not null default current_timestamp,
    primary key (id),
    unique key uk_settle (org_id, sale_id, brand_id, type, rid),
    key idx_settle_sale (org_id, sale_id, status)
) engine=innodb default charset=utf8mb4 comment='厂商结算行(三段式,1单N品牌)';

-- 退货单(唯一创建入口 = 退货审批批准(修老发起即建);status:3审批中/0初始(批准生效)/
-- 1已退款(PaymentRefundedEvent 回写)/2无效(驳回/撤回,留痕不物理删);
-- type 沿老 onetype 语义:2全退/3倒单;amount 恒正存绝对值,结算行落负值)
create table sales_refunds (
    id           bigint not null auto_increment,
    org_id       bigint not null,
    sale_id      bigint not null comment '销售单(sales_order.id)',
    order_id     bigint not null comment '装修单(decoration_order.id)',
    type         tinyint not null comment '2全退/3倒单',
    status       tinyint not null default 3 comment '3审批中/0初始/1已退款/2无效',
    approval_id  bigint null comment '内核轻量审批单(yz_approval.id)',
    is_affirm    tinyint not null default 0 comment '客户确认位 0/1',
    affirmed_at  datetime null,
    amount       bigint not null default 0 comment '退货额(厘,恒正=Σ明细total绝对值)',
    remark       varchar(200) null,
    created_by   bigint not null,
    created_by_name varchar(64) not null,
    created_at   datetime not null default current_timestamp,
    updated_at   datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    key idx_refund_sale (org_id, sale_id, status)
) engine=innodb default charset=utf8mb4 comment='退货单(审批驱动,客户确认幂等)';

-- 退货明细(pid=销售明细锚点;num 恒正,发起审批时原子扣减销售明细 num,驳回回加)
create table sales_refunds_item (
    id           bigint not null auto_increment,
    org_id       bigint not null,
    refund_id    bigint not null comment '退货单(sales_refunds.id)',
    sale_item_id bigint not null comment '销售明细(sales_order_item.id)',
    goods_name   varchar(128) not null,
    color_name   varchar(128) not null default '',
    format_name  varchar(128) not null default '',
    num          bigint not null comment '退货数(千分位,恒>0)',
    selling      bigint not null comment '售价快照(厘)',
    total        bigint not null comment '退货额快照(厘,恒正)',
    created_at   datetime not null default current_timestamp,
    primary key (id),
    key idx_ritem_refund (org_id, refund_id)
) engine=innodb default charset=utf8mb4 comment='退货明细';

-- 配送批次(履约面;老单头 status 死字段不迁:进行中/完结由明细聚合判定,不入列)
create table sales_delivery (
    id           bigint not null auto_increment,
    org_id       bigint not null,
    sale_id      bigint not null comment '销售单(sales_order.id)',
    order_id     bigint not null comment '装修单',
    remark       varchar(200) null,
    created_by   bigint not null,
    created_by_name varchar(64) not null,
    created_at   datetime not null default current_timestamp,
    primary key (id),
    key idx_dlv_sale (org_id, sale_id)
) engine=innodb default charset=utf8mb4 comment='配送批次';

-- 配送明细(四级状态机 0初始→1配送→2审核→3确认→4完结,每级操作人+时间留痕,
-- 越级/不符显式报错(修老静默 continue);品牌跳级权限与按批次分成克隆(方林嫌疑)不迁)
create table sales_delivery_item (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    delivery_id   bigint not null comment '配送批次(sales_delivery.id)',
    sale_item_id  bigint not null comment '销售明细(sales_order_item.id)',
    goods_name    varchar(128) not null,
    color_name    varchar(128) not null default '',
    format_name   varchar(128) not null default '',
    num           bigint not null comment '本批配送数(千分位,恒>0)',
    status        tinyint not null default 0 comment '0初始/1配送/2审核/3确认/4完结',
    tran_by       bigint null, tran_at  datetime null,
    check_by      bigint null, check_at datetime null,
    confirm_by    bigint null, confirm_at datetime null,
    finish_by     bigint null, finish_at datetime null,
    created_at    datetime not null default current_timestamp,
    primary key (id),
    key idx_ditem_dlv (org_id, delivery_id),
    key idx_ditem_sitem (sale_item_id, status)
) engine=innodb default charset=utf8mb4 comment='配送明细(四级履约)';

-- 轨迹(操作日志全覆盖;含状态重置类管理动作)
create table sales_track (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    sale_id       bigint not null,
    action        varchar(32) not null comment 'create/updateItem/confirm/collect/confirmPay/generateSettle/vpost/post/rejectSettle/refund/deliver/tran/check/confirmItem/finishItem/finish/terminate/fullRefund/countermand',
    detail        varchar(500) null,
    operator_id   bigint not null,
    operator_name varchar(64) not null,
    created_at    datetime not null default current_timestamp,
    primary key (id),
    key idx_track_sale (org_id, sale_id)
) engine=innodb default charset=utf8mb4 comment='销售单轨迹';

-- 客户确认验证码(三重校验服务端强制:phone+code 同时比对,修老单传其一即绕过;
-- 短信通道 V2 接入前,验证码经内核通知中心发操作人人工转告)
create table sales_verify_code (
    id         bigint not null auto_increment,
    org_id     bigint not null,
    biz_type   varchar(16) not null comment 'order销售单/refund退货单',
    biz_id     bigint not null,
    phone      varchar(32) not null,
    code       varchar(8) not null,
    expire_at  datetime not null,
    used_at    datetime null,
    created_at datetime not null default current_timestamp,
    primary key (id),
    key idx_vcode (org_id, biz_type, biz_id, phone)
) engine=innodb default charset=utf8mb4 comment='客户确认验证码';
