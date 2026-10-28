-- 附加待审徽章位(老 constructionschedule.additemStatus:节点下存在待确认附加人工行=1,
-- 仅查询过滤无操作接口;老由 quotation 域跨域直写本表——违例,营造改事件回写:
-- addon-quotation 附加确认线发布 NormAdditionPendingChangedEvent,本域 NormPendingConsumer
-- 消费自写;未点火单/模板外节点零行命中=幂等空转)。R3(D7 定额子域第二轮)。
alter table construction_schedule
    add column additem_status tinyint not null default 0 comment '0无待审/1有待审附加人工行(定额子域事件回写)' after work_status;
