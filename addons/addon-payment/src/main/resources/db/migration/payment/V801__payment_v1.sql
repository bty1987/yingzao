-- 款项域 V1 真表(addon-payment,错误码段 270000;迁移段 800+)
-- 设计口径:docs/rules/payment.md + docs/prd/addon-payment.md
--   三件套 = 款项单(类型字典化,禁中文名分支)+ 统一收据(双人留痕+多渠道合计平衡)+ 预付款科目化台账
--   (余额表+流水表分离,原子条件扣减——复活老 AdvancePaymentDaoImpl 死代码的 balance>=delta 守卫)。
--   金额口径 = 厘(×1000 定点整数)恒正,方向用 direction/flow_type 表达;跨模块回写全事件化(总线三件套)。
--   表前缀 payment_(军规 3);MySQL 5.6 兼容:索引列宽 ≤767 字节。

-- 款项类型字典(组织级;code 是协议键——老 paymentType 中文字符串承担业务分支的反面教材灭绝)
create table payment_bill_type (
    id             bigint not null auto_increment,
    org_id         bigint not null,
    code           varchar(50)  not null comment '类型编码(协议键,如 deposit/contract/addreduce)',
    name           varchar(64)  not null comment '类型名(展示快照来源)',
    sort           int          not null default 1 comment '收付顺序(>=1;顺序门控,老 sort=0 隐蔽成本款线不迁)',
    scale_template varchar(500) null comment '比例模板 JSON([{sort,scale}],‰;签约拆分数据源,NULL=不拆)',
    status         tinyint      not null default 0 comment '0启用 1停用',
    created_at     datetime not null default current_timestamp,
    updated_at     datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_bill_type (org_id, code),
    key idx_bill_type_org (org_id, status)
) engine=innodb default charset=utf8mb4 comment='款项类型字典';

-- 款项单(业务溯源三元组 biz_addon/biz_type/biz_id 与内核审批同构;类型名/顺序建档快照)
-- direction=0 收款:0 未收 → 1 已收 → 2 已确认(终态);4 已作废
-- direction=1 退款:0 退款审批中 → 1 已退款(终态);4 已作废(驳回/撤回,原款项零改动——修老「驳回顶态」)
create table payment_bill (
    id                     bigint not null auto_increment,
    org_id                 bigint not null,
    biz_addon              varchar(50) not null comment '业务插件 id(溯源三元组)',
    biz_type               varchar(50) not null comment '业务类型',
    biz_id                 varchar(64) not null comment '业务单 id',
    type_code              varchar(50) not null comment '款项类型(payment_bill_type.code)',
    type_name              varchar(64) not null comment '类型名快照',
    direction              tinyint     not null default 0 comment '0收款 1退款',
    origin_bill_id         bigint      null comment '原款项单(direction=1 时必填)',
    origin_status_snapshot tinyint     null comment '退款提交时原款项状态快照',
    amount                 bigint      not null comment '应收/应退金额(厘,恒正)',
    sort                   int         not null default 1 comment '顺序门控(类型字典快照,收款单用)',
    status                 tinyint     not null default 0 comment '见 direction 注释',
    refund_approval_id     bigint      null comment '退款审批单(yz_approval.id)',
    remark                 varchar(200) null,
    created_by             bigint null,
    created_at             datetime not null default current_timestamp,
    updated_at             datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    key idx_bill_biz (org_id, biz_addon, biz_type, biz_id),
    key idx_bill_org_status (org_id, status, direction),
    key idx_bill_origin (origin_bill_id)
) engine=innodb default charset=utf8mb4 comment='款项单';

-- 统一收据(V1 收据与收款款项单 1:1;批量收款 N:1 挂接 V2;收/退共用,方向随款项单)
create table payment_receipt (
    id             bigint not null auto_increment,
    org_id         bigint not null,
    receipt_no     varchar(24)  not null comment '收据号,建档后按 id 生成(R+8位)',
    bill_id        bigint       not null comment '款项单(payment_bill.id)',
    direction      tinyint      not null comment '0收款 1退款',
    amount         bigint       not null comment '收据金额(厘)=Σ渠道行',
    collector_id   bigint       not null comment '收款/退款执行人',
    collector_name varchar(64)  not null comment '执行人姓名快照(双人留痕第一半)',
    collect_time   datetime     not null,
    confirmer_id   bigint      null comment '确认人(留痕第二半,收款单 confirm 时回填)',
    confirmer_name varchar(64) null,
    confirm_time   datetime    null,
    created_at     datetime not null default current_timestamp,
    primary key (id),
    unique key uk_receipt_no (receipt_no),
    key idx_receipt_bill (bill_id)
) engine=innodb default charset=utf8mb4 comment='统一收据';

-- 收据渠道行(V1 渠道仅为方式记录,Σ渠道==应收在服务层强制;渠道插件 V2)
create table payment_receipt_channel (
    id           bigint not null auto_increment,
    org_id       bigint not null,
    receipt_id   bigint       not null comment '收据(payment_receipt.id)',
    channel_code varchar(32)  not null comment '渠道编码(白名单:cash/bank/scan/other)',
    amount       bigint       not null comment '渠道金额(厘,>0)',
    primary key (id),
    key idx_channel_receipt (receipt_id)
) engine=innodb default charset=utf8mb4 comment='收据渠道行';

-- 预付款科目字典(组织自定义;首次访问懒预置 合同/工程/主材 三科目——替代老正林列级硬编码)
create table payment_advance_subject (
    id         bigint not null auto_increment,
    org_id     bigint not null,
    code       varchar(50) not null comment '科目编码(协议键)',
    name       varchar(64) not null,
    status     tinyint not null default 0 comment '0启用 1停用(停用仅挡新变动,存量账户保留)',
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_advance_subject (org_id, code)
) engine=innodb default charset=utf8mb4 comment='预付款科目字典';

-- 预付款账户(科目 × 业务归属一户一行;老「一单一行四科目列」收敛为科目维度行)
create table payment_advance_account (
    id         bigint not null auto_increment,
    org_id     bigint not null,
    subject_id bigint      not null comment '科目(payment_advance_subject.id)',
    ref_type   varchar(50) not null comment '业务归属类型(如 decoration 订单)',
    ref_id     varchar(64) not null comment '业务归属 id(字符串兼容业务键)',
    balance    bigint      not null default 0 comment '余额(厘)=Σ生效流水,对账红线',
    status     tinyint     not null default 0,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_advance_account (org_id, subject_id, ref_type, ref_id),
    key idx_account_subject (subject_id)
) engine=innodb default charset=utf8mb4 comment='预付款账户';

-- 预付款流水(amount 恒正,方向看 flow_type;balance_after 快照使任意时点可对账)
create table payment_advance_flow (
    id             bigint not null auto_increment,
    org_id         bigint not null,
    account_id     bigint      not null comment '账户(payment_advance_account.id)',
    subject_id     bigint      not null,
    flow_type      tinyint     not null comment '1收入 2划扣 3提现 4退回',
    amount         bigint      not null comment '发生额(厘,恒正)',
    balance_after  bigint      not null comment '操作后余额快照',
    biz_addon      varchar(50) null comment '业务溯源(划扣/回补来源三元组)',
    biz_type       varchar(50) null,
    biz_id         varchar(64) null,
    operator_id    bigint      null,
    operator_name  varchar(64) null,
    remark         varchar(200) null,
    created_at     datetime not null default current_timestamp,
    primary key (id),
    key idx_flow_account (account_id),
    key idx_flow_org (org_id, created_at)
) engine=innodb default charset=utf8mb4 comment='预付款流水';
