-- 审批表单快照(docs/15 R15~R16:审批移动端补全,内核只透传不解释)。
-- 人工发起的表单审批:表单值随提交存快照,审批单自包含(审批人直接看表单内容,
-- 对齐钉钉「审批单=表单快照」行业惯例);系统发起的审批(领料审核/退款等)两列恒空,行为零变化。
-- form_data = 业务表单值 JSON(schema 由插件在 addon.yml approval-forms 段声明,内核不认业务字段);
-- attachments = 附件 yz_file.id JSON 数组(挂接语义与 yz_file.biz_* 同源,展示走 /api/files/{id})。
-- MySQL 5.6 向下兼容:TEXT 存 JSON 字符串(库无 JSON 列)。
alter table yz_approval
    add column form_data   text null comment '表单值 JSON 快照(内核透传不解释,系统发起审批为空)' after biz_id,
    add column attachments text null comment '附件 yz_file.id JSON 数组' after form_data;
