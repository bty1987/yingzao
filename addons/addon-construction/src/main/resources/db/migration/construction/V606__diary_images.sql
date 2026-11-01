-- 施工日记图片字段(docs/15 R17 施工域页面族,app P0-3 第⑤项「日记拍照」的存储落位):
-- 老日记 has_drawing 只是布尔标记(文件存储基建就绪前仅标记)——F1 文件抽象(R10~R11)落地后,
-- 日记图片以 yz_file.id 数组随行存储,业务表存 id 即完成挂接(与 V111 yz_approval.attachments 同惯例:
-- 前端持 id 经带票据 downloadFile 换本地路径展示,后端零字节流)。
-- MySQL 5.6 向下兼容:TEXT 存 JSON 字符串(库无 JSON 列)。
alter table construction_diary
    add column images text null comment '图片 yz_file.id JSON 数组(业务表存 id,前端持 id 拼 URL)' after has_drawing;
