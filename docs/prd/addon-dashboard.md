# PRD - D4 驾驶舱与报表框架(F8 报表框架最小面 + addon-dashboard 看板族)

> 状态:**已拍板(按推荐,持久授权)+写码首轮已兑现**(2026-10-30 第 104 次:内核 report 最小面 V109+MetricProvider SPI+两数据端点+埋点+addon-dashboard 骨架(390000/V1901)+北极星五 provider+工程维 ads 表日刷链;冒烟 2520/2520+enforce 101/101 全绿;Web 看板页三方向初稿已出 docs/design/dashboard-dir-1/2/3.html 待拍板——唯一剩余闸)
> 口径输入:**docs/06**(高管驾驶舱专题定稿归档 2026-10-20:六维 50+ 指标口径/口径治理七条/空态原则/信息架构/图表硬规则)+ **docs/07**(fl-yy 资产盘点)+ **docs/scripts/ads-reference/**(6 张聚合 SQL 参考,头部口径/尾部校验格式)
> 老系统参考:老系统 `com.fanglin.report`(metric_* 分域预计算表族 + echarts + job,01 过堂判「内核:报表框架」)+ fl-yy BattleBoard 14 Controller(2026-10-30 快照逐名核对在档)
> 归属:**两件套拆分**(开放问题 1)——报表框架最小面(F8)→ **内核 yingzao-core 新 report 包**(错误码 **124000** 段,124xxx 未占用;迁移 **V109+**,core 段现至 V108);看板族纯读面 → **新行业插件 addon-dashboard**(错误码 **390000**(210000–380000 连续占满后的首位);迁移 **1900+**;菜单「驾驶舱」)

## D4 范围裁决(先说清本 PRD 覆盖什么)

fl-yy 战报/驾驶舱在营造拆三处:D3 PK 对赌(已实现)、**本 PRD(D4 看板族+承载它的报表框架)**、其余按域划走。docs/06 §7.1 方法论先行:**先盘点现成资产再动手**——盘点表见 §四,结论=北极星五分之四有现成契约/端点可包装,V1 真正要新建的聚合表只有工程维一张。

## 业务闭环(一句话)

高管打开驾驶舱,**30 秒扫完经营健康度**(结论条 3 句话异常置顶 + 六维健康卡 + 签约-回款双线 + 大区排名榜),每个数字可点开看口径(五要素)、异常走黄/红阈值告警、数据一律从聚合层出并带统一时点;数据终态 = 内核指标注册(MetricProvider 扩展点)+ 各 addon 聚合表(`{addonId}_ads_*`)+ 埋点单表。

## 谁与场景

| 角色 | 场景 | 高频动作 |
|---|---|---|
| 高管/总经理 | 每日晨会前扫健康度、月度经营会 | 看六维卡、点开口径、按维度切换、看大区排名 |
| 经营管理者 | 追目标达成与告警 | 看北极星卡、下钻(V1 组织维) |
| 各 addon 开发者 | 新指标上线 | 实现 MetricProvider 注册(内核零改动) |
| 系统 | T+1 聚合批 | yz_plan 日刷 handler(ads 表) |

## 核心模型(三件套,内核零业务知识)

1. **内核 report 包(F8 最小面,V109)**:
   - **指标五要素元数据**(docs/06 §2.1):口径定义(分子/分母/时间窗写到不追问)/数据来源(精确到表或契约)/更新频率(T+1 日批)/对比(环比·同比·目标进度)/告警阈值(黄红两级数值化)——可配置、可点开查看,禁止写死在图表代码;
   - **口径确认状态生命周期**:confirmed / pending(灰灯「口径确认中」,宁可空不可错——漏斗族 V1 即以此态示人);
   - **MetricProvider 扩展点**(`YzExtensionPoint`):内核定义 `fetch(metricKey, MetricQuery)`,业务插件实现注册;**内核数据端点=聚合枢纽**,addon-dashboard 只消费内核端点,插件间零依赖(军规 2);指标声明进 addon.yml 新 `metrics` 段(key/i18n 名/口径文本/频率/阈值/口径状态),provider 实现与声明分离;
   - **数据端点两枚**:`GET /api/report/metrics`(按登录人下发注册指标+五要素+口径状态)/ `GET /api/report/metrics/{key}/data`(参数=组织/时间窗/维度,返回数据+**asOf 统一时点**+空态结构);
   - **空态原则内建**(docs/06 §2.4 四分支):聚合表未建→骨架+口径确认中/当日未产出→「今日数据 X 点后产出」不显 0%/上游延迟→黄条「数据截至 X 日」不静默用旧数/正常无数据→绿态文案;
   - **埋点单表 `yz_report_event`**(5 类:pageview/dimension_switch/card_exposure/card_click/dwell;服务编排不服务考核);
   - 错误码 124000 段(指标不存在/未授权/参数非法/口径未确认等,实现轮逐条落 error.md)。
2. **addon-dashboard(看板族,390000/1900+)**:聚合首页(结论条+六维健康卡 2×3+签约-回款双线趋势+大区签约额排名榜)+北极星卡联动抽屉;**按角色默认落地页**(角色×默认视图配置表 `dashboard_role_home`,V1 仅 admin 可配无矩阵);「驾驶舱」菜单组;
3. **业务聚合表归各 addon 自建自刷**:`{addonId}_ads_*` 前缀+各 addon Flyway 版本化+头部口径注释/尾部校验查询(ads-reference 仓库规范)+REPLACE INTO 近 90 天滚动全量重刷(模式 2)+yz_plan 链式日刷 handler(biz_key 按日幂等+死亡死信+启动对账,member-expire-clear/target-pk-archive 先例)——**按需建**(开放问题 3):现成契约/端点能出的不建表,V1 仅工程维一张。

## V1 指标范围与资产盘点(北极星×5+排行,docs/06 §3 蓝本收窄)

| 指标(docs/06 口径) | 落点 | 现成资产盘点(2026-10-30 实证) | V1 动作 |
|---|---|---|---|
| ★月签约合同额(业绩营销) | decoration | ✅ `DecorationApi.contractSignedTotalByMonth/Between`(第 73 次为 target 建) | provider 包装零新表 |
| ★月目标达成率(经营管理) | target | ✅ addon-target achievement 端点(73/74/76 次) | provider 包装 |
| ★工单当日达成率(工程交付) | construction | ❌ 无聚合;ads-reference 01 口径照搬 | **V1 唯一新 ads 表** `{construction}_ads_wo_task_daily`+日刷 handler;源表映射 construction 节点排期/放线真列,实现期核对 |
| ★月主材业绩(供应链) | sales | ⚠️ SalesApi 仅单据引用,无月度聚合 | sales 域 provider(月度签单权责聚合,本域 SQL);口径细节开放问题 2 |
| ★月经营性回款(财务) | payment | ⚠️ PaymentApi.listBills 按 biz 三元组,无月度聚合 | payment 域 provider(收款明细按月聚合,认领到账口径);财务维时间窗恒「月」 |
| 大区签约额排名 | 内核 organization 维度 | ✅ org 树(S1) | dashboard 侧按组织聚合 |
| 六维健康卡+结论条+双线 | dashboard 组合 | 各域 provider | 聚合首页组合 |
| 漏斗五段/渠道分布/渠道 7 日转化/投放费效 ROI | customer+market+decoration 跨域 | 🔴 **前置缺口实证**(R33):`customer_lead` 仅 status 0 登记/1 转化,**无有效甄别态、无到店/预约时间戳**,source 枚举与 market 渠道体系双轨脱节;`market_inbound` 已有归因三键+承接回链 | **V1 不做,转「口径确认中」灰灯**——空态原则正是为此设计;R33(客源甄别态+到店时间戳+漏斗口径)落地后专项轮接入 |

🔴 **诚实边界(继承 target/PK 先例)**:业务单据归属=公司级,**分区/部门维度分子恒 0**,看板 note 双语明示;「下钻两级(大区→部门)」随单据归属模型自然点亮,V1 组织维度仅根视角。**权责/收付双标注**(§2.3-5):业绩卡=权责口径、回款卡=收付口径,同屏双标注。**统一时点**(§2.3-6):契约查询期直出的指标 asOf=now、ads 表指标 asOf=批次时点,每卡显式标注——混合模式下禁止全页假装同一时点(实现期核对清单 4)。

## 口径治理(框架行为,不是约定)

docs/06 §2.3 七条中五条由框架结构性承载:**唯一口径对账**(签约额唯一口径=DecorationApi 契约,target/PK/驾驶舱三消费方同源,新增口径须走契约禁另写 SQL)/ **漏斗统一同表出数**(漏斗 provider 全局唯一注册,禁各页自写)/ **指标独享**(指标 key 全局唯一,一指标一 key,注册期查重)/ **统一时点**(asOf 结构化)/ **告警通用基线**(阈值数值化入元数据,黄红两级)。归属快照两视图(§2.3-2)V1 不涉及(单组织);权责收付(§2.3-5)同屏双标注。

## 老缺陷收敛与方林嫌疑(不搬清单)

| 老系统/fl-yy 缺陷或特性 | 营造处置 |
|---|---|
| 老 report 模块各域 metric 表散落、口径写死图表代码 | 五要素元数据+provider 注册,口径可点开 |
| fl-yy Mapper 注解直读数仓 dws/ads(SQL Java 常量类拼接) | 聚合 SQL 归各 addon 自己的迁移+handler,头部口径/尾部校验仓库规范 |
| bi-prod 数仓 2011 表/ads·dws·api 分层命名/fl-* 服务接口【方林实现层】 | 不搬;营造=单实例内 `{addonId}_ads_*` 薄聚合 |
| 7 位高管默认落地页矩阵具体值/企微自建应用清单/三折叠规格【方林载体】 | 不搬;「按角色配默认落地页」思想保留(V1 最小配置表) |
| 北京专区五看板【区域定制】 | 不搬 |
| SQL 凭证明文/7026 行巨型 ServiceImpl(docs/07 §五反面教材) | 凭证只进配置;单类行数软上限,配置/计算/查询拆包 |

## fl-yy BattleBoard 14 Controller 去向(逐名,2026-10-30 快照核对)

| Controller | 去向 |
|---|---|
| BattleBoardDashboardController / BattleBoardAnalysisController | **本 PRD**:聚合首页 V1 / 明细分析面 V2 |
| BattleBoardTargetController | ✅ addon-target 已实现(达成率端点复用) |
| BattleBoardGeneralManagerPkController | ✅ addon-target PK 对赌已实现(第 102 次) |
| BattleBoardPankeReportController / LeaderPankeReportController | R33+ 漏斗专项(前置客源甄别态) |
| BattleBoardPankeCustomerController / LeaderPankeCustomerController | R37 addon-customer V2 候选 PRD(盘客客户管理) |
| BattleBoardPankeSyncController | 不迁(方林 CRM 同步器,营造无此源) |
| BattleBoardRiskOrderController | D4 V2 候选(在施风险口径定稿后) |
| BattleBoardMaterialController | addon-sales 维度:北极星 V1 收,明细战报 V2 |
| BattleBoardWorkbenchController | 不迁(过程任务管理,通知中心+轻量审批已覆盖诉求) |
| BattleBoardTargetIncentiveController | D1 激励活动平台(与 D4 达成率分母协同) |
| BattleBoardRewardGroupActivityController | member/market 活动域(荣誉墙纯读复用) |

## 端覆盖

- **Web 后台**:`/dashboard` 聚合首页(ECharts 图表);菜单「驾驶舱」组首项;🔴 **huashu-design 三方向初稿先行**(docs/15 军规点名 D4 看板页)——设计输入=docs/06 §4 信息架构(五区块骨架/产品原则十条/克制清单)+§5 图表选型硬规则/色彩语义(红涨绿跌仅用于环比)/KPI 卡规范/动效克制,**风格选择交用户拍板,不替选**;
- 移动端(uni-app)/企微钉钉:H5 投屏态 V1 不做(克制清单;方林三折叠属载体特性);
- C 端:无(驾驶舱纯内部管理面)。

## 闭环校验

- 闭环起点:业务 addon 指标 provider 注册 + ads 表日刷在挂(启动对账自愈);
- 闭环终点:高管 30 秒扫完健康度,每个数字口径可查、告警可见、异常有归因句;
- 异常路径:聚合失败=空态 pending 不以旧数冒充;日刷链断=死信可见可重试(总线+plan 双先例);指标 provider 异常=该卡黄条降级不炸整页。

## 非目标(本期不做)

- 漏斗五段/渠道分布/7 日转化/投放费效 ROI(R33 前置,已实证缺口);
- 风险单看板/盘客客户管理(R37)/业绩预测(D5)/激励活动平台(D1)/发票税务(商业插件);
- 明细分析面与导出 Excel/行级权限/拖拽布局/主题皮肤(克制清单 §4.3-9);
- 移动端/投屏态/实时数仓(V1 全 T+1 日批,查询期直出除外);
- 下钻两级完整版(待单据归属模型)。

## 验收标准

- [ ] 指标五要素齐全;口径未确认=灰灯「口径确认中」,不以估算值出图;
- [ ] 页面指标零跨域直查明细(经内核端点/契约);
- [ ] 空态四分支齐备;asOf 每卡显式;告警黄红阈值数值化生效;
- [ ] 日刷链断死信可见可重试;埋点 5 类落库;
- [ ] huashu-design 三方向初稿→用户拍板→实现→视觉验收全流程走完。

## 实现期核对清单(拍板后写码逐条销)

1. MetricProvider SPI 签名对齐 `YzExtensionPoint` 现有形态;addon.yml `metrics` 段解析入 AddonConventionPostProcessor 约定(零登记纪律);
2. `{construction}_ads_wo_task_daily` 源表列映射逐一核对(construction_schedule/节点状态真列,勿照抄 ads-reference 的 dwd_wo_task 列名);MySQL 5.6 兼容(REPLACE INTO/索引 189 封顶);
3. 日刷 handler=@Lazy PlanService 断环(第 N 次惯例)+biz_key 按日幂等+启动对账;
4. asOf 混合时点标注;enforce 样例表补 `/api/report` 端点;错误码 124000/390000 段先 grep 台账再用;
5. 冒烟专用数据隔离+段首定向清理;告警阈值断言(黄/红各一);空态四分支各一断言;
6. Web=huashu-design 三方向先行,拍板后实现,浏览器实测截图入仓(军规)。

## 开放问题(待拍板,可整体「按推荐」)

1. **落点两件套拆分**:推荐内核 report 最小面(V109/124000 段)+addon-dashboard(390000/1900+)分立——照 01 过堂判定「报表框架=内核,行业报表=行业插件」;备选全塞 addon-dashboard(内核不留 report 包,F8 欠账挂起);
2. **V1 指标范围**:推荐北极星×5+大区排名+六维健康卡(漏斗族转口径确认中灰灯);月主材业绩口径推荐=当月签单权责额(sales_order 创建口径),备选=出货口径(待 sales 域出货物料聚合);
3. **聚合表策略**:推荐按需建(V1 仅工程维一张新 ads 表,其余查询期直出/契约包装);备选北极星五张全建 ads 表(T+1 稳定时点,代价=五枚日刷 handler);
4. **刷新机制**:推荐 yz_plan 链式日刷每 addon 一枚(03:00,biz_key 按日幂等+启动对账);备选内核统一调度器(违反「内核不订阅业务」精神,不推荐);
5. **埋点 V1 含不含**:推荐含(单表+两 API 成本极低,「领导在看什么」唯一数据源);备选 V2 再做;
6. **按角色默认落地页**:推荐 V1 最小配置表(admin 可配,聚合首页缺省);备选 V2 矩阵化(角色×维度页全配)。

## 老系统回溯指针(实现者用)

- 老系统(只读):`D:\ERP\Rhadamanthus\src\com\fanglin\report\`——po/ 各域 metric 预计算表族(DecorationDailyMetricPO/ConstructionMonthlyMetricPO/CustomerMonthlyMetricPO/DecorationDesignerRankPO…)、job/ 批任务、echarts 渲染;口径散在 SQL 无元数据=本 PRD 修复的原始病灶;
- fl-yy(只读快照):`D:\Git\fl-yy\fl-yy-biz\...\cycx\controller\BattleBoard*Controller.java` 14 个(2026-10-30 逐名核对);Mapper 直读数仓形态见 `dal/mapper/beijing`;
- 口径参考:`docs/scripts/ads-reference/01_ads_wo_task_daily.sql`(工单日达成,头部口径/尾部校验格式范本)+ 02(渠道漏斗,含 partial=1 诚实标注先例);
- 营造侧现成资产:DecorationApi/MarketApi 聚合契约(第 73/74 次)、addon-target achievement、S1 org 树/OrgScopeService、yz_plan(V106)、AddonConventionPostProcessor 约定接入(第 64 次)、web 槽位/菜单下发(GET /api/addons)。
