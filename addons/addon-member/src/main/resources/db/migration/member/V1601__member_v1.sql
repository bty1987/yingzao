-- 会员营销域 V1 真表(addon-member,错误码段 350000;迁移段 1600+,supplier 1500+ 之后)
-- 设计口径:docs/rules/member.md + docs/prd/addon-member.md
--   老系统"会员"无独立实体=五处四模块拼盘(活动=information.activity/积分=customer.cppoint 三表/
--   兑换=sales.salesactivityitem/核销=decoration.decorationactivityitem/残缺台账=payment.paymentactivity),
--   积分真源在外部积分商城,ERP 侧只是回写通道+台账镜像。
--   营造口径:积分账务收编本插件一体落地——
--   ① 活动主档:时间窗真生效(修老 queryActivityForValid 不看时间 + 9 条积分 SQL 无时段 + start/end 恒 null 双哑弹);
--     validays 必填 >0(修老 VO 不拷贝恒 0 → 可用装修积分恒 0);每 org+type 至多一个默认(可空唯一列);
--   ② 积分账户:分项(org+customer+pointType)各持 balance,事件驱动记分(消费收款确认/退款事件,
--     废除老"实时 SQL 聚合 9 条"口径);余额扣减一律条件 UPDATE(WHERE balance>=x,修老 read-modify-write 互踩);
--     流水只追加,uk(org,src_addon,src_type,src_id,type) 事件幂等锚(总线重放不重复记账);
--     🔴 量纲红线:积分=整数,禁任何隐式缩放(老 Arith.DEF_SCALE=3 使 mul(x,1e6)=×1000、div(v,1000) 恒等,
--     两域积分差 1000 倍——字面与行为全面背离,照抄必炸);
--   ③ 礼品:积分价 integral_price(整数积分)与金额价 unit_price(厘,业绩/金额口径)显式分列
--     (老 unitPrice 单列被商城当积分值复用,量纲混乱根源);三开关 isPrefor/isStock/isPayment 语义 0=算 1=不算 原样保留;
--     库存条件 UPDATE 原子扣(修老 9 处 check-then-act 竞态可超卖);no 全局唯一=外部商城商品一等公民
--     (幂等 upsert 沿 supplier ext_no 模式);去老"总部仓硬编码 company=0",按 org 建库存;
--   ④ 兑换:商城单号 exchange_no 全局唯一幂等 upsert(修老 makeMemberOrder 无防重);每单每线上礼品限一件
--     (DB 唯一约束+服务层去重,修老 off-by-one count>1 才拒);撤销必须校验状态(修老 cancelMemberOrder
--     不校验状态可作废已核销);落单与扣积分/扣库存同事务;核销一次性(状态守卫+过期拦截 350009);
--   ⑤ 组织级配置 member_config:装修签约自动核销开关与说明(修老 content 硬编码'签约自动核销'字面量)。
--   表前缀 member_(军规 3);MySQL 5.6 兼容:索引列宽 ≤767 字节、无函数索引、utf8mb4。

-- 活动主档(积分商城心脏:倍率/有效期/适用域唯一来源)
create table member_activity (
    id          bigint not null auto_increment,
    org_id      bigint not null,
    name        varchar(64) not null comment '活动名称',
    type        tinyint not null comment '适用域 1装修 2主材 3销售',
    multiple    int not null default 1000 comment '积分倍率,千分位 1000=1倍(对齐老系统运行行为,§13-1)',
    validays    int not null comment '收款后有效天数(>0):记分批次 expire_time = 收款确认时刻 + validays 天',
    start_time  datetime not null comment '生效起(落单/记分查询真生效)',
    end_time    datetime not null comment '生效止',
    is_default  tinyint null comment '1=该组织该域默认活动(NULL=非默认;可空唯一列:每 org+type 至多一个 1)',
    status      tinyint not null default 0 comment '0有效 1失效(可逆;停用默认活动允许,重新启用即恢复)',
    remark      varchar(255) null,
    created_by  bigint null comment '建档人(yz_user.id)',
    created_at  datetime not null default current_timestamp,
    updated_at  datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_member_activity_default (org_id, type, is_default),
    key idx_member_activity_org (org_id, status, type)
) engine=innodb default charset=utf8mb4 comment='会员活动主档';

-- 礼品主档(含实时库存数;扣减一律条件 UPDATE)
create table member_gift (
    id             bigint not null auto_increment,
    org_id         bigint not null,
    no             varchar(32) not null comment '礼品编号(全局唯一,外部商城商品一等公民;不传自动 MG+8位按 id)',
    name           varchar(64) not null,
    integral_price bigint not null comment '兑换积分价(整数积分,消灭老系统 ×1000/×1e6 三尺度)',
    unit_price     bigint null comment '金额单价(厘):isPrefor 业绩/isPayment 金额口径消费,可空',
    is_prefor      tinyint not null default 0 comment '0=算业绩 1=不算(老三开关语义原样保留)',
    is_stock       tinyint not null default 0 comment '0=算库存(兑换原子扣减) 1=不算',
    is_payment     tinyint not null default 0 comment '0=计金额 1=不计',
    stock_num      bigint not null default 0 comment '当前库存(条件 UPDATE stock_num>=x 原子扣,修老 9 处竞态)',
    status         tinyint not null default 0 comment '0可用 1禁用',
    remark         varchar(255) null,
    created_at     datetime not null default current_timestamp,
    updated_at     datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_member_gift_no (no),
    key idx_member_gift_org (org_id, status)
) engine=innodb default charset=utf8mb4 comment='礼品主档与库存';

-- 礼品库存流水(只追加;修老 AwardRecord 同构,出入库/盘点/兑换/撤销全留痕)
create table member_gift_record (
    id          bigint not null auto_increment,
    org_id      bigint not null,
    gift_id     bigint not null comment '礼品(member_gift.id)',
    gift_no     varchar(32) not null comment '编号快照',
    type        tinyint not null comment '0入库 1出库 2盘盈 3盘亏',
    subtype     varchar(32) not null comment '业务动作:manual/exchange/revoke/checkin/checkout 等',
    num         bigint not null comment '变动数量(恒正,方向由 type 表达)',
    after_num   bigint not null comment '变动后库存快照(对账红线:库存=Σ流水可复核)',
    biz_addon   varchar(32) null comment '来源域(兑换联动溯源)',
    biz_type    varchar(32) null,
    biz_id      varchar(64) null,
    remark      varchar(255) null,
    created_by  bigint null,
    created_at  datetime not null default current_timestamp,
    primary key (id),
    key idx_member_gift_record_gift (org_id, gift_id, id)
) engine=innodb default charset=utf8mb4 comment='礼品库存流水(append-only)';

-- 积分账户(分项:org+customer+pointType 各持余额;懒开户)
create table member_point_account (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    customer_id   bigint not null comment '客户(customerprofile_customer.id,主数据唯一写点纪律:本插件不建客户)',
    customer_name varchar(64) null comment '客户名快照(开户时经 CustomerProfileApi,展示用)',
    point_type    tinyint not null comment '分项 1装修 2主材 3运营(运营仅手工调整,不可兑换)',
    balance     bigint not null default 0 comment '余额(整数积分;含已过期批次——过期只拦兑换/核销,不清零)',
    created_at  datetime not null default current_timestamp,
    updated_at  datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_member_point_account (org_id, customer_id, point_type)
) engine=innodb default charset=utf8mb4 comment='积分分项账户';

-- 积分流水(只追加;uk=事件幂等锚,总线重放不重复记账)
create table member_point_record (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    customer_id   bigint not null,
    point_type    tinyint not null comment '分项 1装修 2主材 3运营',
    type          tinyint not null comment '1收款记分 2兑换扣减 3撤销回补 4退款冲减 5运营调整',
    delta         bigint not null comment '变动(带符号;积分=整数,禁隐式缩放)',
    balance_after bigint not null comment '变动后余额快照',
    expire_time   datetime null comment '记分批次过期时刻(收款确认+validays;负向行/手工行 NULL=永不过期)',
    activity_id   bigint null comment '记分来源活动(倍率与有效期快照依据)',
    src_addon     varchar(32) null comment '幂等锚:来源域(payment/member;手工行 NULL)',
    src_type      varchar(32) null comment '幂等锚:来源类型(order/bill/exchange/exchange_cancel/exchange_item_cancel;手工行 NULL)',
    src_id        varchar(64) null comment '幂等锚:来源单 id(手工行 NULL;MySQL 唯一索引多 NULL 合法)',
    reason        varchar(255) null comment '运营调整原因(必填)或说明',
    created_by    bigint null,
    created_at    datetime not null default current_timestamp,
    primary key (id),
    unique key uk_member_point_src (org_id, src_addon, src_type, src_id, type),
    key idx_member_point_record_cust (org_id, customer_id, point_type, id)
) engine=innodb default charset=utf8mb4 comment='积分流水(append-only,事件幂等锚)';

-- 兑换单(外部商城同步落单;exchange_no 幂等 upsert)
create table member_exchange (
    id           bigint not null auto_increment,
    org_id       bigint not null,
    exchange_no  varchar(64) not null comment '外部商城单号(全局唯一,幂等 upsert 锚点;重复推送回显不重扣)',
    customer_id  bigint not null,
    customer_name varchar(64) null comment '客户名快照(落单时经 CustomerProfileApi,展示用)',
    point_type   tinyint not null comment '本单消耗分项 1装修 2主材(3运营不可兑 350017)',
    order_id     bigint null comment '关联装修单(签约自动核销匹配键;可空)',
    point_total  bigint not null comment '总消耗积分',
    status       tinyint not null default 0 comment '0已受理 1已核销(全部明细核销) 2已撤销 3已发货(V2 预留不投产)',
    remark       varchar(255) null,
    created_by   bigint null comment '落单操作人(商城服务账号 yz_user.id)',
    created_at   datetime not null default current_timestamp,
    updated_at   datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_member_exchange_no (exchange_no),
    key idx_member_exchange_org (org_id, status, id),
    key idx_member_exchange_order (org_id, customer_id, order_id)
) engine=innodb default charset=utf8mb4 comment='兑换单';

-- 兑换明细(每单每线上礼品限一件=uk 兜底,修老 off-by-one)
create table member_exchange_item (
    id              bigint not null auto_increment,
    org_id          bigint not null,
    exchange_id     bigint not null comment '兑换单(member_exchange.id)',
    gift_id         bigint not null,
    gift_no         varchar(32) not null comment '快照',
    gift_name       varchar(64) not null comment '快照',
    integral_price  bigint not null comment '单件积分价快照',
    num             bigint not null default 1 comment '件数(V1 恒 1:每单每线上礼品限一件)',
    integral        bigint not null comment '本行积分 = integral_price × num',
    status          tinyint not null default 0 comment '0受理 1已核销 2已撤销',
    expire_time     datetime null comment '落单时可用批次最早过期时刻(展示与核销参考;实时闸以流水聚合为准)',
    consumed_at     datetime null,
    consumed_by     bigint null,
    consume_content varchar(128) null comment '核销说明(人工/签约自动核销取组织配置,修老硬编码)',
    created_at      datetime not null default current_timestamp,
    updated_at      datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_member_exchange_item (exchange_id, gift_id),
    key idx_member_exchange_item_org (org_id, exchange_id)
) engine=innodb default charset=utf8mb4 comment='兑换单明细';

-- 组织级配置(懒初始化;签约自动核销修老硬编码)
create table member_config (
    org_id               bigint not null,
    auto_sign_consume    tinyint not null default 0 comment '装修签约自动核销开关 0关 1开',
    sign_consume_content varchar(128) not null default '签约自动核销' comment '自动核销说明(修老 content 硬编码字面量)',
    updated_at           datetime not null default current_timestamp on update current_timestamp,
    primary key (org_id)
) engine=innodb default charset=utf8mb4 comment='会员营销组织级配置';
