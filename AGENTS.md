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

## 当前进度(2026-10-12 夜间自动任务更新,第 16 次运行)

- ✅ 41 模块过堂终版;插件框架六点代码化;客源→客户细链冒烟 7/7(b1bde89);客源 V2 分配链冒烟 12/12(9689f13);内核通知中心冒烟 16/16(0d7c914);内核轻量审批状态机冒烟 25/25(b68eb4f);示范插件 addon-example+Web 通知/审批工程页冒烟 28/28(e148667);
- ✅ **uni-app 移动端骨架 `app/`**(第 6 次,一码三出):uni-app + Vue3 + Vite(@dcloudio 统一 3.0.0-alpha-5030120260930001,vite 5.2.8);八页 = 登录/工作台/客源登记/我的机会/我的审批(三视角+详情全操作)/通知列表/我的;API_BASE 条件编译;`build:h5`/`build:mp-weixin`/`build:app` 三端构建全绿;真机运行待 uni-app 账号与 appid;
- ✅ **Web 客源分配 + 机会列表工程页(第 7 次,customer V2 web 侧闭环)**:`api/index.js` 接线 assign/chances/confirm/complete 四接口;Leads.vue 加分配弹窗(manual 选人走 GET /api/users filterable / auto 轮询);新增 Chances.vue 机会列表(状态机 0→3→4 徽章 + 确认接单/标记完成);addon-customer 0.2.1 addon.yml 下发 `/customer/chances` 菜单,动态路由零硬编码注册;dev 库一键冒烟全绿;
- ✅ **内核 RBAC 最小设施(第 8 次)**:`yz_role/yz_user_role/yz_role_menu/yz_role_capability` 四表(V103 core 段)+ `/api/roles` 管理七端点(列表/建/删/详情/菜单·功能点·成员全量替换授权,错误码 122000-122005 双语);授权只存身份字符串(菜单=addon.yml path/功能点=mounts capability),授权不随插件启停漂移(修老 functionright 散装实现);内置 admin 角色旁路全部过滤且不可删改授权;`GET /api/addons` 去白名单改按登录人过滤(管理员全量/普通用户授权并集/无菜单无挂载插件整体隐藏),登录回显角色编码;Web 新增 Roles.vue 角色管理工程页(/roles 静态路由,侧栏仅 admin 可见)+ 登录存 yz_roles;
- ✅ **多级审批组合方式示范(第 8 次,RFC §6.8 兑现,纯插件侧)**:addon-example 升 0.2.0——days≥3 长请假须二级审批(290006 缺二级审批人拒绝),L1 通过的 Finished 事件在监听里调内核 submit 派生 L2 审批单并改挂 approval_id(approval_level 1→2),任一级驳回/撤回即终态,全部回写带 approval_id 守卫,竞态悬置单走内核 cancel 兜底;内核审批单零改动;
- ✅ **款项域 L2 规则清单(第 9 次,docs/rules/payment.md,五域知识资产)**:payment 模块 185 文件/19 ServiceImpl 四路并行深读——通用收款生命周期 0→1→2 全族同构(守卫三件套/收据双人留痕/"收款即确认"实为前端联动);金额口径全库厘(1e3)实锤+渠道缩放黑魔法(paytran ×10000/×1000 并存);装修款线五态+双类型位+sort 顺序门控+比例双闸+退款三轨并存;预付款四科目台账(三条路径写同一余额、原子扣减 SQL 是死代码、负数可反转余额);主材/销售款批量 N:1 收据挂接+先写后验反模式;paytran=渠道枢纽(makePayment 分发六族/分次支付静默 return/外销 paytran 永不置 USED);PaymentFunction 功能字典三层结构(思想收编 RBAC v2);钱包族实锤半成品(绑卡状态机断裂+卡明文)建议不迁;跨模块写表面全集→营造切领域事件架构红线;缺陷 10 条(错误码漂移一族/confirmPayment NPE/服务款退款清零)+方林嫌疑机制级 11 条;§13 待业务确认 10 条;
- ✅ **RBAC v2 设计稿(第 9 次,docs/03-RBAC端点鉴权与数据权限设计.md,纯文档未实施)**:业务 API 端点级鉴权 = addon.yml 新增 `api` 段声明(prefix/exact → require 菜单 path 或 capability,复用 v1 身份字符串授权,角色 UI 零新增维度)+ 内核 RbacInterceptor 统一拦截(管理员旁路/122006 双语/授权并集 Caffeine 缓存+变更主动失效);两阶段收紧(observe 观察期留审计日志 → enforce 未声明即拒);数据权限三层(L0 org 硬边界+L1 归属可见 V2 落地、L2 部门范围等内核组织架构 V3);§8 五项决策待拍板后按 §9 实施清单开工;
- ✅ **addon-example 多级审批前端对齐 + 转交串联冒烟(第 9 次)**:web Leaves.vue 补**二级审批人选择器**(此前 days≥3 长请假在 web 根本发不出,真缺口)+列表审批级别徽章;uni-app 新增请假页(pages/leave 列表含二级徽章/撤回 + create 发起含二级审批人 picker,工作台入口,镜像 addon.yml 状态字典);app 全局样式补 btn-mini.warn;冒烟 70→**88 步**:新增「转交×多级串联」18 步——非处理人转交 121002/L1 转交后待办迁移、转交后处理人通过仍触发派生 L2(派生链与处理人无关实证)/L2 二次转交/两级走完回写通过;
- ✅ 冒烟扩至 **88/88 全绿**(dev 库 192.168.5.21 yingzao);mvn clean package 全绿;web npm build 全绿;app 三端构建全绿;
- ✅ **款项 PRD(第 10 次,docs/prd/addon-payment.md,纯文档待确认)**:规则底座直转业务闭环——**款项单+统一收据+预付款科目化三件套**:款项单类型字典化(类型/顺序/比例模板,灭绝中文名字符串分支)+签约拆分拒绝重建;收据双人留痕+Σ渠道==应收+先验后写;预付款=组织自定义科目字典+原子扣减 SQL(复活老 DAO 死代码)+金额强制>0;退款统一走内核轻量审批(废三轨并存,带状态快照);跨模块回写全事件化(收/确认/退/预付款变动四事件,业务插件自监回写);码段 270000/迁移 800+;非目标剥离渠道适配(V2 插件族)/钱包不迁/批量收款/客户微信确认;
- ✅ **营销获客域知识资产(第 10 次,docs/rules/market.md + docs/prd/addon-market.md,第六域)**:market 老模块 107 文件/15 ServiceImpl 三路并行深读——模块定性:**2026-07 新增**(老系统最现代化代码:幂等键/AES 手机密文/REQUIRES_NEW 桥接)+**纯叶子**(全仓零反向依赖)+三族(获客链/官网案例库 E 族/微信名片族);获客链=活动(6态含2死态)×渠道→投放源(渠道码/二维码/UTM 三件套)→进线(幂等键+手机密文+EAV 动态字段,幂等三退化实锤)→REQUIRES_NEW 承接桥直调 customer(**孤儿事务缺陷**+housePO 判空序 NPE 地雷)→归因 insert-only 四级漏斗(order 级未实现);案例库=城市-案例-人物-图片-报名五件套展示 CMS(每城每类露 10 条/上下架布尔开关/报名手机明文+钉钉播报/地理回退硬编码"沈阳"/Eopinions 按错 PK 更新);名片族=外部 ISV 白标前台+留资通知(isv 配置键前后端两义/PII 明文进 GET URL/事务内 httpGet/全族零 FLException 静默 return);缺陷 13 条+方林嫌疑 12 条+§11 待确认 8 条;PRD 闭环=投放→进线→**承接改事件+回写契约(废跨插件直调)**→漏斗统计,案例报名复用进线链,码段 280000/迁移 1000+(900+ 被示例占用);
- ✅ **building 域考证(第 11 次,docs/rules/building.md,第七份 rules,决定性负结果)**:building 不是楼盘档案而是**二手房中介业务**(151 行字典全中介词汇;楼盘/房屋档案实际在 communion 模块);**生产 building catalog 0 表**(2026-10-11 生产只读库复核,全库仅 communion.buildingnamerule 一张)、代码引用的权限表生产也不存在 → **从未投产的死模块**;代际 2018-01→2019-09 活跃后荒废、全仓零反向依赖、装修单 hid 链路不经它;缺陷 12 条(状态常量同值 1=签约=失效/三单号共享装修单日计数器非原子/count→save 无唯一键/收钥匙 type=3 硬编码不在字典);**结论:不建 addon-building 也不并入 addon-customer——整域不迁**,仅沉淀三条可迁移规则(物理房屋档案与业务挂牌分离/联系号码走 customerPhone 主数据/反面教材三条立红线);docs/01 待办"独立 vs 并入"建议改判废弃(§7 待业务确认 5 条);
- ✅ **主材销售域 L2 规则清单(第 11 次,docs/rules/sales.md,第八份 rules,第七个活域)**:sales 141 文件/18 ServiceImpl 5038 行三路并行深读(主链/结算退货采购线/外销活动族)——定性=装修单附属主材销售域(挂 orderId 无 FK)+**至今高频生产维护**(113 commits,8.3.030 仍在打行业规则补丁:退货结算防重 370033/空配置套装降级)+**跨模块写表面最大域**(44 文件/7 外部模块直写 sales 表:payment 改款项轴与 pompany/approval 改主单状态与预扣明细/inventory 直改五计数/balance 回写对账,零防腐层);四轴状态机(status 缺 1,写点全在 payment/approval 域)+守卫组合表(明细在全款确认后冻结/付了钱未确认即可配送)+配送四级(**品牌跳级权限+按配送批次分成克隆结算行**=方林玩法)+结算三段式(预结→厂结→结算按品牌 1:N,vbalance/pbalance 双值人工修正,**让利公式量纲不自洽:三套公式三种舍入,cvalue 真实量纲代码不可确证**)+退货线(审批批准是唯一创建入口/客户微信确认幂等/370033 防重/**退货不改主单 status 实证**,作废 3 写点全在别处)+外销族(oales 7 态/atttype×type 正交/**paytran 永不置 USED 断链实证:collectOalesPayment ptids 零引用**/oalesitem 参数名错位链路断裂+千倍虚增嫌疑)+活动族=**积分商城四处三模块无单一 owner**(活动主档 information/兑换 sales/核销 decoration/台账 payment,建议整体剥离会员营销插件);金额口径=毫×1000 全域+件数×1000+积分 ÷1e6+**addOales award/profit 缩放缺口事故(元当厘)**;缺陷 20 条(信任前端金额/免付款假收款单/验证码绕过/双码三处/主链零操作日志)+方林嫌疑 16 条+§13 待确认 16 条;
- ✅ **addon-sales PRD(第 11 次,docs/prd/addon-sales.md,第九份 PRD,待业务确认)**:闭环=开单(挂装修单)→客户确认→收款(addon-payment 事件)→出货配送→厂商结算→完结/废单;V1=主链+四轴+结算三段(去留随规则 §13 拍板)+退货(rid 幂等)+履约四级;**非目标剥离**:外销 V2 精简版/活动积分(归会员营销插件)/满意度(统一 survey)/品牌跳级与分成克隆(默认砍);码段 300000 建议/迁移 1100+(290000/900+ 被 addon-example 占用);随附两条全局红线=金额统一最小单位显式换算(消灭 tranargs 隐式 ×1000)/库存计数事件化(禁直写他域表);
- ✅ **库存域 L2 规则清单(第 12 次,docs/rules/inventory.md,第九份 rules,第八个活域)+ addon-inventory PRD(第十份,待业务确认)**:inventory 老模块 140 文件/20 ServiceImpl 4841 行三路并行深读(库存主链/外辅辅材/采购横切)+生产只读库复核——定性=「单据即库存」的流水账(库存增减内联在单据创建事务,冲销=反向类型新单无作废,12 类货单=天然库存事件字典:进货/进退/出货/出返/调拨/采购入库/辅料/辅退/套合/套拆/盘盈/盘亏,前端手工建单仅进货/出货两类);生产超大活域(manifest 258 万/两明细表 1700 万行级,外辅 7.7 万单 2026 仍在加功能)+**生产零落地四件实锤(purchasebalance 采购结算 0 行从未投产/manifesttemplate 模板 0 行/storehousestock 月结死表 182 行/checklist 盘点孤表 1171 行)**;全域唯一真守卫=goodsstorehousestock 原子扣减 SQL(`num>=:num`,2026-10-01 才修上),其余五处 check-then-act 竞态+单号计数器读改写竞态+恒假守卫 `size()<0`+260028 死校验(punum 可打穿负)+punum 配额永不释放+采购退货断链+INIT 删行不回加 xgnum 等**缺陷 27 条**;跨模块写表面:seiya 直连 DAO 绕 Service 写库存(2026-09 新增)/approval 走 Service/inventory 直写 sales 五计数两处+工程辅料额度两表;260xxx 错误码被 **7 个外模块**借用(半个公共段);外辅「厂商即用户」+钉钉 fanglin 域名硬编码兜底+多租户半成品(库存/仓库/授权全无 company)等方林嫌疑 8 条;字典 0=肯定反直觉+外辅「完成(5)」字典缺席端点不可达;Occessories(外辅)≠Accessories(领料)拼写地雷正名;PRD 闭环=出入库记账→库存事件分层(单据面/事件面拆开,sales/construction 订阅回写自己的账),码段 310000 建议/迁移 1200+;**非目标剥离**:模板/月结/盘点孤表/采购结算手工台账(统一走 addon-payment)/金蝶死代码/厂商即用户(归 addon-supplier);待业务确认 14 条(多租户边界/单据即库存去留/punum 语义/采购结算重做/外辅结算接钱线/领料双闸去留等);
- ✅ **material 域考证与拆分裁决(第 13 次,docs/rules/material.md,第十份 rules,第二个决定性负结果+basedata 档案章节兑现)**:material 两包 125 文件/20 ServiceImpl 三路并行深读(material 主链五表/商品档案族/oods 三族)+生产只读库复核——**addon-material 建议改判不建(拆分归并)**:①material 主链=**1.x 代"主材子订单"销售域**(品牌专卖店模式,与 sales 同源同日起家 2014-10-7,旧包名 materialOrder 残留;sales 是 2.0 重写版),生产考古层实证:主单 43 万但**新增 2019 骤停(2017 峰值 14.6万→2019 3132→2021 仅 1 单,此后零单)**,而 materialbalance 结算长尾仍活(最新 2026-08-22,预结算在途 31 万行)+payment.materialpayment 第三收款线 59 万行 2026-08 仍在发生——**新增死/存量结算活**的考古层活域;裁决=主链不迁以 addon-sales 为唯一口径(回应 sales.md §13-15 两套主材销售之谜)、材料款收款线并入 addon-payment 款项类型、五链状态机/审批预扣模式/双轨结算旁路等作为反面教材与对照物入档;②商品档案族归 addon-basedata(已定案,本晚补齐档案细节+**addon-basedata PRD 兑现"商品档案另出章节"欠账**:goods 主档 70.8 万/SKU 属性/多价格体系/套餐/型号库/辅材/定材十模型改法表+五条横切修正——查重下沉 DB 唯一约束(goods.no/barcode 老系统全程无查重)/导入器全量校验(pinyin List.toString 方括号脏值)/砍 inventory 三镜像表(库外触发器同步)/company→org_id 单层化/砍商品池级联);③oods 三族归属定案建议:noods 定材(1680)→basedata/soods 选材(12.8万+item 31.6万,qiid 挂 quotationitem 实证)→addon-quotation(写侧)+addon-sales(读侧)/aoods 辅材(4.9万+charge 2877,2026-03 还在新建收费模板模块)→basedata 或 inventory 待确认;④"两套主材销售并存"定案:material 无 ctype/cvalue,优惠三承担方(coupon 公司券/venderMaling 厂商承担/maling 商场让利)与 sales 同族;缺陷 18 条(恒真守卫 isStock/量纲自差 discountTotal 恒 0/事务内 sendMessage/双轨结算旁路/错误码 130-180-150-260 四段漂移)等;方林嫌疑 10 条(company==1 硬编码等级/中文支付方式常量/正林第二品牌账户/ds 字典同文件两套口径自相矛盾/流水计数器借装修单);**待业务确认 8 条**(改判不建/存量 43 万单收口策略/材料款形态/辅材归属/型号库合并/选材两侧拆分/noods 集团两级组织化/优惠承担方对齐);
- ✅ **balance 结算域 L2 规则清单(第 14 次,docs/rules/balance.md,第十一份 rules,第九个活域,未提取活域知识资产拼图收官)+ addon-balance PRD(第十一份,待业务确认)**:balance 老模块 108 文件/13 ServiceImpl 三路并行深读(主链+账户扣款/销售结算线/选材外销线)+生产只读库 15 表全量复核——定性=**结算汇聚域**(上游三源明细按品牌+供应商聚合成三张结算单→通用资金头 balance→账户双腿+扣款池的厂商应付闭环;自己不产生金额,全部是上游聚合);三结构性事实=①明细表全是三列挂接表零快照(结算单金额=查询时动态聚合,锁定锁不住上游改价=金额漂移根源)②单向吸附+回写三字段(全仓唯一跨模块服务调用 sales→submitBalanceSalesBalance)③资金头是死账本(7 万行 status 全 0/type 全 0/startTime/endTime/balanceTime 全 NULL,唯一活链=payedBalance 打 isPay 97%);生产考古=四线全活(balance/balancesales 最新记录当天)但 2023 年 3 万行突增疑补录(代码 2020 建成闲置 3 年)+六死表(apportdetail/balanceaccount/balancedccount/balancedetail/comcost 全 0+picture 仅 9 行)+f384101d3 账户/扣款闭环改造至今未部署生产(balanceaccount 仍 0 行);关键勘误两处=①选材结算真实数据源是 quotation.selectionItem 而非 soods(soods 是商品库存资料,与 material.md 的"选材单据"是两族同名不同物,关系待确认)②聚合除数 10^6 而非修数先例口头流传的 DIV 1000(数量/单价双千倍,修数迁移须按列量纲重推);金额口径红线=**全族千元制实锤**(api tranargs ÷1000 链,模块 CLAUDE.md"分"系文档错误记缺陷)+四套舍入并存(承接 sales.md 三套公式)+grade A/C 硬编码(销售桶恒 A/选材桶恒 C,A 级按 income 结的规则只活在前端)+信任前端金额三处;缺陷 39 条精选(外销绑入无防重利润重复累加/batch 把商品 gid 当品牌 bid 写入/submit 自动建单分支创建后未赋回必抛回滚=永不成功/discount 无状态守卫已结算可改/池原子 SQL 无守卫+金额全链无>0 校验负数可反转守卫/拆分可搬走已勾对明细/字典三处漂移:balanceOales 字典 5 态 vs PO 3 态、balanceCutpay 缺 type=0 键而生产数据全是 0、selection grade 三条挂同一 GRADE_A_INT 字段);正面教材 f384101d3(账户双腿分流/原子 UPDATE 资金守卫/懒开户/bind-unbind 对称闭环/DTO 兼容旧客户端零前端改动);错误码 350xxx 与 error.md 零漂移(治理受益区);**待业务确认 10 条**(金额量纲定案/账户闭环投产策略/grade 语义/确认态去留/拆分去留/两族选材关系/审核线形态/完结唯一定义/2023 突增考古/死件四件);
- ✅ **communion 档案域考证(第 15 次,docs/rules/customerprofile.md,第十二份 rules,第十个活域,全域主数据底座)+ addon-customerprofile PRD(第十二份,待业务确认)**:communion 档案半区(Customer/CustomerPhone/CustomerAccount/CustomerRecord + Village/House + BuildingNameRule/VillageRule)三路并行深读+生产 9 表复核——定性=**全 ERP 主数据底座**(79.4 万客户/87.3 万房屋/4.9 万楼盘,decoration.hid 挂接率 100%)+**全系统最干净的主数据纪律:唯一写点**(客户/房屋建档全仓仅 communion 两入口,其余模块一律只读,与 sales/inventory 跨模块直写灾难形成对照);**四生产考古**:①楼盘库是新建的——48,897 行 createTime 全在 2025-12-18 后(P- 代两周批量导入 3.8 万,L- 代 seiya 通道持续导入且 company 全 0)②customer.status=2 生产 84,387 行系 2015-2025 历史电子签通道写入的**退役态**(provider 全=1),2026 起新写入全走 status=1(18,559 行实证)③houselayout 户型库 8 行死功能(vs house 87 万)④village.cpid/bnrid 死列;**核心规约四条直接收编**:手机号=客户身份锚点/existCustomer·existHouse get-or-create 幂等建档/电话读取必留痕(customerrecord 35 万逐年翻倍,双写 log.customerLog 迁移中)/主数据唯一写点;加密体系 ENC(V1:)+三形态兼容读+密钥四级兜底(🔴 密钥与会话 cookie 同源);缺陷 32 条精选(updateCustomer 拼音条件反转 NPE/HouseDAO 对不存在 status 字段过滤必炸死雷/updateHouse ignores 契约漂移——楼牌四要素可提交不生效+面积无法清零/updateCustomerNameAPhone.do 死 API 断链——**客户改名审批流是断的**/existCustomer 窄口径同自然人多档/换房审批前后端双注释死亡但 submit 仍可 POST 产脏单/密文泄漏两处/countVillageByNameACpid SQL 缺空格炸弹/BuildingNameRule update 实为再插一行)+方林嫌疑 10 条(正林常量挂客户 PO/沈阳地图中心/relationshipType 值即中文/feature"三不"进表结构/密钥同源/开关式多租户)+**§19 待确认 12 条**(档案归属独立 addon vs 并入 addon-customer/门牌明文 94% 存量加密策略/status=2 归并/电子签五件套与档案解耦/对公账户单一事实源/共享楼盘池两层/客源快照瘦身等);PRD 闭环=城市→城区→楼盘树→房屋(四段+密文+唯一键)→客户(手机锚点一人一档)→查询审计;**淘汰清单=houselayout/换房审批/对公六要素双向同步**;码段 **330000 建议/迁移 1400+**(300/310/320 已被 sales/inventory/balance 占用,军规 5"customerprofile 300+"系早期示例已顺延);
- ✅ **information 域考证(第 16 次,docs/rules/information.md,第十三份 rules,第十一个活域,🎉 41 模块全域覆盖收官)+ 过堂表两处预判推翻**:information 144 文件/21 ServiceImpl 三路并行深读+生产 24 表全量复核——定性=**五个互不相通机制拼盘**,逐族裁决不建 addon-information(与 material 同判拆分归并);**活性矩阵**(2026-10-11 读数)=秒级活跃 8 族(message 877 万/messagequeuing 86.9 万/messagecueuing 32.2 万/plan 46.6 万/events 41.2 万/awardrecord 25.4 万/activity/awardpurchase)+低频 2 族(notice/worksheet)+死亡 5 族(vpayment·vticket 止 2022-09 会销停办/phonecall 仅 17 行止 2019/assassin 936 行止 2018-03 断链 8 年与 customerprofile.md"客户改名审批断链"互证/messagedueuing 0 行);**两处预判推翻**:①Plan/Worksheet 非删除节——Plan 是**生产级异步导出/导入调度平台**(communion.task 脚本注册+executeTime 排队+Python 调度器回调,14 个前端功能点在用,失败率 10%,2025-12 仍在迭代,plan_export_*.py 脚本全族的调度底座,🔴必迁),Worksheet 2024-05 刚改版;②Activity/Award 非"营销噱头"——Activity 是**积分商城心脏**(atttype=1 活动主档+multiple 倍率/validays 有效期唯一来源+六域消费 11 import 20+ SQL join)、AwardStock 是礼品兑换物资底账(25 万流水,三域 9 处扣减全链竞态),🔴随积分商城剥离 addon-member(sales.md §13 联动);**三条判决性数据**:messagecueuing 32.2 万全 status=0 **零消费假队列**(写入方以为在通知外部系统,实际无人读,30188 条 args 修复白修)/messagequeuing **44% 事件 status=2 失败无重试=近半丢失**(营造事件总线三件套红线:消费确认+重试+死信)/payment.activitypayment **0 行从未投产**(V 系才是现役,修 sales.md 存疑);V 系定性修正=2019 线下营销会人工登记发奖闭环(非微信抽奖);通知件映射=Message 主体即 yz_notification(映射现成)+钉钉剥离为 AFTER_COMMIT 投递器/Queuing 族删表、33 种 event 按域还给插件做领域事件;缺陷 23 条(事务内发钉钉/必崩死接口 updatePlan 整数溢出/PlanParamPO 主键映射 @Column(tpid) vs 生产列 ppid 必炸/12·24 小时制混用/updatePlanForAdvance 无鉴权可任意改写/活动时间窗形同虚设等)+方林嫌疑 6 条(company 三形态不对称/总部仓硬编码/钉钉微信双通道耦合/PM 系统同步契约属公司特性);§16 拆分终表 11 族+§17 待业务确认 8 条;
- ✅ docs/prd/ 十二份不变(information 拆分归并不出独立 PRD);**rules 十三份**(customer/quotation/decoration/construction/payment/market/sales/inventory/balance/customerprofile 七活域规则+building/material/information 三域拆分归并考证);三决策已定(组织单层/轻量自研/building 滞后→**考证建议改判废弃待确认**);RFC §6 新坑 6-9 条;
- ✅ **风格已定:方向三「东方纸墨极简」**(2026-10-11 用户选定,原话与 DNA 落档 `docs/design/direction-approved.md`)+ **web 视觉落地完成**:视觉 token `web/src/styles/yingzao.css`(纸白暖底 #FAF9F5/墨黑 #1A1A18/朱印红 #B03A2E 唯一点缀/宋体 display/8px 材分模数;Element Plus 官方 CSS 变量主题化+古籍双线表格+方角墨黑主按钮);Login 重做为古籍扉页(双线版框+朱印「法式」+墨线输入框);Layout 弃侧栏改顶部横导航(全部逻辑保留:动态菜单/admin 角色项/通知铃/登出清理);各页硬编码颜色 token 化;npm build 全绿。**app(uni-app)端样式对齐待下轮**;各页面视觉验收待用户浏览器确认;
- ⏳ 待用户:~~风格三选一~~(已定方向三,余=web 视觉浏览器验收+app 端样式对齐)/ 规则确认:客源 5 + 报价 8 + 装修单 10 + 施工 15 + 款项 10 + 营销获客 8 + **building 废弃定案 5(docs/rules/building.md §7)** + 主材销售 16(docs/rules/sales.md §13)+ 库存 14(docs/rules/inventory.md §14)+ **material 拆分裁决 8(docs/rules/material.md §14,含 addon-material 改判不建/存量 43 万单收口/材料款归 payment/辅材归属等)** + **balance 结算 10(docs/rules/balance.md §16,含金额量纲定案/账户闭环投产策略/grade 语义/确认态去留/拆分去留/两族选材关系/审核线形态/完结唯一定义等)** + **customerprofile 档案 12(docs/rules/customerprofile.md §19,含档案归属独立 addon vs 并入 addon-customer 定案[docs/01 行 58 与军规 5 码段示例口径冲突]/门牌明文 94% 存量加密策略/status=2 退役态归并/电子签五件套解耦/对公单一事实源/客源快照瘦身[涉 addon-customer 契约联合拍板]等)** + **information 拆分裁决 8(docs/rules/information.md §17,含 Plan 调度平台归属定案[🔴删除节改判必迁]/Activity·Award 随积分商城剥离 addon-member[🔴改判]/PM 外发契约去留/公告板归属/自建日程去留/工单共享池定案/五族删除复核/通知 type 字典定案)** / PRD 确认(客源/客源分配/报价/装修单/施工/basedata(**含增补商品档案章节**)/款项/营销获客/主材销售/库存/balance 结算/**customerprofile 客户与楼盘档案**十二份)——确认后对应 addon 进入写码 / **RBAC v2 设计稿 §8 五项决策**(声明位置/观察期时长/method 维度/数据权限 L2 时机/字段脱敏)/ 企微钉钉凭证 / uni-app 账号与 appid / Docker 环境;
- ⏳ 可自动推进(下一晚建议,与视觉零耦合):🎉 41 模块规则提取**全域覆盖收官**(十三份 rules);①九域规则交叉核对第二遍(material/balance/customerprofile/information 四次裁决落地后,对 quotation(selectionPart/soodsitem/selectionItem 三族"选材"辨析)/decoration 涉商品与档案引用/information 33 种 event 与各域事件的对照做增量核对;material/building/customerprofile/information 裁决回写 docs/01 过堂表待业务确认后执行,customerprofile 归属定案后同步修订 docs/01 行 58 与 AGENTS.md 军规 5 码段示例)②RBAC v2 拍板后按 §9 实施清单写码③任一 PRD 确认后对应 addon 进入写码(basedata 规则底座最全、库存 V1 面积小、**balance 含 f384101d3 当代正确写法可直接收编**、customerprofile 四条规约与唯一键方案现成,四者确认成本低)④app(uni-app)端样式对齐方向三「东方纸墨极简」(风格已定非新拍板,视觉 token 移植 app 全局样式);
- 📋 每晚自动任务收尾:`docs/reports/<日期>-总结.md`(同日多运行按次序号区分),最新=2026-10-12-第16次运行总结.md。

## Git 纪律

- 提交信息中文、结构化(①②③罗列改动),风格参照已有提交;
- 只纳入本任务相关文件;commit 前 `git status` 核对;
- 推送只推 `origin`(gitee);github 靠 gitee 自动同步。
