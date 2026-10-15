-- V105:事件消费完成登记表——exactly-once 投递语义(information.md「44% 丢失」红线的
-- 最后一环:at-least-once 重试语义下,多消费者部分失败整条重试会让已成功消费者重放;
-- 本表记录 (task, consumer) 完成对,重投递/requeue 时跳过已登记消费者)。
-- 崩溃窗口说明:登记在消费者成功返回之后,进程崩溃于两者之间会退化为重投递
-- (at-least-once),此时由消费者业务幂等兜底——表语义=消除「常态重放」,不承诺崩溃级 exactly-once。
create table yz_event_consumed (
    task_id     bigint not null,
    consumer    varchar(128) not null comment '消费者 bean 简单名',
    consumed_at datetime not null default current_timestamp,
    primary key (task_id, consumer)
) engine=innodb default charset=utf8mb4 comment='事件消费完成登记(exactly-once 投递语义)';
