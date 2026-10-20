-- V107:用户停用位(用户管理,内核 100-199 版本段)——
-- 0 启用(默认,存量行行为不变)/1 停用;停用 = 登录拒绝(120102)+ 踢出在管会话(TokenStore.evictUser)。
alter table yz_user add column status tinyint not null default 0
    comment '0 启用 / 1 停用' after display_name;
