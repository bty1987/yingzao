-- V106:异步任务调度内核(YzPlan)——information.md「Plan 必迁」裁决兑现:
-- 老系统 plan(communion.task 脚本注册+executeTime 排队+外部 Python 调度器回调)的
-- 内核化重做:处理器为 Spring bean(YzPlanHandler SPI,与 YzEventConsumer 同姿势),
-- 任务落库同事务、到期调度执行、失败指数退避重试、超限死信可见可重试。
-- 外部执行器(HTTP 回调型任务,V2):老 plan_export_*.py 族迁移时的对接口。
create table yz_plan (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    handler       varchar(128) not null comment '处理器注册名(YzPlanHandler.handler())',
    biz_key       varchar(128) null comment '业务键(幂等/对账,可空)',
    params        text null comment '参数 JSON(透传 handler)',
    status        tinyint not null default 0 comment '0待执行 1成功 2失败重试中 3死信 4已取消',
    max_retries   int not null default 3,
    retry_count   int not null default 0,
    next_run_time datetime null comment '到期时间(null=立即可执行)',
    claimed_at    datetime null comment '执行认领时刻(崩溃超时回收)',
    result        text null comment '成功结果摘要(handler 返回)',
    last_error    text null comment '最后一次失败原因',
    created_by    bigint null,
    created_at    datetime not null default current_timestamp,
    updated_at    datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    key idx_plan_due (status, next_run_time),
    key idx_plan_org (org_id, status, id),
    key idx_plan_biz (org_id, handler, biz_key)
) engine=innodb default charset=utf8mb4 comment='异步任务计划(内核调度,处理器=YzPlanHandler SPI)';
