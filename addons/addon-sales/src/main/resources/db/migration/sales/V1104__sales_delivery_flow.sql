-- B2 配送域 V2:品牌配送流程模板 + 里程碑分批结算(addon-sales,错误码段 300200;迁移 V1104)
-- 设计口径:docs/prd/addon-sales-delivery.md(行业中性化,2026-10-31 第 106 次 PRD)
--   老机制只读考证:Rhadamanthus SalesDeliveryServiceImpl(递归连打+分成克隆)+ SalesDeliveryStatusRightPO
--   (checked+crate 两义同表——老缺陷,拆正交配置不抄实现);
--   两正交概念:品牌配送流程模板(免人工位,finish 恒人工防自答)+ 里程碑分批结算行(认领计划视图,
--   Σ 分批 = 预结总额,末批=总额−已批合计补尾差);预结行(sales_settlement)三段式全额不动,零资金轴改动;
--   配置粒度品牌级(uk org+brand),未配置=四级全人工(V1 现行为不变);分批行只随 settle_id 引用,
--   归属字段零克隆(修老 SerializationUtils 整行连抄);免人工自动推进 track 记 auto_ 前缀留痕(修老无留痕)。

-- 品牌配送流程模板(一行一品牌;免人工位与四级分成比例正交配置:只配流程不配分成/只配分成不配流程均合法)
-- finish(完结级)恒人工不设免人工位;分成比例万分比(0=该级不分成,启用时 Σ四级=10000 强守卫 300201)
create table sales_delivery_flow (
    id           bigint not null auto_increment,
    org_id       bigint not null,
    brand_id     bigint not null comment '品牌(basedata_brand,经 BrandApi 校验)',
    auto_tran    tinyint not null default 0 comment '配送级免人工 0/1',
    auto_check   tinyint not null default 0 comment '审核级免人工 0/1',
    auto_confirm tinyint not null default 0 comment '确认级免人工 0/1(finish 恒人工无位)',
    rate_tran    int not null default 0 comment '配送里程碑分成(万分比)',
    rate_check   int not null default 0 comment '审核里程碑分成',
    rate_confirm int not null default 0 comment '确认里程碑分成',
    rate_finish  int not null default 0 comment '完结里程碑分成',
    updated_by   bigint not null,
    updated_at   datetime not null default current_timestamp on update current_timestamp,
    created_at   datetime not null default current_timestamp,
    primary key (id),
    unique key uk_flow (org_id, brand_id)
) engine=innodb default charset=utf8mb4 comment='品牌配送流程模板(免人工位+里程碑分成,正交)';

-- 里程碑分批结算行(认领计划视图:预结行按品牌配置比例的里程碑拆分;幂等 uk;修老 deliver_schedule
-- 魔法值=显式 milestone 字典/Σcrate 不校验=末批补尾差+Σ守卫/克隆行无关联=settle_id 强关联)
create table sales_settlement_batch (
    id          bigint not null auto_increment,
    org_id      bigint not null,
    settle_id   bigint not null comment '结算行(sales_settlement.id,type=0 购货预结行)',
    milestone   tinyint not null comment '1配送/2审核/3确认/4完结(字典 deliveryMilestone)',
    rate        int not null comment '万分比快照(生成时点计划)',
    total       bigint not null default 0 comment '预结应收×份额(厘,HALF_UP;末批=总额−已批合计)',
    balance     bigint not null default 0 comment '结算基值份额',
    vbalance    bigint not null default 0 comment '厂结值份额',
    pbalance    bigint not null default 0 comment '结算值份额',
    difference  bigint not null default 0 comment '让利差份额',
    status      tinyint not null default 0 comment '0计划/1已认领(厂结/结算完成回写,只标记不守卫)',
    claimed_at  datetime null,
    created_at  datetime not null default current_timestamp,
    primary key (id),
    unique key uk_batch (org_id, settle_id, milestone)
) engine=innodb default charset=utf8mb4 comment='里程碑分批结算行(认领计划视图,Σ=预结总额)';
