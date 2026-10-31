-- 示范申请单(docs/15 R15~R16:审批移动端补全——schema 驱动表单审批的插件侧参考实现)。
-- 发起链:移动端按 addon.yml approval-forms 段 schema 渲染表单 → POST /api/example/requests
-- → 本表落业务单 + 同事务 approvals.submit(formData/attachments 透传内核快照)→ 返回审批单 id;
-- 回程:RequestFinishedConsumer 消费 ApprovalFinishedEvent(bizType=demo-request)回写 status。
-- 表单值快照存在内核 yz_approval.form_data(审批单自包含),本表只存业务需要自持的列;
-- 快照列(order_label/village/customer)取自发起端选中数据——示范工程从简,生产插件应经契约自取。
create table example_request (
    id           bigint not null auto_increment,
    org_id       bigint not null,
    applicant_id bigint not null comment '发起人',
    order_id     bigint null comment '装修单(表单 query 字段,可空)',
    order_label  varchar(64) null comment '装修单号快照',
    village      varchar(200) null comment '楼盘快照(query 预填)',
    customer     varchar(64) null comment '客户快照(query 预填)',
    reason       varchar(500) not null comment '申请事由',
    amount       bigint null comment '预估金额(厘,端点收元×1000)',
    attachments  varchar(1000) null comment '附件 yz_file.id JSON 数组',
    approval_id  bigint not null comment '内核审批单(yz_approval.id)',
    status       tinyint not null default 0 comment '0审批中 1通过 2驳回 3撤回',
    created_at   datetime not null default current_timestamp,
    primary key (id),
    key idx_example_request_applicant (org_id, applicant_id),
    key idx_example_request_approval (approval_id)
) engine=innodb default charset=utf8mb4 comment='示范申请单(表单审批)';
