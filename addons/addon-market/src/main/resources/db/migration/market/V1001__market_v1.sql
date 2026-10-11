-- 营销获客域 V1(addon-market,错误码段 280000;迁移段 1000+)
-- 老系统 com.fanglin.market(2026-07 新增模块)的行业模型收敛:幂等键下沉 DB 唯一约束
-- (修老 phone 空掺 UUID + 先查后插无锁)、渠道/投放源补恢复接口(修老停用即终局)、
-- 归因逐级落行(insert-only)、承接走领域事件+回写契约(修老 REQUIRES_NEW 孤儿事务)。
-- 金额口径:预算为厘(×1000),面积千分位,与全域统一。

-- 单号计数器(MI 进线流水;last_insert_id 原子自增)
create table market_counter (
    id         bigint not null auto_increment,
    org_id     bigint not null,
    prefix     varchar(8)  not null comment '单号前缀',
    ymd        varchar(8)  not null comment '日段 yyyyMMdd',
    seq        int         not null default 0,
    primary key (id),
    unique key uk_market_counter (org_id, prefix, ymd)
) engine=innodb default charset=utf8mb4 comment='营销单号计数器';

-- 营销活动(status: 0草稿 2发布 3停用 5归档;老 1 warm/4 finish 设计半成品死态不迁)
create table market_campaign (
    id           bigint not null auto_increment,
    org_id       bigint not null,
    name         varchar(128) not null,
    description  varchar(512) null,
    budget       bigint not null default 0 comment '预算(厘,仅记录不管控)',
    status       tinyint not null default 0,
    publish_time datetime null,
    created_at   datetime not null default current_timestamp,
    updated_at   datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_market_campaign (org_id, name),
    key idx_market_campaign_org (org_id, status)
) engine=innodb default charset=utf8mb4 comment='营销活动';

-- 投放渠道(status: 0启用 1停用;带恢复接口,修老无恢复停用即终局)
create table market_channel (
    id         bigint not null auto_increment,
    org_id     bigint not null,
    name       varchar(128) not null,
    status     tinyint not null default 0,
    created_at datetime not null default current_timestamp,
    primary key (id),
    unique key uk_market_channel (org_id, name),
    key idx_market_channel_org (org_id, status)
) engine=innodb default charset=utf8mb4 comment='投放渠道';

-- 投放源(活动×渠道的落地物:渠道码/落地页 URL/UTM 三件套;code 组织内唯一 DB 兜底)
create table market_source (
    id           bigint not null auto_increment,
    org_id       bigint not null,
    campaign_id  bigint not null comment '归属活动',
    channel_id   bigint not null comment '归属渠道',
    name         varchar(128) not null,
    code         varchar(32)  not null comment '渠道码(组织内唯一)',
    url          varchar(512) null comment '落地页地址',
    utm_source   varchar(64) null,
    utm_medium   varchar(64) null,
    utm_campaign varchar(64) null,
    status       tinyint not null default 0 comment '0启用 1停用',
    created_at   datetime not null default current_timestamp,
    primary key (id),
    unique key uk_market_source_code (org_id, code),
    key idx_market_source_campaign (org_id, campaign_id),
    key idx_market_source_channel (org_id, channel_id)
) engine=innodb default charset=utf8mb4 comment='投放源';

-- 进线(归因三键冗余反范式换统计效率;幂等键组织内唯一 DB 兜底;手机密文+hmac 双轨)
create table market_inbound (
    id             bigint not null auto_increment,
    org_id         bigint not null,
    inbound_no     varchar(32) not null comment 'MI+yyMMdd+4位流水',
    campaign_id    bigint null comment '归因三键(报名进线为空)',
    channel_id     bigint null,
    source_id      bigint null,
    case_id        bigint null comment '案例报名来源(market_ecase.id)',
    case_name      varchar(128) null comment '案例名快照(案例删除后历史可读)',
    name           varchar(64) null comment '访客姓名',
    phone_cipher   varchar(128) null comment '手机密文(AES-256-GCM v1:)',
    phone_hash     varchar(64) null comment '手机 HMAC(等值检索)',
    idempotent_key varchar(80) not null comment '幂等键(HMAC(org-投放三元组/case-phoneHash))',
    accept_mode    varchar(16) not null default 'chance' comment 'source仅客源/chance客源+机会',
    note           varchar(512) null,
    status         tinyint not null default 0 comment '0待承接 1承接成功 2承接失败 3承接中 4已忽略',
    error_msg      varchar(512) null,
    customer_lead_id bigint null comment '承接结果:客源(customer_lead.id)',
    chance_id        bigint null comment '承接结果:机会(customer_chance.id)',
    accept_time    datetime null,
    ignored_by     bigint null,
    created_at     datetime not null default current_timestamp,
    primary key (id),
    unique key uk_market_inbound_key (org_id, idempotent_key),
    unique key uk_market_inbound_no (org_id, inbound_no),
    key idx_market_inbound_campaign (org_id, campaign_id),
    key idx_market_inbound_channel (org_id, channel_id),
    key idx_market_inbound_source (org_id, source_id),
    key idx_market_inbound_case (org_id, case_id),
    key idx_market_inbound_status (org_id, status)
) engine=innodb default charset=utf8mb4 comment='营销进线';

-- 进线动态表单字段(EAV,承接落地页不定字段;行业共性保留)
create table market_inbound_item (
    id          bigint not null auto_increment,
    inbound_id  bigint not null,
    field_key   varchar(64) not null,
    field_value varchar(512) null,
    primary key (id),
    key idx_market_inbound_item (inbound_id)
) engine=innodb default charset=utf8mb4 comment='进线动态字段';

-- 归因流水(insert-only 四级漏斗逐级落行:0提交 1建客源 2建机会;老 order 级无写点不迁,三级口径)
create table market_attribution (
    id          bigint not null auto_increment,
    org_id      bigint not null,
    inbound_id  bigint not null,
    campaign_id bigint null,
    channel_id  bigint null,
    source_id   bigint null,
    event_type  tinyint not null comment '0提交 1建客源 2建机会',
    ref_id      bigint null comment '引用 id(客源/机会)',
    created_at  datetime not null default current_timestamp,
    primary key (id),
    key idx_market_attr_campaign (org_id, campaign_id, event_type),
    key idx_market_attr_channel (org_id, channel_id, event_type),
    key idx_market_attr_inbound (inbound_id)
) engine=innodb default charset=utf8mb4 comment='归因流水(insert-only)';

-- 进线操作留痕(登记/重试/忽略/承接失败;修老 retry 无权限校验无日志)
create table market_track (
    id          bigint not null auto_increment,
    inbound_id  bigint not null,
    action      varchar(32) not null comment 'submit/retry/ignore/acceptFail',
    reason      varchar(512) null,
    operator_id bigint not null default 0 comment '0=系统承接链',
    created_at  datetime not null default current_timestamp,
    primary key (id),
    key idx_market_track (inbound_id)
) engine=innodb default charset=utf8mb4 comment='进线操作留痕';

-- 手机号读取留痕(解密强制留痕;修老密文泄漏无痕)
create table market_phone_access_log (
    id          bigint not null auto_increment,
    inbound_id  bigint not null,
    operator_id bigint not null,
    action      varchar(32) not null default 'reveal',
    created_at  datetime not null default current_timestamp,
    primary key (id),
    key idx_market_phone_log (inbound_id)
) engine=innodb default charset=utf8mb4 comment='进线手机号读取留痕';

-- 案例城市字典(老地理定位回退硬编码"沈阳"不迁,默认运营城市 V1 走组织配置,先给字典)
create table market_ecity (
    id         bigint not null auto_increment,
    org_id     bigint not null,
    name       varchar(64) not null,
    pinyin     varchar(128) null,
    status     tinyint not null default 0 comment '0启用 1停用',
    created_at datetime not null default current_timestamp,
    primary key (id),
    unique key uk_market_ecity (org_id, name)
) engine=innodb default charset=utf8mb4 comment='案例城市字典';

-- 案例人物库(老 eperson:type 0设计师/1监理)
create table market_eperson (
    id         bigint not null auto_increment,
    org_id     bigint not null,
    name       varchar(64) not null,
    pinyin     varchar(128) null,
    type       tinyint not null default 0 comment '0设计师 1监理',
    title      varchar(64) null comment '头衔',
    status     tinyint not null default 0 comment '0启用 1停用',
    created_at datetime not null default current_timestamp,
    primary key (id),
    unique key uk_market_eperson (org_id, type, name)
) engine=innodb default charset=utf8mb4 comment='案例人物库';

-- 案例(上下架布尔开关非审核流,行业共性保留;面积千分位;工期天仅施工图,效果图恒 0)
create table market_ecase (
    id           bigint not null auto_increment,
    org_id       bigint not null,
    city_id      bigint not null comment '城市(market_ecity.id)',
    designer_id  bigint null comment '设计师(market_eperson.id)',
    supervisor_id bigint null comment '监理(market_eperson.id)',
    name         varchar(128) not null,
    village      varchar(128) null comment '楼盘',
    style        varchar(32) null comment '风格',
    layout       varchar(32) null comment '户型',
    area         bigint null comment '面积(千分位,㎡×1000)',
    cycle        int null comment '施工工期(天,仅施工图)',
    cover_url    varchar(512) null,
    type         tinyint not null default 0 comment '0效果图 1施工图',
    status       tinyint not null default 0 comment '0下架 1上架',
    created_at   datetime not null default current_timestamp,
    updated_at   datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    key idx_market_ecase_org (org_id, city_id, type, status)
) engine=innodb default charset=utf8mb4 comment='官网案例';

-- 案例图片(删案例级联删行,修老孤儿文件记录)
create table market_epicture (
    id         bigint not null auto_increment,
    case_id    bigint not null,
    url        varchar(512) not null,
    sort       int not null default 0,
    primary key (id),
    key idx_market_epicture (case_id)
) engine=innodb default charset=utf8mb4 comment='案例图片';
