-- 组织级决算检查配置(懒初始化;老 systemconfig per-company 九散键收敛,军规 7 单层组织)。
-- 六项检查口径见 docs/rules/decoration.md §8:任一 FAIL → 拒决算(250023);全关 = 保持 V1 行为
-- (款项全收齐单一硬闸)。target 单位 ‰(老 99 百分比口径统一换算为 990)。
create table decoration_config (
    org_id                    bigint not null,
    check_enable              tinyint not null default 0 comment '决算检查总闸 0关/1开(关=款项全收齐硬闸,开=六子项合成)',
    check_sign_enable         tinyint not null default 0 comment '子项:已签约(0关/1开;迁移白名单已硬闸,恒 PASS)',
    check_sales_enable        tinyint not null default 0 comment '子项:主材销售无未完结单(经 SalesApi,0关/1开)',
    check_construction_enable tinyint not null default 0 comment '子项:决算节点全完工(经 ConstructionApi,0关/1开)',
    check_selection_enable    tinyint not null default 0 comment '子项:选材(预留列;选材域插件交付前恒 UNCHECK)',
    check_deco_payment_enable tinyint not null default 0 comment '子项:装修收款比例闸(0关/1开;开=替代款项全收齐硬闸)',
    check_sale_payment_enable tinyint not null default 0 comment '子项:销售收款比例闸(经 SalesApi.collectedRatio,0关/1开)',
    deco_payment_target       int not null default 990 comment '装修收款目标(‰;990=99%)',
    sale_payment_target       int not null default 990 comment '销售收款目标(‰)',
    created_at                datetime not null default current_timestamp,
    updated_at                datetime not null default current_timestamp on update current_timestamp,
    primary key (org_id)
) engine=innodb default charset=utf8mb4 comment='装修组织级配置(决算六项检查)';
