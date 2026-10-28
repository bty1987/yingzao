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
   - 【第 86 次 A6 冒烟实证:V401 注释与实现语义漂移 10 倍】V401 列注释「管理费率千分比‰,50=5%」,但 recalc 实现 manage=dis×manage_rate×manage_discount÷1e8(discount=10000 时=dis×rate÷10000=**万分比系数**)——50 实得 0.5%。以实现为准:**费率 rate=万分比(1000=10%)**,V401 注释待勘误;A6 口径版本 manageRate=1000 即 10%;
4. **税的可选性**:版本 taxDiscount=0(NOTTAXDISCOUNT)→ 不生成税项(见 §4 自动生成);
5. **总价公式(由明细聚合,价格快照支撑)**:undisPrice(未折)/disPrice(折后)=Σ功能项;totalPrice=disPrice+管理+搬运+税;成本线 costPrice/consPrice/comsPrice(预算成本/施工成本/公司成本)与利润线 unachePrice 并存——**成本/利润字段是方林经营分析口径【方林嫌疑】,营造 V1 只做对客报价线,成本线归报表插件**;
6. 默认报价变更(切 defaults)同步回写装修单 decoration.quotationPrice/discount——跨域写,营造改事件(QuotationDefaultChangedEvent)或查询期汇总,V1 建议**查询期汇总**(避免双写)。

## 4. 报价明细模型(功能区→功能项)

- 功能区类型:0 管理 / 1 工程量 / 2 功能区总价 / 3 不计算 / 7 综合;
- 功能项类型:0 常规 / 1 自定义 / 2 搬运费 / 3 管理费 / 4 忽略 / 5 成本 / 6 未完成 / 8 税 / 9 综合;subtype 0 工程project/1 材料material;
- **创建报价自动生成**:"生成项目"功能区(TYPE_MANAGEMENT,排序 9999)+ 搬运/管理两个自动项(+税项,若版本启税);"综合项目"功能区由公司配置 QUOTATION_CMPREH_CONFIG 开关控制【方林嫌疑:公司开关→组织级配置】;
- **🔴 分表化石**:功能区项实体按 `quotationFunctionAreaIndex`(systemConfig)选 QuotationFunctionAreaItemPO0–4(五张物理表,factoryToClazz 动态选类)——大数据量分表思想的散装实现。营造 **v1 单表+org_id 索引**,分表归 V3 性能专项;
- 价格快照:每个功能项可关联报价项(quotationItem),价格历史进 quotation_price,**保价重算 = 按时间点取最后快照回填**(updateQuotationForPrice);
- 【营造实现定案回写(第 84 次,A4)】**报价项库 quotation_item + 价格快照 quotation_item_price + 明细挂接 quotation_function_item.catalog_item_id(V404)**:老 data.quotationItem 主数据回归 addon-quotation 本域(uk org+name,同名单价=维护事故);**调价=价格唯一写点**(插新快照+同步当前价,同价幂等零写入;老 addQuotationPrice 初始快照 2015-01-01 hack 与 updateQuotationPrice 可改历史价的审计坑不迁,历史 append-only);重算端点按时间点取各挂接项最后快照回填并重聚合,**自由行/无快照行跳过(老 continue 同语义)**,仅编辑态可重算;老三价(add/unit/subPrice)收敛单 price 列;返回回填行数+track reprice 留痕;PRD 见 docs/prd/addon-quotation.md §7;
- 复制报价(clone/replica/copy)带 **KEEPRICE 位掩码**:0 取最新 / 1 保时间 / 2 保价格 / 3 全保;跨代克隆(Quotetion)是历史包袱;
- 报价数量上限:systemConfig `quotationlimit`(按 company,默认 3)【方林嫌疑:公司配置→营造组织级 addon 配置】;
- 版本联动:新报价取"公司+装修单类型"的默认版本;整装单在 `PROTATION_KEEPRICE_DAYS_INT`(默认 365 天)内沿用整装产品版本,超期用预售产品版本【方林嫌疑:365/版本选择规则是公司商务政策】;
- **保价窗口**:KEEPRICE_DAYS_INT(默认 365)内,报价 updateTime 回拨到装修单合同时间——合同价锁定语义【方林嫌疑:行业常见"合同期内不涨价",天数可配置化保留】。

## 5. 定额体系(Normtion,材料/人工/工时)

- 定额单按报价(qid)建,创建报价时自动 addNormtion;
- **生成 = 全量重算**:先禁用(disable)该报价全部定额项,再从三来源重建——①默认报价 ②该单全部拓展项 ③该单全部增减单(三来源 × 三类型 = 9 组展开);装修单已完工(PROJECTSTATUS_FINISH)→ 清掉禁用项、不再生成;
- **【第 90 次增量考证:实例层常量全集】**三类实例行 type 均 0 报价/1 拓展/2 增减/**3 附加(QUOTA,手工登记行)**;NORMTYPE 各异——材料 0 材料/1 套餐(PACK,Normpack 展开)、人工 0 工作费/1 追加、工时 0 工日/1 追加;atttype 0 收/1 支(减项反向走支);status 材料与工时两态(0 初始/1 禁用),**人工四态 0/1/2 确认/3 驳回**(确认线全族唯一);
- **【触发点实证(第 90 次)】**①放线确认 DrawLineServiceImpl(全量=默认报价+全部拓展);②**增减款收齐 DecorationPaymentServiceImpl(按 arId 单源重算,带 schedule)**;③手动三入口(NormtionAction:makeNormtion 全量/makeNormtionForQuotation 报价线/makeNormtionForExtension 拓展线)——报价编辑本身不触发,放线=定额基准确立;
- **档案层动作面(第 90 次)**:三档案(挂报价项 qiid)仅 page/queryByQiid/add/delete 四动作**无 update(改=删了重加)**;附加项档案 Normadd(公司级:name+节点+unit+price)与材料定额套餐 Normpack/NormpackItem 为全 CRUD+启停;Normood add 守卫=定材必存在(130118);
- **人工定额(NormriceItem)确认线 = 附加项审核**(老 NormriceItemServiceImpl):登记走附加项档案(Normadd,带 sid→NORMTYPE_ADD 追加)或手工填(→NORMTYPE_WORKFEE);0 待确认→confirm(2,记 userId)/reject(3);删除仅 0 可(200063);**联动工程排期 additemStatus**(节点下存在待确认行→schedule.additemStatus=1,跨域直写 construction schedule 表——营造改事件回写);确认人/时间列系 2026-10 生产补列(方林 91320a149),营造原生;
- **工费工资聚合口径(老 amountNormriceItemForSalary 实锤)**:`Σ(if(atttype=0,+salary,−salary)×if(trannum=0,1,num/1000×trannum/1000))` 按 qid+cnid,计入=**非附加行 status=0 + 附加行 status=2 且 NORMTYPE_ADD(追加)**;**quirk:手工工作费附加行(WORKFEE+QUOTA)确认后不计入——疑缺陷**,消费方=WorkerSalaryServiceImpl(工资域);
- NormtionPO 头:moodTotal/mriceTotal **冗余合计**+moodPrintNum/mricePriceNum 打印计数,status 仅 0【冗余合计与打印管控均为方林实现习惯,营造不迁】;
- 【方林嫌疑】"定额"概念行业共性(成本核算底座),但确认/驳回线挂工费工资(联动 addon-salary)是公司玩法 → 营造保留定额展开+全量重算,确认线随附加项审核保留(费用审核行业共性);
- 【实现定案 2026-10-28,第 97 次(R2)】V405 六表(档案/附加档案/套餐/成员/单头/实例单表);**实例 status 四态 0/1/2/3 全族统一**(0 初始/1 禁用=重算换版/2 确认/3 驳回——材料工时老两态用前两位,人工四态原生,单表收敛关键);uk(org,quotation,source_type,source_id,kind,archive_id,pack_item_id)——🔴附加行自引用解=archive_id null(uk 多 NULL 不防重),套餐成员 archive_id=0+pack_item_id,重算锚只覆盖 0/2;增减行 source_id=**差量明细行 id**(同单多行防撞,单源重算按 arId 查明细);聚合口径收口=**非附加行 status=0+附加行 status=2**(冒烟实锤 status=0 单条件把待审附加误计入,差值精确断言逼出);无默认报价的放线=generate 静默零展开不进死信;JdbcTemplate queryForList(sql, List) 传 List 不展开必须 .toArray()(惯例代码全 toArray,新写漏一处冒烟 110000 逼出);
- 【营造映射定案 2026-10-27,第 90 次】**定额子域 PRD 已产出:docs/prd/addon-quotation-norm.md**(推荐 addon-quotation V2 子域/231200 段/V405+):档案层单表+kind 位(挂 V404 报价项库 catalog_item_id)+实例单表收敛(沿五分表收敛先例)+source_type 四分(0 报价/1 拓展预留/2 增减/3 附加)+触发点三处(放线成功【需契约新增 ConstructionDrawnEvent——construction 域现仅失败事件】/增减款收齐【PaymentCollectedEvent 既有】/手动)+附加确认线三态(0/2/3,confirmer 原生)+待审徽章事件回写(NormAdditionPendingChangedEvent)+**NormApi 工费/工日/材料聚合契约**(工资底座,首个消费方 addon-construction);**7 项开放问题待拍板(归属/表形态/工时/套餐/聚合口径 quirk/千分位缩放/联动方式),拍板前不写码**;
- 【实现定案 2026-10-28,第 98 次(R3)】**NormApi 三聚合契约落地**(addons-api/实现在本域 NormApiImpl):laborFeeByNode(kind=1,老 amountNormriceItemForSalary 公式逐字对齐,atttype 支向负)/workdaysByNode(kind=2,Σworkdays 行和)/materialSummaryByOrder(kind=0 按商品聚合,quantity=Σ±num×norm_num÷1000 **行级 HALF_UP 后累加**——A5 先例);计入谓词全族统一「非附加 status=0+附加 status=2」与 R2 summary 同源;**待审徽章改事件回写收口**:NormAdditionPendingChangedEvent 由 addon-construction NormPendingConsumer 消费,写 construction_schedule.additem_status(V604;pendingCount>0→1,绝对值幂等;老 countNormriceItem 注释「普通行不得计入否则无法归零」口径在发布方按 source_type=3 天然满足);construction 排期详情工费/工日列=读取期经契约聚合零落库;材料汇总 HTTP 投影 GET /api/quotation/norms/orders/{id}/materials(归既有 prefix 零新增闸);

## 6. 增减项(AddReduce,合同后变更)

状态机:0 未提交 → 1 已提交(生成款项,回写 dpId)→ 2 已付款 → 3 已确认;配套位:isAffirm(工费确认 0/1)、isSalary(工费标识 0/1,标识后可驳回 200065);

- type:0 增项 / 1 减项 / 2 漏项;paytype:0 补款 / 1 公司承担(COM)/ 2 ARO / 3 退款(语义待考,提交线仅 paytype=0 可操作 200062)【方林嫌疑:COM/ARO 是方林财务口径】;
- 明细行:addPrice 增补价 / unitPrice 原单价 / subPrice 退减价 + initnum/num(原量/现量)+ 关联原报价项(qiid)——增减=对原报价行的差量修正,类型六分(0 报价常规/1 系统/2 自定义/3 报价项/4 增项/5 拓展项);
- 提交 = 批量(同一装修单)聚合生成**一笔**款项(正补负退,1e6 归一,见 §3.1),未付可整体撤销(cancel);
- 切换默认报价被拒的条件:功能区存在拓展项/增减项(200028/200032)——**变更链与报价版本强绑定**;
- 【方林嫌疑】漏项(LEAKAGE)单列类型 + 漏项追责玩法是方林管理特色,行业少见于系统化 → v1 保留 type 枚举位但漏项流程不做。
- 【营造映射定案 2026-10-16,第 57 次扩充】**定材包挂包**:quotation_quotation.material_package_id(V402;setMaterialPackage 仅编辑态可挂/换/解绑,包存在且启用经 MaterialQuotaApi 契约校验 230023;解绑守卫 230153=存在已确认差量不可解绑,与增减线联动);领料侧消费经 QuotationApi.designatedMaterialPackageId(inventory.md §13.1);
- 【营造映射定案 2026-10-16】增减线 V2 详细设计见 docs/prd/addon-quotation-addreduce.md:差量单(0 草稿/1 已提交/2 已付款/3 已确认,草稿可删、已提交未付款可撤)+明细行挂 ref_qiid+金额全厘服务端算+提交单事务聚合经 PaymentApi.createBill(typeCode=ar_diff)+PaymentConfirmedEvent 回程置已付+财务 confirm 终态;paytype COM/ARO 不迁(方林财务口径);重签前置「无未确认差量单」与决算金额「折后合计+Σ已确认差量」双向联动。
- 【营造实现定案回写(第 81 次,A1)】**增减净退款 V2.1(撤 230105 净负拒)**:submit 净额<0 不再拒,不生成应收款,遍历同装修单已提交兄弟差量单的款项单(bizId=arId——库实锤差量款三元组挂 arId 非 orderId)找已收/已确认者按 |净额| 发起部分退(经 PaymentApi.submitRefundPartial);无可退→remark 追加人工标记(轨迹可见零新表);净负单照常 status=1+发事件;净正行为零变化。老「净负即拒」把减项拆单逼成假单据的运营绕法收口。验证第 81 次报告。

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
| 价格快照时间线 + 保价重算 | quotation_item/quotation_item_price(V404) | ✅ 0.5.0(第 84 次 A4) |
| 金额口径 | **厘(×1000 定点整数,全局定案),禁 1e6/万分比黑盒**,系数字段语义文档化 | V1 红线(✅第35次核对回写) |
| 定额三来源全量重算 | addon-quotation 定额子域 | V2(**PRD 已出** docs/prd/addon-quotation-norm.md,第 90 次) |
| 增减项(→款项生成) | addon-quotation,发 QuotationAddReduceSubmittedEvent,款项归 addon-payment | V2 |
| 拓展项/选材/测量复测 | V2/V3(选材联动 addon-sales/材料) | V3 |
| 整装产品报价(Protation 公式) | addon-quotation 产品报价扩展点(公式求值器接口化) | V3 |
| 报价分配 | 策略接口模式(同客源 V2) | V3 |
| 通知(站内信/钉钉直连) | 领域事件 + 内核通知中心 | 架构已定 |
| 成本/利润线(consPrice 等) | 报表/经营分析插件 | 不进 V1 |

## 11. 待业务方确认清单

1. 【已定案 2026-10-12】金额单位=厘(×1000 定点整数,第 19 次交付已按厘落地,勿再议分/元);历史数据迁移口径:1e6 列迁移时按列量纲重推(material.md 勘误同源);
2. 报价数量上限(默认 3)与保价窗口(默认 365 天)的行业默认值;【第 84 次注:keep_price_days 列已建(quotation_config,默认 365),其老语义=复制报价时 updateTime 回拨窗口,营造复制报价未建——该窗口随复制报价功能消费,重算不复用】;
3. 审核线(提交→审核→完成)是否 V1 保留,还是接入自研轻量工作流(与客源两级审批同题);
4. 成本/利润字段(consPrice/comsPrice/unachePrice)确认不进开源核心、归经营分析插件?
5. 增减项 paytype 的 COM/ARO 语义与漏项(LEAKAGE)流程是否公司特有(建议:不迁);
6. 【第 90 次演进】定额确认/驳回线已随定额子域 PRD 收口为「附加项审核」(费用审核行业共性保留,不等 addon-salary)——聚合口径 quirk(手工工作费附加行不计入)等 7 项开放问题见 docs/prd/addon-quotation-norm.md 待拍板;
7. 测量/复测子域归 addon-quotation 还是 addon-building;
8. 整装产品报价(公式字符串求值)是否进开源版(建议:公式求值器接口开源,内置实现简单四则,复杂公式商业插件)。
