-- V1901 驾驶舱:按角色默认落地页最小配置表(PRD 开放问题 6 推荐口径,V1 仅 admin 可配无矩阵)
-- 语义:某角色成员登录后默认落地路由(聚合首页缺省);home_path=菜单 path 身份字符串,
-- 授权闸仍归 RBAC 菜单授权,本表只管"落到哪"。
create table dashboard_role_home (
    id        bigint not null auto_increment,
    org_id    bigint not null,
    role_id   bigint not null comment '角色(yz_role.id)',
    home_path varchar(189) not null comment '默认落地路由(菜单 path)',
    updated_by bigint not null,
    updated_at datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_role_home (org_id, role_id)
) engine=innodb default charset=utf8mb4 comment='按角色默认落地页(V1 最小配置,admin 可配)';
