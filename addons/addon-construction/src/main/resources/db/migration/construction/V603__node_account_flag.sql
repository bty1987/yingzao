-- 决算节点标志(老 ConstructionNodePO.account / ACCOUNT_VALID_INT 口径收编):
--   节点模板加 account(1=参与装修决算检查);排期快照冗余同名列(点火时从模板拷贝,
--   快照自洽不受模板后续改停影响——同「快照存值不存序」纪律)。
-- 消费方:addon-decoration 决算六项检查「施工」子项(经 ConstructionApi 契约,禁直查)。
alter table construction_node
    add column account tinyint not null default 0 comment '0不参与/1参与装修决算检查' after end_talk;

alter table construction_schedule
    add column account tinyint not null default 0 comment '参与决算快照(点火时拷贝自模板)' after accessory_limit;
