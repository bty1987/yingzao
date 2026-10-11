# Yingzao(营造)AI 开发指南

> 本文件是 yingzao 仓库的 AI 工作指引。任何会话(无论工作目录在哪)开始前,按"必读文档"顺序自举上下文。

## 项目定位

- **装修行业开源 ERP**,非公司级项目;核心开源 + 一切皆插件(Odoo 式三层:平台内核 / 行业插件套件(开源)/ 商业插件(闭源));
- 品牌名取自《营造法式》——材分模数制 = 古代插件化;tagline:**现代装修企业的营造法式**;
- 仓库:gitee.com/bty1987/yingzao(主)+ github.com/bty1987/yingzao(gitee 自动同步,**勿直推 github**);默认 master 直接提交。

## 必读文档(上下文自举顺序)

1. `docs/01-剔除保留清单.md` —— **需求规格书**:41 模块过堂终版 + 横切红线(公司差异沉插件/字典单源/Flyway 禁手工 SQL);
2. `docs/02-插件框架RFC.md` —— 插件机制六点设计 + spike 结论(三坑)+ 验收清单;
3. `README.md` —— 定位 / 架构图 / 路线图;
4. `docs/prd/` —— **各 addon 的 PRD(业务闭环)** + 模板;`docs/rules/` —— 从老代码提取的实现口径(状态机/金额口径/字典枚举)。

### 文档金字塔(知识资产的分工)

- **L0 骨架**(已有):清单/RFC/本文件——做什么、怎么造、怎么干活;
- **L1 PRD**(`docs/prd/<addon>.md`):业务闭环——谁用、什么流程、闭环起点/终点/异常路径、验收标准;**每个 addon 开工前产出,业务方确认后才写码**,不批量预写;
- **L2 规则清单**(`docs/rules/<addon>.md`):实现口径——AI 从老代码提取的状态机/金额口径/流程规则,是 PRD 的技术事实底座;
- 分工一句话:PRD 讲业务(确认"对不对"),规则清单讲口径(明确"怎么算"),老代码是唯一事实源。

## 业务逻辑来源(参考项目)

- 🔴 **唯一业务参考 = 本机老系统 `D:\ERP\Rhadamanthus`**(Java 8 / Struts2 / Hibernate3,4666 个 Java 文件 / 40+ 模块;仅本机存在,开源访客忽略本节)——**只读参考,禁止修改老系统任何文件**;
- **提取方法论:不是平移代码,是提取行业模型**。每个 addon 开工前的固定流程:
  1. 读对应老模块(模块→业务域映射见 docs/01 过堂表)的 `service/impl` 与 PO,提取:状态机(每个 status 字段的枚举与流转)、金额口径(分存储/换算规则)、流程规则、字典枚举;
  2. 产出 **L2 规则清单**(`docs/rules/<addon>.md`)+ **L1 PRD**(`docs/prd/<addon>.md`,业务闭环),经业务方确认;
  3. 按 docs/01 横切红线剔除方林特性(company 分叉/公司口径/硬编码字典);
  4. 才允许写 yingzao 代码。禁止照抄老代码——技术栈不同,且老代码带历史包袱(如 tinyint 复用枚举、隐式单位换算);
- 老系统大量运行期坑(金额 ÷1000、枚举复用、加密体系等)**未全部文档化**,提取规则时必须完整读 service 实现,不依赖印象;
- 老系统的查询条件配置化(decorationfunctionright/constructionfunctionright 机制)、功能点字典等"思想好实现散"的设计,提取时吸收思想、重做实现。

## 技术基线

- Java 17 + Spring Boot 3.3.x + Maven 多模块(根 pom);MySQL 8 + Flyway;Redis(认证正式版接入);
- 前端:Vue3 + Element Plus(Web 后台,未开始);uni-app(小程序 + APP 一码双出,未开始);企微/钉钉 = 宿主 H5 免登(未开始);
- 后端 ORM 暂用 JdbcTemplate(spike 级),正式版评估 MyBatis-Plus;
- 🔴 **不引入工作流引擎**(Flowable 等)——2026-10-10 拍板轻量自研(固定节点+转交/审批的状态机)。

## 架构军规(违反 = 返工)

1. 核心代码**禁止出现任何公司判断**;公司特性 100% 沉淀到插件;
2. 插件之间**禁止依赖实现模块**,只准依赖 `yingzao-addons-api`(领域事件/共享扩展点契约);
3. 核心表结构永不因插件变更;插件自有表 `{addonId}_` 前缀;扩展表 `{addonId}_{entity}_ext`;
4. 错误码分段:内核 100000–199999;addon 在 addon.yml 声明独占段(如 customer=210000);同码同消息,禁止漂移;
5. Flyway 全程管 schema,禁手工 SQL;版本分段 = core 100–199,addon 对齐码段(customer 200+,customerprofile 300+),各 addon 独立 migration location;
6. 文案零硬编码;i18n basename 按模块独立(`i18n/messages,i18n/customer,...`),中英双语必须同步;
7. 组织只有单层 organization(`org_id` 列),集团多公司场景压后;
8. Spring 6.1 两坑:`@TransactionalEventListener(AFTER_COMMIT)` 上的 `@Transactional` 必须 `REQUIRES_NEW`;编译必须 `-parameters`(根 pom 已配);
9. 改编译配置后必须 `mvn clean package`——增量编译会残留旧 class。

## 前端工作流(审美强制流程)

- 🔴 **Web / 移动端 / H5 页面,视觉设计与审美审核由 huashu-design 技能负责**:任何新页面先出 3 个风格方向初稿给用户选,选定后才工程实现;实现完成后由 huashu-design 做视觉验收,通过才算完成;
- 页面工程规范:Web = Vue3 + Element Plus;移动端 = uni-app;企微/钉钉免登进入同一移动端页面;
- 后端数据源:`GET /api/addons` 下发已启用插件 + 槽位挂载(mounts)+ 字典,前端据此动态注册路由/菜单/槽位组件,禁止硬编码插件路由。

## 本地开发与冒烟

- 构建:`mvn clean package -DskipTests`(在仓库根);
- 运行:`YINGZAO_DB_URL / YINGZAO_DB_USER / YINGZAO_DB_PASSWORD` 环境变量 + `java -jar yingzao-app/target/yingzao-app-*.jar`;
- dev 库:192.168.5.21 独立库 `yingzao`(root/Neusoft!,仅 spike 冒烟用,与老系统库隔离;实测为 MySQL 5.6,基线 8 的 SQL 需向下兼容);
- 🔴 冒烟请求含中文必须用 python urllib(UTF-8),Git Bash 的 curl 发中文 = GBK 炸 JSON;
- jar 被锁:按 8080 端口找 PID `taskkill //PID x //F`。

## 当前进度(2026-10-11 夜间自动任务更新,第 8 次运行)

- ✅ 41 模块过堂终版;插件框架六点代码化;客源→客户细链冒烟 7/7(b1bde89);客源 V2 分配链冒烟 12/12(9689f13);内核通知中心冒烟 16/16(0d7c914);内核轻量审批状态机冒烟 25/25(b68eb4f);示范插件 addon-example+Web 通知/审批工程页冒烟 28/28(e148667);
- ✅ **uni-app 移动端骨架 `app/`**(第 6 次,一码三出):uni-app + Vue3 + Vite(@dcloudio 统一 3.0.0-alpha-5030120260930001,vite 5.2.8);八页 = 登录/工作台/客源登记/我的机会/我的审批(三视角+详情全操作)/通知列表/我的;API_BASE 条件编译;`build:h5`/`build:mp-weixin`/`build:app` 三端构建全绿;真机运行待 uni-app 账号与 appid;
- ✅ **Web 客源分配 + 机会列表工程页(第 7 次,customer V2 web 侧闭环)**:`api/index.js` 接线 assign/chances/confirm/complete 四接口;Leads.vue 加分配弹窗(manual 选人走 GET /api/users filterable / auto 轮询);新增 Chances.vue 机会列表(状态机 0→3→4 徽章 + 确认接单/标记完成);addon-customer 0.2.1 addon.yml 下发 `/customer/chances` 菜单,动态路由零硬编码注册;dev 库一键冒烟全绿;
- ✅ **内核 RBAC 最小设施(第 8 次)**:`yz_role/yz_user_role/yz_role_menu/yz_role_capability` 四表(V103 core 段)+ `/api/roles` 管理七端点(列表/建/删/详情/菜单·功能点·成员全量替换授权,错误码 122000-122005 双语);授权只存身份字符串(菜单=addon.yml path/功能点=mounts capability),授权不随插件启停漂移(修老 functionright 散装实现);内置 admin 角色旁路全部过滤且不可删改授权;`GET /api/addons` 去白名单改按登录人过滤(管理员全量/普通用户授权并集/无菜单无挂载插件整体隐藏),登录回显角色编码;Web 新增 Roles.vue 角色管理工程页(/roles 静态路由,侧栏仅 admin 可见)+ 登录存 yz_roles;
- ✅ **多级审批组合方式示范(第 8 次,RFC §6.8 兑现,纯插件侧)**:addon-example 升 0.2.0——days≥3 长请假须二级审批(290006 缺二级审批人拒绝),L1 通过的 Finished 事件在监听里调内核 submit 派生 L2 审批单并改挂 approval_id(approval_level 1→2),任一级驳回/撤回即终态,全部回写带 approval_id 守卫,竞态悬置单走内核 cancel 兜底;内核审批单零改动;
- ✅ **冒烟扩至 70/70(第 8 次)**:smoke_all.py 新增 RBAC 15 步(旁路/过滤/122003/建角色/122004/三类授权/详情回显/按人可见/删除回落)+ 多级审批 13 步(290006/L1 通过派生 L2/改挂守卫/L2 待办/121002/两级走完回写);MySQL 5.6 utf8mb4 索引 767 字节坑实锤(RFC §6.9,索引列 varchar ≤189);首轮 69/70 唯一失败为冒烟断言口径错(capability 授权后挂载随插件条目合法出现)非后端缺陷;
- ✅ docs/prd/addon-basedata.md(主材计划独立设计,未写码待确认);四域知识资产齐(rules customer/quotation/decoration/construction;PRD 六份含 basedata);三决策已定(组织单层/轻量自研/building 滞后);RFC §6 新坑 6-9 条;
- ✅ 风格三方向初稿(`docs/design/`)仍 🔴 未选定,选定前不做正式视觉(web 与 app 同批重做);
- ⏳ 待用户:风格三选一(docs/design/index.html)/ 客源 5 条 + 报价 8 条 + 装修单 10 条 + 施工 15 条规则确认 / PRD 确认(客源/客源分配/报价/装修单/施工五份 + basedata 材料计划 7 条)——确认后对应 addon 进入写码 / 企微钉钉凭证 / uni-app 账号与 appid / Docker 环境;
- ⏳ 可自动推进(下一晚建议,与视觉零耦合):①老代码规则提取补域(如 payment 款项域/marketing 营销域,四域之外新增 L2 清单)②addon-example 补「审批转交在多级串联中的表现」与 uni-app 端请假示例页对齐多级 ③业务 API 端点级鉴权设计(RBAC v2:菜单路径→API 前缀映射,01 清单 right 域"重设计"的下半场)④任一 PRD 确认后对应 addon 进入写码(basedata 最成熟:规则 CRUD+生成/推算/回填三件套已设计到位,确认即开工);
- 📋 每晚自动任务收尾:`docs/reports/<日期>-总结.md`(同日多运行按次序号区分),最新=2026-10-11-第8次运行总结.md。

## Git 纪律

- 提交信息中文、结构化(①②③罗列改动),风格参照已有提交;
- 只纳入本任务相关文件;commit 前 `git status` 核对;
- 推送只推 `origin`(gitee);github 靠 gitee 自动同步。
