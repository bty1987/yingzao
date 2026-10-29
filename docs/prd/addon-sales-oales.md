# PRD - addon-sales 外销子域(厂商品牌外销单,精简版)

> 状态:**已确认(2026-10-28 用户拍板「后续无特殊说明一律按推荐」七项全按推荐)+ R4 已实现(第 99 次:V1103 两表一留痕/六态状态机/确认建款翻转 sales_oales 懒预置/SettlementItemReadyEvent 结算接入零新基建/退货跳态出生负计快照/废弃守卫收紧+撤结算/驳回回草稿/Web Oales.vue+/sales/oales 菜单;冒烟 oales 段 45 步;实现定案回写 rules/sales.md §6)**
> 老系统参考:sales 模块 Oales 系(OalesPO/OalesItemPO/OalesPayPO/OalesRecordPO + OalesServiceImpl 16 方法)+ payment 模块 OalesPaymentServiceImpl(渠道收款)+ balance 模块 BalanceOales 系(结算线);实现口径见 `docs/rules/sales.md` §6、`docs/rules/balance.md` §5(均已深读在档)
> 归属:推荐 **addon-sales V2 子域**(开放问题 1;addon-balance PRD 非目标是「上游结算源 addon-sales(销售明细/外销)」,docs/11 D6 同口径);若拍板独立插件则码段/迁移另议
> 本子域声明:错误码 **300100–300150 小段**(addon-sales 300000 段内分段,300031 已占用,沿 quotation 231200/balance 320100 子段先例);迁移 **V1103+**;表前缀 `sales_oales_`(对齐 sales_counter/sales_order 惯例,军规 3)

## 业务闭环(一句话)

销售员在**装修单**下为某**品牌(厂商)**登记一笔**外销单**(平行于主材销售主单的另一条销售线:按品牌从厂商进货、对外销售,公司赚利润差),经**提交→确认(生成款线)→收款→完结**走完单据生命周期;**利润按品牌+厂商发「结算就绪」事件**进入 addon-balance 统一结算单,与厂商完成利润结算。闭环起点=外销单登记;闭环终点=完结(款收齐+利润已入结算)或废弃。

## 谁与场景

| 角色 | 场景 | 高频动作 |
|---|---|---|
| 销售员 | 为装修单客户代购某品牌外销商品 | 登记外销单(品牌/明细/利润)、提交、作废草稿 |
| 销售管理 | 审核外销单据 | 确认、驳回(回草稿)、完结 |
| 财务 | 收款与利润结算 | 收款(payment 通用链)、结算单吸附利润 |
| 厂商(结算视角) | 按利润结算 | addon-balance 结算单(零感知外销细节) |

## 核心模型(两表+留痕,老四表收敛)

- **sales_oales(单头)**:
  - 归属列:org_id/order_id(挂装修单,必填)/no(单号:组织短名+yyMMdd+流水,sales_counter 同族计数器)/foid(退货单回挂原单 id,可空);
  - 商品列:brand_id+brand_name 快照(经契约取品牌默认厂商 supplier_id+supplier_name——老 brand→supplier 默认取品牌厂商,快照落列禁前端传名,对齐采购单厂商引用化惯例);
  - 方向列:type(**0 购货/1 退货**);老 atttype(0 内购/1 外购 进货方向)**不迁**(开放问题 2——atttype×type 正交是老「内购自用 vs 外购转卖」的库存方向口径,营造 V1 外销单全部=进货方向,退货行天然同向,枚举位无业务消费点);
  - 金额列:total(货款合计,厘,服务端按明细重算)/profit(利润,厘,服务端算=total−cost 或手填,见开放问题 3);
  - 状态列:status(六态,见下)/creater/create_time/finish_time/abate_reason;
  - 【不迁】award/awardType(部门奖励分成,【方林嫌疑】随激励专题 D1 对齐,开放问题 4)/isScale+isActivity(见老缺陷收敛表)/cost+threshold 保密分级(开放问题 3);
- **sales_oales_item(明细)**:oid/name/format/color/model/price(厘)/num(千分位)/total(=price×num 服务端算)——老 oalesitem「VO 无 oales 属性→oid 恒空断链+total 千倍虚增」缺陷在重做中物理消灭(明细经单头聚合重算,拒绝前端 total);
- **sales_oales_track(操作留痕)**:oid/type/operater/operate_time/before_status/after_status/comment——老 oalesrecord 15 type 收敛为 track 惯例(对齐 sales_track);修老「deleteOales 先删单再补记录→孤儿记录」(留痕与单据同事务,删除软作废不留孤儿);
- 【不迁】oalespay(手工付款登记)——两入口规则冲突的根源,**收款唯一入口=addon-payment 款项单**(营造哲学:每步资金动作都有 payment 单据留痕,第 67 次定案);payment.oalespayment 镜像同步不迁。

## 状态机(老 7 态收敛 6 态)

```
0 草稿 ──submit──► 1 待确认 ──confirm(建款线)──► 2 已确认 ──PaymentCollected──► 3 已收款 ──finish──► 4 已完结
  ▲                  │                             │
  └──cancel(撤回)────┘   1 ──reject(驳回+原因)──► 0 草稿(track 留痕)
任意 0/1/2 ──abate──► 8 已废弃(终态;3/4 不可直接废——老「任意态可废不回滚已收款」缺陷收紧)
```

- 与老 7 态(0 初始/1 提交/2 确认/3 驳回/4 复核/5 完结/6 废单)差异:**驳回态消亡**(老 3 可重回 1=改后重提,营造收敛为驳回回草稿+原因留 track,状态位减一);**复核档消亡**(老 4 复核=collect 收款自动写入,营造改为收款事件回程置 3 已收款——语义等价,名称对齐营造款线词汇,修 collectOalesPayment ptids 断链的「渠道收款停在 SUCCESS 可重复核销」);
- confirm 守卫:老=「付款登记存在(370026)」先款后确认;**营造推荐翻转为确认时经 PaymentApi.createBill 生成款项单(typeCode=`sales_oales`,金额=total)**,收款走 payment 通用链(开放问题 5);
- finish 守卫:status=3 已收款;abate 守卫:仅 0/1/2,3/4 须先走退款/完结(修老「已完结已结账可废且不回滚已收款」);
- 退货:老 refundOales 守卫 4 复核态、复制原单跳态出生(直接 2 确认);营造=**已确认(2)/已收款(3)单可发起退货**,退货单=独立单头(type=1,foid=原单,明细默认复制可改,**profit 负计**),创建即 status=2 已确认(跳态出生保留——退货不需要再走提交确认,老语义合理),款线/结算事件照常参与(负利润进结算聚合,对齐老 balanceoales `if(type=0,profit,−profit)` 口径)。

## 款线与协议键

- 协议键 **`sales_oales`** 登记 `docs/rules/payment.md` §15(类型行组织自建,惯例同 sales_full/sales_deposit);单款线,无定金拆分;
- confirm 事务内 createBill→PaymentCollectedEvent 回程置 3(条件 UPDATE 幂等,对齐 sales 双轴回程姿势);退款=payment 部分退/整单退通用链(PaymentApi.PartialRefundCmd 已就位,A1 交付),不另建退款状态机。

## 结算接入(addon-balance 零新基建)

- **status→2 已确认(含退货单出生)时发 `SettlementItemReadyEvent`**(settlementType=`sales:oales`,amount=profit 快照,brand_id/supplier 携带,orgId/orderId/bizId=单头 id)——SettlementItemConsumer/onItemReady 已就位(第 46 次交付),balance 侧零改动;
- abate 时发 `SettlementItemWithdrawnEvent`(对齐老 unbind 回置 accountStatus 的对账语义);**仅 0/1/2 可废=已入结算的单据必然已确认,修老「6 废单/0 初始外销单也能绑入+同一 oid 重复绑入利润重复累加」**(事件就绪=可结算唯一入口,天然防重);
- 老缺陷「结算建 balance 头不写金额(零金额空壳)」——balance 侧 V1301 统一结算单模型快照落行,不迁移此坑;
- 老回写 oales.accountStatus 借 quotation.SelectionItemPO 常量(域错)——营造事件语义下 accountStatus 列消亡(就绪/撤回事件即对账状态),不迁。

## 契约与事件(addons-api 增量,全部加法零破坏)

| 增量 | 类型 | 说明 |
|---|---|---|
| `SettlementItemReadyEvent(settlementType="sales:oales")` | 既有事件复用 | 利润结算就绪;balance 消费器已实现,零改动 |
| `SettlementItemWithdrawnEvent` | 既有事件复用 | 废弃撤回 |
| `PaymentCollectedEvent(typeCode=sales_oales)` | 既有事件消费 | 收款回程置 3(消费者 addon-sales 内建) |
| `DecorationApi`?否——**零跨域契约** | — | 外销单挂 orderId 只做归属展示,不读装修域数据(老连 house/customer 反查仅展示用途,营造单头不冗余,列表经 orderId 直查装修单既有分页契约) |

## 老缺陷收敛表(重做即修复,不新开码)

| 老缺陷(rules 实证) | 营造处置 |
|---|---|
| oalesitem 明细链全断(oid 恒空 400001/total 千倍虚增) | 明细经单头聚合服务端重算,拒前端 total |
| addOales award/profit 不在 tranargs→元当厘缩小 1000 倍 | 金额全部厘制 API 契约显式换算(tranargs 机制本身不迁) |
| amountOales 汇总不过滤 type,退货靠手填负数 | 聚合按 type 分支(购货正计/退货负计),利润服务端算 |
| oalespay 与 payment 双入口可堆多款/改额漂移 | 收款唯一入口=payment 款项单,oalespay 不迁 |
| collectOalesPayment ptids 断链可重复核销 | 收款回程=PaymentCollectedEvent 事件驱动,幂等条件更新 |
| abate 任意态可废不回滚已收款 | 废弃守卫收紧(仅 0/1/2) |
| 6 废单/0 初始单可绑结算+重复绑入利润累加 | 结算就绪=事件唯一入口,已确认才发 |
| deleteOales 先删单再补记录孤儿 | 初始态草稿物理删同事务留痕,提交后只可废弃 |
| balanceoales 绑入无守卫/建头零金额/借他域常量回写 | 见「结算接入」——三坑全在事件化中消亡 |

## 错误码段(300100–300150,双语,登记 error.md 由实现轮落)

300101 外销单不存在 / 300102 外销单状态不可操作 / 300103 明细非法(数量/单价≤0) / 300104 利润非法(负值/超货款) / 300105 品牌不存在或停用 / 300106 品牌未配置默认厂商 / 300107 装修单不存在或不可挂外销(活单守卫) / 300108 退货原单状态不可退 / 300109 已收款/已完结不可废弃 / 300110 完结须先收齐款 / 300111 撤回仅待确认态(复用守卫姿势,实现期按需收窄)

## 划界(本期不做)

- **atttype 内购/外购方向位**(开放问题 2)/**isScale 配比开关**——老 isScale 唯一消费点=DecorationAdditionServiceImpl:229 把「需配比」外销利润并入装修单增减总额(【方林嫌疑】财务口径);营造 V1 外销与装修款线**解耦**,不并入增减/决算;若真实诉求,随增减域 V3 做组织开关(开放问题 6);
- **award/awardType 部门奖励分成**——【方林嫌疑】随激励专题(D1/D3)对齐,开放问题 4;
- **cost/threshold 保密分级**——开放问题 3;
- **渠道支付适配**(老 OalesPaymentService paytran 族)——归 addon-payment 渠道插件(C2,凭证后);
- **oalesrecord 15 type 全量对位**——收敛 track 惯例,type 枚举位不逐一保真;
- **外销报表/金蝶凭证**——kingdee 归商业插件只读消费;
- **打印/导出**——随 Web 页按需。

## 实现期核对清单(拍板后写码逐条销)

1. SettlementItemReadyEvent 的 payload record 字段实读(brand_id 是否已含/需否加字段——加法扩展零破坏);
2. createBill 的 biz 三元组与 typeCode 懒预置姿势对齐 sales_full(A5 前例:ar_diff 类型行懒预置照 AdvanceService 科目惯例);
3. PaymentCollectedEvent 消费器 @Lazy 断环(总线↔服务构造环,第 N 次惯例);
4. 品牌默认厂商契约:basedata 品牌域是否已有 supplier 挂接,无则本域快照列由前端选择+契约校验(310065/310066 同构守卫复用 evaluation);
5. 装修单活单守卫经 DecorationApi 既有契约,零直查;
6. 冒烟专用数据隔离(独立品牌/厂商/装修单,段首定向清理);Web 页走既有 addon-sales Web 工程(Orders.vue 同族),无新风格需求(管理页非新视觉面,huashu-design 不触发)。

## 开放问题(待拍板,可整体「按推荐」)

1. **落点**:推荐 addon-sales V2 子域(300100 小段/V1103+)——balance PRD 与 docs/11 均预设外销归 sales;备选独立插件;
2. **atttype 进货方向位**:推荐不迁(V1 全部进货方向,枚举位无消费点);备选保留位随库存方向域;
3. **利润口径与保密分级**:老 cost/threshold 确认后才可见【方林嫌疑】;推荐 **profit 由手填改为服务端按明细算(total−成本),成本列不建、利润列直接展示**(行业中性:无保密分级需求,利润即单据属性);备选保留 cost 手填+利润=服务端差值;
4. **部门奖励分成 award**:推荐不迁(随激励专题对齐);备选 V2 组织开关;
5. **确认与款线顺序**:推荐翻转(确认建款→收款),修老「先手工登记付款再确认」的双入口根源;备选保留先款后确认(守卫=款项单存在);
6. **isScale 外销利润并入装修增减**:推荐不迁(解耦,【方林嫌疑】);备选增减域 V3 组织开关。
