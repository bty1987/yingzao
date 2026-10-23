# 03 - RBAC v2 设计稿:业务 API 端点级鉴权与数据权限

> 状态:**已实施(2026-10-12 第 36 次运行,§9 清单全项兑现;§8 五项决策按 2026-10-12 拍板推荐口径执行:addon.yml 集中声明 / observe 起步 / L2 等组织架构立项 / 字段级脱敏不做)**。落地偏差:§7.5 跨组织 L0 断言升级为「org1 角色绑 org2 用户拒绝 122005」(updateUsers 组织守卫即 L0 的授权面体现);enforce 终态 5/5 实证(admin 旁路/内核豁免/未声明拒/未授权拒/授权后通)。**第 37 次运行:§8-3 method 维度实际启用(读放开/写收权分层必需,见 §3),12/12 插件 api 段全齐,enforce 全开前置达成;smoke_enforce.py 专项资产沉淀(静态完备性 309 端点零未声明 + 动态四组 80/80)。**
> 上游:RFC §6.9(RBAC v1 落地 + 边界声明)、docs/01 §right 域「内核:重设计」
> v1 已交付(见 RFC §6.9):`yz_role` 四表 + `/api/roles` 七端点 + `GET /api/addons` 按登录人过滤(菜单/功能点**可见性**门控)。
> 本稿回答 v1 留下的下半场两个问题:**业务 API 的端点级鉴权**怎么声明、怎么拦;**数据权限**切到哪一层。

## 1. 问题定义

v1 结束时的真实防线:

| 层 | 现状 | 缺口 |
|---|---|---|
| 登录态 | `AuthInterceptor` 拦 `/api/**`,Bearer token → userId/orgId | 有(白名单仅 login/health/error) |
| 菜单可见性 | `/api/addons` 按授权并集过滤,前端动态注册路由 | 有 |
| 端点鉴权 | **无**——拿到任何合法 token 即可调任意 `/api/**`,包括未授权菜单背后的业务接口 | 本稿主题 |
| 数据权限 | 仅约定 org_id 隔离(插件自查) | 本稿 §6 |

老系统对照(01 清单 right 域):`functionright` 按**公司整表复制**功能点/查询条件配置,页面按钮与服务端功能字典两套口径,oid/company 错配类历史问题多发。v1 已用「授权行只存身份字符串」修掉存储面;本稿补执行面——**服务端守卫才是真闸门**(v1 报告原话),前端菜单过滤只是体验层。

## 2. 设计原则(从 v1 哲学顺延)

1. **身份字符串一条路走到黑**:端点权限不发明新授权维度,映射回 v1 已有的两类身份——菜单 path(`yz_role_menu`)与 capability(`yz_role_capability`)。角色管理 UI 零新增授权维度:勾了菜单 = 授权该菜单页面的 API;勾了功能点 = 授权该挂载的 API;
2. **声明集中、执行集中**:插件在 `addon.yml` 一处声明 API 权限面(与 menus/mounts 同构),内核一处拦截器统一执行;禁止插件 Controller 里散写权限判断(散写 = 老 functionright 之覆辙);
3. **secure by default,但渐进收紧**:引入即全量拦截会砸死存量插件 → 两阶段(§5);
4. **内核 API 不进映射表**:内核端点自带守卫(登录拦截 + `requireAdmin` 自守卫,如 `/api/roles`),插件映射表只管插件 API;内核端点如果未来需要角色化,走 capability 声明,不蹭插件机制;
5. **移动端零额外工作**:uni-app / 企微钉钉 H5 与 Web 同走 `/api/**` 同闸门同 token,机制层不做端区分。

## 3. 声明:addon.yml 新增 `api` 段

```yaml
addon:
  id: customer
  ...
  api:
    # require 取值三类:
    #   1. 菜单 path(/customer/leads)——授权了该菜单的角色可调
    #   2. capability(customerprofile:view)——授权了该功能点的角色可调
    #   3. authenticated(缺省)——登录即可,不做 RBAC(通用数据源类,如选择人接口)
    - prefix: /api/customer/leads          # 前缀匹配
      require: /customer/leads             # 该 API 面 = 客源管理菜单
    - prefix: /api/customer/chances
      require: /customer/chances
    - prefix: /api/customer/profiles       # 挂 customerprofile 槽位卡片的详情数据
      require: customerprofile:view        # 精确对齐 mounts[].capability
      methods: [GET]                       # 可选,默认不限 method
    - exact: /api/customer/leads/convert   # 精确路径,覆盖前缀
      require: /customer/leads
```

- **匹配语义**:exact 优先于 prefix;prefix 取最长匹配;**pattern 命中但 `methods` 限定不含当前 method 的规则跳过继续找(methods 缺省 = 全兼容),全部不兼容 = 未声明处理**——第 37 次运行定稿(此前版本"method 不匹配放行"存在 enforce 绕闸洞:只声明 POST 的规则会让 GET 直接过,已修复);命中多条时按最长/声明序取**第一条兼容规则**(非并集);
- **method 互补分层**(第 37 次引入的声明模式):同一前缀写两条 methods 互补的规则,表达「GET 读放开 / 写收菜单」——跨页面选择器数据源(商品/装修单列表/客户列表)放开给登录用户,写操作仍收菜单闸:

```yaml
  api:
    - prefix: /api/basedata/goods
      require: authenticated
      methods: [GET]        # 先声明:GET 命中此条(读开放,org 隔离由 L0 硬边界保证)
    - prefix: /api/basedata/goods
      require: /basedata/goods   # 后声明:非 GET 落到此条(写收菜单)
```

  适用面原则(第 37 次口径):**只放开列表/搜索型读端点,且仅限跨页面选择器真实引用的**——basedata 商品(exact 列表+子树 GET)、decoration 装修单列表(exact 仅列表,详情不放)、customerprofile 客户列表(exact 仅列表,电话/账户/审计子资源不放);价格体系等敏感子资源 V1 随前缀放开属已知妥协,细化排 V2;
- **启动期校验**(`AddonRegistry` 装载时):require 为菜单 path 而本插件 menus 里不存在、require 看起来是 capability 而本插件 mounts 里没有该 capability、prefix 互相包含且 require 矛盾——均启动告警日志(不阻断,V2 期;enforce 全开后升启动失败;**注:最长前缀分发与 methods 互补分层属预期重叠,升失败时校验须豁免这两型**,第 37 次注);
- **声明即文档**:`/api/addons` 下发时可附带 `api` 段(脱敏到 require 字段),前端与移动端可据此做按钮级显隐(与 mounts.capability 同机制),但**前端显隐不是闸门**。

### 为什么是 yml 集中声明而不是 Controller 注解

| | addon.yml 集中(推荐) | @YzRequires 注解就近 |
|---|---|---|
| 与 v1 身份字符串哲学 | 同构(menus/mounts 也是 yml 声明) | 引入第二种声明源 |
| 插件作者视角 | 一处看全权限面,新增接口漏声明的风险由 §5 观察模式兜底 | 容易忘(漏注解 = 静默放行,更危险) |
| 内核执行 | 启动建前缀表,拦截器零反射 | 需要 HandlerMethod 反射链路 |
| 重构/改名 | path 即身份,授权行不漂移 | 注解随代码漂移 |

不排斥未来给常用场景加注解糖(V3 再议),执行面始终是同一个拦截器。

## 4. 执行:内核 RbacInterceptor

- 位置:`AuthInterceptor` 之后同链注册(顺序:登录态 → 端点鉴权),只拦 `/api/**`;
- 启动时:`AddonRegistry` 汇总全部启用插件的 `api` 段 → 构建**(exact map + prefix 前缀树)**;插件启停/热注册时重建;
- 请求时:
  1. 管理员(`RoleService.isAdmin`)旁路直放;
  2. 未命中任何规则 → 按 §5 阶段开关决定放行(观察)或拒绝(enforce);
  3. 命中 → `require` 归类:menu path → 查 `grantedMenus(userId)`;capability → 查 `grantedCapabilities(userId)`;`authenticated` → 放行;
  4. 不满足 → 业务错误(惯例:HTTP 200 + `ApiResponse.error`),新错误码 **122006 无端点访问权限**,i18n 中英双语(`rbac.api.forbidden`),响应附 `require` 身份便于前端提示「需要 XX 权限」;
- **权限查询缓存**(顺手修 v1 的一个性能小账:`AddonController` 现在每插件每请求查两遍授权并集):`Caffeine userId → (menus, capabilities)`,TTL 60s + **变更主动失效**(`RoleService.updateMenus/updateCapabilities/updateUsers/delete` 与用户绑定变更时逐出该 userId;全量角色变更逐出全缓存——管理操作低频,简单粗暴够用);缓存必须包住 admin 判定,避免每请求两查 `yz_user_role`;
- 插件 Controller **零改动**:映射表改配置即改闸门,这也是集中声明对插件开发者的红利。

## 5. 两阶段收紧(渐进式 secure by default)

| 阶段 | `yingzao.rbac.mode` | 未声明 API | 已声明 API | 目的 |
|---|---|---|---|---|
| 观察期 | `observe`(默认,上线首阶段) | 放行 + WARN 审计日志(路径/用户/建议 require) | **真拦** | 摸清存量权限面;日志直接产出「待声明清单」 |
| 执法期 | `enforce` | 拒绝 122006 | 真拦 | 终态:未声明即拒绝 |

- 观察期启动横幅打印全部「未受保护端点」清单(按插件分组);观察期长短 = §8 待决策;
- `disabled` 档不设——RBAC 只会越来越严,留退路等于留回归口子;
- 冒烟脚本两阶段各跑一遍(§7)。

## 6. 数据权限:V2 只做两层,L2 留扩展点

| 层 | 内容 | V2 状态 |
|---|---|---|
| **L0 组织隔离(硬边界)** | 一切业务查询必须带 `org_id`;`yz.orgId` 由认证拦截器注入、插件从上下文取,**禁止**从请求参数取 orgId | 落地手段:①本稿配套给 `addons-api` 提供 `YzContext`(userId/orgId 静态上下文);②每个 addon 冒烟用例加「跨组织不可见」断言(建第二个组织写对立数据);③V3 评估 ArchUnit/SQL 解析的静态校验。【**2026-10-21 组织树裁决演进(docs/08,第 72/74 次落地)**:L0 从「org 相等」扩为「**子树包含**」——organization 自引用树(公司→分区→部门,层级=深度标签),登录节点可见集=自身+启用子树,认证拦截器注入 `yz.orgScope`(OrgScopeService Caffeine 60s);「集团/分公司」双实体不做,多公司时树根即集团视角 |
| **L1 归属可见(我的数据)** | 插件业务表带 owner/assignee 列(如 chance.assignee_id),列表默认 `assignee = 当前人`,`me=all` 类参数要求 capability(如 `customer:manageAll`) | 插件自管,内核只供上下文;capability 授权即闸门(复用 §3 机制,不新做) |
| **L2 范围可见(部门/区域/自定义池)** | 老系统 userdepartment/userregion 链与「挂靠池」玩法;需要内核组织架构(部门树)先行 | **~~V3~~ 前置已就绪(第 72 次 V108 organization 树)**:部门树即 L0 子树语义本身(L2=部门节点子树,不再单列);范围作为角色的属性(`yz_role_data_scope` 表)而非用户属性的设计方向不变——换角色即换数据范围 |

明确不做(V2 范围外):行级 SQL 改写器(过度设计,插件自查 org_id + 冒烟断言足够)、字段级脱敏(走 §8 备忘,属「功能点」特例的变体,等真实插件需求)。

## 7. 验收标准(实施完成后冒烟必须覆盖)

1. 观察期:未声明 API 放行且留审计日志;已声明 API 未授权用户 122006、授权用户 200;管理员全通;
2. enforce 期:未声明 API 122006;exact 覆盖 prefix 生效;最长前缀命中;**method 互补分层生效(GET 放开/写收菜单,第 37 次 smoke_enforce.py 验证)**;
3. capability 型 require:授权功能点后 API 通、回收后 122006;
4. 缓存一致性:改授权后 ≤ 即时(主动逐出)生效,不出现陈旧放行;
5. 跨组织:L0 断言——组织 A 登录读不到组织 B 的客源/请假;
6. 移动端:同一 token 在 H5 构建下同闸门(122006 同码);
7. `/api/addons` 下发 `api` 段后,前端可按 require 隐藏按钮(体验层,不做硬断言)。

## 8. 待用户决策清单(拍板前不实施)

> §8 五项已按 2026-10-12 拍板推荐口径执行;以下保留原决策记录。**§8-3 method 维度已于第 37 次实际启用**(读放开/写收权分层必需):用途限定为「同前缀 GET 放开 authenticated / 写收菜单」互补分层与未来写细分,不做全量 method 级授权矩阵——心智负担控制在本稿 §3 示例模式内。
1. **声明位置**:addon.yml 集中(本稿推荐)vs Controller 注解,§3 有对照表;
2. **观察期时长**:建议一个迭代(两周)——期间新插件必须带 api 段合入,存量插件补齐后切 enforce;
3. **method 维度**:V2 是否引入(本稿建议:字段留好、默认不用——老系统功能点不分 method,心智负担先不加);
4. **数据权限 L2**(部门/区域范围)的时机:等内核组织架构立项,还是某个具体插件需求倒逼;
5. 字段级脱敏是否纳入 V2(本稿建议不做,等需求)。

## 9. 实施清单(拍板后按此开工,预估一个夜间任务量)

1. 内核:`AddonDescriptor` 加 `Api` record;`AddonRegistry` 装载校验 + 前缀表;`RbacInterceptor` + 注册;`RoleService` 授权变更逐出缓存 + Caffeine 引入;`YzContext`;错误码 122006 + i18n 双语;`/api/addons` 附 api 段;
2. 插件:三个存量 addon(addon-customer/customerprofile/example)补 `api` 段声明(观察期即可跑);
3. 配置:`yingzao.rbac.mode`(observe/enforce,application.yml + 环境变量可覆盖);
4. 冒烟:smoke_all.py 新增 §7 的 1-4、6 段(约 10-12 步);跨组织断言需要 ensure_users 支持双组织;
5. 文档:RFC §6.10 记录落地结论;本稿状态改「已实施」。

> **实施状态(2026-10-12)**:§9 全项已兑现(第 36 次 1-5;第 37 次补齐全部 12 插件声明与 enforce 专项冒烟资产)。enforce 全开前置达成——切换 = 环境变量 `YINGZAO_RBAC_MODE=enforce` 重启即生效,无代码改动。
