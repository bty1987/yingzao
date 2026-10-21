-- V108:组织树(组织模型裁决 2026-10-21,docs/08;内核 100-199 版本段)——
-- 组织=一棵自引用树(公司→分区→部门→子部门…),层级是深度标签不是独立实体;
-- 不做「集团/分公司」两类公司实体,多公司时树根即集团视角。
-- org_path 物化路径(含自身 id,如 /1/5/12/):子树查询一条 like 免递归;
-- 存量 org(如 1=总部)由迁移种子成为根节点;其余历史 org(如冒烟 org 2)由
-- OrgScopeService 首次触达懒建根,全部既有 org_id 数据零迁移。
create table organization (
    id          bigint not null auto_increment comment '组织 id(=各业务表 org_id)',
    parent_id   bigint null comment '父组织(NULL=根)',
    name        varchar(64) not null comment '组织名称',
    level_type  varchar(16) not null default 'department' comment '层级标签:company/region/department(展示用,不参与权限判定)',
    org_path    varchar(512) not null comment '物化路径(含自身,如 /1/5/12/)',
    status      tinyint not null default 0 comment '0 启用 / 1 停用(停用=子树整体停)',
    created_at  datetime not null default current_timestamp,
    primary key (id),
    key idx_org_parent (parent_id),
    key idx_org_path (org_path(191))
) engine=innodb default charset=utf8mb4 comment='组织树';

-- 种子:org 1 = 总部(根);显式 id 与既有业务表 org_id=1 对齐
insert into organization(id, parent_id, name, level_type, org_path, status)
values (1, null, '总部', 'company', '/1/', 0);
