-- 客户与楼盘档案 V1 真表(addon-customerprofile,错误码段 330000;迁移段 1400+,V300 为 spike 遗留)
-- 设计口径:docs/rules/customerprofile.md + docs/prd/addon-customerprofile.md
--   敏感字段(电话/身份证/门牌/账号)AES-GCM 密文落库,等值检索与唯一约束走 HMAC 列;
--   唯一键全部下沉 DB(get-or-create 幂等的兜底);面积 ×1000 定点整数;坐标 ×10^6 定点整数;
--   手机号=客户身份锚点,唯一约束只作用于「启用中」的号码(active_hmac 非空才参与唯一);
--   房屋不可删(无 delete);客户状态 0 初始/1 已实名(老系统 2=已认证为退役态,不再设置);
--   表前缀 customerprofile_(军规 3 插件自有表); MySQL 5.6 兼容:索引列宽 ≤767 字节。

-- spike 期的 lead 级镜像表,由 V1 真表(客户主档 + get-or-create 事件链)取代
drop table if exists customer_profile;

-- 城市 / 城区:平台共享地理字典(无 org 维度;楼盘归属组织,字典不归属)
create table customerprofile_city (
    id          bigint not null auto_increment,
    name        varchar(64) not null,
    created_at  datetime    not null default current_timestamp,
    primary key (id),
    unique key uk_city_name (name)
) engine=innodb default charset=utf8mb4 comment='城市字典';

create table customerprofile_city_proper (
    id          bigint not null auto_increment,
    city_id     bigint not null comment '城市(customerprofile_city.id)',
    name        varchar(64) not null,
    created_at  datetime    not null default current_timestamp,
    primary key (id),
    unique key uk_proper_city_name (city_id, name),
    key idx_proper_city (city_id)
) engine=innodb default charset=utf8mb4 comment='城区字典';

-- 楼盘(树:parent_id 可空,老系统 pvid 自指改为 NULL=根)
create table customerprofile_village (
    id            bigint not null auto_increment,
    org_id        bigint not null comment '组织(0=平台共享池)',
    no            varchar(32) not null comment '楼盘编号,建档后按 id 生成',
    name          varchar(64) not null,
    city_id       bigint not null,
    city_proper_id bigint not null,
    parent_id     bigint null comment '父楼盘(期/区),NULL=根',
    developer     varchar(64) null,
    delivery_time date null comment '交房时间',
    x             int not null default 0 comment '经度×10^6',
    y             int not null default 0 comment '纬度×10^6',
    mileage       int not null default 0 comment '与公司距离(米)',
    content       varchar(512) null,
    status        tinyint not null default 0 comment '0启用 1禁用',
    created_at    datetime not null default current_timestamp,
    updated_at    datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_village_no (no),
    unique key uk_village_name (org_id, city_proper_id, name),
    key idx_village_org_status (org_id, status)
) engine=innodb default charset=utf8mb4 comment='楼盘档案';

-- 房屋(楼牌号全文密文 + HMAC 唯一 + 四段结构化;不可删)
create table customerprofile_house (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    village_id    bigint not null comment '楼盘(customerprofile_village.id)',
    number_cipher varchar(160) not null comment '门牌全文密文(v1:...)',
    number_hmac   char(64) not null comment '门牌 HMAC(等值检索/唯一)',
    built         varchar(32) null comment '栋',
    unit          varchar(32) null comment '单元',
    floor_num     int null comment '楼层',
    door_num      int null comment '门牌号',
    build_area    int null comment '建筑面积×1000',
    free_area     int null comment '赠送面积×1000',
    inner_area    int null comment '计价面积×1000',
    total_area    int null comment '总面积×1000',
    house_type    varchar(64) null comment '户型文本,如 3室2厅1厨2卫',
    created_by    bigint null comment '建档人(yz_user.id)',
    created_at    datetime not null default current_timestamp,
    updated_at    datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_house_number (village_id, number_hmac),
    key idx_house_org (org_id)
) engine=innodb default charset=utf8mb4 comment='房屋档案';

-- 客户主档案(一人一档;手机号=身份锚点镜像默认号)
create table customerprofile_customer (
    id              bigint not null auto_increment,
    org_id          bigint not null,
    no              varchar(16) not null comment '客户编号,建档后按 id 生成(C+8位)',
    name            varchar(64) not null,
    gender          varchar(16) null,
    phone_cipher    varchar(80) null comment '默认号码密文(镜像 customer_phone)',
    phone_hmac      char(64) null,
    identity_cipher varchar(160) null comment '身份证密文',
    address         varchar(256) null,
    status          tinyint not null default 0 comment '0初始 1已实名',
    created_by      bigint null comment '建档人(yz_user.id)',
    created_at      datetime not null default current_timestamp,
    updated_at      datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_customer_no (no),
    key idx_customer_org_phone (org_id, phone_hmac),
    key idx_customer_org_name (org_id, name)
) engine=innodb default charset=utf8mb4 comment='客户主档案';

-- 客户电话(多号码;active_hmac 仅启用时非空 ⇒ 唯一约束只锁启用中的号码)
create table customerprofile_customer_phone (
    id           bigint not null auto_increment,
    org_id       bigint not null,
    customer_id  bigint not null comment '客户(customerprofile_customer.id)',
    phone_cipher varchar(80) not null,
    phone_hmac   char(64) not null,
    active_hmac  char(64) null comment '启用中=phone_hmac,停用=NULL(唯一约束只作用启用)',
    status       tinyint not null default 0 comment '0启用 1停用',
    is_default   tinyint not null default 0 comment '1=默认号码(每客户至多一个,服务层事务保证)',
    created_at   datetime not null default current_timestamp,
    primary key (id),
    unique key uk_phone_active (org_id, active_hmac),
    key idx_phone_customer (customer_id)
) engine=innodb default charset=utf8mb4 comment='客户电话';

-- 客户收款银行账户(档案,非余额;单一事实源,不与客户主档做对公六要素同步)
create table customerprofile_customer_account (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    customer_id   bigint not null,
    type          tinyint not null default 0 comment '0对私 1对公',
    holder_name   varchar(64) not null comment '户名',
    bank_name     varchar(64) null comment '开户行',
    account_cipher varchar(200) not null comment '账号/卡号密文',
    account_hmac  char(64) not null,
    status        tinyint not null default 0 comment '0启用 1停用(停用=软删)',
    is_default    tinyint not null default 0 comment '1=默认账户(每客户至多一个,服务层事务保证)',
    created_at    datetime not null default current_timestamp,
    updated_at    datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_account_no (customer_id, account_hmac),
    key idx_account_org (org_id)
) engine=innodb default charset=utf8mb4 comment='客户收款账户';

-- 电话查询审计(谁在何时看了谁的号码;解密接口强制留痕)
create table customerprofile_phone_access_log (
    id          bigint not null auto_increment,
    org_id      bigint not null,
    customer_id bigint not null,
    phone_id    bigint not null,
    accessed_by bigint not null comment '查看人(yz_user.id)',
    purpose     varchar(64) null comment '查看事由',
    created_at  datetime not null default current_timestamp,
    primary key (id),
    key idx_access_org_customer (org_id, customer_id)
) engine=innodb default charset=utf8mb4 comment='电话查询审计';
