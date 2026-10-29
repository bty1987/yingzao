# PRD - addon-target PK 对赌子域(绩效 PK 体系,精简版)

> 状态:**已实现**(2026-10-27 第 93 次起草;2026-10-30 第 102 次按推荐拍板落地——用户既有拍板「后续无特殊说明一律按推荐」延续,六项开放问题全按推荐兑现;冒烟 2456/2456+enforce 101/101 全绿)
> 老系统参考:**fl-yy 快照(D:\Git\fl-yy,2026-10-20)PerformancePk 域 12 Controller 全量考证**(本文回溯指针在档;fl-yy 域无 rules 文件,考证事实随 PRD);BattleBoard 域 14 Controller 考证作划界依据;docs/07 §2.2/2.3 为二级摘要
> 归属:推荐 **addon-target V2 子域**(开放问题 1)——PK=经营目标的对赌动作面,与 target_plan 同口径同契约
> 本子域声明:错误码 **370100–370150 小段**(target 370000 段内,370001–370010 已占用,沿 quotation 231200/sales 300100/balance 320100 子段先例);迁移 **V1703+**;表前缀 `target_pk_`(对齐 target_plan/target_stage,军规 3)

## D3 范围裁决(战报/PK 拆解,先说清本 PRD 覆盖什么)

战报与 PK 在 fl-yy 是两个域:**BattleBoard**(14 Controller/102 端点,仅 4 个有写路径,其余纯数仓直读看板)与 **PerformancePk**(12 Controller,完整对赌闭环)。D3 拆解:

- **本 PRD = PK 对赌体系全量**(配置→创建→锁档→开赛→归档→复盘)——fl-yy 13 张表收敛为 **3 张**;含 PK 内生看板(赛事全景/达成排行,读本域表,零数仓依赖);
- **划界到 D4 驾驶舱专题**:战报数仓看板族(五段漏斗/风险单/盘客报表/工作台/主材战报/渠道-设计分析/总经理 PK 看板)——依赖数仓分层与口径专题定稿(docs/06 §2.2 红线「多页面漏斗同表出数」),且五段漏斗(pending→valid→followed→precontracted→signed)在营造无数据模型对应(客源无有效甄别态、无到店/预约时间戳,见划界节);
- **划界到其他域**:盘客客户管理(在管/结束/恢复+跟进记录+支持待办,15 端点)→addon-customer V2 候选 PRD(归属客源域);目标激励计算→D1 激励专题;集团活动荣誉墙(读 PK 赛事的纯读复用)→随 member/market 活动域。

## 业务闭环(一句话)

管理者按周期(月/季)为**参赛单元(组织树节点)**发起一场 PK 赛:筹备期配单元模板(四档目标+PK 金),对赌期逐单元**锁档**(选档定目标、奖金=档位×PK 金),确认开赛后进行期**自动聚合实绩**、完成率全员可查,周期结束由**定时任务冻结最终业绩快照并归档**,复盘(全景/导出)。**奖惩金额只记录不发放**(发放归薪酬/激励域)。闭环起点=创建赛事;闭环终点=已归档(快照落库)或已作废。

## 谁与场景

| 角色 | 场景 | 高频动作 |
|---|---|---|
| 经营管理者 | 月度/季度发起部门间对赌 | 创建赛事、配单元模板、锁档、确认开赛、复盘导出 |
| 参赛单元负责人 | 看本单元目标与完成率、录手工实绩 | 查全景、手工实绩登记(启用单元) |
| 全员 | 关注赛况 | 看板查询(零写权限) |
| 系统 | 周期结束结算 | 定时任务生成快照+归档(yz_plan) |

## 核心模型(3 表,老 13 表收敛)

- **target_pk_event(赛事)**:org_id/cycle_type(**1 月/2 季**)/period(周期键:月=`yyyy-MM`、季=`yyyy-Qn`)/name/start_date/end_date/status(五态见下)/base_tier(缺省基准档)/tier_increment(超档递增系数,万分比;>5 档扩展)/void_reason(作废原因)/draft_data JSON(整包模板草稿,发布落行即清)/created_by/created_at/updated_at;**uk(org_id, cycle_type, period)**——同期同型唯一,天然防「月赛与季赛重合」之外的同期重赛;
- **target_pk_unit(参赛单元,模板+锁档合一)**:event_id/org_node(组织树任一节点)/staffs JSON(参赛人,展示用)/四档目标列 guarantee/base/breakthrough/challenge(厘,直配)/pk_money(厘,单档对赌金)/bonus_preset(自定义奖金,可空)/manual_enabled(手工实绩开关)/manual_performance(手工实绩,厘)/manual_remark/**selected_tier(2 保底/3 冲刺/4 突破/5 挑战,空=未锁)**/target_amount(锁定目标=所选档值或自定义)/bonus_amount(=pk_money×档位系数或自定义)/custom_target/custom_bonus(全自定义)/lock_by/lock_at;**uk(event_id, org_node)**——upsert 单行,取代老「模板全删重插+锁档另表 JOIN」;
- **target_pk_snapshot(归档快照,append-only)**:event_id/org_node/target_amount/final_amount/performance_source(**1 自动/2 手工**)/final_data JSON(全景明细定格)/snapshot_hash(SHA256,防篡改)/created_at;uk(event_id, org_node);
- 【不迁】dept_group 跨赛分组(开放问题 2:推荐「上一届复制」承担复用)/global_pusher 白名单/h5_access_stat 打点/attachment 附件/money_standard 金标准矩阵(开放问题 4)/template_draft 独立草稿表(收敛 event.draft_data 单列)/quarter_money_standard。

## 状态机(老 4 态+1 终态)

```
0 筹备(PREP) ──首次锁档──► 1 对赌中(BET) ──confirm 确认开赛──► 2 进行中(RUNNING) ──归档任务──► 3 已归档(ARCHIVED)
  │                          │                                                  (快照冻结,终态)
  └──────作废(4,终态)◄───────┘  (仅 0/1 可作废;2/3 不可)
```

- **0→1**:首次锁档自动翻态(推荐:显式动作触发;老「进入会议详情即翻态」的隐式副作用不迁,开放问题不设、实现期按推荐);
- **1→2**:confirm 确认开赛(人工;守卫≥1 单元已锁档 370106);
- **2→3**:归档=**yz_plan 定时任务**(周期扫 status=2 且 end_date<今天→逐单元生成快照→event 置 3;对齐 member-expire-clear Plan 内核化先例,biz_key 按日幂等,死亡死信可见可重试;fl-yy XXL-JOB 次月 2 日+「手工 archive 恒抛异常」语义保留=归档只走快照链);
- **4 已作废**:0/1 态可作废(留 void_reason)——修老「delete/reset 无状态守卫,RUNNING/ARCHIVED 赛事可物理删除」;营造不物理删,作废=终态留痕;
- 单元级动作:锁档仅 0/1 态(目标>0+奖金>0 守卫);解锁(清 selected_tier 族)仅 1 态;**重置(清全部锁档回 0)仅 0/1 态且模板保留**(配置是劳动成果,老「reset 物理删全部含模板」不迁);模板行删除仅 0 态;
- 手工实绩:2 态可录(manual_enabled 单元,source=2);**快照生成即冻结,归档后一律读快照**(老「归档后次月 2 号前宽限窗」收敛为快照前有效——快照时点=归档时点,零宽限歧义)。

## 实绩口径(契约复用+诚实边界,本 PRD 最重的一节)

- **单元自动实绩 = DecorationApi.contractSignedTotalByMonth/Between(org_node 子树聚合)**——与 target_plan 达成率**同契约同口径**(docs/06 §2.3 红线「两个页面数字打架」的工程解法;月赛用 ByMonth,季赛用 Between);渠道线对赌不进 V1(开放问题 6);
- 🔴 **诚实边界(继承 target V1)**:业务单据归属=公司级,**分区/部门节点自动实绩恒 0**;PK V1 双轨=自动聚合(公司级单元立即可用)+**手工实绩录入(全部单元可用,source 留痕)**——部门级自动聚合随单据归属模型落位**自然点亮零代码变更**;看板 note 双语明示(开放问题 3);
- **完成率=实绩/目标**;预测达成率**不迁**(fl-yy 依赖 BI 预测同步表,方法论归 D5 业绩预测专题);
- **奖金池/承诺额=查询时聚合**(Σ已锁单元 bonus_amount/target_amount),**不落列不回写**——修老「全景图 detail 读接口回写 event.commitment/bonusPool」的读写副作用;
- 判胜负:**纯人工**(系统只出完成率,不自动判胜者、不发奖)——照搬 fl-yy 正确姿势;复盘调 bonus=batch 追加挑战档奖金收敛为 unit.custom_bonus 编辑(仅归档前)。

## 老缺陷收敛表(重做即修复,不新开码)

| fl-yy 缺陷(第 93 次考证实证) | 营造处置 |
|---|---|
| delete/reset 无状态守卫(RUNNING/ARCHIVED 可物理删) | 状态机守卫+作废终态,零物理删 |
| abort 假保留(allowedActions 有 abort 无实现) | 不设 abort;0/1 作废真实现 |
| 草稿 save/get 无身份与状态校验,任意 eventId 可覆写 | 草稿挂 event 行内(org 守卫+仅 0 态可存) |
| H5 按 `LIKE '%staffCode%'` 匹配参赛人(FL-100 误命中 FL-1001) | 参赛单元=org_node 精确 id 匹配,零字符串模糊 |
| manual 与 BI 口径叠加靠调用方自律 | performance_source 单源+快照冻结 |
| 全景图读接口回写 event(读写副作用) | 奖金池/承诺额查询聚合 |
| calc-pk-money 死接口+金标准双口径并存(表+死矩阵) | 金标准矩阵不迁(开放问题 4),模板直填 |
| 季度重合联动硬编码赛事 ID(LINKED_SETTLEMENT_Q2_PK_ID=4/JULY=6)【方林嫌疑】 | 不迁;uk 同期唯一,归档期重叠仅列表提示 |
| orgId=222 推送硬编码/isysCode「沈阳中心论」/9 种部门类型+组织名关键词推断【方林嫌疑】 | 参赛单元=org 树节点自服务,零类型推断零公司判断(军规 1) |
| 模板全删重插 stage.id 不稳定+空更新刷 update_time | unit 行 upsert(uk),状态列天然留痕 |

## 错误码段(370100–370150,双语,登记 error.md 由实现轮落)

370101 赛事不存在 / 370102 赛事状态不可操作 / 370103 同期赛事已存在 / 370104 参赛单元非法(节点不存在或停用/重复) / 370105 模板非法(目标或 PK 金≤0) / 370106 尚无锁档单元不可开赛 / 370107 单元未锁档 / 370108 锁档仅筹备或对赌中可操作 / 370109 已归档不可变更(读快照) / 370110 手工实绩未启用 / 370111 作废须填原因 / 370112 快照已生成不可重置(实现期按需收窄)

## 划界(本期不做)

- **战报数仓看板族**(五段漏斗/风险单 15 类标签/工作台+支持任务/盘客报表+盘客客户/主材战报/渠道-设计配对分析/总经理 PK 看板)→**D4 驾驶舱专题**;其中五段漏斗前置缺口=addon-customer 数据模型(客源仅 0 登记/1 转化+机会 0/3/4,无有效甄别态、无到店/预约时间戳)——先补客源域再谈看板,不倒挂;
- **盘客客户管理**(在管/结束/恢复两态+跟进记录+支持待办)→addon-customer V2 候选 PRD;
- **目标激励计算**(BattleBoardTargetIncentive:纯 JVM 内存口径,无规则表无状态机)→D1 激励专题,不计欠账;
- **档位扩展**(>5 档递增公式)与 **legacy 档位公式**(保底=冲刺×0.9 十位取整等历史包袱)→四档直配置代;
- **预测达成率/预测表**→D5;**附件归档**(议程/纪要文档)→通用文件基建就绪后;**企微推送/GlobalPusher/H5 打点/每日 9 点进度推送**→通知中心收件人模型(未建,第 87 次实锤)+员工移动端(未开始)+C4 宿主凭证,数据面 V1=Web 赛事全景页;
- **金标准矩阵**(部门类型×周期→PK 金默认值)→开放问题 4。

## 实现期核对清单(拍板后写码逐条销)

1. 归档 Plan handler(member-expire-clear 同构:biz_key 按日幂等+死亡死信+重启对账);归档时点 V1=任务周期判 end_date,不做组织级日配置;
2. @Lazy 断环(总线↔消费者/契约注入,第 N 次惯例);
3. DecorationApi 契约零直查;快照 JSON+SHA256 落库(防口径后漂移,fl-yy 正确惯例保留);
4. 错误码 370100 段先 grep 台账再用;unit 四档列与锁档字段命名对齐齐 spec;
5. 冒烟专用数据隔离(独立组织+赛事,段首定向清理);自动聚合(公司级单断言)+手工实绩双轨断言;
6. Web 页=Targets.vue 同族新页(PkEvents.vue,管理页沿既有风格,**不触发 huashu-design**);若拍板加「战报大屏」独立视觉面,另走三方向初稿流程。

## 开放问题(待拍板,可整体「按推荐」)

1. **落点**:推荐 addon-target V2 子域(370100 小段/V1703+/target_pk_ 前缀)——PK 与 target_plan 同域同口径,docs/11 D3 前置「组织树已就绪」即指此;备选独立插件 addon-performance-pk;
2. **参赛单元复用**:推荐不建分组表,「从上一届复制赛事(含单元模板)」承担复用(fl-yy createMethod=copy 同款);备选保留跨赛周期分组配置表(组织级,月/季分组各一套);
3. **实绩归属双轨**:推荐自动聚合+手工实绩全员可用(source 留痕,看板诚实标注);备选仅公司级单元可赛(等单据归属模型,部门对赌整体顺延);
4. **金标准矩阵**:推荐不迁(单元模板直填 PK 金,管理者按单元议价);备选组织级「周期×单元档位」默认值表(录模板带出可改);
5. **进度推送**:推荐 V1 顺延(通知中心收件人模型未建);备选站内通知最小实现(收件人=event 全体参赛人);
6. **业绩线**:推荐 V1 仅签约线(contract,DecorationApi);备选+渠道线(MarketApi 进线数,个/条单位,对赌「获客量」)。

## 老系统回溯指针(实现者用)

- **PK 域**(fl-yy-biz cycx/):controller/PerformancePk{Event,Template,Meeting,H5,Config,DeptGroup,GlobalPusher,Attachment}Controller+Notification/MonthlyDashboard/Material×4 共 12 个;service/impl:EventServiceImpl(状态机 :44-47、allowedActions :475-485、archive 恒拒 :211)/MeetingServiceImpl(3509 行:锁档 :800-845、档位目标计算 :3022-3068、奖金池回写 :1226-1236、快照读 :1252-1269)/TemplateServiceImpl(草稿 :135)/FinalPerformanceSnapshotServiceImpl(归档 job :117-118)/NotificationServiceImpl(企微通道 :53-59, :398-536);
- 表:dal/entity/PerformancePk*.java 13 张(yy_performance_pk_event/_template/_template_draft/_template_push_staff/_meeting_record/_meeting_snapshot/_final_performance_snapshot/_dept_group/_global_pusher/_attachment/_h5_access_stat/_money_standard/_quarter_money_standard);
- **战报域划界考证**(2026-10-27):BattleBoard 14 Controller 逐端点在档(第 93 次运行报告);RiskConfig 死表(yy_battle_board_risk_config 零调用,规则实写用户 UI 偏好表)/Panke 普通·Leader 双 Controller 端点 100% 同构/TargetIncentive 纯内存无规则表等 quirk 见报告;
- **营造侧**:addon-target V1701/V1702(target_plan/target_stage)、TargetService.actualOf/actualOfRange(契约聚合 L307-329)、错误码 370001-370010、web/src/views/target/Targets.vue;kernel yz_plan(V106)。
