-- 领域事件出站箱(内核 100-199 版本段;2026-10-12 随 addon-payment 落地)
-- 红线来源:docs/rules/information.md——老 messagequeuing 44% 事件 status=2 失败无重试=近半丢失,
-- 营造事件总线三件套红线:消费确认(status=1)+ 失败重试(status=2 + 退避)+ 死信可见(status=3 + 管理端点)。
-- 发布方在业务事务内写本表(outbox,事件与业务变更同生共死),提交后立即投递,失败交由调度器按退避重试。
create table yz_event_task (
    id              bigint not null auto_increment,
    event_type      varchar(128) not null comment '事件类型(契约类简单名,如 PaymentCollectedEvent)',
    payload         mediumtext   not null comment '事件 JSON 快照(只携带 id 与不可变快照)',
    event_key       varchar(189) null comment '业务幂等键(可空;订阅方去重锚点)',
    status          tinyint      not null default 0 comment '0待投递 1已消费 2重试中 3死信',
    retry_count     int          not null default 0,
    max_retries     int          not null default 5 comment '超过即转死信(发布时可按事件定制)',
    next_retry_time datetime     null comment '下次投递时间(退避;NULL=立即可投)',
    claimed_at      datetime     null comment '投递器认领时间(超过 5 分钟未归还视为投递器崩溃,可再认领)',
    last_error      varchar(1024) null comment '最近一次投递失败原因(死信排查入口)',
    created_at      datetime     not null default current_timestamp,
    updated_at      datetime     not null default current_timestamp on update current_timestamp,
    primary key (id),
    key idx_event_due (status, next_retry_time),
    key idx_event_type (event_type)
) engine=innodb default charset=utf8mb4 comment='领域事件出站箱(消费确认+重试+死信)';
