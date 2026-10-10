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

## 7. Phase 0 spike 验收标准

用"客源登记 → 客户建档"一条细链验证全部机制:

- [x] 客源/客户为两个独立 addon,事件打通(客源转化 → 客户建档),零反向依赖(后端已完成,契约模块 yingzao-addons-api);
- [ ] Web 后台:两插件菜单/页面/槽位(客户详情页留一个演示槽位)全部由后端下发;
- [ ] uni-app 一码双出:小程序 + APP 均可登录并完成客源登记;
- [ ] 企微、钉钉 H5 免登进入同一移动端页面;
- [ ] docker-compose 一键起(MySQL8 + Redis + 应用),Flyway 全程建库(Dockerfile/compose 已就位,待容器环境验证);
- [x] 文案零硬编码,i18n 资源中英双语各一份(错误码文案验证通过)。
