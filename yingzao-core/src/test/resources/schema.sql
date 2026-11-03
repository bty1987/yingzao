-- F4a 内核单测 schema(H2 MODE=MySQL):镜像 V100/V102+V111/V104/V105/V106/V108/V112
-- 的最小列面(注释/二级索引省略;生产 DDL 仍以 Flyway 迁移为唯一事实源,禁手工 SQL 红线不变
-- ——本文件仅为 test 域内存库自举,不属部署链路)。
create table yz_user (
    id              bigint not null auto_increment,
    org_id          bigint not null,
    username        varchar(64) not null,
    password_sha256 varchar(64) not null,
    display_name    varchar(64) not null,
    status          tinyint not null default 0,
    created_at      datetime not null default current_timestamp,
    primary key (id),
    constraint uk_yz_user_username unique (username)
);

create table organization (
    id          bigint not null auto_increment,
    parent_id   bigint null,
    name        varchar(64) not null,
    level_type  varchar(16) not null default 'department',
    org_path    varchar(512) not null,
    status      tinyint not null default 0,
    created_at  datetime not null default current_timestamp,
    primary key (id)
);

create table yz_approval (
    id           bigint not null auto_increment,
    org_id       bigint not null,
    title        varchar(200) not null,
    biz_addon    varchar(50) not null,
    biz_type     varchar(50) not null,
    biz_id       varchar(64) not null,
    form_data    text null,
    attachments  text null,
    applicant_id bigint not null,
    assignee_id  bigint not null,
    status       tinyint not null default 0,
    created_at   datetime not null default current_timestamp,
    finished_at  datetime null,
    primary key (id)
);

create table yz_approval_record (
    id          bigint not null auto_increment,
    approval_id bigint not null,
    action      tinyint not null,
    operator_id bigint not null,
    assignee_id bigint null,
    comment     varchar(500) null,
    created_at  datetime not null default current_timestamp,
    primary key (id)
);

create table yz_event_task (
    id              bigint not null auto_increment,
    event_type      varchar(128) not null,
    payload         text not null,
    event_key       varchar(189) null,
    status          tinyint not null default 0,
    retry_count     int not null default 0,
    max_retries     int not null default 5,
    next_retry_time datetime null,
    claimed_at      datetime null,
    last_error      varchar(1024) null,
    created_at      datetime not null default current_timestamp,
    updated_at      datetime not null default current_timestamp,
    primary key (id)
);

create table yz_event_consumed (
    task_id     bigint not null,
    consumer    varchar(128) not null,
    consumed_at datetime not null default current_timestamp,
    primary key (task_id, consumer)
);

create table yz_plan (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    handler       varchar(128) not null,
    biz_key       varchar(128) null,
    params        text null,
    status        tinyint not null default 0,
    max_retries   int not null default 3,
    retry_count   int not null default 0,
    next_run_time datetime null,
    claimed_at    datetime null,
    result        text null,
    last_error    text null,
    created_by    bigint null,
    created_at    datetime not null default current_timestamp,
    updated_at    datetime not null default current_timestamp,
    primary key (id)
);

create table yz_audit_log (
    id          bigint not null auto_increment,
    category    tinyint not null default 0,
    org_id      bigint not null default 0,
    user_id     bigint not null default 0,
    user_name   varchar(64) not null default '',
    action      varchar(128) not null,
    module      varchar(64) not null default '',
    object_type varchar(64) not null default '',
    object_id   varchar(64) not null default '',
    summary     varchar(512) not null default '',
    detail      text null,
    ip          varchar(64) not null default '',
    result      tinyint not null default 0,
    error_code  int null,
    error_msg   varchar(255) not null default '',
    run_time    int not null default 0,
    created_at  datetime not null,
    primary key (id)
);


-- W1 工作台族(target 主材业绩线单测)
create table target_plan (
    id         bigint not null auto_increment,
    org_id     bigint not null,
    org_node   bigint not null,
    ym         char(7) not null,
    line       varchar(24) not null default 'contract',
    amount     bigint not null,
    status     tinyint not null default 0,
    remark     varchar(200) null,
    created_by bigint null,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    primary key (id),
    constraint uk_target unique (org_id, org_node, ym, line)
);

create table target_stage (
    id         bigint not null auto_increment,
    org_id     bigint not null,
    plan_id    bigint not null,
    stage_key  varchar(32) not null,
    sort_no    int not null default 1,
    begin_day  date null,
    end_day    date null,
    amount     bigint not null default 0,
    remark     varchar(200) null,
    status     tinyint not null default 0,
    created_by bigint null,
    created_at datetime not null default current_timestamp,
    primary key (id),
    constraint uk_stage unique (plan_id, stage_key)
);


-- F4b addon 单测批(decoration/sales/payment/quotation 状态机守卫回归,第 125 次)
-- 镜像 V501/V502/V1101/V1102/V801/V802/V401/V405 最小列面,Flyway 红线不变,仅 test 域自举。

-- ---- decoration(V501/V502 最小列面) ----
create table decoration_order (
    id             bigint not null auto_increment,
    org_id         bigint not null,
    order_no       varchar(32) not null,
    customer_id    bigint      not null,
    customer_name  varchar(64) not null,
    house_id       bigint      not null,
    house_label    varchar(200) not null,
    type           tinyint     not null,
    status         tinyint     not null default 0 comment '合同线 0-8',
    project_status tinyint     not null default 0 comment '工程线 0/1/2',
    design_status  tinyint     not null default 0 comment '设计线 0/1/2',
    source_status  int         not null default 0 comment '确认线 0/1/2/11/21',
    designer_id    bigint      not null,
    salesman_id    bigint      not null,
    deposit_time   datetime null,
    contract_time  datetime null,
    recontract_time datetime null,
    account_time   datetime null,
    finish_time    datetime null,
    termination_approval_id bigint null,
    remark         varchar(200) null,
    created_by     bigint not null,
    created_at     datetime not null default current_timestamp,
    updated_at     datetime not null default current_timestamp,
    primary key (id),
    unique key uk_order_no (org_id, order_no)
);

create table decoration_order_profile (
    order_id   bigint not null,
    org_id     bigint not null,
    inner_area bigint null,
    house_type varchar(64) null,
    style      varchar(64) null,
    note       varchar(200) null,
    primary key (order_id)
);

create table decoration_order_belong (
    id       bigint not null auto_increment,
    org_id   bigint not null,
    order_id bigint not null,
    user_id  bigint not null default 0,
    role     int    not null default 0,
    status   tinyint not null default 1,
    primary key (id)
);

create table decoration_order_track (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    order_id      bigint not null,
    action        varchar(32) not null,
    from_value    int null,
    to_value      int null,
    operator_id   bigint not null,
    operator_name varchar(64) not null,
    comment       varchar(200) null,
    created_at    datetime not null default current_timestamp,
    primary key (id)
);

create table decoration_config (
    org_id                    bigint not null,
    check_enable              tinyint not null default 0,
    check_sign_enable         tinyint not null default 0,
    check_sales_enable        tinyint not null default 0,
    check_construction_enable tinyint not null default 0,
    check_selection_enable    tinyint not null default 0,
    check_deco_payment_enable tinyint not null default 0,
    check_sale_payment_enable tinyint not null default 0,
    deco_payment_target       int not null default 990,
    sale_payment_target       int not null default 990,
    created_at                datetime not null default current_timestamp,
    updated_at                datetime not null default current_timestamp,
    primary key (org_id)
);

-- ---- sales(V1101/V1102 最小列面) ----
create table sales_order (
    id                bigint not null auto_increment,
    org_id            bigint not null,
    code              varchar(32) not null,
    order_id          bigint not null,
    order_no          varchar(32) not null,
    customer_name     varchar(128) not null,
    house_label       varchar(256) not null,
    salesman_id       bigint null,
    status            tinyint not null default 0 comment '0初始/2全退/3倒单/4废单/5完结',
    balance_status    tinyint not null default 0,
    fullpay_status    tinyint not null default -1,
    deposit_amount    bigint not null default 0,
    deposit_status    tinyint not null default -1,
    customer_confirmed tinyint not null default 0,
    confirmed_at      datetime null,
    total             bigint not null default 0,
    finish_time       datetime null,
    remark            varchar(200) null,
    created_by        bigint not null,
    created_by_name   varchar(64) not null,
    created_at        datetime not null default current_timestamp,
    updated_at        datetime not null default current_timestamp,
    primary key (id),
    unique key uk_sales_code (org_id, code)
);

create table sales_order_item (
    id          bigint not null auto_increment,
    org_id      bigint not null,
    sale_id     bigint not null,
    package_id  bigint not null default 0,
    goods_id    bigint not null default 0,
    color_id    bigint not null default 0,
    format_id   bigint not null default 0,
    goods_name  varchar(128) not null,
    color_name  varchar(128) not null default '',
    format_name varchar(128) not null default '',
    brand_id    bigint not null default 0,
    brand_name  varchar(64) not null default '',
    num         bigint not null,
    initnum     bigint not null default 0,
    punum       bigint not null default 0,
    xgnum       bigint not null default 0,
    trnum       bigint not null default 0,
    selling     bigint not null default 0,
    discount    bigint not null default 1000,
    dis_price   bigint not null default 0,
    total       bigint not null default 0,
    created_at  datetime not null default current_timestamp,
    primary key (id)
);

create table sales_track (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    sale_id       bigint not null,
    action        varchar(32) not null,
    detail        varchar(500) null,
    operator_id   bigint not null,
    operator_name varchar(64) not null,
    created_at    datetime not null default current_timestamp,
    primary key (id)
);

create table sales_settlement (
    id         bigint not null auto_increment,
    org_id     bigint not null,
    sale_id    bigint not null,
    brand_id   bigint not null default 0,
    brand_name varchar(64) not null default '',
    type       int not null default 0,
    onetype    int not null default 0,
    rid        bigint not null default 0,
    total      bigint not null default 0,
    balance    bigint not null default 0,
    vbalance   bigint not null default 0,
    pbalance   bigint not null default 0,
    difference bigint not null default 0,
    status     tinyint not null default 0,
    vposted_by bigint null,
    vposted_at datetime null,
    posted_by  bigint null,
    posted_at  datetime null,
    created_by bigint not null,
    created_at datetime not null default current_timestamp,
    primary key (id),
    unique key uk_settle (org_id, sale_id, brand_id, type, rid)
);

create table sales_refunds (
    id              bigint not null auto_increment,
    org_id          bigint not null,
    sale_id         bigint not null,
    status          tinyint not null default 0,
    affirmed_at     datetime null,
    remark          varchar(200) null,
    created_by      bigint not null,
    created_by_name varchar(64) not null,
    created_at      datetime not null default current_timestamp,
    updated_at      datetime not null default current_timestamp,
    primary key (id)
);

create table sales_delivery (
    id              bigint not null auto_increment,
    org_id          bigint not null,
    sale_id         bigint not null,
    status          tinyint not null default 0,
    remark          varchar(200) null,
    created_by      bigint not null,
    created_by_name varchar(64) not null,
    created_at      datetime not null default current_timestamp,
    primary key (id)
);

create table sales_delivery_item (
    id          bigint not null auto_increment,
    org_id      bigint not null,
    delivery_id bigint not null default 0,
    sale_item_id bigint not null default 0,
    status      tinyint not null default 0,
    goods_name  varchar(128) not null default '',
    created_at  datetime not null default current_timestamp,
    primary key (id)
);

-- ---- payment(V801/V802 最小列面) ----
create table payment_bill_type (
    id         bigint not null auto_increment,
    org_id     bigint not null,
    code       varchar(50) not null,
    name       varchar(64) not null,
    sort       int not null default 1,
    scale_template varchar(500) null,
    status     tinyint not null default 0,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    primary key (id),
    unique key uk_bill_type (org_id, code)
);

create table payment_bill (
    id             bigint not null auto_increment,
    org_id         bigint not null,
    biz_addon      varchar(50) not null,
    biz_type       varchar(50) not null,
    biz_id         varchar(64) not null,
    type_code      varchar(50) not null,
    type_name      varchar(64) not null,
    direction      tinyint not null default 0,
    origin_bill_id bigint null,
    origin_status_snapshot tinyint null,
    amount         bigint not null,
    sort           int not null default 1,
    status         tinyint not null default 0 comment '0未收/1已收/2已确认/3已退款/4已作废',
    refund_approval_id bigint null,
    remark         varchar(200) null,
    created_by     bigint null,
    created_at     datetime not null default current_timestamp,
    updated_at     datetime not null default current_timestamp,
    primary key (id)
);

create table payment_receipt (
    id             bigint not null auto_increment,
    org_id         bigint not null,
    receipt_no     varchar(24) not null,
    bill_id        bigint not null,
    batch_no       varchar(24) null,
    direction      tinyint not null,
    amount         bigint not null,
    collector_id   bigint not null,
    collector_name varchar(64) not null,
    collect_time   datetime not null,
    confirmer_id   bigint null,
    confirmer_name varchar(64) null,
    confirm_time   datetime null,
    created_at     datetime not null default current_timestamp,
    primary key (id),
    unique key uk_receipt_no (receipt_no)
);

create table payment_receipt_channel (
    id           bigint not null auto_increment,
    org_id       bigint not null,
    receipt_id   bigint not null,
    channel_code varchar(32) not null,
    amount       bigint not null,
    primary key (id)
);

-- ---- quotation(V401/V405 最小列面) ----
create table quotation_quotation (
    id              bigint not null auto_increment,
    org_id          bigint not null,
    order_id        bigint not null,
    order_no        varchar(32) not null,
    customer_name   varchar(64) not null,
    house_label     varchar(200) not null,
    name            varchar(64) not null,
    version_id      bigint null,
    material_package_id bigint null,
    status          tinyint not null default 0 comment '定稿线 0编辑中/1锁定',
    attstatus       tinyint not null default 0 comment '审核线 0初始/1已提交/2已审核/3已完成',
    is_default      tinyint not null default 0,
    discount        int not null default 10000,
    manage_rate     int not null default 0,
    manage_discount int not null default 10000,
    carry_rate      int not null default 0,
    carry_discount  int not null default 10000,
    tax_rate        int not null default 0,
    tax_discount    int not null default 10000,
    undis_price     bigint not null default 0,
    discount_price  bigint not null default 0,
    total_price     bigint not null default 0,
    lock_time       datetime null,
    submit_time     datetime null,
    audit_time      datetime null,
    finish_time     datetime null,
    remark          varchar(200) null,
    created_by      bigint not null,
    created_at      datetime not null default current_timestamp,
    updated_at      datetime not null default current_timestamp,
    primary key (id)
);

create table quotation_track (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    quotation_id  bigint not null,
    action        varchar(32) not null,
    from_value    int null,
    to_value      int null,
    from_att      int null,
    to_att        int null,
    operator_id   bigint not null,
    operator_name varchar(64) not null,
    comment       varchar(200) null,
    created_at    datetime not null default current_timestamp,
    primary key (id)
);

create table quotation_norm_item (
    id              bigint not null auto_increment,
    org_id          bigint not null,
    order_id        bigint not null,
    quotation_id    bigint null,
    source_type     tinyint not null comment '0报价/1拓展/2增减/3附加',
    source_id       bigint not null default 0,
    kind            tinyint not null comment '0材料/1人工/2工时',
    archive_id      bigint null,
    pack_item_id    bigint not null default 0,
    area_id         bigint null,
    item_id         bigint null,
    catalog_item_id bigint null,
    goods_id        bigint null,
    goods_name      varchar(128) null,
    node_id         bigint null,
    node_name       varchar(64) null,
    name            varchar(128) not null,
    unit            varchar(16) null,
    atttype         tinyint not null default 0 comment '0收/1支',
    num             bigint not null default 0,
    norm_num        bigint not null default 0,
    trannum         bigint not null default 1000,
    salary          bigint not null default 0,
    workdays        bigint not null default 0,
    status          tinyint not null default 0 comment '0初始/1禁用/2确认/3驳回',
    confirmer       bigint null,
    confirm_time    datetime null,
    created_at      datetime not null default current_timestamp,
    primary key (id)
);
