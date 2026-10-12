# 02 - 插件框架 RFC

> 状态:**草案**(Phase 0 spike 的设计依据)
> 一句话:**编译期插件,六件事,不多做。**

## 1. 目标与非目标

### 目标(v1)

- 核心代码零公司判断,公司特性 100% 沉淀在插件;
- 插件以独立 Maven 模块存在,启动时按配置装配;
- 前端支持插件注入页面区块与菜单;
- 插件不修改核心表结构即可扩展数据。

### 非目标(v1)

- ❌ 运行时热插拔(Pf4j 式类加载器隔离)——深坑,v2 再议;
- ❌ 插件市场/在线安装——发布形态走构建组装;
- ❌ 微前端(qiankun/wujie)——槽位注册表够用;
- ❌ 插件间任意互调——插件只依赖核心扩展点与领域事件,插件间通信走事件。

## 2. 总体形态

```text
yingzao/
├── yingzao-core/              # 平台内核:插件框架 + RBAC/组织/字典/文件/通知/i18n
├── addons/                    # 行业插件套件(开源,随核心构建)
│   ├── addon-customer/
│   ├── addon-decoration/
│   └── ...
├── yingzao-app/               # 组装壳:按配置选择装配哪些 addon,产出一个可运行单体
├── web/                       # Web 后台(Vue3 + Element Plus)
└── app/                       # uni-app(小程序 + APP)
```

- 插件 = 独立 Maven 模块(独立 jar),Spring Boot 自动装配 + 配置开关;
- 组装壳 `yingzao-app` 按配置决定加载哪些插件,构建产物仍是**一个可运行单体**(模块化单体,不拆服务);
- 商业插件仓库物理隔离(独立闭源仓库),构建时以依赖引入。

## 3. 六点设计

### 3.1 模块化与装配

每个插件提供 `Addon` 描述文件(`addon.yml`):

```yaml
addon:
  id: customer            # 全局唯一,同时是前端命名空间与数据扩展前缀
  name: 客源管理
  version: 0.1.0
  error-code-segment: 210000   # 本插件独占错误码段
  menus: classpath:addon/customer/menus.yml
  dicts: classpath:addon/customer/dicts.yml
```

组装壳配置:

```yaml
yingzao:
  addons:
    enabled: customer, decoration     # 未启用插件的 bean 不装配
```

实现方式:Spring Boot 自动装配 + `@ConditionalOnProperty`;每个插件一个 `AutoConfiguration.imports`。**不做**类加载器隔离。

### 3.2 扩展点(核心留钩子,插件填实现)

核心在业务关键路径定义扩展点接口,插件实现并注册:

```java
// core 定义
public interface SettlementHook extends YzExtensionPoint {
    void afterSettled(SettlementContext ctx);   // 结算完成后
}

// addon 实现,自动被发现
@YzAddonExtension(addon = "fanglin")   // 公司插件示例
public class FanglinSettlementHook implements SettlementHook { ... }
```

约束:
- 扩展点接口**只放核心**,插件不得反向修改;
- 钩子失败的处理策略在接口约定(默认:记录日志不阻断主流程;标 `@Critical` 的钩子异常随主事务回滚);
- 多个插件实现同一扩展点时按 `order` 顺序执行。

### 3.3 领域事件(插件订阅核心,核心不知道插件)

v1 直接用 Spring `ApplicationEvent`,不引 MQ:

```java
// core 行业模块发布
public record DecorationSignedEvent(String orderId, Long customerId, Long orgId) {}

// 插件订阅
@EventListener
public void on(DecorationSignedEvent event) { ... }
```

约束:事件只携带 ID 与不可变快照,不携带可变实体;事务内发布默认 `@TransactionalEventListener(AFTER_COMMIT)`。

### 3.4 前端槽位注册表(插件注入页面)

Web 后台启动时向后端拉取已启用插件的**前端清单**(由 `addon.yml` 声明、构建产物携带):

```json
{
  "addon": "fanglin",
  "mounts": [
    { "slot": "decoration.detail.footer", "component": "FanglinFactoryBalance", "capability": "fanglin:factory-balance" }
  ]
}
```

- 核心 Web 应用内置**槽位**(slot)约定:页面在固定位置留 `<yz-slot name="decoration.detail.footer" :context="ctx" />`;
- 插件包经构建导出组件 + 挂载声明,loader 用 `defineAsyncComponent` 按声明注册;
- 槽位渲染前校验 `capability`(后端下发的功能权限),无权限不渲染;
- 菜单与路由由后端按已启用插件统一下发,前端零硬编码插件路由。

### 3.5 数据扩展(核心表结构永不因插件而变)

两种合法方式,二选一:

1. **扩展表**:命名强约定 `{addonId}_{entity}_ext`,主键 = 业务主键,如 `fanglin_decoration_ext(order_id PK, ...)`;核心仓库读插件数据走应用层组装,不做跨表 join;
2. **预留 JSON 列**:少数高频扩展点表(如客户档案)核心预留 `ext json` 列,插件写结构化 JSON(带 schema 声明)。

红线:**任何插件不得 ALTER 核心表**;插件自己的表在 Flyway 迁移中随插件生命周期管理,命名 `{addonId}_` 前缀。

### 3.6 配置与字典

- 插件自带字典声明(`dicts.yml`),启动时校验注册,管理端统一呈现;**字典库驱动、改库即生效**,杜绝老系统 constant 表 + ds JSON 双源两份同步发版的老路;
- 插件配置项声明在 `addon.yml`,管理端自动生成配置表单,禁止在代码里读散落 properties。

## 4. 横切规范(继承老系统的好设计)

- **错误码分段**:内核 100000–199999,插件按 `addon.yml` 声明独占码段(如 210000–219999);同码同消息,禁止漂移;
- **分层**:Action(REST Controller)→ Service → DAO,方法命名沿用 `动词+对象+By条件+For目的` 约定(沉淀文档另发);
- **迁移**:schema 全部走 Flyway,版本号与插件绑定(`V{addon}__xxx.sql`),禁止手工 SQL;
- **i18n**:第一行代码开始,文案禁止硬编码,全部走 message key。

## 5. 开放问题(Phase 0 spike 必须回答)

1. 扩展点 + 事件两套机制的分界线是否清晰?(经验法则:改返回值/参与计算 → 扩展点;纯通知/旁路 → 事件)
2. 插件自身的 Flyway 迁移如何随启用/停用编排?
3. 前端槽位的 context 传参类型如何约定才能不耦合插件?
4. 插件间确需调用时,走事件编排是否够用?出现反例再议服务化调用。

## 6. Spike 结论(2026-10-10,后端细链已验证)

客源→客户细链已在 dev 库真实跑通(登录→登记→转化→跨插件事件建档→幂等/错误码/i18n 全过),过程中固化的三个工程结论:

1. **插件间契约必须独立模块** `yingzao-addons-api`:只放领域事件与共享扩展点,零实现零依赖。插件之间禁止互相依赖实现模块——首日即验证了这条红线的必要性(事件类放发布方会导致订阅方反向依赖);
2. **AFTER_COMMIT 监听 + @Transactional 必须 `REQUIRES_NEW`**:Spring 6.1 硬约束,直接 @Transactional 启动即炸(RestrictedTransactionalEventListenerFactory);
3. **编译必须带 `-parameters`**:Spring 6.1 不再从字节码局部变量表推断参数名,`@PathVariable`/`@RequestParam` 依赖真实参数名反射。根 pom maven-compiler-plugin 已配置;⚠️ 改编译配置后须 `mvn clean package`(增量编译会残留旧 class);
4. 版本号分段约定(Flyway):core 100–199,addon 对齐错误码段(customer 200+,customerprofile 300+,quotation 400+),各 addon 独立 migration location;
5. spike 范围说明:认证为内存 token(正式版换 Redis);ORM 暂用 JdbcTemplate(正式版评估 MyBatis-Plus);企微/钉钉免登与 uni-app 端见 RFC §7。
6. **插件分段迁移必须 `out-of-order: true`**(2026-10-11 dev 库实锤):各 addon 版本段独立演进必然交错——customer 的 V201 晚于 customerprofile 的 V300 才出现时,Flyway 默认校验直接拒绝启动("Detected resolved migration not applied to database: 201")。分段是红线,乱序是常态,组装壳 application.yml 已配置。
7. **通知中心 = 内核平台设施,内核不订阅业务事件**(2026-10-12 定案并落地):内核只提供通用站内信(`yz_notification` 表 + `NotificationService.send` + `/api/notifications` 收件箱 API,零业务知识);**插件自己监听自己关注的事件,在 AFTER_COMMIT 回调里调内核 API 落通知**(示范:addon-customer `LeadAssignedNotifier` 监听 `LeadAssignedEvent`)。方向理由:若内核订阅 `LeadAssignedEvent`,内核就必须认识每一个插件事件,"一切皆插件"不成立;通知失败必须在订阅方 try-catch 吞掉(主事务已提交,异常上抛只会把成功操作变 500)。
8. **轻量审批 = 内核平台设施,办结结果经事件回插件**(2026-10-11 定案并落地):内核提供通用审批单(`yz_approval` 主单 + `yz_approval_record` 流水,V102 core 段;单处理人状态机 0 审批中 → 1 通过/2 驳回/3 撤回,转交只改 assignee_id 不改状态)+ `/api/approvals` 七端点(提交/三视角列表/详情/通过/驳回/转交/撤回,错误码 121000-121005,双语)。业务语义全部留在提交方:提交时携带 `biz_addon/biz_type/biz_id` 溯源三元组,内核不解释;办结后内核发 `ApprovalFinishedEvent`(含三元组与 result),**插件监听该事件回写自身业务状态**(如装修单过渡态 20/30/32/33 的解除、报价审核线的联动)。明确不做:会签/或签多处理人、条件分支、超时催办、流程图编排——工作流引擎已拍板不引入,审批深度按插件需求在插件侧组合(如多级审批=插件串多个审批单)。**示范工程:`addons/addon-example`(请假)**——插件接入轻量审批的参考实现(业务单与审批单同事务落库/撤回委托内核/监听 Finished 回写,`@TransactionalEventListener(AFTER_COMMIT)`+`REQUIRES_NEW` 军规 8 姿势),第三方插件开发者从它抄作业;内核侧 `GET /api/users`(本组织 id+显示名)为转交/指派场景的选择人数据源(2026-10-11 上午补,dev 冒烟 28/28)。**多级审批组合方式已示范落地(2026-10-11 第 8 次运行)**:addon-example days≥3 长请假串两级——L1 通过的 Finished 事件在监听里调内核 `submit` 派生 L2 审批单并改挂业务单 `approval_id`,任一级驳回/撤回即业务终态,内核审批单零改动;全部回写带 `approval_id` 守卫(只认当前挂着的审批单),竞态丢失改挂时对悬置单走内核 `cancel` 兜底;
9. **RBAC = 内核平台设施,菜单/功能点授权按"身份字符串"存**(2026-10-11 第 8 次运行落地):`yz_role/yz_user_role/yz_role_menu/yz_role_capability` 四表(V103 core 段,错误码 122000-122005,双语)+ `/api/roles` 管理七端点(全部仅管理员,内置 admin 角色旁路全部过滤且不可删改授权)。**授权语义:菜单身份 = `addon.yml menus[].path`,功能点身份 = `mounts[].capability`,授权行只存身份字符串**——菜单仍由插件动态下发,授权不随插件启停漂移(修掉老系统 functionright 按公司复制整表的散装实现)。`GET /api/addons` 去白名单改按登录人过滤(管理员旁路;普通用户 = 各角色授权并集;capability 为空的挂载人人可见;过滤后无菜单且无挂载的插件整体不出现),登录回显角色编码供前端决定管理入口可见性——**服务端守卫才是真闸门**。边界:本设施只做菜单/功能点可见性门控,业务 API 的端点级鉴权与数据权限为 v2 范围。dev 库实锤新坑:MySQL 5.6 utf8mb4 索引键上限 767 字节,`varchar(200)` 做索引/主键成员直接炸(Error 1071),列宽须 ≤189——基线 8 的 DDL 向 5.6 兼容时索引列一律按 189 封顶。dev 冒烟 70/70(含 RBAC 15 步 + 多级审批 13 步)。

10. **RBAC v2 = 端点级鉴权落地,addon.yml 集中声明 + 内核统一拦截**(2026-10-12 第 36 次运行落地,设计稿 docs/03 全文兑现):声明面 = `addon.yml` 新增 `api` 段(`prefix`/`exact` + `require`,require 三类取值:菜单 path / capability / authenticated——**不发明新授权维度,映射回 v1 身份字符串,角色 UI 零新增**);执行面 = 内核 `RbacService`(exact+最长前缀匹配,启动期校验只告警)+ `RbacInterceptor`(认证链之后:管理员旁路 → 未命中按模式 → 命中按 require 归类判定,错误码 122006 带缺失身份双语);两阶段 = `yingzao.rbac.mode`(observe 未声明放行+按 URI 去重审计 / enforce 未声明即拒,默认 observe,**enforce 只对插件 URI 生效——内核端点自带守卫豁免,设计 §2-4 兑现**);授权快照 = RoleService Caffeine 缓存(TTL 60s + 四个变更端点主动全逐出,管理员判定包进缓存)+ `YzContext` ThreadLocal 上下文(数据权限 L0 配套);`GET /api/addons` 附 api 段下发(前端按钮显隐用,显隐不是闸门)。存量三插件(customer/customerprofile/example)已带声明;其余插件 enforce 切换前须补齐。enforce 终态 5/5 实证(admin 旁路/内核豁免/未声明拒/未授权拒/授权后通),observe 冒烟 1134/1134。**第 37 次运行:九插件 api 段补齐(12/12,enforce 全开前置达成)+ method 互补分层语义落内核**——`RbacService.match` 加 method 维度(pattern 命中但 methods 不兼容跳过继续找,全不兼容 = 未声明;修掉"method 不匹配放行"的 enforce 绕闸洞),支撑「同前缀 GET 读放开 authenticated / 写收菜单」声明模式(商品/装修单列表/客户列表三处跨页选择器);专项冒烟资产 `docs/scripts/smoke/smoke_enforce.py`(静态完备性:12 插件 309 端点样例 vs 声明模拟 match 零未声明 + 动态四组:admin 旁路 13/无角色拒 14/全授权通 13/部分授权 authenticated 放开与菜单闸 11,80/80);app 端 http.js 补 122006 友好提示与 err.code(对齐 web 惯例)。切换 enforce = 环境变量重启即生效。

11. **事件总线 Spring 监听渐进迁移(2026-10-12 第 38 次运行启动,首波 = customer LeadConverted/LeadAssigned 链)**:发布侧 `publisher.publishEvent(...)` → `bus.publish(event, eventKey)`(outbox 与业务同事务,eventKey 以业务幂等锚点命名,如 `lead-converted-{leadId}`);消费侧 `@TransactionalEventListener(AFTER_COMMIT)` 方法 → 独立 `YzEventConsumer` SPI bean(参考 `LeadConvertedConsumer`/`LeadAssignedConsumer`)。迁移红利:①失败不再悄然丢失(旧姿势监听异常即丢,information.md「44% 丢失」红线),总线退避重试直至死信可见;②顺带收敛并发隐患——customerprofile `exist` 查后插撞 `uk(org,active_hmac)` 时旧监听异常即丢档,现在重放时并发事务已提交、exist 直接回显;③通知类消费器加查重守卫(`LeadAssignedConsumer` 按 source_addon+source_type+source_id 查重,同一机会只通知一次),业务回写类靠 get-or-create/条件 UPDATE 天然幂等。行为兼容:总线 AFTER_COMMIT 同步投递一轮,冒烟断言时序无感。**待迁移清单(按波次)**:内核 `ApprovalFinishedEvent` 链五个审批回写监听器(decoration 终止/example 多级/inventory 调拨/payment 退款/sales 退货——语义更优但回归面大,独立一波)+ `CustomerCreatedEvent` 等 customerprofile 发布侧;迁移完成的标志 = `grep -r "@TransactionalEventListener" addons` 归零(Web 端 `/api/events` 管理页已就位,死信人工兜底出口可用)。

## 7. Phase 0 spike 验收标准

用"客源登记 → 客户建档"一条细链验证全部机制:

- [x] 客源/客户为两个独立 addon,事件打通(客源转化 → 客户建档),零反向依赖(后端已完成,契约模块 yingzao-addons-api);
- [ ] Web 后台:两插件菜单/页面/槽位(客户详情页留一个演示槽位)全部由后端下发;
- [ ] uni-app 一码双出:小程序 + APP 均可登录并完成客源登记(2026-10-11 第 6 次运行:骨架代码层已落地 `app/`,八页覆盖登录/客源登记/我的机会/通知/我的审批,h5/mp-weixin/app 三端构建全绿;真机登录运行待 uni-app 账号与 appid);
- [ ] 企微、钉钉 H5 免登进入同一移动端页面;
- [ ] docker-compose 一键起(MySQL8 + Redis + 应用),Flyway 全程建库(Dockerfile/compose 已就位,待容器环境验证);
- [x] 文案零硬编码,i18n 资源中英双语各一份(错误码文案验证通过)。
