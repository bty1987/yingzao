# L2 规则清单 - member 会员营销域考证(积分商城)

> 提取自 Rhadamanthus 老系统 2026-10-13(第 42 次夜跑)。范围 = `information` Activity/Award/Member 族 + `customer` Member/PointAccount/Emember/Vscore 族 + `sales` 活动族(§7 已档)+ `decoration` DecorationActivity 族 + `payment` activitypayment/paymentactivity/paytran。两路子代理并行深读 + sales/information/decoration/payment 四份既有 rules 交叉核对。**总定性:老系统"会员"没有独立实体——积分商城四处三模块无单一 owner 之外,积分账户还散在第五处(customer.cppoint),是五处四模块的拼盘;积分真源在外部商城,ERP 侧只是回写通道+台账镜像。**

## 0. 模块定性

- **积分商城五处四模块无单一 owner**(sales.md §7"四处三模块"+本次补全第五处):
  1. 活动主档 = `information.activity`(atttype=1 线上活动 + multiple 倍率 + validays 有效期唯一来源);
  2. 积分账户 = `customer.cppoint` 三表(cppoint/cppointRecord/cppointPayment,挂 company+cid+orderId);
  3. 兑换消耗 = `sales.salesactivityitem`(销售域 10 态,sales.md §7);
  4. 券/物品核销 = `decoration.decorationactivityitem`(11 态,含积分商城同步物品 stateType=15);
  5. 对冲台账 = `payment.paymentactivity`(残缺,仅服务款一个写点)。
- **外部商城回写模式**:`makeMemberOrder / makeMemberOrderItem / cancelMemberOrder / cancelMemberOrderItem / updateMemberGoods / addMemberActivity` 六个 .do 在 WebContent **零引用**——由外部积分商城小程序后端经 HTTP 调用,积分真源在商城,ERP 被动同步;ERP 侧 cppoint 既被同步整单覆写、又可被 grantPoint/consumePoint 手工改,双写互踩。
- 会员三套并存互不相通:**cppoint 积分账户**(cid+orderId 维度)/ **emember 电子会员**(unionId 维度,会销产物)/ **vscore 微信积分**(unionId 维度,仅 vsid/unionId/score 三列)。

## 1. 会员模型:三套并存,无独立实体

### 1.1 cppoint 积分账户(真台账)
- `customer.cppoint`(PointAccountPO):cid/orderId/dpoint(装修)/spoint(主材)/opoint(运营)/point(总额)/company——**按 company+cid+orderId 建账**,装修单维度;`PointAccountDaoImpl.java:35-37`;
- `customer.cppointRecord`(PointRecordPO):type 0 增/1 扣 × pointType 0 装修/1 主材/2 运营 × status 0 有效/1 失效;init() 未设 status,靠 short 默认 0 侥幸等于 STATUS_ABLE(`PointRecordPO.java:223-244`);
- `customer.cppointpayment`(PointPaymentPO):积分↔装修款行(dpid)分摊映射,不是资金流;
- 写点:同步覆写(MemberServiceImpl:765-770)/懒开户+运营积分调整(PointAccountServiceImpl:108-147)/grantPoint:151-202/consumePoint:206-265(余额校验 330049,查后扣非原子)。

### 1.2 emember 电子会员(会销产物,🔴不迁)
- `customer.emember`(EmemberPO):emid/no(=vticket 票号)/name/phone(明文!)/address/unionId/ticket(死字段)/status/activity/**存的是 vactivity.vaid 而非 activity.aid(字段名误导**,EmemberServiceImpl.java:122)/registerTime;
- 注册链 addEmember(:81-137):图形验证码(330012)→微信 phoneCode 换手机号→unionId+no 查重(330013)→**必须持有效活动票 vticket**(160026)→落库回写 vticket isregist=1→发微信。到场闭环 comeVticket(VticketServiceImpl:149-176,160045 未注册/160060 已到场);
- 🔴 与 V 系会销六表同生共死(2022-09 停办),不迁。

### 1.3 vscore 微信积分(孤岛,🔴不迁)
- `customer.vscore`(VscorePO:14-27)仅 vsid/unionId/score;raiseVscore/pickupVscore(160036/160037 不存在/不足)在 VustomerScopeAction/VscoreAction;与 cppoint 无任何代码交集。

### 1.4 与客户/订单的关系
- 会员没有独立主档:**手机号 → communion.customer(cid) → decoration 装修单(orderId)** 三级跳;queryMemberByPhone 支持手机号(三代加密形态展开匹配,SensitiveDataHelper.getAllEncryptedForms:147)或装修单号批量查,限 10 条;
- openid 关联链:小程序 jscode2session → hades.wxopenunion 存 openid+unionId → 客户侧以 unionId 关联 emember/消息投递;员工侧 unionId↔userId 走 wechatUserBind;
- **ERP 侧无收货地址端点与字段**(store.AddressPO 是门店地址);收货信息仅在 emember.address/vticket.address 各落一份。

## 2. 活动主档(information.activity,积分商城心脏)

- 字段语义:type 1 装修/2 主材/3 销售;**atttype 0 线下/1 线上(会员活动专属过滤键,9 条积分 SQL 全部 atttype=1)**;multiple=积分倍率;validays=收款后有效天数;status 0 有效⇄1 失效;defaults 默认(每 type+company 唯一,切换先原生 SQL 全表重置 `ActivityDaoImpl.java:58-64`,默认活动禁停用 160047);paytype/exchange 死字段只写不读;startTime/endTime 无任何查询消费。
- **addMemberActivity 批量建线上活动**(MemberServiceImpl:613-683):入参 MemberActivityVO(orderId/name/startTime/endTime(String!)/type/rate/multiple)+ companys(**AES 加密公司串列表**,CompanyServiceImpl.encode/decode:231-247)+ 会话 userId;type 仅 0/1/2(否则 130183),0→装修域/1→销售域/2→双域各建;每公司每域一条,copyProperties + company=decode + defaults=1 + **atttype=1**;companys null 默认总部。
- 🔴 建档哑弹两颗:①VO startTime/endTime 是 String,拷贝到 Timestamp setter 抛异常被 BeanUtil **静默吞掉**(utils\BeanUtil.java:110-116 catch→continue)→建出的活动 start/end 恒 null;②VO 无 validays 字段→线上活动 validays=0→SQL#2 `DATEDIFF<validays` 恒假→**可用装修积分恒 0**,必须事后 updateActivity 手工补(ActivityServiceImpl:105)。
- 时间窗形同虚设三处:queryActivityForValid 只按 company+status+type(`ActivityDaoImpl.java:48-50`);9 条积分 SQL 均无活动时间段条件;validays 只有两处消费(SQL#2 时间窗 + 兑换物品 expireTime=createTime+validays,MemberServiceImpl:740-746;核销时校验 expireTime<now→140058)。**无定时任务清零**(全仓唯一 timer 已注释)——积分永不清零,过期只体现在查询口径与核销拦截。

## 3. 积分口径(9 条 SQL 逐条 + 量纲地狱)

`customer\dao\impl\MemberDaoImpl.java`(DAO 挂 PointAccountPO,全部原生 SQL 只读计算;销售表无积分余额列,额度实时算):

| # | 方法(行) | 语义 |
|---|---|---|
| 1 | totalMemberPointForDecoration:20-26 | 理论总装修积分=Σ(decorationpayment.referMoney×multiple/1000),onetype=0+twotype=1+atttype=1。**全工程 0 调用=死代码** |
| 2 | amountMemberPointForDecoration:29-35 | 可用装修积分:同 1 + paymentStatus IN(1,2) + sort!=0 + join decorationreceipt + **`DATEDIFF(NOW(),collecttime)<validays` 时间窗**。唯一调用 makeMemberOrder:700 |
| 3 | totalMemberPointForMaterial:38-44 | 理论总主材积分=Σ(salespayment type IN(2,3,8)?+referMoney:-referMoney)×multiple/1000,status IN(1,2),atttype=1。**0 调用=死代码** |
| 4 | amountMemberPointForMaterial:47-53 | 可用主材积分:同 3 包在 CASE WHEN(该单全部 salesdeliveryitem MIN(status)=4 全送达)——**未全送达=0** |
| 5 | amountDecorationPointByOrderId:56-62 | 展示口径装修积分:仅 sort!=0,**无支付状态过滤、无时间窗**——与 #2 口径不一致,queryMemberPoint 页面用 |
| 6 | amountDecorationPointForFreezeByOrderId:65-71 | 冻结装修积分=advancepayment.balance×multiple/1000(预付款余额表,无状态过滤) |
| 7 | amountMaterialPointByOrderId:74-80 | 主材积分=Σ(payment.recharge×multiple/1000),type=1+paymentType=2(主材预付款)+status IN(1,2)+**p.aid=a.aid** |
| 8/9 | queryDecoration/MaterialPointDetailByOrderId:84-98 | 明细行;cast(paymentStatus as signed) 绕 tinyint(1)→Boolean 驱动映射坑(注释 :86) |

- **aid 倍数传递双路**(PaymentServiceImpl.queryDecorationActivity:797-815):paymentType=0(预付款)取该单 decorationactivity 第一条的 aid;paymentType=2(主材预付)取**公司默认活动** queryActivityForDefault(company,TYPE_MATERIAL)。装修单绑/解活动时 `updatePaymentForAid` **批量覆写历史款项行 aid**(DecorationActivityServiceImpl:137/174)→积分统计口径随绑解**追溯变化**。
- **量纲地狱(同名"积分"三种尺度并存)**:
  - MemberDaoImpl 全部 `×multiple/1000`(**multiple 千分位,1000=1 倍**);
  - `salesactivityitem` 兑换明细 `integral×num`,统计 ÷1e6(双 ×1000 抵消,sales.md §7);但 **SalesActivityDaoImpl.java:58 vs :68 总积分 ÷1e6 与 MemberDaoImpl.material ÷1000 并存——装修/销售两域积分天然差 1000 倍**(information.md §10 实锤);
  - decorationactivityitem num/money **×1e6 存储**(MemberServiceImpl:735-736,834-835)、`(int)` 强转 **>2147 溢出**;统计 ÷1e6;
  - cppoint point ×1e6(:769)但 addPointPayment 的 totalPoints=point×multiple **不乘 1e6**(:971)——分摊口径不一致;
  - **Arith 陷阱(根因)**:`Arith.DEF_SCALE=3`,mul(v1,v2)=v1×v2÷10³ → 代码里 `Arith.mul(x,1000000l)` **实际=×1000**(MemberServiceImpl:702,735-736,769,885,889,895-896,946,958,971);`Arith.div(v,1000)`=v÷1000×1000=**恒等无效运算**(PointAccountDaoImpl.java:46,56);`Arith.div(v,1000000)` 实际=÷1000(DecorationActivityItemDaoImpl:60,104,161)。**字面与行为全面背离,照抄必炸**。

## 4. 兑换链(外部商城回写,两版并存)

### 4.1 makeMemberOrder(旧版整单,MemberServiceImpl:687-778)
校验装修单存在(140013)→取净额(SQL#2/#4)→totalo=surplusPoint×1000(Arith 实际 ×1000,:702)→须已绑装修活动(160011)→逐件建 decorationactivityitem(atttype=1/**state=4 配送**/money·num×1e6/no=pointOrderId/expireTime=创建+validays/uniqueId)→**重算并整单覆写 cppoint**(dpoint=totald-amount 等,:754-770)→addPointPayment 按**创建时间序**摊到该单有效装修收款(status 1/2/applying,onetype=0,twotype=1,每笔不超收款额,:969-1056)写 cppointpayment→两条 cppointRecord(USERUP)。**无 no 防重、无状态校验**。

### 4.2 makeMemberOrderItem(新版单件,:781-851)
atttype=1 每单限 1 件(**count>1 才拒=off-by-one,已有 1 件仍放行可到 2 件**,:800-803)→no=pointOrderId 全局唯一(查后插非原子,:805-809 vs :848)→建物品 **state=2 已领+receiveTime**、type=1、**stateType=15 积分商城**(:832-833)、expireTime 用**外部传值**(:842-843)。**不动积分账户、不扣 AwardStock 库存**(仅按 no 查总部仓回填 asid :814-820)——真实扣减在外部商城。

### 4.3 撤销两版
- cancelMemberOrder(:853-899):物品**不校验状态全部置 state=1**(:873-881,可作废已核销!)/按 no 失效 cppointRecord+cppointPayment/回加账户 point/反向台账(ADDUP);receiveTime 被覆盖为撤销时间;
- cancelMemberOrderItem(:901-928):仅 state=0 可撤(140102)——同族两版规则不一致。

### 4.4 updateMemberGoods 商城商品目录同步(:931-967)
按 no upsert 总部仓 AwardStock(company 硬编码 0,:934/:948):查不到新建(unitPrice=point×1000[Arith 实际]/format=category/isPrefor/isPayment),查到则更新——**商城商品池强制落总部仓,分公司仓库不可达**。

- 全程**无支付环节**:纯积分,无支付网关调用。

## 5. 礼品实物链(Award 三件套)

- **AwardStock 礼品主档**(1.2 万行):no 编号/name/num 库存/unitPrice/storeroom(1 装修/2 销售/3 主材仓)/subtype 9 类/三开关 **isPrefor(算业绩)/isStock(算库存)/isPayment(计金额),0=算 1=不算**/status 0 可用 1 禁用;
- **扣减方全景 9 处全链竞态**:装修/销售/主材三域 ActivityItem ServiceImpl 各自领用/配送/退回直改,num 计算=get→内存算→setNum→update,**无乐观锁、无 `WHERE num>=x` 条件,check-then-act 可超卖**(DecorationActivityItemServiceImpl:178-191,274-287,373-382;AwardStockServiceImpl:146-155);仅 isStock=0(计库存)才扣;
- **AwardRecord 流水**(25.4 万行):type 0 入/1 出/2 盘盈/3 盘亏 × subtype 15 种(领取 5/退货 6/配送 7/采购 8/自购 11/福利 13/营销 15 等)× onetype 归属域;totalPrice=Arith.mul(unitPrice,num)(long×long,正面教材);
- **AwardPurchase 采购流**(1.12 万行):0 初始→1 采购(160057/160058)→2 入库→3 结算/4 失效(仅 0 可失效)/5 完成;**fapid 父单链分批入库**:入库须 surplus≥本次(160059),新建子单(fapid=父 apid,status=2),本次==父 surplus→父置 5,父 surplus-=本次;isStock=0 时**库存回加+记 awardrecord**(:193-202);下单推钉钉群(MESSAGE_DING_GROUP_AWARD_PURCHASE);并发入库丢更新;
- 手工出入库/盘点:AwardStockServiceImpl 出库:134-162/入库:166-190/盘点:194-221(160040-160042 受限码)。
- 【营造实现定案回写(第 87 次,A7)】**采购流 member_gift_purchase(V1603)+MemberGiftService 五方法**:老状态机原样回归(0 初始→1 采购中→2 已入库→3 已结算/4 已失效(仅 0)/5 已完成);**父单链分批入库**(老 fapid→father_id):每批新建子单(father_id=父,status=2,surplus=0),父 surplus **条件扣减 WHERE surplus>=本次(修老并发丢更新)**,扣后 0→父置 5;库存联动 is_stock=0→increase+流水(subtype=purchase);老钉钉群通知不迁(内核通知中心收件人模型未定)/老 activity/dept 申请标签不迁(remark 收敛);错误码 350030-350032(老 160057/58/59);PRD 见 docs/prd/addon-member.md §48。

## 6. decoration 侧核销(线下券/物品通用机制,与积分商城交叉)

- decorationactivity:0 初始⇄1 锁定可逆;**一单一活动**(count 查后插,DecorationActivityServiceImpl:116-134);绑定前置=装修单状态∈{0,定金,意向签,重签}(140003)+活动未过期;绑定即批量刷 payment.aid(见 §3);
- decorationactivityitem 11 态:0 初始/1 失效/2 已领/3 退货(回补库存)/4 配送(扣库存)/5 未退/**6 升级无写点=死状态**/7 消耗/8 抵现/**9 采购只写 record 不改 item=半死**/10 核销;type 0 券/1 物品;atttype 0 线下/1 线上;isAffirm 0/1/2;
- 人工核销 consume(:483-538):atttype=1 允许 state∈{0,2}(商城同步物品走 ERP 线下核销闭环)/expireTime 过期拒(140058)/**每单仅一次核销**(count 查后写,并发双核销,:505-508,140094)/state→10/写消息队列;
- **签约自动核销**:DecorationPaymentServiceImpl.makeDecorationPayment:309→校验线上物品恰好 1 件(:335-337)→banchDecorationActivityItem 原生 UPDATE 仅 state=0→10,**content 硬编码'签约自动核销'**(DecorationActivityItemDaoImpl:255-281);
- 客户确认 isAffirm:sendDecorationActivityItem 发微信小程序消息(:586-595)→affirmDecorationActivityItem(:599-662):验证码/phoneCode 换手机号/手机号须与单内客户一致(130115)/必须已绑 unionId(330017);0→1 客户确认→2 查收(state 离开{0,3,7} 时);
- 优惠联动:每次流转 `subDecorationForCut` 重算 decoration.couponCut/thingCut/factPrice=总价−券−物(:664-674;DAO 按 atttype 分支 state 聚合 SUM(money×num),统计状态集合三处不一致:count 用 {0,2,4,5,6,7,8,10}/3 参 amount 用 {0,2,4,5,6,10}/2 参 amount 仅 atttype 分叉两态);
- 每次流转写 decorationactivityrecord 流水。

## 7. payment 侧(残缺台账+死代码克隆)

- **paymentactivity 残缺**:表 payment.paymentActivity(paid/orderId/type 0 预付/1 装修/2 主材/3 销售/4 服务/pid/rid/aid/realMoney/multiple);**全工程唯一写点=服务款**(ServicePaymentServiceImpl:298,TYPE_SERVICE+活动倍数);TYPE_DECORATION/SALES/MATERIAL 无任何写点;校验方法 checkPaymentActivityForDecoration/ForSales **0 调用=死代码**+TODO(:114-115);读口径 SUM(realMoney×multiple)/1e6;`String.valueOf(null)=="null"` 缺陷——NULL 时 parseInt("null") 必炸(null 检查永不生效,PaymentActivityDaoImpl:34-38);
- **activitypayment=死代码**(克隆 VpaymentServiceImpl 逐行同构,同 160026/160033-35/160046 码):生产 **0 行从未投产**、UI 孤儿页面未接菜单、V 系才是现役(information.md §11 裁决);mno 会员号/category/brand/money/arid/status/expire(死字段只读 0);
- **paytran TYPE_ACTIVITY=5**:POS 活动款支付通道流水(addPaytran 前端透传 type 落初始态→collect 批量置 USED+挂 rid);V 系停办后休眠;随 V 系不迁。

## 8. 缺陷清单(精选 19 条)

1. **库存扣减竞态 ×9 处**可超卖/回补丢失(§4 全景,check-then-act 无锁);
2. no 唯一/绑活动/核销防重/consumePoint 余额四组**查后写非原子**,并发穿透;
3. **off-by-one 限件**:count>1 才拒,每单线上物品实际可 2 件(MemberServiceImpl:800-803);
4. cancelMemberOrder **不校验物品状态**可作废已核销商品,与 cancelMemberOrderItem(仅 state=0)同族不一致(:873-881 vs :917);
5. **Arith 陷阱**:mul(x,1e6)=×1000/div(v,1000)恒等/div(v,1e6)=÷1000——字面与行为全面背离(§3);
6. **两域积分差 1000 倍**:MemberDaoImpl.material ÷1000 vs SalesActivityDaoImpl ÷1e6 同业务双口径;
7. addMemberActivity 双哑弹:时间 String 静默吞→start/end 恒 null;validays 不拷→0→可用装修积分恒 0(§2);
8. 时间窗三处不生效(queryActivityForValid 不看时间/积分 SQL 无时段/无清零任务);
9. 死代码族:total* 两 SQL/check* 两方法/activitypayment 整模块/state 6·9 死状态/MemberAction.member 字段/注释掉的主材分摊(:1084-1088);
10. freezeMaterialPoint 硬编码恒 0(MemberServiceImpl:291);
11. 展示口径(#5 无支付过滤)≠可用口径(#2)——页面显示与可兑额度不一致;
12. cppoint 双写互踩:同步整单覆写 vs 手工 grant/consume read-modify-write;operatePoint 直接信任外部 surplusPoint 覆盖(:767);
13. 表名大小写混用:decorationActivityitem vs decorationactivityitem 两形态并存(Linux MySQL 大小写敏感隐患);
14. '签约自动核销'字面量 + 消息 JSON 手工拼接;
15. take-first:取 decorationactivity 第 0 条(:706,794)——表允许多条、绑定限 1 条、此处却赌第一条;
16. 统计状态集合三处不一致(§6);
17. 同义不同码:"装修单已签约"=140003 与 140096 两码并存;
18. EmemberPO.activity 实存 vaid 字段名误导;PointRecordPO.init 未设 status 侥幸正确;
19. emember.phone/vticket.phone **明文存储** vs customer 电话三代加密双轨并行。

## 9. 方林特性嫌疑(重写须剥离/参数化)

1. **company 列三形态不对称**(activity/awardstock/awardpurchase/cppoint 严格过滤;积分 SQL 无 company 过滤依赖 orderId 全局唯一)+**商城商品池硬编码总部仓 company=0**——单中心假设,多公司不可用;
2. companys 入参 **AES 加密公司串**(CompanyServiceImpl.encode/decode)——公司列表参数加密属公司内部约定;
3. 钉钉群通知(AwardPurchase)+微信服务通知(确认/注册/到场)双通道深度耦合——投递器须可插拔;
4. '签约自动核销'业务规则硬编码在 SQL 字面量;
5. emember 必须持有效活动票注册(160026)——线下会销获客模式专属;
6. 外部商城回写 .do 无独立鉴权描述(搭 RBAC/会话便车)——私有协议;
7. queryMemberForInfo 推荐关系查询(chanceRefer/originRefer/orderReferd 三路)混在 Member 族——实为老系统"老带新"营销,非积分域,剥离。

## 10. 错误码台账(老系统,同码同治理反面)

| 段 | 码(精选) |
|---|---|
| 16xxxx 活动/营销(本族主段) | 160011 活动不存在/160012 状态不可改/160026 活动票不存在/160033-35 活动款三连/160036-37 Vscore 不存在·不足/160038 库存不足/160040-42 出入库盘点受限/160043 物品不存在/160045-46 票未注册·未到场/160047 默认活动禁停/160051 票不可操作/160057-59 采购三连/160060 票已到场 |
| 33xxxx 积分/验证 | 330012 验证失败/330013 电子会员已存在/330017 装修单未绑定/330047 积分不存在/330048 已存在/330049 不足/330050 运营积分不能为空 |
| 14xxxx 装修物品 | 140003/140096 已签约双码/140013 单不存在/140058 过期/140094 重复核销/140101 不存在/140102 不可操作/140103 超限/140104 编号重复 |
| 其他 | 130021/130182/130183 客户电话·参数/130174 支付记录不存在/150081 款项活动不存在/210011 表单/230017 公司/240075 奖品不存在 |

## 11. 可迁移规则(收编进 yingzao 的口径)

1. **活动主档**:atttype(type 适用域)/multiple/validays/status/defaults 唯一默认语义必迁;🔴时间窗必须真生效(营造:活动 status+有效期双闸,建档必填 validays>0);去 company 挂 org;
2. **积分账户**:分项账(balance 按 pointType 分项)+流水(record 只追加,撤销写反向)+分摊映射(payment 挂款)三件套模型保留;🔴改事件驱动:装修/主材/销售收款确认事件 → member 记积分,**废除"实时 SQL 聚合 9 条"口径**(销售表无余额列的设计不收);运营积分=手工调整留痕;
3. **过期语义**:validays=收款后有效天数,核销时校验;V1 无清零任务(沿老系统,过期只拦核销),字段与 SQL 保留将来清零钩子;
4. **礼品实物链**:AwardStock(no 唯一/unitPrice/num/三开关 isPrefor·isStock·isPayment/status)+AwardRecord(只追加流水)+AwardPurchase(V2)迁移;🔴扣减必须原子 SQL:`UPDATE gift SET num=num-:x WHERE id=:id AND num>=:x` 检查受影响行数(修 9 处竞态);三开关语义原样保留;
5. **兑换**:兑换单+明细(no=外部商城单号一等公民,**幂等 upsert**,沿 supplier ext_no 模式);同步通道=服务账号经角色授权的 API(修"无鉴权 .do");每单限件→DB 约束+服务层精确计数(修 off-by-one);撤销须校验当前状态(修 #4);兑换落单与积分扣减、库存扣减同事务;
6. **核销**:核销一次性(唯一约束防重)+过期拦截语义保留;与 decoration 侧交互走事件(签约自动核销→监听装修签约事件,配置化 content,非硬编码);
7. **量纲**:🔴积分=整数,不再 ×1000/×1e6 存储(消灭三尺度);multiple 显式声明,**推荐千分位(1000=1 倍)对齐老系统运行行为**(MemberDaoImpl 口径),PRD 待业务确认;金额一律厘(全局红线);
8. **错误码**:新段 350000 独占(350000-350999),同码同消息+i18n 双语。

## 12. 不迁移清单(死族/会销产物/死代码)

- emember 电子会员+vticket 注册绑定链(🔴会销 2022-09 停办);
- vscore 微信积分孤岛;
- V 系六表全套+paytran TYPE_ACTIVITY 活动款通道;
- activitypayment+ActivityPaymentServiceImpl(生产 0 行死代码克隆);
- paymentactivity 残缺台账(唯一写点服务款,check* 全死)——积分记账改事件驱动后无此表;
- information.activityitem(3.6 万行早期"活动-赠品"配置,仅 information 内部读写,残缺功能)——information.md §16 已判归档删除;
- queryMemberForInfo 推荐关系查询(老带新,归属 addon-market 或独立营销功能,非 member)。

## 13. 待业务确认(按推荐口径执行,不阻塞)

1. **multiple 千分位(1000=1 倍)vs 万分比(10000=1 倍)**——推荐千分位对齐老系统运行行为,避免历史活动语义换算错;
2. 销售域积分 ÷1e6 与装修域 ÷1000 的 1000 倍差——老数据如何换算/是否承认销售积分天然"小"——推荐:营造统一一套倍数,不保留域间差;
3. AwardPurchase 采购流 V1 随礼品走 or V2——推荐 V2(纯内部采购流,先保兑换闭环);
4. decorationactivityitem 线下券/物品(老系统装修单优惠组成部分)留 decoration 域,member 只做"线上兑换物品"——推荐 V1 如此,核销交互走事件;
5. 运营积分(opoint 手工调整)V1 是否保留——推荐保留(商城外部积分对账入口);
6. 外部积分商城系统本身重写排期(不在 yingzao 范围,本插件只供同步 API+台账)。

### 关键文件索引

- `customer\dao\impl\MemberDaoImpl.java`(9 条积分 SQL)/`customer\service\impl\MemberServiceImpl.java`(商城回写六方法)/`customer\service\impl\PointAccountServiceImpl.java`(账户)/`customer\service\impl\EmemberServiceImpl.java`(会销注册)/`customer\po\PointAccountPO.java`
- `information\service\impl\ActivityServiceImpl.java`/`AwardStockServiceImpl.java`/`AwardPurchaseServiceImpl.java`/`information\dao\impl\ActivityDaoImpl.java`/`AwardStockDaoImpl.java`
- `decoration\service\impl\DecorationActivityServiceImpl.java`/`DecorationActivityItemServiceImpl.java`/`decoration\dao\impl\DecorationActivityItemDaoImpl.java`
- `payment\service\impl\PaymentActivityServiceImpl.java`(残缺)/`ActivityPaymentServiceImpl.java`(死)/`ServicePaymentServiceImpl.java:298`(唯一写点)
- `utils\Arith.java`(DEF_SCALE=3 陷阱根源)

## 14. 营造实现定案回写(第 68 次巡检补,细节见 docs/prd/addon-member.md 实施注记)

- **addon-member V1 全量落地**(第 43/44 次,V1601 八表,码段 350000):活动时间窗真生效+validays 必填>0(修 §2 双哑弹)/积分事件驱动记账(消费 PaymentConfirmed/Refunded,废 §3 实时聚合 9 条)/礼品原子扣(修 §5 九处竞态)/兑换 no 幂等 upsert+DB 限件(修 off-by-one)/核销一次性+过期拦截;**积分=整数唯一换算点**(款项厘×千分位倍率÷1000 HALF_UP,灭 §3 量纲地狱);
- **V1.1**(第 52 次):发货登记投产(到店自提与寄递并行);**过期清算钩子**——§11-3「V1 无清零任务」演进为组织开关 expire_clear_enabled(默认关),清算=过期正向批次逐行生成清算负行(src_id=原正行 id 作 uk 幂等锚),balance 向 available 收敛;
- **清算调度迁 yz_plan 内核**(第 63 次,information.md §16「Plan 必迁」的首个业务消费方):@Scheduled 硬调度废除,链式续期模型(执行同事务挂下一轮 03:00 计划,biz_key 按日幂等),死信可见可重试+重启对账自愈;
- 签约自动核销=SignAutoConsumeConsumer 监听 DecorationSignedEvent,说明文案组织配置(修 §6 硬编码 content)。
