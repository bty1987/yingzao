# PRD - addon-quotation 定额子域(报价定额/工费成本底座)

> 状态:已确认(2026-10-28 用户拍板「按推荐」七项)+ R2 已实现(第 97 次:V405 六表/生成引擎三路径/附加确认线/两事件+construction 挂点/三触发点消费器/Web Norms.vue;冒烟 2246/2246+enforce 95/95)+ **R3 已实现(第 98 次:NormApi 三聚合契约 addons-api/实现在本域——laborFeeByNode 老公式逐字对齐/workdaysByNode/materialSummaryByOrder 行级 HALF_UP;V604 construction_schedule.additem_status+NormPendingConsumer 事件回写替代老跨域直写;施工详情工费/工日列读取期契约聚合零落库;材料汇总 HTTP 投影归既有闸;冒烟 R3 段=真实施工节点联结+徽章置1/归零+工费精确到厘+放线事件消费断言);实现定案回写 rules/quotation.md §5/rules/construction.md §14.2;D7 两轮收官**
> 老系统参考:quotation 模块 Normtion 系(Normtion/NormoodItem/NormriceItem/NormtimeItem 实例层)+ data 模块 Normood/Normrice/Normtime/Normadd/Normpack 系(档案层);实现口径见 `docs/rules/quotation.md` §5(本轮已增量回写)
> 归属:推荐 **addon-quotation V2 子域**(开放问题 1);若拍板独立插件则码段 390000/迁移 1900+
> 本子域声明:错误码 **231200–231299**(addon-quotation 230000 段内分段);迁移 **V405+**

## 业务闭环(一句话)

预算员在**报价项库**上挂材料/人工/工时**定额档案**(每单位用量/节点单价/工日),**放线成功时全量重算**把默认报价(+增减差量已确认行)展开为该装修单的**定额实例**(材料用量/节点工费/工日),作为**工费工资、材料领用、排期成本**的底座;施工中的临时追加走**附加项确认线**(确认才计入工费聚合)。闭环起点=放线成功生成实例;闭环终点=工程验收(实例固化,禁用行清理,不再重算)+附加行全部确认/驳回。

## 谁与场景

| 角色 | 场景 | 高频动作 |
|---|---|---|
| 预算员 | 报价项挂定额(成本口径源头) | 维护材料/人工/工时定额档案 |
| 工程管理/项目经理 | 放线后定额固化;施工中临时追加人工费 | 重新生成、登记附加项 |
| 财务/工资核算 | 节点工费审核(附加项待审徽章) | 确认/驳回附加项 |
| 监理(未来) | 按节点看工费与工日 | 只读查看 |

## 核心模型(档案层 + 实例层)

### 档案层(组织级主数据,挂报价项库 quotation_item)

- **quotation_norm_archive(单表+kind 位,推荐;开放问题 2)**:kind 0 材料/1 人工/2 工时;catalog_item_id(挂 V404 报价项库,必填);name 快照;
  - 材料列:goods_id(挂 basedata 商品,老「定材」必填 231204)+norm_unit/norm_num(定额单位/每单位用量)+store_unit/store_num(库房单位/用量);
  - 人工/工时列:construction_node_id(施工节点,老挂 data.constructionnode)+unit+trannum(转换系数千分位,1000=1.0)+salary(单价厘,人工)+workdays(工日,工时);
- **quotation_norm_addition(附加项档案)**:name+construction_node_id+unit+price(厘)+启停——老 Normadd(公司级模板,登记附加项时选模板带出或全手工);
- **quotation_norm_pack + quotation_norm_pack_item(材料定额套餐,开放问题 3)**:包名+启停;成员挂 goods+双单位量——老 Normpack/NormpackItem;实例展开时按包成员一次展开(老 NORMTYPE_PACK=1 位)。

### 实例层(单据维度,一单一头)

- **quotation_norm(定额单头)**:order_id+quotation_id(默认报价)+creater/create_time;一单唯一(uk org+order);老 moodTotal/mriceTotal 冗余合计**不落库**(查询期聚合,修老冗余漂移);
- **quotation_norm_item(单表+kind 位;老三张实例表收敛——沿 quotation_function_item 收敛五分表先例)**:
  - 公共列:order_id/quotation_id/source_type(**0 报价/1 拓展预留/2 增减/3 附加**)/source_id(来源单 id:报价=功能项 id、增减=差量单 id、附加=自身)/area_id/item_id(挂功能项)/catalog_item_id/atttype(0 收/1 支——减项反向走支);
  - 材料列(可空):goods_id/norm_unit/norm_num/store_unit/store_num;
  - 人工/工时列(可空):node_id/name 快照/unit/trannum/salary/workdays/num;
  - 确认线列(仅附加行使用):status(0 初始/2 确认/3 驳回;**非附加行恒 0 语义**)+confirmer/confirm_time(**原生带**——老系统 2026-10 才在生产补列,营造不欠这账)+draft 无(草稿不设,附加行即登记即待审——老无草稿);
- uk:(org, quotation, source_type, source_id, kind, 档案 id)——重算幂等复用的锚。

## 生成引擎(全量重算,老 makeNormtion 语义收敛)

- **触发点(老实证三处)**:①**放线成功**(successDrawline/retroactiveDrawline 补录)→全量重算(默认报价+增减);②**增减差量款收齐**(PaymentCollectedEvent typeCode=ar_diff)→按该差量单重算;③**手动「重新生成」**(管理动作,全量);
- **算法**:按来源先禁用(source+kind 维度)旧行→重建:同 uk 行**复用置 0 并刷新数值**(老幂等复用语义——数值以档案×明细量为准,不保留旧值);新行插入;
- **完工即固化**:工程验收(AcceptedEvent 或契约)后重算=**物理清禁用行+拒绝新增生成**(老 PROJECTSTATUS_FINISH 分支);
- **数量口径**:实例量=明细量×档案定额量;**千分位缩放:老=整乘不除,营造推荐 ÷1000 HALF_UP(对齐 A5 套合套拆先例)**——实现期冒烟逐例断言,列为口径核对项(开放问题 6);
- **附加行不参与重算**(source=3 行是人工登记,重算只动 0/2 两来源;老 disable 按来源维度天然隔离);
- **拓展来源(type=1)预留不生成**——拓展项域未建(诚实边界,随报价 V3 拓展项接入;枚举位先占)。

## 附加项确认线(人工费审核,老 NormriceItem 四态收敛三态)

- 登记:选附加项档案(带节点+单价)或手工填(节点+名称+单位+数量+单价厘)→status=0 待确认;登记后即时参与待审徽章;
- 流转:0→**确认**(2,记 confirmer/confirm_time)或 **驳回**(3);删除仅 0 可(231201);
- **待审徽章联动**:节点下存在待确认附加行→该节点排期行打「待审」——营造**发事件 NormAdditionPendingChangedEvent(orgId/orderId/nodeId/pendingCount),addon-construction 消费自写 additem_status**(老跨域直写 schedule 表 → 事件回写惯例,开放问题 5);
- **工费聚合口径(NormApi 契约,工资底座)**:`laborFeeByNode(org,order,node)=Σ(非附加行 status=0 + 附加行 status=2) × (atttype=0?+1:−1) × salary × num/1000 × trannum/1000`——老 SQL 逐字对齐;**老 quirk:手工工作费附加行(非追加)确认后不计入聚合,疑缺陷——营造推荐收口为「附加行确认即计入,追加/手工同权」**(开放问题 4);
- 同族契约:`workdaysByNode`(工日合计)/`materialSummaryByOrder`(材料定额汇总,领料对账/预算对照用)——首个消费方 addon-construction(节点工费列+待审徽章)。

## 契约与事件(addons-api 增量,全部加法零破坏)

| 增量 | 类型 | 说明 |
|---|---|---|
| `ConstructionDrawnEvent` | 事件(新增) | 放线成功/补录成功发布(orgId/orderId/drawlineId/operatorId)——construction 域现仅失败事件,成功路径 ignite 无事件;本子域消费=触发全量重算 |
| `NormAdditionPendingChangedEvent` | 事件(新增) | 附加待审集合变化;addon-construction 消费更新排期待审位 |
| `NormApi`(laborFeeByNode/workdaysByNode/materialSummaryByOrder) | 契约(新增) | 工费/工日/材料聚合;工资核算与排期展示的数据源 |
| `PaymentCollectedEvent(typeCode=ar_diff)` | 既有消费 | 触发对应差量单来源重算(零契约改动) |

## 错误码段(231200–231299,双语)

231201 附加项状态不可操作 / 231202 定额单不存在 / 231203 报价项不存在 / 231204 定材(商品)不存在 / 231205 节点不存在 / 231206 完工后禁止生成 / 231207 档案被实例引用不可删(改停用)/ 231208 附加项档案不存在 / 231209 数量或单价非法 / 231210 重复生成进行中(防并发,条件更新闸)——登记 error.md 惯例由实现轮落;档案维护复用既有守卫姿势。

## 划界(本期不做)

- **拓展项来源展开**(type=1 预留)——随报价 V3 拓展项域;
- **选材联动**(selection 下拨/特批对定额的冲抵)——V3;
- **测量/复测子域**——另 PRD(开放问题:归 quotation 还是 building);
- **报价分配**——V3(复用客源策略接口模式);
- **工资单/工费发放**——addon-salary 未立,本子域只出 NormApi 聚合契约;
- **打印计数**(moodPrintNum/mricePriceNum)不迁【方林嫌疑:打印管控】;
- **冗余合计列**(moodTotal/mriceTotal)不迁——查询期聚合;
- 老跨域直写 construction schedule 表——改事件回写。

## 验收标准(实现轮逐条对)

- [ ] 档案层:三 kind 维护页(挂报价项库+商品/节点),删除被引用拒 231207;
- [ ] 附加项档案启停可用;
- [ ] 放线成功→定额实例生成(默认报价展开:材料=明细量×定额量/工费=节点单价行/工日行),源行禁用-复用-新增三路径断言;
- [ ] 增减差量款收齐→该差量单来源行重算;
- [ ] 完工(验收)后:禁用行清理+重算拒 231206;
- [ ] 附加项:登记→待审徽章事件→确认(2+confirmer)→计入聚合;驳回(3)不计入;删除仅 0;
- [ ] NormApi.laborFeeByNode 与手算逐例精确到厘(含 atttype 支向负值);
- [ ] 手动重新生成幂等(重复调用零新增行);
- [ ] 错误码 2312xx 全走 i18n 双语;enforce 冒烟样例同步;
- [ ] mvn clean package 全绿 + dev 库迁移 V405 成功 + 冒烟 ≥25 步全绿;Web 页面走 huashu-design 三方向初稿流程。

## 建议实施节奏

- **第 1 轮**:迁移 V405+档案/实例层服务+生成引擎+附加确认线+事件两枚+Web 基础页(体量大头);
- **第 2 轮**:NormApi 契约+addon-construction 消费(待审徽章/节点工费列)+冒烟补全+enforce 扩样例。

## 实现期核对清单(第 91 次预走;拍板后写码时逐条销,不落码)

1. **ConstructionDrawnEvent 挂点**:写码先 grep construction 域 `ignite(` 现状实证「仅失败事件」结论仍成立,再在 successDrawline/retroactiveDrawline 成功路径插发布;事件类落 addons-api,发布在 addon-construction,消费在 addon-quotation;
2. **@Lazy 断环**:消费者→服务→YzEventBus 构造环惯例(第 89 次 AcceptanceVisitConsumer 同款,启动即死家族);
3. **ar_diff 消费守卫**:PaymentCollectedEvent 差量款 bizId=**arId 非 orderId**(第 81 次库实证),重算按该差量单单源;
4. **完工固化事件**:验收事件复用 `ConstructionAcceptedEvent`(第 89 次 addon-service 已消费实证存在),消费置固化位+拒重算 231206;
5. 🔴**附加行 uk 自引用**:实例 uk 含 source_id,附加行 source_id=自身→插入时无 id 可填——重算复用锚只需覆盖 0/2 来源(老 disable 按来源维度),附加行要么 uk 排除(source_id 记 0+业务查重)要么落库后补挂——**实现期必须先解此题再写引擎**;
6. **千分位 HALF_UP 姿势**:照 A5 套合套拆 QuantityCalc 先例;冒烟逐例断言到厘(开放问题 6 拍板前不定案);
7. **迁移**:V405 起一文件含全部五表(档案 2+套餐 2 若进+实例 2,按拍板增减);MySQL 5.6 向下兼容;uk 列序与查询路径对齐;
8. **端点归闸**:全部挂 /api/quotation 既有 prefix,零新增闸声明(沿 QuotationArController 先例);enforce 样例同步;
9. **码段先查后用**:2312xx 落码前 grep 台账防撞(第 55 次 210008 撞码教训);
10. **冒烟纪律**:专用商品/报价/装修单跨轮隔离(第 57 次 B7 残留教训)+段首定向清理;断言中文走 python urllib;
11. **Web 三页走 huashu-design 流程**:定额档案/实例详情/附加确认——军规前端审美流程不豁免(先三方向初稿待选)。

## 待业务方拍板清单(拍板前不写码)

1. **归属**:addon-quotation V2 子域(推荐——与报价项库/增减线同域闭环,老系统亦是同族两库)vs 独立 addon-norm(码段 390000);
2. **档案/实例表形态**:单表+kind 位(推荐——沿五分表收敛先例,重算/聚合引擎单套)vs 三表对称(材料/人工/工时各一套,字段显式无互斥);
3. **工时(norm time)是否 V1 进**:推荐进(与人工同构仅 salary↔workdays 差异,边际成本低,工日是排期/成本双用途);
4. **材料定额套餐(norm_pack)V1 是否进**:推荐进(老 NORMTYPE_PACK 位实证在用;不进则 V1 只留枚举位);
5. **附加行工资聚合口径**:照老(仅「追加型」确认后计入,手工工作费型不计——疑老缺陷)vs 收口「确认即计入,追加/手工同权」(推荐);
6. **千分位缩放**:实例量=明细量×档案量 **÷1000 HALF_UP**(推荐,对齐 A5 先例)vs 老整乘不除——影响领料对账数字,需业务确认存量口径预期;
7. **待审徽章联动**:事件回写(推荐,addon-construction 消费)vs 查询期计算(零事件但每次查)。

> 拍板方式:可整体回复「按推荐」;单项改判请指明编号。拍板后下一轮进入写码(同 addon-service 第 88→89 次节奏)。
