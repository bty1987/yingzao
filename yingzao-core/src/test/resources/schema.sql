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
