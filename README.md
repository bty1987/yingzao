<div align="center">

# 营造 Yingzao

**现代装修企业的营造法式**

*The Open-Source ERP for the Renovation Industry*

[![License](https://img.shields.io/badge/License-AGPL--3.0%20%2B%20商业双授权-blue.svg)](#license)
[![Java](https://img.shields.io/badge/Java-17-orange.svg)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.x-green.svg)]()
[![MySQL](https://img.shields.io/badge/MySQL-8.0-4479A1.svg)]()
[![Flyway](https://img.shields.io/badge/Flyway-管理%20Schema-red.svg)]()
[![Vue](https://img.shields.io/badge/Vue-3.x%20%2B%20Element%20Plus-brightgreen.svg)]()
[![uni-app](https://img.shields.io/badge/uni--app-小程序%20%2F%20APP-2B993D.svg)]()
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-ff69b4.svg)]()

**客源 · 报价 · 合同 · 施工 · 材料 · 结算 —— 装修行业全链条,一个系统管完**

[快速开始](#快速开始) · [架构](#架构三层皆插件) · [业务全景](#业务全景行业插件套件) · [路线图](#路线图) · [文档](#文档)

</div>

---

## 这是什么

**营造(Yingzao)** 是一套面向装修行业(家装/整装/局装)的开源 ERP:从**客源线索**进来,到**报价、合同、施工、材料、结算**出去,行业全价值链一个项目覆盖。

它不是"通用 ERP 加几个装修字段",而是把装修行业二十年被验证过的业务模型——报价定额库、施工节点排期、主材销售、选材结算——以干净的架构重新建造一遍。

### 为什么叫"营造"

名字取自宋代李诫的**《营造法式》**——中国第一部建筑设计与施工标准。它发明的**材分模数制**:构件按标准模数在作坊预制、到现场拼装,宫殿与民居用同一套模数体系——这正是一千年前的**插件化架构**。

本项目以同样的思想构建:

> **核心只做平台与插件框架,一切业务皆插件,一切公司特性皆插件。**
> 装哪套插件,它就是谁家的系统。

### 它解决什么问题

- 装修行业数字化碎片化:获客一个 SaaS、报价一个工具、施工一个 App、财务一套金蝶,数据割裂,对不上账;
- 现有装修软件多为黑盒闭源,装企被供应商绑定,想改一个审批流都要等排期;
- 通用开源 ERP(Odoo/ERPNext 等)缺少装修行业深度:没有施工节点、定额报价、选材结算这些行业心脏。

营造的答案:**行业深度开源**——核心开源 + 行业插件套件开源,装企可自部署、可二开、可贡献回社区。

## 架构:三层皆插件

```text
┌─ 商业插件(闭源)────────────────────────────────────┐
│   金蝶对接 / 电子签 / 聚合支付 / 工资社保 / 工地监控 / …   │
├─ 行业插件套件(开源,随核心发布)────────────────────────┤
│   营销获客 / 客源 / 客户档案 / 楼盘 / 报价 / 合同 /       │
│   施工 / 主材销售 / 库存 / 材料 / 结算 / 款项 / 供应链      │
├─ 平台内核 yingzao-core(开源)──────────────────────────┤
│   插件框架 + RBAC / 组织 / 字典 / 文件 / 通知 / 审批 /     │
│   报表 / 任务调度 / 导入导出 / 企微钉钉适配 / i18n         │
└─ 端 ────────────────────────────────────────────────┘
    Web 后台(Vue3) · uni-app(小程序 + APP) · 企微 / 钉钉 H5 免登
```

| 层 | 开源 | 职责 |
|---|:---:|---|
| 平台内核 `yingzao-core` | ✅ | 插件框架(扩展点/领域事件/前端槽位)、组织账号、RBAC、字典、文件、通知、审计、i18n 等通用设施。**内核永不出现任何公司判断** |
| 行业插件套件 `addons/*` | ✅ | 装修行业业务域,随核心一起发布,是本项目的主体价值 |
| 商业插件 | ❌ | 财务对接、电子签、支付渠道等敏感/重运维能力,闭源变现,支撑项目长期投入 |

插件之间**禁止互相依赖实现**,只通过 `yingzao-addons-api`(领域事件与共享契约)协作;插件自有表以 `{addonId}_` 前缀隔离,核心表结构永不因插件变更。

## 业务全景(行业插件套件)

```text
获客链:  渠道/进线/归因(addon-market) ─→ 线索/跟进(addon-customer) ─→ 转化建档(addon-customerprofile)
交付链:  报价定额(addon-quotation) ─→ 装修合同(addon-decoration) ─→ 施工节点/排期/验收(addon-construction)
供应链:  商品/材料档案(addon-basedata) ─→ 主材销售(addon-sales) ─→ 库存/材料(addon-inventory / addon-material)
资金链:  款项/收款(addon-payment) ─→ 结算(addon-balance) ─→ 供应链协同(addon-supplier)
```

| 插件 | 业务域 | 状态 |
|---|---|---|
| `addon-customer` | 客源:线索登记、跟进、转化 | 🚧 已落地(spike 链路) |
| `addon-customerprofile` | 客户档案:转化后自动建档 | 🚧 已落地(spike 链路) |
| `addon-market` | 营销获客:渠道、进线、归因、案例、电子名片 | 规划中 |
| `addon-quotation` | 报价:报价单、定额库引用、增减项 | 规划中 |
| `addon-decoration` | 装修合同:合同、装修单状态机 | 规划中 |
| `addon-construction` | 施工:节点、排期、放线、验收、质检、日记 | 规划中 |
| `addon-basedata` | 基础数据:商品/材料档案、定材包(多插件共享) | 规划中 |
| `addon-sales` | 主材销售:销售单、结算 | 规划中 |
| `addon-inventory` / `addon-material` | 库存与材料管理 | 规划中 |
| `addon-balance` | 结算:结算单、选材结算 | 规划中 |
| `addon-payment` | 款项:收款、退款、下拨 | 规划中 |
| `addon-supplier` | 供应链协同(厂商协同) | 规划中 |
| `addon-building` | 楼盘档案(归并方式 spike 后定) | 评估中 |
| `addon-production` | 定制生产(自有工厂的装企才需要) | V1 不做 |

> 完整的模块判定过程(老系统 41 模块逐一过堂)见 [docs/01-剔除保留清单.md](docs/01-剔除保留清单.md)。

## 核心特性

- **插件化架构(自研)**——编译期装配的插件框架:`addon.yml` 清单、扩展点钩子、领域事件、前端槽位注册表、数据扩展约定。公司差异零侵入核心;
- **行业全链条**——获客 → 建档 → 报价 → 合同 → 施工 → 材料 → 结算,一条价值链贯通,跨插件靠领域事件协作(如客源转化后自动建档);
- **一码多端**——Web 管理后台(Vue3 + Element Plus)+ uni-app(微信小程序 + APP)+ 企微/钉钉免登 H5,一套账号体系收口;前端按 `GET /api/addons` 下发的插件清单动态注册路由/菜单/槽位,插件页面即插即显;
- **干净的数据模型**——无历史包袱:业务状态机显式化、金额口径统一、错误码分段、i18n 中英双语从第一行代码开始;
- **可审计的演进**——Flyway 全程管理 schema(内核与各插件独立版本分段),文档金字塔(需求规格书 / RFC / PRD / 规则清单)先于代码。

## 技术栈

| 端 | 选型 |
|---|---|
| 后端 | Java 17 · Spring Boot 3.3.x · Maven 多模块 |
| 数据 | MySQL 8 · Flyway(禁手工 SQL) · Redis(认证/会话) |
| 前端 Web | Vue 3 · Element Plus |
| 移动端 | uni-app(小程序 + APP 一码双出) · 企微/钉钉 H5 免登 |
| 部署 | Docker · docker-compose |

## 仓库结构

```text
yingzao/
├── yingzao-core            # 平台内核:插件框架 + 通用设施
├── yingzao-addons-api      # 插件间契约:领域事件 / 共享扩展点(插件协作唯一通道)
├── addons/
│   ├── addon-customer      # 客源(线索/跟进/转化)
│   └── addon-customerprofile  # 客户档案(转化后自动建档)
├── yingzao-app             # 组装壳:打包所有启用的插件,单一可运行 jar
├── docs/
│   ├── 01-剔除保留清单.md   # 需求规格书:41 模块过堂终版
│   ├── 02-插件框架RFC.md    # 插件机制设计 + spike 结论
│   ├── prd/                # 各 addon 业务闭环 PRD
│   └── rules/              # 各 addon 实现口径(状态机/金额/字典)
├── docker/docker-compose.yml  # 一键启动(MySQL 8 + 应用)
└── pom.xml
```

## 快速开始

> 当前为 **Phase 0 spike 阶段**:插件框架与"客源→客户档案"最小链路已在 dev 库冒烟通过,Web 前端尚未开始。以下步骤可完整跑通后端骨架。

**方式一:docker-compose 一键启动**

```bash
git clone https://gitee.com/bty1987/yingzao.git
cd yingzao/docker
docker compose up -d
# 应用: http://localhost:8080  (MySQL 同步启动,Flyway 自动建表)
```

**方式二:本地构建运行**

```bash
# 仓库根目录
mvn clean package -DskipTests

export YINGZAO_DB_URL="jdbc:mysql://<host>:3306/yingzao?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
export YINGZAO_DB_USER=root
export YINGZAO_DB_PASSWORD=<password>

java -jar yingzao-app/target/yingzao-app-*.jar
```

## 路线图

| 阶段 | 内容 | 状态 |
|---|---|:---:|
| Phase 0 | 插件框架 spike + 客源→客户最小链路(后端已通,Web/小程序/企微/钉钉全端打通进行中) | 🚧 进行中 |
| Phase 1 | 平台内核正式版:RBAC、组织、字典、文件、通知、轻量审批流、i18n | 规划中 |
| Phase 2 | 行业插件套件:报价、合同、施工、材料、结算、款项 | 规划中 |
| Phase 3 | 商业插件与插件市场、托管版 | 规划中 |

## 文档

| 文档 | 内容 |
|---|---|
| [docs/01-剔除保留清单.md](docs/01-剔除保留清单.md) | 需求规格书:老系统 41 模块逐一过堂,判归内核/行业/商业/删除 |
| [docs/02-插件框架RFC.md](docs/02-插件框架RFC.md) | 插件机制六点设计 + spike 结论(含三个工程坑) |
| [docs/prd/](docs/prd) | 各 addon 的 PRD(业务闭环),含模板 |
| [docs/rules/](docs/rules) | 各 addon 的实现口径(状态机/金额口径/字典枚举) |
| [AGENTS.md](AGENTS.md) | AI 协作开发指南(架构军规/文档金字塔/工作流) |

## 基本信息

| 项 | 值 |
|---|---|
| 主仓库 | <https://gitee.com/bty1987/yingzao>(/issues 在此提) |
| GitHub 镜像 | <https://github.com/bty1987/yingzao>(自动同步,勿直推) |
| 分支模型 | `master` 单主干直接提交 |
| 开发语言 | Java 17(后端) / Vue 3 · uni-app(前端,未开始) |
| 协议 | AGPL-3.0 + 商业双授权(见下) |
| 当前状态 | Phase 0——插件框架 spike 已验证,欢迎 Watch/Star 关注进展 |

## 参与贡献

项目处于早期架构落地阶段:

- 🐛 **Issue**:Bug 报告、架构质疑、文档纠错,随时欢迎;
- 💬 **行业需求反馈**:你是装企从业者?把你日常最痛/最离不开的流程告诉我们,这比代码更宝贵;
- 🔧 **PR**:目前暂不接受功能 PR(骨架未稳),文档类 PR 可提;
- 📖 参考 [AGENTS.md](AGENTS.md) 了解协作约定与架构军规。

## License

本项目采用 **AGPL-3.0 + 商业双授权**:

- 社区版以 **AGPL-3.0** 开源——你可以自由使用、修改、自部署,但基于它的二次开发对外提供服务时须同样开源;
- 不接受 AGPL-3.0 的商业使用(如闭源二开、深度定制交付),可另行购买商业授权;
- 商业插件族(财务对接/电子签/支付等)闭源,单独授权。

LICENSE 文件随首个公开版本一同发布。

---

<div align="center">

**一梁一柱,皆有法式。**

⭐ 如果这个项目对你有价值,欢迎 Star 支持

</div>
