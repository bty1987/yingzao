-- V1602:member V1.1——兑换单发货登记 + 积分过期清算钩子。
-- ①兑换单发货(status=3 已发货投产):物流公司/单号登记(不接承运商 API,V1.1 人工登记口径);
--   0 已受理/1 已核销均可发货(核销=到店自提完成,发货=寄递完成,两种履约并行),2 已撤销不可发;
-- ②过期清算:member_config 加组织级开关(默认关=行为不变),开启后调度器每日清算——
--   对过期正向批次逐行生成清算负行(type=6,src_type=expire_clear,src_id=原正行 id 作幂等锚,
--   重放撞 uk 跳过),使 balance 向 available 收敛;亦可手动端点触发。
alter table member_exchange
    add column ship_company varchar(64) null comment '物流公司(人工登记)',
    add column ship_no varchar(64) null comment '物流单号',
    add column ship_time datetime null comment '发货时刻';

alter table member_config
    add column expire_clear_enabled tinyint not null default 0
        comment '积分过期清算调度开关 0关 1开(默认关=过期只拦兑换不清零,V1 行为不变)';
