-- V1204:库存组织级配置 + 领料定材校验闸(B 类#7)。
-- 老口径(rules/inventory.md §3):领料受三闸——工程辅料额度双闸(施工域,已交付)+
-- **定材校验**(quotation→normtion→noods 按定额量拦 260082,老 AccessoriesItemServiceImpl:105-147)。
-- 营造收口:定材校验=组织级开关(默认关=行为不变),开启后按「报价挂定材包 → 包成员定额量
-- + 包浮动量」拦累计领用(同量纲千分位直接比较)。
create table inventory_config (
    org_id              bigint not null,
    quota_check_enabled tinyint not null default 0
        comment '领料定材校验开关 0关(=V1 行为不变) 1开',
    updated_at          datetime not null default current_timestamp on update current_timestamp,
    primary key (org_id)
) engine=innodb default charset=utf8mb4 comment='库存组织级配置';
