-- RBAC 最小设施(内核 100-199 版本段)
-- 角色-菜单-功能点授权:菜单身份 = addon.yml menus[].path(路径即身份),功能点身份 = mounts[].capability。
-- 语义:绑定内置 admin 角色(builtin=1)= 平台管理员,旁路全部过滤;普通用户可见菜单/功能点 = 其全部角色的授权并集。
-- 吸收老系统 functionright「思想好实现散」的教训重设计:授权只存身份字符串,菜单随插件动态下发,授权行不随插件启停漂移。
create table yz_role (
    id         bigint      not null auto_increment,
    org_id     bigint      not null comment '组织(单层)',
    code       varchar(50) not null comment '角色编码(小写字母开头,内置 admin)',
    name       varchar(64) not null comment '角色名称',
    builtin    tinyint     not null default 0 comment '1=内置角色(不可删/不可改授权)',
    remark     varchar(200),
    created_at datetime    not null default current_timestamp,
    primary key (id),
    unique key uk_yz_role_org_code (org_id, code)
) engine=innodb default charset=utf8mb4 comment='角色';

create table yz_user_role (
    user_id bigint not null comment 'yz_user.id',
    role_id bigint not null comment 'yz_role.id',
    primary key (user_id, role_id),
    key idx_yz_user_role_role (role_id)
) engine=innodb default charset=utf8mb4 comment='用户-角色绑定';

create table yz_role_menu (
    role_id   bigint       not null comment 'yz_role.id',
    menu_path varchar(189) not null comment '菜单路径(addon.yml menus[].path;5.6 utf8mb4 索引 767B 上限内)',
    primary key (role_id, menu_path)
) engine=innodb default charset=utf8mb4 comment='角色-菜单授权';

create table yz_role_capability (
    role_id    bigint       not null comment 'yz_role.id',
    capability varchar(100) not null comment '功能点(addon.yml mounts[].capability)',
    primary key (role_id, capability)
) engine=innodb default charset=utf8mb4 comment='角色-功能点授权';

-- 种子:组织 1 内置管理员角色 + 绑定种子账号 admin
insert into yz_role(org_id, code, name, builtin, remark)
values (1, 'admin', '管理员', 1, '内置:平台管理员,旁路全部权限过滤');
insert into yz_user_role(user_id, role_id)
select u.id, r.id from yz_user u join yz_role r on r.org_id = u.org_id and r.code = 'admin'
where u.username = 'admin';
