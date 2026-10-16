# L2 规则清单 - 报价域(addon-quotation)

> 提取自:`D:\ERP\Rhadamanthus\src\com\fanglin\quotation\`(QuotationServiceImpl / AddReduceServiceImpl / NormtionServiceImpl / QuotationAssignServiceImpl 及 po/ 系)
> 提取日期:2026-10-11(自动任务·夜间推进)
> 🔴 每条规则标注【方林嫌疑】:行业共性 vs 方林实现习惯,需业务方确认后决定去留
> 老系统报价错误码段 = 2000xx(200001-200115+),营造 addon-quotation 声明 **230000** 段,迁移 **400+**

## 0. 模块名词表(老系统命名混乱,先辨析)

| 老概念 | 实体 | 语义 |
|---|---|---|
| Quotation | quotation | **报价单主实体**(现行,2014+),挂在装修单(orderId)下,一单多份(上限可配,默认 3),一份默认报价(defaults) |
| Quotetion | quotetion | 旧代报价(no 编号 + content 快照),`cloneQuotation` 从旧代克隆——历史包袱,营造**不迁**,只保留"克隆产生新版本"思想 |
| Protation | protation | **整装产品报价**(2021+):产品维度报价(quotePrice×quoteArea),带 manageFormula/taxFormula **公式字符串**、利润率、活动价 |
| Ruotation | ruotation | 报价版本留痕(装修单+报价+版本,终止审批线) |
| QuotationPrice | quotation_price | **价格快照时间线**(按 quotationItem+时间查最后价),保价重算的基准 |
| QuotationFunctionArea/Item | 功能区/功能项 | 报价明细的二维结构:功能区(分区域)→功能项(项目),见 §4 |
| Normtion/Normood/Normrice/Normtime | 定额单/材料/人工/工时 | 定额库体系,见 §5 |
| AddReduce | add_reduce | 增减项(合同后变更),见 §6 |
| Extension | extension | 拓展项(报价外增减,type add/sub) |
| Selection | selection | 选材单(报价锁定前置校验来源),Item 类型含报价项/拓展增减/增项+**下拨(13)/特批(23)变体**【方林嫌疑:下拨/特批是公司管理玩法】 |
| Qeasure / Remeasure | 测量/复测 | 量房数据(功能区分项),归 addon-quotation 的测量子域 |
| QuotationAssign 系 | 报价分配 | 预算员派单(auto/manual/reassign/transfer),见 §8 |

【方林嫌疑·命名】Quotation/Quotetion/Protation/Ruotation 四兄弟是 8 年演化的化石层——营造只保留一条报价主线 + 产品报价(Protation 思想)扩展点。

## 1. 报价单状态机(status,单状态位)

| status | 含义 | 流转 |
|---|---|---|
| 0 | 初始(编辑中) | addQuotation 创建 |
| 1 | 锁定 | lockQuotation;解锁回 0(unlock,仅 status=1 可解) |

- **编辑门槛:仅 status=0 可操作**(checkQuotationForStatusByEnabled,锁定后一切编辑/改价/切默认被拒 200030"报价不可操作");
- 锁定前置校验:存在未关联选材项 → 拒 200041;
- lockTime 只记首次(幂等);unlock 是老系统审计留痕点(ServiceRecord);
- updateQuotationForStatus 是**裸状态位写入口**(页面直传 status,无流转校验)——已知坏味道,营造不迁。

【方林嫌疑】"锁定"模型行业共性(报价定稿);但解锁权限、锁定与审核的关系各家不同。

## 2. 审核线(attstatus,与 status 正交的第二状态位)

| attstatus | 含义 |
|---|---|
| 1 | 已提交(submitQuotation) |
| 2 | 已审核(auditQuotation) |
| 3 | 已完成(finishQuotation,记 finishTime) |

- submit → 通知报价创建人的部门经理 + 装修单设计师(站内信);audit → 通知设计师 + **钉钉推送错误点**给创建人 + 写 QuotationRecord(TYPE_SPECIAL);finish → 通知设计师;
- workTime/auditTime 手工记录工时(预算员工时统计)。

【方林嫌疑】①通知对象(部门经理/设计师)与钉钉直连是方林组织玩法 → 营造一律领域事件(QuotationAuditedEvent 等)+通知中心订阅;②审核"错误点反馈"内容字段是方林预算质检玩法,行业可借鉴,V2 再议;③两级状态位(status×attstatus)正交设计**保留**——定稿线与审核线分离是好设计。

## 3. 金额口径(最高优先级红线)

1. **缩放**:老系统金额 long 存储,**1e6 缩放**(百万分之一元)——增减单提交生成款项时 `div(total,1000000)×1000000` 按 1e6 精度归一,负数带符号向零取整(正=补款 ONETYPE_ADD,负=退款 ONETYPE_REFUND);营造已定案(2026-10-12 全局金额红线+第 21 次交付):**统一厘(×1000 定点整数)**,禁 1e6;【✅第35次核对回写:原推荐“分或元”已被全局定案推翻】
2. **折扣**:discount 万分比系数,**10000=不打折**(addQuotation 初始 setDiscount(10000)),如 9500=0.95;
3. **费率三元组**:管理费/搬运费/税各带 rate(整数)+discount(short 折上折),创建报价时从**报价版本**(QuotationVersion)带出默认值,可单报价覆盖;
4. **税的可选性**:版本 taxDiscount=0(NOTTAXDISCOUNT)→ 不生成税项(见 §4 自动生成);
5. **总价公式(由明细聚合,价格快照支撑)**:undisPrice(未折)/disPrice(折后)=Σ功能项;totalPrice=disPrice+管理+搬运+税;成本线 costPrice/consPrice/comsPrice(预算成本/施工成本/公司成本)与利润线 unachePrice 并存——**成本/利润字段是方林经营分析口径【方林嫌疑】,营造 V1 只做对客报价线,成本线归报表插件**;
6. 默认报价变更(切 defaults)同步回写装修单 decoration.quotationPrice/discount——跨域写,营造改事件(QuotationDefaultChangedEvent)或查询期汇总,V1 建议**查询期汇总**(避免双写)。

## 4. 报价明细模型(功能区→功能项)

- 功能区类型:0 管理 / 1 工程量 / 2 功能区总价 / 3 不计算 / 7 综合;
- 功能项类型:0 常规 / 1 自定义 / 2 搬运费 / 3 管理费 / 4 忽略 / 5 成本 / 6 未完成 / 8 税 / 9 综合;subtype 0 工程project/1 材料material;
- **创建报价自动生成**:"生成项目"功能区(TYPE_MANAGEMENT,排序 9999)+ 搬运/管理两个自动项(+税项,若版本启税);"综合项目"功能区由公司配置 QUOTATION_CMPREH_CONFIG 开关控制【方林嫌疑:公司开关→组织级配置】;
- **🔴 分表化石**:功能区项实体按 `quotationFunctionAreaIndex`(systemConfig)选 QuotationFunctionAreaItemPO0–4(五张物理表,factoryToClazz 动态选类)——大数据量分表思想的散装实现。营造 **v1 单表+org_id 索引**,分表归 V3 性能专项;
- 价格快照:每个功能项可关联报价项(quotationItem),价格历史进 quotation_price,**保价重算 = 按时间点取最后快照回填**(updateQuotationForPrice);
- 复制报价(clone/replica/copy)带 **KEEPRICE 位掩码**:0 取最新 / 1 保时间 / 2 保价格 / 3 全保;跨代克隆(Quotetion)是历史包袱;
- 报价数量上限:systemConfig `quotationlimit`(按 company,默认 3)【方林嫌疑:公司配置→营造组织级 addon 配置】;
- 版本联动:新报价取"公司+装修单类型"的默认版本;整装单在 `PROTATION_KEEPRICE_DAYS_INT`(默认 365 天)内沿用整装产品版本,超期用预售产品版本【方林嫌疑:365/版本选择规则是公司商务政策】;
- **保价窗口**:KEEPRICE_DAYS_INT(默认 365)内,报价 updateTime 回拨到装修单合同时间——合同价锁定语义【方林嫌疑:行业常见"合同期内不涨价",天数可配置化保留】。

## 5. 定额体系(Normtion,材料/人工/工时)

- 定额单按报价(qid)建,创建报价时自动 addNormtion;
- **生成 = 全量重算**:先禁用(disable)该报价全部定额项,再从三来源重建——①默认报价 ②该单全部拓展项 ③该单全部增减单(三来源 × 三类型 = 9 组展开);装修单已完工(PROJECTSTATUS_FINISH)→ 清掉禁用项、不再生成;
- 定额项来源类型:0 报价(OFFER)/ 1 拓展(EXPAND)/ 2 增减(ADDCUT);人工定额带 normtype(0 工作费/1 追加)与收/付向(ATTTYPE 0 收取/1 支付);
- 人工定额(NormriceItem)四态:0 初始 / 1 禁用 / 2 确认 / 3 驳回(确认线是人工/工费核算的前置)——材料/工时无此线;
- 打印计数:moodPrintNum/mricePriceNum(NormtionPO)【方林嫌疑:打印管控】;
- 【方林嫌疑】"定额"概念行业共性(成本核算底座),但确认/驳回线挂工费工资(联动 addon-salary)是公司玩法 → 营造保留定额展开+全量重算,**确认线延后到 addon-salary 需求时再议**。

## 6. 增减项(AddReduce,合同后变更)

状态机:0 未提交 → 1 已提交(生成款项,回写 dpId)→ 2 已付款 → 3 已确认;配套位:isAffirm(工费确认 0/1)、isSalary(工费标识 0/1,标识后可驳回 200065);

- type:0 增项 / 1 减项 / 2 漏项;paytype:0 补款 / 1 公司承担(COM)/ 2 ARO / 3 退款(语义待考,提交线仅 paytype=0 可操作 200062)【方林嫌疑:COM/ARO 是方林财务口径】;
- 明细行:addPrice 增补价 / unitPrice 原单价 / subPrice 退减价 + initnum/num(原量/现量)+ 关联原报价项(qiid)——增减=对原报价行的差量修正,类型六分(0 报价常规/1 系统/2 自定义/3 报价项/4 增项/5 拓展项);
- 提交 = 批量(同一装修单)聚合生成**一笔**款项(正补负退,1e6 归一,见 §3.1),未付可整体撤销(cancel);
- 切换默认报价被拒的条件:功能区存在拓展项/增减项(200028/200032)——**变更链与报价版本强绑定**;
- 【方林嫌疑】漏项(LEAKAGE)单列类型 + 漏项追责玩法是方林管理特色,行业少见于系统化 → v1 保留 type 枚举位但漏项流程不做。
- 【营造映射定案 2026-10-16】增减线 V2 详细设计见 docs/prd/addon-quotation-addreduce.md:差量单(0 草稿/1 已提交/2 已付款/3 已确认,草稿可删、已提交未付款可撤)+明细行挂 ref_qiid+金额全厘服务端算+提交单事务聚合经 PaymentApi.createBill(typeCode=ar_diff)+PaymentConfirmedEvent 回程置已付+财务 confirm 终态;paytype COM/ARO 不迁(方林财务口径);重签前置「无未确认差量单」与决算金额「折后合计+Σ已确认差量」双向联动。

## 7. 测量/复测(Qeasure/Remeasure)与拓展项(Extension)

- Qeasure 量房:STATUS_INIT 单态,功能区分项(option/item);Remeasure 复测无状态位(仅明细)——测量子域**薄**,营造并入报价 addon 的"量房"页签或归 addon-building 楼盘档案,待定;
- Extension 拓展项:0 初始 / 1 锁定;type 0 增 / 1 减;atttype 0 常规 / 1 会碰(MET)/ 3 报价 / 4 活动【方林嫌疑:MET/活动类型】;管理费折扣策略位(NONE/MANAGE)。

## 8. 报价分配(QuotationAssign,预算员派单)——与客源分配同构

- **队列模型**:分配请求(Request,PENDING)入队与消费拆成两个独立事务/接口;
- 前置校验链:装修单存在(140013)→ 已有报价员则拒(200097)→ config(company+department)启用且 autoAssign=auto(200101/200113)→ 候选池非空(200114);
- **候选规则**:UserRuleItem 按 company+department+装修类型匹配,**跨公司排除**(在其他公司有非待分配单的预算员),按 **lastFinishTime ASC**(最闲优先)取第一个;
- 分配类型:1 正向自动 / 2 手动 / 3 逆向自动 / 4 转交;Record 记成败 + 失败原因("暂无可用预算员");
- 逆向自动/转交/洽谈(Talk)子流程与客源分配的 region/dept/变体同构【方林嫌疑:整条分配玩法是公司产能管理,行业有类似但规则私有】;
- 营造做法:**复用 addon-customer V2 的策略接口模式**(mode→strategy bean),候选池规则(engine/pool)归 V2 规则模块,通知走事件。

## 9. 字典枚举(源头)

- 功能区/功能项类型(§4);装修风格 style(报价单维度);报价版本(公司+装修单类型维度,启用/停用,默认标志);
- 单位 measurementUnit、工种 workType(功能项维度,ds 字典);
- 【营造】全部走配置化字典(dicts.yml + 库驱动),版本管理 = addon-quotation 配置域,禁 systemConfig 散落。

## 10. 营造 addon-quotation 落地映射建议

| 老概念 | 营造归属 | 状态 |
|---|---|---|
| 报价单 status 锁定线 + attstatus 审核线(两态位正交) | addon-quotation 核心 | V1 |
| 功能区/功能项明细 + 自动生成项目 | 单表(v1 不分表) | V1 |
| 报价版本(费率默认值/启税) | addon-quotation 配置域 | V1 |
| 报价数量上限/保价天数/综合项开关 | addon.yml 配置项(组织级) | V1 |
| 价格快照时间线 + 保价重算 | quotation_price 表 | V2 |
| 金额口径 | **厘(×1000 定点整数,全局定案),禁 1e6/万分比黑盒**,系数字段语义文档化 | V1 红线(✅第35次核对回写) |
| 定额三来源全量重算 | addon-quotation 定额子域 | V2 |
| 增减项(→款项生成) | addon-quotation,发 QuotationAddReduceSubmittedEvent,款项归 addon-payment | V2 |
| 拓展项/选材/测量复测 | V2/V3(选材联动 addon-sales/材料) | V3 |
| 整装产品报价(Protation 公式) | addon-quotation 产品报价扩展点(公式求值器接口化) | V3 |
| 报价分配 | 策略接口模式(同客源 V2) | V3 |
| 通知(站内信/钉钉直连) | 领域事件 + 内核通知中心 | 架构已定 |
| 成本/利润线(consPrice 等) | 报表/经营分析插件 | 不进 V1 |

## 11. 待业务方确认清单

1. 【已定案 2026-10-12】金额单位=厘(×1000 定点整数,第 19 次交付已按厘落地,勿再议分/元);历史数据迁移口径:1e6 列迁移时按列量纲重推(material.md 勘误同源);
2. 报价数量上限(默认 3)与保价窗口(默认 365 天)的行业默认值;
3. 审核线(提交→审核→完成)是否 V1 保留,还是接入自研轻量工作流(与客源两级审批同题);
4. 成本/利润字段(consPrice/comsPrice/unachePrice)确认不进开源核心、归经营分析插件?
5. 增减项 paytype 的 COM/ARO 语义与漏项(LEAKAGE)流程是否公司特有(建议:不迁);
6. 定额确认/驳回线(联动工费)是否 V2 做,还是等 addon-salary 需求;
7. 测量/复测子域归 addon-quotation 还是 addon-building;
8. 整装产品报价(公式字符串求值)是否进开源版(建议:公式求值器接口开源,内置实现简单四则,复杂公式商业插件)。
