-- V7003:商品导入历史台账(导入器 V1.2;org 级记录,仅成功导入落账——
-- 失败导入整单拒绝由错误明细返回,不入台账(避免噪音),与「全量拒绝制」一致)。
create table basedata_import_history (
    id          bigint not null auto_increment,
    org_id      bigint not null,
    import_type varchar(32) not null comment '导入类型(goods/套餐随 type 扩展)',
    imported    int not null default 0 comment '成功导入行数',
    failed      int not null default 0,
    detail      varchar(500) null comment '摘要(文件名/备注)',
    created_by  bigint null,
    created_at  datetime not null default current_timestamp,
    primary key (id),
    key idx_imp_org (org_id, id)
) engine=innodb default charset=utf8mb4 comment='商品导入历史台账';
