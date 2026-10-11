-- 装修单域 V1 真表(addon-decoration,错误码段 250000;迁移段 500+)
-- 设计口径:docs/rules/decoration.md + docs/prd/addon-decoration.md
--   四条状态线(合同/工程/设计/确认)独立推进互不覆盖;合同线显式迁移表白名单(修老 changeDecorationForStatus 裸写);
--   老跳号过渡态 20/30/32/33 不迁(显式审批单代替,终止=提交内核审批→通过回写终态,驳回原状态零改动);
--   单号 = 可配前缀+yyMMdd+日序号(原子计数器+唯一索引兜底);展示名一律建档快照(军规:跨插件只读契约,禁直查他域表)。
--   表前缀 decoration_(军规 3);MySQL 5.6 兼容:索引列宽 ≤767 字节。

-- 装修单主单(客户×楼盘唯一在途;金额一律厘)
-- status 合同线:0初始 → 1定金 → 2预签 → 3签约 → 4重签 → 7决算 → 8完工;终态支线 5废单/6终止
create table decoration_order (
    id             bigint not null auto_increment,
    org_id         bigint not null,
    order_no       varchar(32) not null comment '单号(前缀+yyMMdd+4位日序号)',
    customer_id    bigint      not null comment '客户档案(customerprofile,经契约校验)',
    customer_name  varchar(64) not null comment '客户名快照(展示用)',
    house_id       bigint      not null comment '房屋档案(customerprofile,经契约校验)',
    house_label    varchar(200) not null comment '房屋展示快照(楼盘+四要素)',
    type           tinyint     not null comment '单类型:0半包 1包干 2整装(决定口径表,算法 V2)',
    status         tinyint     not null default 0 comment '合同线:0初始/1定金/2预签/3签约/4重签/5废单/6终止/7决算/8完工',
    project_status tinyint     not null default 0 comment '工程线:0初始/1开工/2完工',
    design_status  tinyint     not null default 0 comment '设计线:0待设计/1设计中/2已完成',
    source_status  int         not null default 0 comment '确认线:0待确认/1合同确认/2放线确认;变体11微改/21大改(%10==1=合同确认位)',
    designer_id    bigint      not null comment '设计师(归属线快照)',
    salesman_id    bigint      not null comment '销售顾问(归属线快照)',
    timelimit      int         null comment '工期(天,整数;老百万分位天不迁)',
    deposit_time   datetime    null comment '定金时间(首收定金款事件回写)',
    contract_time  datetime    null comment '首签时间',
    recontract_time datetime   null comment '重签时间(不覆盖首签)',
    account_time   datetime    null comment '决算时间',
    finish_time    datetime    null comment '完工时间',
    termination_approval_id bigint null comment '终止审批单(yz_approval.id,回写守卫)',
    remark         varchar(200) null,
    created_by     bigint      not null,
    created_at     datetime not null default current_timestamp,
    updated_at     datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_order_no (org_id, order_no),
    key idx_order_org_status (org_id, status),
    key idx_order_customer (org_id, customer_id),
    key idx_order_house (org_id, house_id)
) engine=innodb default charset=utf8mb4 comment='装修单主单';

-- 1:1 补充信息(老 decorationInfo;面积口径 m²×1000 与房屋档案一致;预签门槛 V1=本信息已登记)
create table decoration_order_profile (
    order_id             bigint not null comment '装修单(decoration_order.id,1:1)',
    org_id               bigint not null,
    inner_area           bigint null comment '套内面积(m²×1000,与房屋档案同口径)',
    house_type           varchar(32) null comment '房型快照(如 三室两厅)',
    style                varchar(32) null comment '装修风格',
    expect_drawline_time datetime null comment '预计放线时间(签约门槛 V2 回补,随工程域)',
    note                 varchar(500) null comment '客户需求',
    updated_at           datetime not null default current_timestamp on update current_timestamp,
    primary key (order_id)
) engine=innodb default charset=utf8mb4 comment='装修单补充信息(1:1)';

-- 服务团队(老 decorationUser;role 协议键灭绝中文分支;人员姓名建档快照)
create table decoration_order_team (
    id         bigint not null auto_increment,
    org_id     bigint not null,
    order_id   bigint      not null comment '装修单(decoration_order.id)',
    user_id    bigint      not null comment '成员(yz_user.id)',
    user_name  varchar(64) not null comment '姓名快照',
    role       varchar(32) not null comment '角色协议键:designer/salesman/escort/other',
    created_at datetime not null default current_timestamp,
    primary key (id),
    unique key uk_order_team (order_id, user_id, role)
) engine=innodb default charset=utf8mb4 comment='装修单服务团队';

-- 归属(设计师/销售两条线;rate 千分比 1000=独占;转归属=旧行失效+新行,合尺 V2)
-- status: 0未生效(新建) → 1有效;2失效(被转出/终止联动)——老 2=待确认副本/3=已审批 合尺两阶段不迁
create table decoration_order_belong (
    id         bigint not null auto_increment,
    org_id     bigint not null,
    order_id   bigint     not null comment '装修单(decoration_order.id)',
    line       tinyint    not null comment '归属线:0设计师 1销售',
    user_id    bigint     not null comment '归属人(yz_user.id)',
    user_name  varchar(64) not null comment '姓名快照',
    rate       int        not null default 1000 comment '分佣比例(千分比,1000=独占;合尺各半=500 V2)',
    status     tinyint    not null default 0 comment '0未生效/1有效/2失效',
    remark     varchar(200) null,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    key idx_belong_order (org_id, order_id, line, status),
    key idx_belong_user (org_id, line, user_id, status)
) engine=innodb default charset=utf8mb4 comment='装修单归属';

-- 操作轨迹(老 decorationOperate;action 协议键;append-only 审计口径,状态变化记前后值)
create table decoration_order_track (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    order_id      bigint       not null comment '装修单(decoration_order.id)',
    action        varchar(32)  not null comment '动作协议键:create/deposit/presign/sign/resign/obsolete/terminate/account/finish/designConfirm/drawlineConfirm/measureApply/measureSuccess/measureFail/profileSave/belongTransfer',
    from_value    int          null comment '状态迁移前值(合同线/确认线/量房线)',
    to_value      int          null comment '状态迁移后值',
    operator_id   bigint       not null comment '操作人',
    operator_name varchar(64)  not null comment '操作人姓名快照',
    comment       varchar(200) null,
    created_at    datetime not null default current_timestamp,
    primary key (id),
    key idx_track_order (order_id, created_at)
) engine=innodb default charset=utf8mb4 comment='装修单操作轨迹';

-- 量房/放线计划(老 decorationmeasure;状态机 0排队→1已申请→2成功/3失败,失败可重申)
-- 老按公司时间重叠查重不迁(方林嫌疑:按公司隔离→团队日历 V2);成功联动主单工程线 0→1 开工
create table decoration_order_measure (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    order_id      bigint  not null comment '装修单(decoration_order.id)',
    status        tinyint not null default 0 comment '0排队/1已申请/2成功/3失败',
    plan_time     datetime null comment '预计量房时间',
    apply_time    datetime null comment '申请时间',
    finish_time   datetime null comment '办结时间(成功/失败)',
    operator_id   bigint   null comment '申请人',
    operator_name varchar(64) null,
    remark        varchar(200) null,
    created_at    datetime not null default current_timestamp,
    updated_at    datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    key idx_measure_order (order_id, status)
) engine=innodb default charset=utf8mb4 comment='装修单量房/放线计划';

-- 单号日计数器(前缀组织级固定 YZ;原子自增 + 主单唯一索引兜底)
create table decoration_order_counter (
    org_id   bigint not null,
    seq_date char(6) not null comment 'yyMMdd',
    seq      int    not null default 0,
    primary key (org_id, seq_date)
) engine=innodb default charset=utf8mb4 comment='装修单号日计数器';
