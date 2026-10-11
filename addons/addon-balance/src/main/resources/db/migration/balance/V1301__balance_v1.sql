-- 结算域 V1 真表(addon-balance,错误码段 320000;迁移段 1300+)
-- 设计口径:docs/rules/balance.md + docs/prd/addon-balance.md
--   结算汇聚域:上游插件发布「明细可结算」事件携金额快照,本域聚桶落快照行——
--   替代老三列挂接桥表+查询时动态聚合(金额漂移根源)与跨域 JOIN(军规 2);
--   统一结算单模型按 settlement_type 区分上游域(sales/selection/oales 协议键),
--   状态机 0初始→1锁定→2待审(可选)→3已结算→4已完结(终态后全部写操作拒绝);
--   资金头内联结算单(amount 应付/cutpay 扣款冲抵/surplus 余量/paid 实付),完结唯一定义
--   = surplus 清零且已付款,单向无逆向(修老条件/无条件两写点冲突);
--   账户双腿收编 f384101d3 正面教材:台账腿(应付登记+原子消耗结算单余量)/
--   出纳现金腿(实付+懒开户同步台账,允许透支=预付);账户净头寸不变式 surplus=refer-real;
--   扣款池补三守卫(池原子 SQL/unbind-deduct 状态守卫/金额恒正);
--   归属人存身份字符串(供应商主数据实体随 addon-supplier,空=未指定);
--   单号 = JS+yyMMdd+4 位全局流水(计数器原子自增);全链 org_id 隔离;表前缀 balance_(军规 3);
--   MySQL 5.6 兼容。

-- 单号计数器(原子 last_insert_id 自增)
create table balance_counter (
    id       bigint not null auto_increment,
    org_id   bigint not null,
    prefix   varchar(4) not null comment '单类型前缀:JS结算单',
    date_key int not null comment 'yyMMdd',
    last_seq int not null default 0,
    primary key (id),
    unique key uk_counter (org_id, prefix, date_key)
) engine=innodb default charset=utf8mb4 comment='结算单号计数器';

-- 结算单(统一模型,三来源共用;四项 income/cost/payment/profit 由快照行服务端加总,不信任前端)
create table balance_settlement (
    id              bigint not null auto_increment,
    org_id          bigint not null,
    no              varchar(32) not null comment '单号:JS+yyMMdd+4位流水',
    settlement_type varchar(32) not null comment '上游域协议键 sourceAddon:sourceType(如 sales:settlement)',
    name            varchar(128) not null comment '结算单名',
    brand_id        bigint not null default 0 comment '品牌(聚桶维度,0=未分组)',
    brand_name      varchar(64) not null default '',
    supplier        varchar(64) not null default '' comment '归属人身份字符串(空=未指定,实体随 addon-supplier)',
    supplier_name   varchar(128) not null default '',
    grade           varchar(8) not null default 'A' comment '上游携带的分级维度(老 grade 硬编码嫌疑配置化:上游事件携带,本域不解释)',
    status          tinyint not null default 0 comment '0初始/1锁定/2待审/3已结算/4已完结',
    income          bigint not null default 0 comment '应收=Σ快照行income(厘)',
    cost            bigint not null default 0 comment '让利国补=Σ快照行cost(厘)',
    payment         bigint not null default 0 comment '应付=Σ快照行payment(厘,退货行负值加总)',
    profit          bigint not null default 0 comment '利润=income-payment(服务端算)',
    discount        bigint not null default 0 comment '整单让减(仅初始态可改,0<=x<=payment)',
    amount          bigint not null default 0 comment '应付总额=payment-discount(结算动作定格)',
    cutpay          bigint not null default 0 comment '扣款冲抵累计(含返款负冲)',
    surplus         bigint not null default 0 comment '余量(结算时=amount;台账入账/扣款bind原子消耗)',
    paid            bigint not null default 0 comment '出纳实付累计(信息面)',
    is_pay          tinyint not null default 0 comment '付款标 0/1(批量幂等)',
    item_count      int not null default 0,
    settled_by      bigint null,
    settled_at      datetime null,
    finished_at     datetime null,
    created_by      bigint not null,
    created_at      datetime not null default current_timestamp,
    updated_at      datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_bset_no (org_id, no),
    key idx_bset_bucket (org_id, settlement_type, brand_id, supplier, grade, status),
    key idx_bset_status (org_id, status)
) engine=innodb default charset=utf8mb4 comment='结算单(统一模型,快照行聚合)';

-- 结算明细快照行(事件携快照落行;uk 锁死同一上游明细全局只挂一单——修老外销绑入无防重利润重复累加)
create table balance_settlement_item (
    id             bigint not null auto_increment,
    org_id         bigint not null,
    settlement_id  bigint not null comment '结算单(balance_settlement.id)',
    source_addon   varchar(32) not null comment '上游插件协议键',
    source_type    varchar(32) not null comment '上游明细类型协议键',
    source_item_id varchar(64) not null comment '上游明细 id',
    qty            bigint not null default 0 comment '数量(千分位,信息面)',
    price          bigint not null default 0 comment '单价(厘,信息面)',
    income         bigint not null default 0 comment '应收快照(厘)',
    cost           bigint not null default 0 comment '让利国补快照(厘)',
    payment        bigint not null default 0 comment '应付厂商快照(厘,退货行负值)',
    memo           varchar(200) null,
    created_at     datetime not null default current_timestamp,
    primary key (id),
    unique key uk_bitem_source (source_addon, source_type, source_item_id),
    key idx_bitem_set (org_id, settlement_id)
) engine=innodb default charset=utf8mb4 comment='结算明细快照行(上游事件携带,零回查上游表)';

-- 结算账户(归属人维度钱包;type 写后不可变;净头寸不变式 surplus=refer_money-real_money)
create table balance_account (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    belonger      varchar(64) not null default '' comment '归属人身份字符串(空=未指定/平台池)',
    belonger_name varchar(128) not null default '',
    type          tinyint not null comment '0结算(应付台账)/1出纳(现金),写后不可变',
    refer_money   bigint not null default 0 comment '应付登记累计(入金方向)',
    real_money    bigint not null default 0 comment '实付累计',
    surplus       bigint not null default 0 comment '净头寸=refer-real(出纳腿守卫>=0;结算腿允许透支=预付)',
    status        tinyint not null default 0 comment '0有效/1停用',
    created_by    bigint not null,
    created_at    datetime not null default current_timestamp,
    updated_at    datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_bacct (org_id, belonger, type)
) engine=innodb default charset=utf8mb4 comment='结算账户(懒开户locate-or-create)';

-- 账户流水(append-only;balance_after=操作后净头寸快照)
create table balance_account_detail (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    account_id    bigint not null comment '账户(balance_account.id)',
    settlement_id bigint null comment '关联结算单(可空=纯入金)',
    action        varchar(24) not null comment '协议键:cashIn出纳入金/settleEnter结算入账/payCash出纳付款/lazySync归属账户同步',
    money         bigint not null comment '发生额(厘,恒正,方向看action)',
    balance_after bigint not null comment '操作后净头寸',
    memo          varchar(200) null,
    operator_id   bigint not null,
    created_at    datetime not null default current_timestamp,
    primary key (id),
    key idx_baccd_acct (org_id, account_id)
) engine=innodb default charset=utf8mb4 comment='账户流水(append-only)';

-- 扣款池(厂商扣款额度池;surplus<=total 不变式)
create table balance_cutpay_pool (
    id         bigint not null auto_increment,
    org_id     bigint not null,
    name       varchar(128) not null,
    type       tinyint not null default 0 comment '字典 cutpayPoolType(0通用/1合作费/2活动费/3管理费/4保证金/5年返;修老字典缺0键生产数据全是0的脱节)',
    supplier   varchar(64) not null default '' comment '归属厂商(空=通用池)',
    total      bigint not null comment '初始额度(厘,恒正)',
    surplus    bigint not null default 0 comment '余量(扣款单创建原子消耗)',
    status     tinyint not null default 0 comment '0有效/1停用',
    created_by bigint not null,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    key idx_bpool (org_id, status)
) engine=innodb default charset=utf8mb4 comment='扣款池';

-- 扣款单(0初始待绑定⇄1已绑定;bind 原子消耗池余量+冲抵结算单;unbind 精确逆操作回补两侧;
-- deduct 子流水留痕不改史,Σdeduct<=money)
create table balance_cutpay (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    pool_id       bigint not null comment '扣款池(balance_cutpay_pool.id)',
    settlement_id bigint null comment '结算单(绑定时落)',
    money         bigint not null comment '扣款额(厘,恒正)',
    type          tinyint not null default 0 comment '0扣款/1返款(返款仅字典预留,V1 bind 拒绝)',
    status        tinyint not null default 0 comment '0初始/1已绑定',
    deduct_total  bigint not null default 0 comment '已拆子流水累计',
    memo          varchar(200) null,
    bound_at      datetime null,
    created_by    bigint not null,
    created_at    datetime not null default current_timestamp,
    updated_at    datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    key idx_bcp_pool (org_id, pool_id, status),
    key idx_bcp_settle (org_id, settlement_id)
) engine=innodb default charset=utf8mb4 comment='扣款单(bind/unbind 对称闭环)';

-- 扣款子流水(append-only 留痕,修老 deduct 核减父单参与 surplus 双计)
create table balance_cutpay_deduct (
    id         bigint not null auto_increment,
    org_id     bigint not null,
    cutpay_id  bigint not null comment '扣款单(balance_cutpay.id)',
    money      bigint not null comment '子流水额(厘,恒正)',
    memo       varchar(200) null,
    created_by bigint not null,
    created_at datetime not null default current_timestamp,
    primary key (id),
    key idx_bcd_cutpay (org_id, cutpay_id)
) engine=innodb default charset=utf8mb4 comment='扣款子流水(仅留痕,不参与冲抵计算)';

-- 结算单操作流水(全动作覆盖,修老 record 结算动作不写+operateTime 全 NULL)
create table balance_settlement_track (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    settlement_id bigint not null,
    action        varchar(32) not null comment 'ready吸附/withdrawn上游退回/lock/unlock/audit/unaudit/settle/discount/unbindItem/enter/pay/payMark/finish',
    detail        varchar(500) null,
    operator_id   bigint not null,
    operator_name varchar(64) not null,
    created_at    datetime not null default current_timestamp,
    primary key (id),
    key idx_btrack (org_id, settlement_id)
) engine=innodb default charset=utf8mb4 comment='结算单操作流水';
