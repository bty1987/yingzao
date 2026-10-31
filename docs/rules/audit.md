# audit 审计日志(内核)——实现口径(老系统 log 域提取)

> 营造内核平台设施(docs/15 F3,R24~R25);业务参考=老系统 `com.fanglin.log` 域(ActionLog/LoginLog 双表体系),只读考证,零代码平移。

## 0. 归属与划界

- **归属**:内核 `yingzao-core` `com.yingzao.core.audit` 包;迁移 V112(core 100-199 段);错误码零新增(查询端点仅 admin 守卫,复用 122003);i18n 零新增键(无业务文案);
- **V1 划界(不迁项)**:
  - 老全请求拦截器捕获(AccessInterceptor 全量记录)→ 营造 **@AuditLog 注解 opt-in**(注解到哪记到哪,查询/读端点天然零噪声;老 logFlag 三档 NONE/ALWAYS/AUTO 配置面随 operation 表,不迁);
  - 老月度归档分表(actionlog_yyyymm + UNION ALL 历史查询)→ V1 不迁,统一**到期物理删除**;归档是老系统量级产物,营造 V1 量级(注解 opt-in)不需要;
  - 老采样率(actionLog.sampling)与全局熔断开关 → opt-in 注解天然低量,V1 不做采样;
  - 老业务侧 ThreadLocal bind(ActionLogContext)→ SpEL 表达式直接从注解取对象 id/摘要,零 ThreadLocal(线程池场景泄漏风险归零);
  - 老 CustomerLog/DecorationLog/KingdeeSyncLog 等业务日志域 → 各归各 addon V2。

## 1. 操作日志(老 actionLog → yz_audit_log category=0)

老系统字段(ActionLogPO):actionName / createUserId / createTime / runTime / ip / companyId / moduleId / operateType(ADD/UPDATE/DELETE/QUERY/EXPORT/LOGIN/OTHER)/ businessId / params(脱敏 JSON)/ resultState(0 成功 1 业务失败 2 系统异常)/ errorCode / errorMsg / traceId。

营造收敛(单表 yz_audit_log):

| yingzao 列 | 来源与口径 |
|---|---|
| category | 0=操作 1=登录(老两表合一;量级 V1 够用) |
| user_id / user_name | 操作人 id + **display_name 快照**(用户删除后审计仍可读;登录行=尝试登录名) |
| action | @AuditLog.action,缺省=方法名 |
| module | @AuditLog.module,缺省=类包最后一段 |
| object_type / object_id | @AuditLog 声明;object_id 支持 **SpEL**(如 `#id`) |
| summary | 前后差异摘要:@AuditLog.summary SpEL;缺省=脱敏参数 JSON |
| detail | 请求参数 JSON(**敏感键脱敏**) |
| result / error_code / error_msg | BizException→1;其他 Throwable→2;正常→0 |
| run_time | 毫秒 |

**写库纪律(老 safeAddActionLog 语义原样继承)**:REQUIRES_NEW 独立事务(业务回滚审计仍在——失败恰恰是最需要留痕的时刻)+ **吞掉一切异常绝不影响主业务** + 切面在 proceed() 之后/异常重抛之前记录。

**脱敏(老 ParamSanitizer 键族收敛)**:password/pwd/oldpwd/newpwd/token/secret/authorization/password_sha256 精确命中替换 `***`;phone/mobile/idcard/bankcard/cardno/certno 模糊命中替换;超长截断 2000 字符;脱敏自身失败落 `[sanitize-error]` 兜底。

## 2. 登录日志(老 loginLog → yz_audit_log category=1)

老系统字段(LoginLogPO):id(登录名)/ loginId / loginTime / sourceIp / successFlag(0 失败 1 成功)/ operateType(PASSWORD/AUTHCODE/PHONE…) / resultState / errorCode / errorMsg / companyId / traceId。

营造收敛:
- **记录点**:①`POST /api/auth/login` 三个失败出口(110001 密码误/120106 组织停用/120102 账号停用)+成功出口;②**踢出**(UserController 停用/重置密码 → evictUser 两处,action=evict,summary 记原因;老系统踢出不留痕,营造补上——审计价值最高的事件之一);
- 失败行 user_id 尽量带回(密码误但用户存在时记真实 id,便于暴力破解审计;用户不存在=0);
- C 端(portal)登录 V1 不记(C 端令牌匿名免登体系,登录面 = web-mp/portal,量与语义随 C 端体系 V2)。

## 3. 保留策略(老 LoginLogCleanupJob + ActionLogArchiveJob → yz_plan)

- 老口径:登录日志 `recordLoginLogDay` 默认 30 天,每日 03:00 定时 DELETE;操作日志按月归档分表(营造不迁,见 §0);
- 营造口径:**统一保留期** `yingzao.audit.retention-days`(缺省 90,0=不清理),**yz_plan 链式续期**(member-expire-clear 第 63 次先例):handler=`audit-log-purge`,启动对账补挂(部署自举+断链自愈),每日 03:00 执行按 cutoff 批量 DELETE(1000 行/批防长锁);执行时 params.retentionDays 可覆盖全局配置(冒烟/应急用);
- 计划在 /api/plans 管理页可见可取消可死信重试(调度透明化=老系统 Cron 裸跑没有的能力)。

## 4. 首批注解消费方(验范式)

- UserController:`create` / `changeStatus` / `resetPassword`(管理面三写操作;object_id 取路径变量或返回值,changeStatus 摘要记启停方向);
- 各 addon Controller 后续按轮次逐个挂注解(挂了即审计,零框架改动);
- 查询端点(/api/audit/*)自身不挂注解(查询零噪声)。

## 5. 实现定案回写(第 115 次)

- 表形态:**单表 yz_audit_log 双 category**(老 actionLog+loginLog 两表合一;V1 量级判断,归档/分表不迁);
- @AuditLog 五属性(action/module/objectType/objectId/summary),object_id 与 summary 支持 SpEL,参数名靠根 pom `-parameters`(军规 8)直接可读;
- Recorder=TransactionTemplate REQUIRES_NEW + 全吞异常;登录钩子内联 AuthService 三失败出口+成功出口;
- purge 计划恒链式续期(retention=0 时执行空转不续断链——开关关闭期间链条不断,再开启无需重启);
- Web 两页=操作日志/登录日志(admin 客户端按角色显隐+服务端 requireAdmin 双闸),沿 Plans/Events 管理页族(既有纸墨体系,不触发 huashu 三方向流程——管理查询页判例同 Plans.vue 第 66 次)。
