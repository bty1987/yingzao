-- 三口径表(A6:半包配比/整装满减/包干增项;错误码 250030-250032;迁移段 500+)
-- 设计口径:docs/rules/decoration.md §4-§5(三口径表统一门禁+金额口径)
--   老三张口径表(decorationmidpack/decorationholonomy/decorationinclusive)行业中性回归:
--   本质 = 「合同价与主材/服务费指标如何从默认报价推导」的三种商务口径;
--   统一门禁(老三表 make 前置链一致):主单合同线 status∈{0,1,2,4}(签约/决算/完工锁死 250002)
--   + 默认报价存在且定稿锁定(经 QuotationApi 契约 250030);老「订单活动锁定」门禁不迁(活动域未建,随活动域接入);
--   重算前置 = 口径行初始态(确认后冻结 250031;老 Holonomy servicePaidup=0 冻结=已收款冻结,
--   营造款线不在此链,口径确认即冻结);老 update Midpack 仅允许改 materialPrice → make 幂等重建+手填额入参;
--   口径与款线解耦:老 make 后重建款计划(makeDecorationPayment),营造款线=签约 scaleTemplate 拆分,
--   口径表只产推导指标(Inclusive 推导总额可作签约 contractAmount 输入),不回写装修单——诚实边界;
--   比例制式显式化:配比/费率统一 ppm(百万分比,200000=20%)——老 ppm/万分比/千分比/百分比四制式并存不迁;
--   金额一律厘(老 1e6 缩放黑盒不迁);表前缀 decoration_(军规 3);MySQL 5.6 兼容。

-- 半包配比口径(主材配额 = 工程基础额 × 主材配比;服务费 = 主材配额 × 服务费率)
create table decoration_caliber_midpack (
    id             bigint not null auto_increment,
    org_id         bigint not null,
    order_id       bigint not null comment '装修单(decoration_order.id)',
    quotation_id   bigint not null comment '默认报价快照(quotation_quotation.id)',
    material_rate  int not null default 0 comment '主材配比(ppm,200000=20%;0=手填主材额)',
    service_rate   int not null default 0 comment '服务费率(ppm,基数=主材配额)',
    project_base   bigint not null default 0 comment '工程基础额(厘,=报价全总额快照)',
    material_price bigint not null default 0 comment '主材配额(厘,推导或手填)',
    material_origin bigint not null default 0 comment '初始推导额(厘,重算对照)',
    service_price  bigint not null default 0 comment '服务费(厘,推导)',
    total_price    bigint not null default 0 comment '推导总额(厘,=工程基础+主材+服务费)',
    status         tinyint not null default 0 comment '0初始(可重算)/1确认(冻结)',
    remark         varchar(200) null,
    created_by     bigint not null,
    created_at     datetime not null default current_timestamp,
    updated_at     datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_midpack_order (org_id, order_id)
) engine=innodb default charset=utf8mb4 comment='半包配比口径';

-- 整装满减口径(折上折:基数取整余数不打折;主材配额同半包;管理费可选跟随折扣)
create table decoration_caliber_holonomy (
    id             bigint not null auto_increment,
    org_id         bigint not null,
    order_id       bigint not null comment '装修单(decoration_order.id)',
    quotation_id   bigint not null comment '默认报价快照(quotation_quotation.id)',
    discount_base  bigint not null default 0 comment '满减基数(厘;工程额按基数取整,余数不打折)',
    discount       int not null default 10000 comment '折扣(万分比,9000=0.9)',
    manage_follow  tinyint not null default 0 comment '管理费跟随折扣(0不跟随/1跟随)',
    maching        int not null default 0 comment '主材配比(ppm)',
    service_rate   int not null default 0 comment '服务费率(ppm,基数=主材配额)',
    project_base   bigint not null default 0 comment '工程基础额(厘,=报价全总额快照)',
    project_price  bigint not null default 0 comment '折后工程额(厘,余数不打折算法)',
    manage_price   bigint not null default 0 comment '管理费(厘,可跟随折扣)',
    material_price bigint not null default 0 comment '主材配额(厘,推导)',
    service_price  bigint not null default 0 comment '服务费(厘,推导)',
    total_price    bigint not null default 0 comment '推导总额(厘)',
    status         tinyint not null default 0 comment '0初始(可重算)/1确认(冻结)',
    remark         varchar(200) null,
    created_by     bigint not null,
    created_at     datetime not null default current_timestamp,
    updated_at     datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_holonomy_order (org_id, order_id)
) engine=innodb default charset=utf8mb4 comment='整装满减口径';

-- 包干增项口径(合同总额 = 报价总价 + 常规增减 + 会碰(MET)增减按口径分支)
create table decoration_caliber_inclusive (
    id             bigint not null auto_increment,
    org_id         bigint not null,
    order_id       bigint not null comment '装修单(decoration_order.id)',
    quotation_id   bigint not null comment '默认报价快照(quotation_quotation.id)',
    base_total     bigint not null default 0 comment '报价总价快照(厘)',
    common_undis   bigint not null default 0 comment '常规增减未折额(厘,加正减负)',
    common_dis     bigint not null default 0 comment '常规增减折后额(厘,加正减负,原值入库不再乘折扣)',
    common_manage  bigint not null default 0 comment '常规增减管理费(厘)',
    common_tax     bigint not null default 0 comment '常规增减税金(厘)',
    met_undis      bigint not null default 0 comment '会碰(MET)增减未折额(厘)',
    met_dis        bigint not null default 0 comment '会碰增减折后额(厘)',
    met_manage     bigint not null default 0 comment '会碰增减管理费(厘)',
    met_tax        bigint not null default 0 comment '会碰增减税金(厘)',
    met_flag       tinyint not null default 1 comment '会碰计入口径:1 UPMET仅正计/2 DOWNMET仅负计/3 COMMET无条件(老公司配置改口径行参数,逐单可选)',
    total_price    bigint not null default 0 comment '推导合同总额(厘,可作签约 contractAmount 输入)',
    status         tinyint not null default 0 comment '0初始(可重算)/1确认(冻结)',
    remark         varchar(200) null,
    created_by     bigint not null,
    created_at     datetime not null default current_timestamp,
    updated_at     datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_inclusive_order (org_id, order_id)
) engine=innodb default charset=utf8mb4 comment='包干增项口径';
