-- 售后三件套 V1(addon-service,错误码段 380000;迁移段 1800+)
-- 设计口径:docs/prd/addon-service.md(已确认 2026-10-26,按推荐)+ docs/rules/construction.md §9(老三件套考证)
--   合并建模:fl-yy 退单回访任务闭环为骨架(草稿→提交完成→标记无法完成),R 本体装修回访场景化挂 biz_type;
--   老缺陷全部废弃不迁:String 日期/operaterId+Name 字符串冗余/type+level 前端字典/零守卫 CRUD;
--   投诉状态机扩展 0 待处理→1 处理中→2 已处理(处理结果必填;承诺时限超期=查询期计算不落列);
--   回访任务三来源:手工/投诉处理联动(组织开关 visit_on_complaint 默认开)/竣工验收联动
--   (visit_on_acceptance 默认关;监听 ConstructionAcceptedEvent,消费时实时读开关,exists 查重);
--   保修 append-only+uk(org,order) 一单一保;保修期=起点+months(查询期计算,不落到期列);
--   金额无(本域零金额);日期 datetime/date 类型列(老 String 不迁);表前缀 service_(军规 3);MySQL 5.6 兼容。

-- 组织级配置(懒初始化:首次读取无行用默认并落行)
create table service_config (
    org_id                bigint not null,
    visit_on_complaint    tinyint not null default 1 comment '投诉处理完联动生成回访任务(1开/0关)',
    visit_on_acceptance   tinyint not null default 0 comment '竣工验收联动生成回访任务(1开/0关)',
    acceptance_visit_days int not null default 7 comment '验收回访计划日偏移(天)',
    warranty_months       int not null default 24 comment '保修时长默认(月)',
    updated_at            datetime not null default current_timestamp on update current_timestamp,
    primary key (org_id)
) engine=innodb default charset=utf8mb4 comment='售后服务组织级配置';

-- 投诉单
create table service_complaint (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    order_id      bigint not null comment '装修单(decoration_order.id,经契约校验)',
    order_no      varchar(32) not null comment '单号快照',
    customer_name varchar(64) not null comment '客户名快照',
    type          varchar(16) not null comment '投诉类型(字典 complaintType:quality/service/design/other)',
    level         varchar(16) not null comment '级别(字典 complaintLevel:normal/urgent)',
    content       varchar(500) not null comment '投诉内容',
    handle_result varchar(500) null comment '处理结果(标记已处理必填)',
    handler_id    bigint null comment '处理人',
    handler_name  varchar(64) null,
    status        tinyint not null default 0 comment '0待处理/1处理中/2已处理',
    promise_time  datetime null comment '承诺处理时限(null=无;超期=查询期计算)',
    created_by    bigint not null,
    created_at    datetime not null default current_timestamp,
    updated_at    datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    key idx_complaint_org (org_id, status),
    key idx_complaint_order (org_id, order_id)
) engine=innodb default charset=utf8mb4 comment='投诉单';

-- 回访任务(三来源:manual 手工/complaint 投诉联动/acceptance 验收联动)
create table service_visit_task (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    order_id      bigint not null comment '装修单',
    order_no      varchar(32) not null,
    customer_name varchar(64) not null,
    biz_type      varchar(16) not null comment 'manual/complaint/acceptance(terminate 归 V2)',
    biz_id        bigint null comment '来源单据 id(投诉 id/验收 id;手工 null)',
    status        tinyint not null default 0 comment '0待回访/1已完成/2无法完成',
    reason        varchar(32) null comment '无法完成原因(字典 visitReason,必填 when status=2)',
    rating        tinyint null comment '满意度 1-5(完成时必填)',
    content       varchar(500) null comment '回访内容(提交时落)',
    draft_content varchar(500) null comment '草稿(可反复保存不改状态)',
    visitor_id    bigint null,
    visitor_name  varchar(64) null,
    visit_time    datetime null,
    plan_time     datetime null comment '计划回访日(逾期=查询期计算)',
    created_by    bigint not null,
    created_at    datetime not null default current_timestamp,
    primary key (id),
    key idx_visit_org (org_id, status),
    key idx_visit_order (org_id, order_id)
) engine=innodb default charset=utf8mb4 comment='回访任务(fl-yy 闭环语义)';

-- 保修登记(append-only:只插不改,作废=状态位;uk 一单一保)
create table service_guarantee (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    order_id      bigint not null comment '装修单',
    order_no      varchar(32) not null,
    customer_name varchar(64) not null,
    start_time    date not null comment '保修起点(默认竣工验收日,可手工改——补录)',
    months        int not null comment '保修时长(月;建单缺省取组织配置)',
    status        tinyint not null default 0 comment '0有效/1作废',
    remark        varchar(255) null,
    created_by    bigint not null,
    created_at    datetime not null default current_timestamp,
    primary key (id),
    unique key uk_guarantee_order (org_id, order_id)
) engine=innodb default charset=utf8mb4 comment='保修登记(保修期=起点+月数,查询期计算)';
