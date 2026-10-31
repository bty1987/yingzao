-- 修正 V902 设计错误(冒烟抓出):approval_id 在业务单落库时尚不存在(create=先插业务单→
-- 同事务 approvals.submit→回填),not null 无默认值与写入顺序矛盾——首单 insert 即 1364。
-- 放开为 null,提交事务内回填后即非空(既有数据零影响,V902 首轮冒烟未成功插入任何行)。
alter table example_request modify column approval_id bigint null comment '内核审批单(yz_approval.id,提交事务内回填)';
