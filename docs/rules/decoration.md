# L2 规则清单 - 装修单域(addon-decoration)

> 来源:老系统 `D:\ERP\Rhadamanthus\src\com\fanglin\decoration\`(189 文件/27 ServiceImpl,老系统最大域)。
> 2026-10-12 夜间任务提取:主域(DecorationPO/DecorationServiceImpl/DecorationInfoServiceImpl)亲读 + 三组子域(合同审计/量房优惠/账户活动)全量读 service impl。
> 【方林嫌疑】= 涉及公司分叉/公司口径,沉淀插件前必须剔除或配置化。错误码段建议 **250000**,迁移段 **500+**(对齐码段:core 100/customer 200/customerprofile 300/quotation 400)。

## 0. 模块名词表(老系统命名混乱,先辨析)

| 类 | 表 | 实际含义 |
|---|---|---|
| DecorationPO | decoration | **装修单主单**(合同线+工程线+设计线三态并行,客户×楼盘唯一) |
| DecorationInfoPO | decorationInfo | 主单 1:1 补充信息(面积/房型/需求等) |
| DecorationAdditionPO | decorationAddition | ⚠️ 名不副实:**人员/时间快照表**(设计师/销售姓名部门冗余、保护期时间),非增项(增项在 quotation 域 Extension) |
| DecorationOperatePO | decorationOperate | 操作轨迹(15 种操作类型工厂方法,几乎空 service+富 PO) |
| DecorationUserPO | decorationUser | 服务团队成员(type: 0业务/1装饰/2服务/3合同/4销售/99其他) |
| DecorationBelongPO | decorationbelong | 归属(设计师/业务员两条线,千分比 rate,转归属/合尺) |
| DecorationMeasurePO | decorationmeasure | 量房/放线计划 |
| DecorationMidpackPO | decorationmidpack | 半包口径表(主材/服务费配比) |
| DecorationHolonomyPO | decorationholonomy | 整装满减口径表(折上折) |
| DecorationInclusivePO | decorationinclusive | 包干口径表(增项汇总) |
| DecorationActivityPO/Item | decorationActivity(Item) | 订单活动与活动项(券/物 11 态) |
| DecorationContractPO | decorationContract | 合同(电子签路由/签署/归档) |
| DecorationAuditPO + AuditRecord + AuditScale | — | 预算审核单/审核记录/审批比例(0→1→2→3 单向链) |
| DecorationAccountPO + AccountStatus | — | 决算账户 + **决算六项前置检查** |
| DecorationBalancePO | decorationbalance | 余额(⚠️ 零缩放透传,资损坑) |
| DecorationFunctionPO | decorationfunction | 装修功能字典(纯配置行,被 right 表按公司引用) |

## 1. 主单四条状态线(DecorationPO.java:51-113)

1. **合同线 status**(主状态机):
   `0初始 → 1定金 → 2预签 → 3签约 → 4重签 → 7决算 → 8完工`;终态支线 `5废单 / 6终止`;
   过渡态跳号:`20待审中 / 30可终止 / 32预签可终止 / 33签约可终止`(跳号区分"审批中/可终止"两阶段)。
2. **工程线 projectStatus**(long):`0初始 → 1开工 → 2完工`,由量房成功联动置 1(DecorationMeasureServiceImpl:244-248)。
3. **设计线 designStatus**:`0待设计 → 1设计中 → 2已完成`;签约联动 0→1(DecorationContractServiceImpl:612-615),合同方案客户确认置 2。
4. **确认线 sourceStatus**:`0待确认 → 1合同确认 → 2放线确认`;**变体值 11=微改 / 21=大改**(%10==1 表示"合同确认"位,DecorationInfoServiceImpl:219)。禁止跳步:0 不可直跳 2(DecorationInfoServiceImpl:204);不可倒退(sourceStatus >= 目标即拒,DecorationInfoServiceImpl:201)。

> 正交性:合同线管商务,工程线管施工,设计线+确认线管方案交付。四线独立推进、互不覆盖——这是装修单区别于普通订单单状态机的核心模型。

**其他位**:balanceStatus(0结算/1结算中)、otherStatus(0显示/3隐藏,hiddenDecoration 切换)、repairStatus(返修,无枚举常量)。
**单类型 type**:`0半包 MIDPACK / 1包干 INCLUSIVE / 2整装 HOLONOMY`(DecorationPO.java:101-105),决定走哪张口径表与销售比例口径。

## 2. 单号与创建联动(DecorationServiceImpl:984-1158 makeDecoration)

- **单号 = 公司短名 + yyMMdd + 4 位日序号**(counting 表按 type+date+company 计数,DecorationServiceImpl:1002-1013)。【方林嫌疑】单号带公司前缀 → 营造改为可配前缀 + 计数器,公司无关。
- 创建时**一键七从表**:decoration_info(1:1)、belong(设计师+销售两条)、addition(人员姓名/部门**快照**冗余)、advance_payment(预付款账户)、operate(轨迹)、service(服务节点)、audit(预算审核单)。
- **部门伙伴自动带人**:设计师部门的 TYPE_DECO 伙伴自动成为 escorter(监理);TYPE_SALES 伙伴与楼盘所在城区分区 TYPE_SALES 伙伴自动写入服务团队(DecorationServiceImpl:1063-1110)。【方林嫌疑】部门/分区绑定合伙人是公司组织模型 → 插件化,内核不做。
- 报价版本按 (company, type) 取默认,缺省退化通用类型,再无则 200022 拒绝。
- 事件:MessageQueuing 落 `DECORATION_CREATED`(JSON 事件表,与客源 CUSTOMER_STATUS_CHANGED 同机制)→ 营造映射 = 领域事件 `DecorationCreatedEvent`。

## 3. 状态流转门槛(主 ServiceImpl + ContractServiceImpl)

| 动作 | 前置(全绿才可流转) | 目标态 | 证据 |
|---|---|---|---|
| 预签 | status∈{0,1,2} + 活动存在且全部锁定 + 预单存在且 Protation 锁定(值=2,注意与报价锁定值=1 不同) | 2 | ContractServiceImpl:744-818 |
| 签约 | status∈{0,1,2,4} + **存在已付/已确认合同款** + 无未处理审批 + 默认报价存在 + 工程信息+预计放线时间必填 | 3(联动报价锁定/计奖规则/归属刷新/账户状态/事件 CUSTOMER_SIGNED) | ContractServiceImpl:507-685 |
| 重签 | 仅 status=3 + 无未处理审批 + **无增减项** | 4(recontractTime,不覆盖 contractTime) | ContractServiceImpl:689-740 |
| 废单 | 排除法:已废/已签约/已完工/已终止/已重签不可废(140021-140026) | 5 | DecorationServiceImpl:1468-1512 |
| 终止 | 仅 status∈{32,33}(先经审批进"可终止"过渡态);联动:未付款项置 UNPAY、已付 Payment 置 PAID、报价留痕 Ruotation 置 EFFECT、归属置失效 | 6 | DecorationServiceImpl:1514-1656 |
| 决算 | 仅 status=3 + 无未处理审批 + 六项前置检查(见 §8) | 7(accountTime) | DecorationAccountServiceImpl:182-216 |
| 完工 | 工程线联动(决算后) | 8 | — |

⚠️ **changeDecorationForStatus 是裸写**(任意 status 直改,DecorationServiceImpl:1406-1427)——状态机校验散在 action/业务方法,内核不拦。营造必须收敛为**显式状态机**(合法迁移表白名单),这是重写核心收益之一。

## 4. 金额与比例口径(红线,老系统多制式并存)

1. **金额 = long 整数存储,主流缩放 1e6(百万分位=1 元)**:couponCut/thingCut 等 DAO 聚合后 ÷1e6(DecorationActivityItemDaoImpl:60,104);中期/满减配比计算 `round(base × rate ÷ 1e6) × 1e6`(DecorationMidpackServiceImpl:134-147,DecorationHolonomyServiceImpl:152-177)。
2. ⚠️ **÷1000 与 %.3f 同时存在**(服务记录日志"主材配额 %.3f",DecorationMidpackServiceImpl:179)——**同一字段两种缩放并存,必有一处错**,老系统以"显示对不对"为准修补。营造已定案:**统一厘(×1000 定点整数)存储**(第 21 次交付兑现),插件域内单一口径。【✅第35次核对回写:原“元 decimal(12,2)”已被全局厘制推翻】【方林嫌疑·待业务确认】
3. **比例四制式并存**:主材/服务费配比 = ppm(百万分比);折扣 discount = 万分比(10000=不打折);归属 rate = 千分比(1000=独占);payScale/saleScale/操作轨迹 rate = 百分比×100。
4. **魔法值 1918**:saleScale 默认"1918"=19.18%,半包/包干单直接置 1918(DecorationMidpackServiceImpl:150 等 3 处)——**导致销售收款比例检查(≥99)形同虚设**。营造:比例默认值必须显式配置,禁止魔法数。【方林嫌疑·待业务确认 1918 的业务含义】
5. 满减折上折算法:基数取整余数不打折 `折后 = round(((总价−余数)×折扣÷10000+余数) ÷1e6)×1e6`(DecorationHolonomyServiceImpl:154-157);管理费可选跟随折扣(manageDiscount=1)。
6. 包干增项汇总:加项×1000、减项×−1000 入库;主材增项是否计入总价按公司配置 `QUOTATION_EXTENSION_MET_FLAG`:`UPMET`(仅正计)/`DOWNMET`(仅负计)/`COMMET`(无条件)三分支(DecorationInclusiveServiceImpl:185-197)。【方林嫌疑·核心口径分叉】
7. 余额 DecorationBalance.balance:**全链路零缩放透传**,存储单位由前端约定(DecorationBalanceServiceImpl:110,122)——资损级坑,重写禁止照抄。
8. 工期 timelimit = 百万分之一天(÷1e6 得天数,DecorationMeasureServiceImpl:228-237);designPeriod 千分位存储(天×1000,DecorationInfoServiceImpl:255-279)。

## 5. 三口径表统一门禁(Midpack/Holonomy/Inclusive)

三张口径表的 make 前置链**完全一致**,应抽象为一个门禁:
1. 主单 status ∈ {0,1,2,4}(签约/决算/完工后锁死);
2. 默认报价存在且**锁定**(Quotation STATUS_LOCK=1);
3. 订单活动存在且全部锁定(无 STATUS_INIT=0);
4. 已有口径记录的重算前置:Holonomy 要求 status=0 且 servicePaidup=0(有已付服务费即冻结,DecorationHolonomyServiceImpl:142-144);Midpack 仅允许改 materialPrice。
5. 口径落库即生成装修款计划 makeDecorationPayment。

## 6. 量房/放线(DecorationMeasureServiceImpl)

状态机:`0排队 → 1已申请 → 2成功 / 3失败`(失败可重申,直接置 1)。
- **申请放线查重:同公司内时间重叠拒绝**(120015,countDecorationMeasure(start,end,company),:301)。【方林嫌疑:按公司隔离 → 营造按组织+团队日历】
- 成功前置 status=1(120017);按施工节点生成进度表(startTime=放线时间+Σtimelimit÷1e6 天);**联动主单工程线 0→1(开工)**。
- 客户确认(量房信息):0→1→2 不可跳步、不可倒退(与确认线同款规则,130-186)。
- 已知 bug:242 行误用 constructionScheduleDao 更新量房 PO——重写勿照抄。

## 7. 归属与保护期(DecorationBelongServiceImpl)

- 状态:`0未生效(新建) → 1有效`;`2待确认副本 / 3已审批`;rate 千分比(1000 独占/500 合尺各半)。
- **转归属 tran**:主单 status∈{2,3,4,8} 拒绝(140004),即仅初始/定金/废单/终止/决算可转;旧记录 0→1,新记录 rate=1000 status=0,同步主单 designer/salesman 与 addition 快照,事件 TEAM_DESIGNER_CHANGED/TEAM_SALESMAN_CHANGED。
- **合尺(一人两单分佣)**:直生效版把原 1000→500+新建 500;客户确认版生成 status=2 副本待客户短信/微信确认后转正(confirmDecorationBelong,验证码+手机号解密比对)。
- 保护期字段在 DecorationAddition(designProtectTime/salesProtectTime/belongTime)——保护期判定散落,与客源域保护期(customer.md §7)同思想:转移时清快照、记时间。
- 客户确认归属:同款微信 phoneCode 换手机号+电话解密比对(130115)【方林嫌疑:按公司路由微信凭证】。

## 8. 决算六项前置检查(DecorationAccountStatusServiceImpl)——多公司分叉重灾区

总状态 = 六子项合成:任一 FAIL→FAIL;否则任一 PASS→PASS;否则 UNCHECK(未启用)。子项:0签约(status∈{3,7})/1主材销售(未完销售单=0)/2施工(结算节点全部完工)/3选材(未完成项=0)/4装修收款(payScale≥target)/5销售收款(saleScale≥target)。
- 【方林嫌疑】9 个系统配置 key 全部按 company 取值、查不到回退 company=0 全局(:31-47,325-334):CHECK_ENABLE 总闸+五分项开关+两个收款 target(默认 99)。营造:检查项与阈值=组织级配置(插件 config 表),算法进插件。
- "未处理款项"校验被注释掉(:206-208)——允许带未收款发起决算,**是否有意放宽待业务确认**。
- 【营造实现(B 类#6 回补,2026-10-13)】组织级配置表 decoration_config(九列:总闸+六子项开关+两 target ‰,懒初始化默认全关/target 990);
  子项实时计算不落台账表(老 decorationaccountstatus+事件刷新整层消掉);选材子项预留(选材域插件交付前恒 UNCHECK);
  总闸关=保持「款项全收齐」硬闸(250016);总闸开=六项合成,任一 FAIL 拒 250023,「装修收款比例」子项接管收款闸
  (target 可配 990=99%,1000=全收齐——老"注释放宽"的行业口径[尾款决算后收]由此可配,§13-5 随之定案)。
  施工子项取数经新契约 ConstructionApi.accountNodes(节点模板 account 标志 V603+排期快照冗余);
  销售子项经 SalesApi.countUnfinishedOrders(未完=status not in {2,4,5},老 countSalesForUnfinish 同口径)。

## 9. 合同与电子签(DecorationContractServiceImpl)

- 合同状态:`0初始 → 1已签 → 3归档`;支线 `2作废 / 5未生成`;type 路由矩阵(签章/签订/查看/下载 × status)决定跳转。
- ⚠️ noticeStatus 常量注释与实际行为**语义相反**(PO:56-58 说 0=已确认,代码置 1 当"已确认"用,ContractServiceImpl:433-434)——重写以业务口径复核。
- 电子签厂商路由:公司×场景 → 公司级 → 全局(company=0)→ 兜底法大大;厂商缺失 fail-fast(130097)。【方林嫌疑:整套按公司路由 → 插件 provider 策略,凭证组织级】
- 合同表无 company 列,存量靠全局凭证( ElecsignRouter COMPANY_GLOBAL=0)。

## 10. 审核线(Audit/AuditRecord/AuditScale)

- 预算审核单:一单一审,先到先得抢占认领(140048),无角色校验(在 action 层)。
- 审核记录:0初始→1已生成比例;更新前置 status=0 且无比例记录(140045)。
- 审批比例:**严格单向链 0初始→1确认→2冲账→3回冲**,其余一律 140047;删除仅 0/1;⚠️ 140044/45/46/47 四码在三个方法间语义混用——重写统一。
- 金额 long 无缩放注释。

## 11. 活动与活动项(Activity/ActivityItem)

- 活动:`0初始 ⇄ 1锁定`(可逆);**一单一活动**(count 查重 116-118);绑定后把订单定金付款记录 aid 指向活动。
- 活动项 11 态:0初始/1失效/2已领/3退货/4配送/5未退/6升级/7消耗/8抵现/9采购/10核销;券(0)/物(1);线上(1)/线下(0)。
- 关键规则:领取/配送扣奖池库存(负数拒 160038);退货回加;**核销防重(一单一次,state=10 已存在拒 140094)**;线上可 0/2 起步核销,线下仅 0;失效前提主单∈{0,1,2,4}。
- 优惠联动:**每次状态变化全量重算 couponCut/thingCut/factPrice=总价−券−物** 落主单(subDecorationForCut);计入口径:type+isPayment=0+(线下指定态 或 线上已核销)。
- 金额:SUM(money×num) long 直算无缩放;与主单 1e6 口径的关系靠 DAO ÷1e6 弥合——老系统口径漂移实锤。

## 12. 进度里程碑 schedule 数值表(DecorationAdditionServiceImpl:92-162)

0 订单生成 → 1 预算(默认报价存在)→ 2 合同签单(status=3)→ 3 签单设计方案完成(sourceStatus=1)→ 4 开工设计方案完成(sourceStatus=2)→ 施工节点 sort+2(sort>2)→ 115 决算(status=7)→ 116 工程完工(projectStatus=2)。
> 跳号 115/116 与合同线 20/30/32/33 同款"预留段"思路。营造映射:里程碑表配置化(节点 key+顺序),数值不硬编码。

## 13. 查询视角与功能字典(查询条件配置化思想)

- pageDecorationFor* 十余个视角(设计师/销售/报价员/监理/主材员/归属/公司/客户/通用…),可见状态集由 **DecorationStatusRightPO** 配置表决定(部门/职务→状态列表,-1=全部,right/po/DecorationStatusRightPO.java:17)——"查询条件配置化"实例,思想保留、实现重做:营造 = RBAC 数据权限(可见状态集/数据范围)统一模型,禁止为每个角色写一个分页方法。
- 功能字典 decorationfunction(纯配置行 key/value/type/subtype/sort)被 decorationfunctionright 按 company 引用决定页面渲染项(right/po/DecorationFunctionRightPO.java:28)。【方林嫌疑:按公司授权 → 营造=插件槽位(mounts)+组织级开关】字典 CRUD:name 查重(140105)/更新排除自身/被引用不可删(140106)。

## 14. 已知缺陷清单(重写勿照抄)

1. changeDecorationForStatus 裸写无状态机校验(§3);
2. 金额 1e6 与 ÷1000 双缩放并存;余额零缩放透传(§4);
3. saleScale=1918 魔法默认值使销售收款检查失效(§4.4);
4. 量房 success 误用 DAO 更新(DecorationMeasureServiceImpl:242);
5. InclusivePO 列常量 "decoration.no" 疑似复制粘贴 bug(应为 decoration.company);
6. 合同 noticeStatus 注释与行为相反(§9);
7. 审核错误码 140044-47 语义混用(§10);
8. 轨迹 prosign 工厂方法 value 恒 0(DecorationOperatePO:316-328);
9. DecorationServicePO/DO 同表双映射、AuditScale 常量 _SHORT 实为 int——命名漂移;
10. 一单一活动/一单一次核销/图片单选互斥三个唯一约束全靠应用层 count,并发穿透;
11. updateDecorationAccount 全量 BeanUtil.copyProperties 覆盖资金字段,无校验;
12. checkOverDecoration 查重带公司白名单(量房规则跨公司共享,DecorationServiceImpl:1819-1843)【方林嫌疑】。

## 15. 方林嫌疑汇总(公司分叉机制级)

| # | 机制 | 代表证据 |
|---|---|---|
| 1 | 主单三公司字段 company/conpany/bompany(conpany/bompany 为 AES 加密公司号,decode 进库;ptv 转换时被排除) | DecorationPO.java:221-225,722 |
| 2 | 几乎所有 service 方法签名带 `int company`,分页/消息/查重按公司隔离 | 各 ServiceImpl |
| 3 | 决算检查 9 配置 key 公司级+回退全局 | AccountStatusServiceImpl:31-47 |
| 4 | 包干主材增项三分支 UPMET/DOWNMET/COMMET | InclusiveServiceImpl:185-197 |
| 5 | 电子签厂商按公司路由+全局兜底 | ElecsignRouter:108-132 |
| 6 | 部门/分区"伙伴"自动带监理与销售 | DecorationServiceImpl:1063-1110 |
| 7 | 功能字典按公司授权渲染 | DecorationFunctionRightPO:28 |
| 8 | 图片上传上限按公司配置 `decorationpicturelimit{type}` | DecorationPictureServiceImpl:74 |
| 9 | 微信手机号解密按公司路由凭证 | AccountServiceImpl:263 等 |
| 10 | 单号带公司短名前缀;计奖规则按公司初始化 | §2/ContractServiceImpl:570-590 |

## 16. 待业务方确认清单(§ 编号对照正文)

1. **金额终局口径**:1e6 还是元(decimal)?÷1000 日志是否说明存在第二套真实口径?(§4.1-2)
2. **1918 魔法值**的业务含义(19.18%?某年月份?),新系统默认销售比例取什么?(§4.4)
3. **包干主材增项三分支**(UPMET/DOWNMET/COMMET)是否保留为组织级配置?(§4.6)
4. **裸状态机收敛**:过渡态 20/30/32/33 是否保留跳号语义,还是改显式审批子状态?(§1/§3)
5. 【已定案 2026-10-13】**决算六项检查**开关与阈值=组织级配置(decoration_config),默认全关(target 990‰=老 99%),推荐配置挂「待用户决策清单」;允许带未收款决算=总闸开+decoPayment target<1000 时可行(老注释放宽的可配置化),总闸关保持全收齐硬闸(保守默认)。(§8)
6. **合尺/保护期**:客户确认两阶段是否保留(体验重但防飞单)?保护期时长规则?(§7)
7. **归属千分比**是否保留"按单分佣比例"模型,还是简化为一单一归属?(§7)
8. **里程碑数值表**(0-4/节点+2/115/116)迁移为配置,还是前端自算?(§12)
9. **电子签**:V1 是否需要合同域?若需要,厂商路由收敛为单 provider 还是策略插件?(§9)
10. **活动/券核销**域是否划入 decoration 还是独立营销 addon?(§11)【✅已裁决 2026-10-13 member.md】**线下券/物品(atttype=0)留 addon-decoration**——它是装修单价格模型组成部分(couponCut/thingCut/factPrice 联动重算),剥离将切断 factPrice 闭环;**积分商城线上兑换(atttype=1)归 addon-member**(兑换单替代"外部商城同步落 item"旧通道);核销交互走事件(签约自动核销=member 监听装修签约事件,配置化 content,修老硬编码字面量),见 docs/prd/addon-member.md;

## 17. 营造 addon-decoration 落地映射(草案,详见 docs/prd/addon-decoration.md)

| 老系统 | 营造 V1 |
|---|---|
| DecorationPO 四线状态 | decoration_order 表:status/project_status/design_status/source_status 四列 + **显式迁移表**(代码白名单) |
| 七从表一键创建 | order 主表 + order_profile(1:1) + order_team(人员) + 归属表;快照列下沉 |
| 单号 counting 表 | order_no 生成器(可配前缀+日期+序号,唯一索引兜底) |
| 三口径表 | addon-decoration 内 {addonId}_order_caliber 系列(半包/整装 V1 只做主档,口径算法进 V2) |
| MessageQueuing JSON 事件表 | 领域事件(DecorationCreatedEvent/DecorationSignedEvent…入 addons-api)+ 内核通知中心消费 |
| DecorationStatusRight 配置表 | RBAC 数据权限(可见状态集),core 统一模型 |
| 站内信 MessagePO | 内核 yz_notification(NotificationService,已落地 2026-10-12) |
