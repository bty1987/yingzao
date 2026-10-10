-- 用户表(内核 100-199 版本段)
create table yz_user (
    id              bigint not null auto_increment,
    org_id          bigint not null comment '组织(单层)',
    username        varchar(64)  not null,
    password_sha256 varchar(64)  not null,
    display_name    varchar(64)  not null,
    created_at      datetime     not null default current_timestamp,
    primary key (id),
    unique key uk_yz_user_username (username)
) engine=innodb default charset=utf8mb4 comment='用户';

-- 种子账号 admin / yingzao123(sha256 由 MySQL 计算)
insert into yz_user(org_id, username, password_sha256, display_name)
values (1, 'admin', sha2('yingzao123', 256), '营造管理员');
