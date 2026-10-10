# Yingzao(营造)AI 开发指南

> 本文件是 yingzao 仓库的 AI 工作指引。任何会话(无论工作目录在哪)开始前,按"必读文档"顺序自举上下文。

## 项目定位

- **装修行业开源 ERP**,非公司级项目;核心开源 + 一切皆插件(Odoo 式三层:平台内核 / 行业插件套件(开源)/ 商业插件(闭源));
- 品牌名取自《营造法式》——材分模数制 = 古代插件化;tagline:**现代装修企业的营造法式**;
- 仓库:gitee.com/bty1987/yingzao(主)+ github.com/bty1987/yingzao(gitee 自动同步,**勿直推 github**);默认 master 直接提交。

## 必读文档(上下文自举顺序)

1. `docs/01-剔除保留清单.md` —— **需求规格书**:41 模块过堂终版 + 横切红线(公司差异沉插件/字典单源/Flyway 禁手工 SQL);
2. `docs/02-插件框架RFC.md` —— 插件机制六点设计 + spike 结论(三坑)+ 验收清单;
3. `README.md` —— 定位 / 架构图 / 路线图。

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
- dev 库:192.168.5.21 独立库 `yingzao`(root/Neusoft!,仅 spike 冒烟用,与老系统库隔离);
- 🔴 冒烟请求含中文必须用 python urllib(UTF-8),Git Bash 的 curl 发中文 = GBK 炸 JSON;
- jar 被锁:按 8080 端口找 PID `taskkill //PID x //F`。

## 当前进度(2026-10-10)

- ✅ 41 模块过堂终版(需求规格书);插件框架六点代码化;客源→客户细链 dev 库冒烟 **7/7 通过**(提交 b1bde89);
- ⏳ spike 前端段:Web 后台(Vue3:菜单/槽位下发渲染)、uni-app 一码双出、企微/钉钉免登、docker-compose 容器验证;
- ✅ 三决策已定:组织单层 / 工作流轻量自研 / building 归并滞后。

## Git 纪律

- 提交信息中文、结构化(①②③罗列改动),风格参照已有提交;
- 只纳入本任务相关文件;commit 前 `git status` 核对;
- 推送只推 `origin`(gitee);github 靠 gitee 自动同步。
