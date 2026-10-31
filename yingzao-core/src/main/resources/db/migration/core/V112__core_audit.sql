-- F3 审计日志(docs/15 R24~R25):操作+登录单表双 category(老 actionLog/loginLog 两表合一,
-- V1 注解 opt-in 量级判断;归档分表/采样/ThreadLocal bind 均不迁,口径见 docs/rules/audit.md)。
-- MySQL 5.6 兼容:TEXT 不带 DEFAULT。
create table yz_audit_log (
  id bigint not null auto_increment,
  category tinyint not null default 0 comment '0=操作 1=登录',
  org_id bigint not null default 0 comment '组织(登录失败未知=0)',
  user_id bigint not null default 0 comment '操作人/被踢用户;登录失败未知=0',
  user_name varchar(64) not null default '' comment '操作人显示名快照/尝试登录名',
  action varchar(128) not null comment '动作(login/logout/evict/方法名)',
  module varchar(64) not null default '' comment '模块(包段或声明)',
  object_type varchar(64) not null default '' comment '对象类型',
  object_id varchar(64) not null default '' comment '对象主键',
  summary varchar(512) not null default '' comment '差异/动作摘要',
  detail text null comment '脱敏参数 JSON',
  ip varchar(64) not null default '' comment '来源 IP',
  result tinyint not null default 0 comment '0成功 1业务失败 2系统异常',
  error_code int null,
  error_msg varchar(255) not null default '',
  run_time int not null default 0 comment '耗时毫秒(操作行)',
  created_at datetime not null comment '记录时刻',
  primary key (id),
  key idx_audit_time (created_at),
  key idx_audit_org (org_id, category, created_at),
  key idx_audit_user (user_id, created_at)
) engine=innodb default charset=utf8mb4 comment='审计日志(操作+登录,保留策略走 audit-log-purge 计划)';
