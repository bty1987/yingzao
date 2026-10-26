-- V300 死表下线(A0 清理;docs/11 批次 A 搭车件)
-- customer_profile 表 = addon-customerprofile spike 期遗留(2026-10-11 前后,错误码段 220000 旧约定),
-- 第 17 次已判定下线:正式客户主档 = V1401 customerprofile_customer(lead_id 唯一建档语义已收编),
-- 本表全代码零引用(第 87 次开工 grep 实证),生产/dev 均无数据依赖;
-- 回滚:按本文件头注释逆建表(无数据可回,表恒空)。
drop table if exists customer_profile;
