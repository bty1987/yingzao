-- 电子名片子域 V2(D6b 精简版,docs/prd/addon-market-card.md 按推荐拍板 2026-10-28;
-- 码段 280100–280150;老四表 card/cuser/cardPhoto/cnotice 重做)。
-- 修老缺陷:①一人一卡 uk(org,user) 强约束;②手机 AES+hash 双列全域红线(老明文+GET query
-- 明文外发三重违例);③卡片与访客解耦(老 cuser nullable 无条件解引用未绑卡整页 500);
-- ④visitor uk(org,union_id)(老 upsert 先查后插无唯一索引);⑤留资通知事件化零网络出事务
-- (老事务内同步 httpGet 明文 PII 外发);⑥删名片级联相册(老孤儿图片记录);⑦isv 配置键两义
-- 拆 enabled/notifyUrl 两键 V1 不启用。表前缀 market_card_(军规 3);MySQL 5.6 兼容。

create table market_card (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    user_id       bigint not null comment '归属销售员(yz_user.id,一人一卡)',
    user_photo    varchar(500) null comment '头像 URL',
    user_name     varchar(64) not null comment '名片姓名(展示快照)',
    phone_cipher  varchar(128) not null comment '手机密文(AES-256-GCM v1:,CryptoBox)',
    phone_hash    varchar(64) not null comment '手机 HMAC(等值检索)',
    wechat        varchar(64) null,
    industry      varchar(64) null,
    profession    varchar(64) null,
    address       varchar(200) null,
    info          varchar(1000) null comment '个人简介',
    status        tinyint not null default 0 comment '0启用/1停用(停用前台 280103)',
    share_token   varchar(64) not null comment '分享链接随机串(名片页路由锚,重置即旧链接失效)',
    created_at    datetime not null default current_timestamp,
    updated_at    datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_card_user (org_id, user_id),
    unique key uk_card_token (share_token)
) engine=innodb default charset=utf8mb4 comment='电子名片(一人一卡)';

create table market_card_photo (
    id         bigint not null auto_increment,
    org_id     bigint not null,
    card_id    bigint not null comment '名片(market_card.id,删名片级联)',
    url        varchar(500) not null,
    sort_no    int not null default 0,
    created_at datetime not null default current_timestamp,
    primary key (id),
    key idx_cphoto_card (card_id, sort_no)
) engine=innodb default charset=utf8mb4 comment='名片相册';

create table market_card_visitor (
    id               bigint not null auto_increment,
    org_id           bigint not null,
    union_id         varchar(64) not null comment '微信 unionId(授权后回填;uk 强约束修老 upsert 无索引)',
    first_visit_time datetime not null default current_timestamp,
    last_visit_time  datetime not null default current_timestamp,
    primary key (id),
    unique key uk_visitor_union (org_id, union_id)
) engine=innodb default charset=utf8mb4 comment='名片访客身份(V1 匿名留资可空关联,授权回填)';

create table market_card_notice (
    id           bigint not null auto_increment,
    org_id       bigint not null,
    card_id      bigint not null comment '名片(market_card.id;老 suid/ruid 双向语义单义化=直接挂卡)',
    visitor_id   bigint null comment '访客(market_card_visitor.id;匿名留资为空)',
    name         varchar(64) not null,
    phone_cipher varchar(128) not null comment '手机密文(AES-256-GCM v1:)',
    phone_hash   varchar(64) not null comment '手机 HMAC(同人同卡幂等回显锚+频次闸)',
    comment      varchar(500) null,
    status       tinyint not null default 0 comment '0待跟进/1已读(守卫=名片主人,修老任意写)',
    reader_id    bigint null,
    read_time    datetime null,
    created_at   datetime not null default current_timestamp,
    primary key (id),
    key idx_cnotice_card (card_id, status, id),
    key idx_cnotice_hash (card_id, phone_hash)
) engine=innodb default charset=utf8mb4 comment='名片留资(通知经 CardNoticeCreatedEvent 零网络出事务)';
