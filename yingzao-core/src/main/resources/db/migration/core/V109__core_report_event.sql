-- V109 内核报表框架:看板埋点单表(F8 报表框架最小面,D4 第 104 次运行)
-- 5 类事件(pageview/dimension_switch/card_exposure/card_click/dwell),
-- 服务编排不服务考核——埋点数据不进任何考核口径;指标五要素元数据在 addon.yml metrics 段声明,不落库。
create table yz_report_event (
    id          bigint not null auto_increment,
    org_id      bigint not null,
    user_id     bigint not null,
    event_type  varchar(20) not null comment 'pageview/dimension_switch/card_exposure/card_click/dwell',
    metric_key  varchar(64) null comment '事件关联指标(pageview/dimension_switch 可空)',
    payload     varchar(500) null comment '补充上下文(超长截断)',
    created_at  datetime not null default current_timestamp,
    primary key (id),
    key idx_report_event (org_id, event_type, created_at)
) engine=innodb default charset=utf8mb4 comment='看板埋点(5类,服务编排不服务考核)';
