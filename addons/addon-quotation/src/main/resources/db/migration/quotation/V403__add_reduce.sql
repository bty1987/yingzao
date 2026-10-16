-- V403:增减差量单(合同后变更,B 类联动/用户关注;详细设计 docs/prd/addon-quotation-addreduce.md)。
-- 老口径收编(rules/quotation.md §6):0 未提交 → 1 已提交(生成款项回写 dpId)→ 2 已付款 → 3 已确认;
-- 营造收敛:草稿物理删/已提交未付款可撤(归档记忆 status 回 0 + withdrawn_at)/
-- 付款经 PaymentConfirmedEvent 回程置 2(bizType=ar_diff)/财务 confirm 2→3 终态;
-- 明细行挂 ref_qiid 对原报价功能项做差量修正,金额全厘服务端算(add_price = num × unit_price);
-- paytype COM/ARO 方林财务口径不迁;差量款项 typeCode=ar_diff(协议键,quotes 直调 PaymentApi 落单)。
create table quotation_add_reduce (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    order_id      bigint not null comment '装修单(decoration_order.id;开单前置=签约)',
    order_no      varchar(32) not null comment '装修单号快照',
    ar_no         varchar(32) not null comment '差量单号 AR+yyMMdd+4位流水(uk org+no)',
    quotation_id  bigint not null comment '关联默认报价(锁定+审核完成)',
    status        tinyint not null default 0 comment '0草稿/1已提交/2已付款/3已确认(终态);撤回归档回 0+withdrawn_at',
    total_add     bigint not null default 0 comment 'Σ增补(厘,服务端聚合)',
    total_sub     bigint not null default 0 comment 'Σ退减绝对值(厘)',
    dp_id         bigint null comment '差量款项单(payment_bill.id,提交时经 PaymentApi 生成)',
    withdrawn_at  datetime null comment '撤回时刻(已提交未付款撤回归档)',
    confirm_time  datetime null comment '财务确认时刻(终态)',
    remark        varchar(255) null,
    created_by    bigint null,
    created_at    datetime not null default current_timestamp,
    updated_at    datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_ar_no (org_id, ar_no),
    key idx_ar_org_order (org_id, order_id, status)
) engine=innodb default charset=utf8mb4 comment='增减差量单(合同后变更)';

create table quotation_add_reduce_item (
    id          bigint not null auto_increment,
    org_id      bigint not null,
    ar_id       bigint not null comment '差量单(quotation_add_reduce.id)',
    item_type   tinyint not null comment '0 增项 / 1 减项 / 2 漏项(枚举位保留,流程同增项)',
    ref_qiid    bigint null comment '关联原报价功能项(quotation_function_item.id;纯新增行可空)',
    goods_id    bigint null comment '商品(basedata_goods.id,可空=非标项)',
    goods_name  varchar(128) not null,
    unit        varchar(32) null,
    unit_price  bigint not null comment '原单价快照(厘)',
    num         bigint not null comment '差量数量(千分位;正增负减)',
    add_price   bigint not null comment '差量金额(厘;服务端算 num×unit_price,带符号)',
    created_at  datetime not null default current_timestamp,
    primary key (id),
    key idx_ar_item (ar_id),
    key idx_ar_item_ref (org_id, ref_qiid)
) engine=innodb default charset=utf8mb4 comment='增减差量明细行';

-- 单号计数器(AR 差量单号;last_insert_id 原子自增,与 inventory_counter 同款)
create table quotation_counter (
    name varchar(64) not null,
    seq  bigint not null default 0,
    primary key (name)
) engine=innodb default charset=utf8mb4 comment='报价域单号计数器';
