-- V1302 毛利与管理线子域(addon-balance,docs/prd/addon-balance-margin.md 第 91 次 PRD,
-- 2026-10-28 用户拍板「按推荐」五项:balance 落点/根直接子组织/万分比/实际值恒 null/无审批)——
-- 两类线:集团管理线(主体=组织树根,树根即集团视角 docs/08)/分公司要求线(主体=根的直接子组织,
-- 直接配置不反算);每业务年度固定 17 周期 = 年 1 + 季 4 + 月 12,发布必须整年完整提交;
-- 三口径线值万分比整数(0<x≤10000,5000=50%,对齐 A6「比例制式统一 ppm」全局定案);
-- 版本化:发布即插新版本 + append-only 全量 17 行快照(历史一手证据免链回放,fl-yy 单行
-- upsert+变更链复现的收敛)+ 变更明细仅记 before≠after 行(一人一年多版本,latest=id 最大,
-- 乐观并发闸 baseVersionId 防静默覆盖);分公司行冗余拷贝发布时点当期集团线值,集团行引用列 null
-- (修 fl-yy 一行两用);实际值快照列就位 V1 恒 null(三口径利润源未接,F1 后新发布起填充,存量不回补);
-- 发布无删除无反发布,历史版本只读;本域纯配置零跨域(军规 2 自证);MySQL 5.6 兼容。

-- 版次(一主体一年多版本,无 uk,latest = id 最大)
create table balance_margin_line_version (
    id          bigint not null auto_increment,
    org_id      bigint not null comment '主体组织:集团线=根(1),分公司线=根的直接子组织',
    biz_year    int not null comment '业务年度,如 2026',
    line_type   tinyint not null comment '1 集团管理线 / 2 分公司要求线',
    created_by  bigint not null comment '发布操作人(yz_user)',
    created_at  datetime not null default current_timestamp,
    primary key (id),
    key idx_mlv_org (org_id, biz_year, line_type)
) engine=innodb default charset=utf8mb4 comment='毛利管理线版次';

-- 变更明细(仅 before≠after 记行;change=after-before 读取期计算,不落列)
create table balance_margin_line_change (
    id           bigint not null auto_increment,
    version_id   bigint not null,
    cycle_type   tinyint not null comment '1 年 / 2 季 / 3 月',
    period_value varchar(10) not null comment 'yyyy | yyyy-Qn | yyyy-MM',
    rate_type    tinyint not null comment '1 综合 / 2 基装(工程) / 3 主材',
    before_value int null comment '上一版本线值(万分比),首发无',
    after_value  int null comment '本版本线值(万分比)',
    primary key (id),
    key idx_mlc_version (version_id)
) engine=innodb default charset=utf8mb4 comment='毛利管理线变更明细';

-- 生效快照(append-only 每版本全量 17 行;当前生效 = 主体该年最新版本快照,历史版本行 = 当时口径证据)
create table balance_margin_line_publish (
    id                       bigint not null auto_increment,
    version_id               bigint not null,
    org_id                   bigint not null comment '主体组织(冗余,免 join 证主体)',
    cycle_type               tinyint not null comment '1 年 / 2 季 / 3 月',
    period_value             varchar(10) not null,
    comprehensive_line       int not null comment '综合线(万分比)',
    project_line             int not null comment '基装(工程)线(万分比)',
    main_material_line       int not null comment '主材线(万分比)',
    group_comprehensive_ref  int null comment '当期集团综合线引用(分公司行冗余拷贝;集团行 null)',
    group_project_ref        int null comment '当期集团基装线引用',
    group_main_ref           int null comment '当期集团主材线引用',
    actual_comprehensive_rate int null comment '实际综合毛利率快照(V1 恒 null,F1 后新发布起填充)',
    actual_data_as_of        datetime null comment '实际值数据时点',
    primary key (id),
    unique key uk_mlp (version_id, period_value),
    key idx_mlp_org (org_id, period_value)
) engine=innodb default charset=utf8mb4 comment='毛利管理线生效快照(append-only)';
