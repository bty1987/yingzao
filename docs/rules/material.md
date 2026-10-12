# material 域 L2 规则清单(材料管理 + 商品档案 + oods 三族)

> 提取自 Rhadamanthus 老系统 2026-10-11(第 13 次夜跑)。范围 = `com.fanglin.material`(35 文件/5 ServiceImpl,独立 catalog `material`)+ `com.fanglin.data.material`(90 文件/15 ServiceImpl,PO 集中 `com.fanglin.data.po`,catalog `data`)。三路子代理并行深读 + 生产只读库复核一次(information_schema + material 库聚合)。**本域是第一个"考古层活域"——新增已死、存量结算长尾仍活,裁决为拆分归并、不建 addon-material(§13/§14 待业务确认)。**

## 0. 模块定性

1. **material 主链 = 1.x 代"主材子订单"销售域(品牌专卖店模式)**:五表 material/materialitem/materialrefund/materialbalance/materialactivityitem 全在独立库 material,单号 `M{公司短名}{yyMMdd}{流水}`(`MaterialServiceImpl.java:241-244`,流水计数器**借用装修单计数器** TYPE_DECORATION_ORDER_INT,`:242`)。与 sales **同源不同代**:两域 ServiceImpl 文件头同为 `2014-10-7 baity`,payment 侧残留旧包名 `com.fanglin.service.impl.materialOrder`(`MaterialPaymentServiceImpl.java:3`);sales 是 2.0 重写版(goods 商品库+supplier+导购+ctype/cvalue),material 是品牌维度(BrandPO→bid+brand 字符串双存,`MaterialPO.java:148-150`,材料员 materialer)。
2. **生产考古层证据(material 库聚合,2026-10-11 复核)**:主单 43.0 万,逐年 2015:6.7万→2016:8.6万→2017:14.6万(峰值)→2018:13.5万→**2019:3132→2020:9→2021:1→2022 至今 0**——新增死亡 5 年+;但 `materialbalance` 最近一笔结算 **2026-08-22**,status=0 预结算在途 31.0 万行/厂结 4362/已结 12.4 万;`payment.materialpayment` 59.1 万行,最新同样 2026-08-22 15:48:04(与结算同一动作时刻)——**存量收口长尾仍在生产运行**。
3. **与 sales 裁决(sales.md §13-15 的答案)**:material 为旧代遗留,重写以 sales 为主口径;material 无 ctype/cvalue(sales 专属),有 coupon/venderMaling/maling 三承担方(sales 亦有,maling=商场让利/venderMaling=厂商承担/coupon=公司补贴券,补款恒等式 `total==coupon+venderMaling+定金+补款`,`MaterialPaymentServiceImpl.java:353-360`)。
4. **data.material = 三族**:商品档案族(goods/color/format/price/goodspackage/goodsRecord/accessoriestype/materialmodel/remeasureformat,已定案归 addon-basedata)、oods 族(noods 定材/Soods 选材/Aoods 辅材)、消费方 11 模块 66 文件(inventory 19/data 17/sales 7/imports 7/quotation 6/approval 3/construction 2/communion 2/seiya/personel/balance 各 1)——共享基础数据实锤。
5. **生产体量(data 库)**:goods 70.8 万(此前档案记 32.5 万已过时)/color 26.7万/format 29.7万/price 30.6万/materialmodel 4.6万/aoods 4.9万/aoodscharge 2877/noods 1680/soods 12.8万/soodsitem 31.6万/normood 5.0万/accessoriestype 42/goodspackage 393;**cormrice 0 行(2026-10 新表待上生产)/remeasureformat 0 行(回尺指标零落地)/material.activityitem 0 行(活动赠品零落地)/material.payment 0 行**。

## 1. material 主单与明细(考古层状态机)

- **material.status**(short,`MaterialPO.java:80-98`):0初始/1删除/2退货/3预结算/4?购货/5结算/6全退/7倒单/8作废/9完结。写点:1=deleteMaterial(`MaterialServiceImpl.java:573`)、3=preBalanceMaterial(`:461`)、5=postBalanceMaterial(`:538`)与 postMaterialBalance(`MaterialBalanceServiceImpl.java:176`)、8=退定金(`MaterialPaymentServiceImpl.java:1162`)、9=passBalanceMaterial(`MaterialServiceImpl.java:399`)。**2/4/6/7 零写点=死枚举**(全退/倒单仅存在于审批文案 `ApprovalMaterialRefundServiceImpl.java:162`,不回写主单)。
- **depositStatus/fullStatus 双财务位**(共用 0初始/1提交/2已收/3确认/4审批中,`MaterialPO.java:100-108`):定金 1=生成款(`MaterialPaymentServiceImpl.java:324`)→2=collect(`:535`)→3=confirm(`:1009`);补款审批 4=submit(`ApprovalMaterialFullPaymentServiceImpl.java:82`)→1=approve(`:148`)/0=refuse(`:174`)。
- **materialitem.balanceStatus**:0/1预结算/3结算(**跳 2**,`MaterialItemPO.java:47-51`;写点 `MaterialServiceImpl.java:426,502`);item.status/paymentStatus/refundStatus 三列零写点死列。
- **守卫**:删单/优惠券/满减须 status=0∧fullStatus=0∧balanceStatus=0(`:555-569,345-355,373-383`);预结算 DAO 层另须 fullStatus=3 已确认(`MaterialDaoImpl.java:83`)。
- **prePoint 双语义**:CALCTYPE_BASE(0)时=底价金额、CALCTYPE_POINT(1)时=千分点(`MaterialItemServiceImpl.java:444-451`)——同一字段两种量纲,消费方必须按 calcType 分支。

## 2. materialrefund 退款审批线(approval 域互作)

- 状态(`MaterialRefundPO.java:17-23`):0=审批通过建行(`ApprovalMaterialRefundServiceImpl.java:287`,批次 ident 用 `new Random().nextInt(9999)` `:264`)→2=退款收款 SQL 直改(`MaterialPaymentServiceImpl.java:871`)→3=确认(`:1000`);**1撤销零写点**。
- 审批互作:提交即**预扣** item.num/total 与主单 total/distotal(`:184-218`),refuse 回加(`:394-409`),approve 另生成 type=4 退款款(`:244-258`)并按结算状态补 materialbalance 行(1预结算退货/2结算退货,`:338-351`)。
- 退货不回写主单 status(与 sales「退货不改主单」同判)。

## 3. materialbalance 结算线(与 sales 三段式同构的前身)

- 状态 0预结算→1厂商结算→2完结(`MaterialBalancePO.java:48-52`;`MaterialBalanceServiceImpl.java:113,133,172`);vbalance(厂结值)/post(结算值)/difference 与 sales 的 pbalance/vbalance 同族。
- **双轨旁路**:`postBalanceMaterial`(`MaterialServiceImpl.java:481`)不经 balance 行直接把主单置 5/2,该单 balance 行**永久停留 0**——两条结算轨道并存,对账必裂。
- **量纲注记(1e6 勘误传播,B 类联动#5)**:materialbalance/materialpayment 是 material 库内**自闭合**结算线,不经 balance 域选材聚合(其聚合除数 10^6 的勘误见 balance.md——"DIV 1000"口头先例系按彼时列量纲);存量 43 万单修数迁移时按各列实际量纲逐列重推,勿套用任何统一除数。
- 利润公式:预结算=套餐 total×prePoint / BASE(price-底价)×num / POINT(price-salePrice×(1000-point))×num,再减 maling(`MaterialServiceImpl.java:437-458`);结算差额 post-pre(`:512-529`,注释写"×0.5"代码没有);退款利润镜像(`ApprovalMaterialRefundServiceImpl.java:297-331`)。**difference 双语义**:预结算行=总价-利润(`:471`)、退款行=利润本身(`ApprovalMaterialRefundServiceImpl.java:344`)。

## 4. materialactivityitem 活动赠品(生产 0 行,整链零落地)

- state 0初始/1失效/2已领/3退货/4配送/5未退/6升级死值/7消耗(`MaterialActivityItemPO.java:27-41`):add 建行(receiveTime 创建时即写,`MaterialActivityServiceImpl.java:99`);abate 0→1;receive 0→2、delive 0→4(**扣 information.awardstock+写 awardrecord**,跨域直写 `:191,195,235,299,304`);unrefund 2→5;refund {2,4,5}→3 回加库存;expend {2,4}→7。
- 生产 0 行 → 营造不迁,积分/赠品统一归会员营销域(sales.md §7 同判)。

## 5. payment.MaterialPayment = 第三条收款线(独立于装修款/预付款)

- `payment.materialpayment` 类型 0-7=初始/预付款/定金/补款/退款/补款退款/券退款/定金退款(`MaterialPaymentPO.java:43-57`),状态 0待收/1已收/2已确认,收据挂 `payment.materialreceipt`(collect/confirm 两段);生产 59.1 万行,2026-08 仍在发生。
- **跨模块直连(payment 绕 Service 写 material)**:payment 直接 import MaterialDao/MaterialItemDao/MaterialRefundDao(`MaterialPaymentServiceImpl.java:36-40`),写 MaterialPO 的 deposit/full 字段(`:323-325,387-392,543,767,864,869`)、裸 SQL 改 materialrefund(`:871`)、读 item 汇总校总额(`:362`);material 侧不回写 payment(单向)。跨模块写表面第三案(sales/inventory 之后)。
- 预付款划付借道 advancepayment,uniqueid="M"+mrId(`:597-599`);账户按品牌分 materialBalance/zhenglinBalance(**"正林"第二品牌账户硬编码**,`MaterialPaymentPO.java:81`)。
- **中文支付方式常量**(`MaterialPaymentPO.java:59-71`)与 company int 冗余到每笔款且由前端覆盖(`:554,773`)。

## 6. 商品档案族(归 addon-basedata,档案章节素材)

- **GoodsPO 全字段**(`data/po/GoodsPO.java:57-121`):gid/gpid(→communion.goodsPool 商品池)/no/name/alias/pinyin/barcode/category(品类名字符串)/bid(FK communion.brand)+brand(名称快照双存)/measurementUnit/purchase/selling/confine(进价/售价/限价,厘)/pieceNum(件数×1000)/atid(FK accessoriestype)/type(0商品/1套餐)/zerokind~thrkind(四级品类挂列 FK goodsKind)/path/isShow/series/elevel(环保等级)/material/quality/color/format/model(规格描述串)/status/company。
- 启停:`updateGoodsForStatus` 直落 VO 值无校验(`GoodsServiceImpl.java:388-401`);0=启用/1=停用。
- **查重缺口**:仅 add 按 company+name 查重且只查启用行(260046,`GoodsServiceImpl.java:263-267`);**no/barcode 全程无查重**;updateGoods 改名不重查重(`:364-384`)。
- **价格体系 PricePO**(`PricePO.java:33-51`):pid/gid(裸 long 无 FK 映射)/name(价格体系名=等级价渠道价)/purchase/selling/confine/ctype+cvalue(与 sales CTYPE 0=保留价/1=点位同语义族)/status;无时间有效性;add 无查重;delete 物理删(260016)。
- Color/Format=goods 1:N 明细,无 company 随主表;addColor 有查重(260034)、**addFormat 无查重**(`FormatServiceImpl.java:86-97`);方法名拼写错误 `updataColorForStatus`(`ColorServiceImpl.java:107`)。
- **MaterialModel=独立主材型号库**(4.6 万行,与 goods **无任何外键**):mmId/no/name/brand FK+双冗余串+单价/进价+discount+calcType(0=底价/1=点位)+base/point(`MaterialModelPO.java:41-49`);page 按 BrandUserRight 品牌权限过滤(180020),ForAdvance 变体不做品牌校验;新建默认停用;material 域点位计算口径来源。
- **GoodsPackage=套餐明细**(393 行):pid 实为主商品 gid(**注释"被绑商品ID"与语义相反**,`GoodsPackagePO.java:30`);无 status 物理删(260062);成员不可再为套餐(260043)。
- **GoodsRecord=商品使用流水**(type 0商品/1选材/2辅材):无状态机无闭环,goods 可空不校验(`GoodsRecordServiceImpl.java:110-115`)→ 营造砍或并入操作日志。
- **RemeasureFormat=回尺指标(生产 0 行)**:rfid/name/type/value 挂 brand.bid,被 quotation 回尺项消费(130140)——归 addon-quotation,不进基础数据。
- **AccessoriesType=按工种划分的辅材类别**(42 行):atid/name/type/sort/status+workType FK;queryAccessoriesTypeByWtid 的 wtid=工种 id。
- **goodsKind 四级品类树**:gkid/no/name/level/parent 自引用,无 company 全公司共享;goods 侧四级挂列写死层级数。
- **商品池级联复制**(方林运营模式):gpid 指向 communion 池且位与开关开→批量复制池的 color/format/price/图纸 INSERT..SELECT(`GoodsServiceImpl.java:329-357`)。
- **inventory.color/format/price 三张镜像表**(PO=data/po/ColorGO/FormatGO/PriceGO,catalog=inventory):代码只读无写入点,靠库外触发器同步——双源漂移风险,营造砍。
- **imports 七导入器=脏值源**:GoodsImport 零校验零查重,pinyin 存 `List.toString()` 带方括号格式与 addGoods 单串格式不一致(`GoodsImportService.java:67` vs `GoodsServiceImpl.java:286`);PriceImport 不校验 gid 存在;Color/Format 导入不校验 gid。

## 7. oods 三族(noods 定材/soods 选材/aoods 辅材)

| 族 | 定性 | 关键外键 | 归属建议 | 生产行数 | 2026 活性 |
|---|---|---|---|---|---|
| noods | 定材包(集团 pnid 两级:0=集团行/自建,>0=引用行存集团快照;仅浮动量 surplus 引用行可改) | nid/pnid | addon-basedata | 1680 | 高(08-28 pnid 改造 c203cc88d) |
| soods/soodsitem | 选材:报价项上可选商品级备选(qiid→quotationitem 实证 `SoodsItemPO.java:67-71`);soods.type=2=辅材型选材 | sid/qiid/gid | addon-quotation(写侧在报价,copySoods 是报价项操作) | 12.8万/31.6万 | 中(08-31 选材导入) |
| aoods(+Charge/Cost) | 辅材档案:atid 辅类/gid 商品/nid 定材/cnid 节点/定额与库房双单位(normunit/normnum/storeunit/storenum)/bompany 归属公司 | aid/atid/nid/cnid | addon-basedata(物料档案)或 addon-inventory(消费方) | 4.9万/2877/Cost | 高(Charge 2026-03 新模块 a13f8811b) |
| cormrice/cormood/cormtime | 共用项(整装)规格级三定额:工费/材料/工时,2026-10 统一挂 qfid(报价规格) | qfid | addon-basedata(与 quotation 规格模型对齐) | 0(新表待上) | 极高(10-06/10-09) |

- **Noods 后端不设防**:updateNoods copyProperties 无 ignore,pnid/company/status 可被 VO 覆写,集团/公司控权全靠前端(`NoodsServiceImpl.java:121`);queryNoods 查不到静默空 VO 不报错(与 updateNoods 的 130118 不一致)。
- **Soods 价差闸**:addSoodsItem 校验 soods.selling>报价项 unitPrice 则 130037(`SoodsItemServiceImpl.java:134`);gid+qiid 查重 130032;**copySoods 不校验源目标存在性与公司归属**(`SoodsServiceImpl.java:147-153`),deleteSoodsItem 裸奔(`SoodsItemServiceImpl.java:156-161`);copySoods SQL 内置价格闸 `s.selling<=tqi.unitprice`(`SoodsItemDaoImpl.java:99-106`)。
- **Aoods**:bompany(辅材归属公司)/company 双公司字段语义零注释靠 commit 口传(5b3684aa5);copyAoods 仅当目标公司 aoods 为空才执行且 company 列照抄源行(`AoodsServiceImpl.java:324-335`);pageAoods 的 companyType 任意属性名透传进 criterion(`AoodsDaoImpl.java:26-30`,注入面);AoodsCharge=辅料单费用模板(被 occessories2create 消费,价格注释"分"实为厘);AoodsCost=辅材-施工成本限值(ccid→ConstructionCost),**后端无消费者**。

## 8. 金额与数值口径

- 全链 long 厘(×1000;`Arith.div(referMoney,1000.0)` `MaterialPaymentServiceImpl.java:608,900`);coupon/maling/venderMaling 为 int 厘。
- **Arith DEF_SCALE=3 使 mul(a,b)=a×b÷1000**(`Arith.java:26`)——点位 permille 恰好复用厘标度,这是"÷1000 隐式魔法"的算术根源(与 sales 量纲不自洽同根)。
- aoods/AoodsCharge 金额同为厘但 PO 注释写"分"、页面标签写"元"(`AoodsChargePO.java:46-48` vs `aoodsCharge.js:188`)——注释漂移三连。
- 承担方语义:coupon=公司补贴券(降客户应付)/venderMaling=厂商承担/maling=商场让利(仅预结算扣利润=公司收益减项);**difference 字段双语义**(预结算行=总价-利润,退款行=利润本身)。

## 9. 跨模块写表面全景(营造事件化依据,第三案)

- **入向(谁写 material)**:payment 绕 Service 直连三 DAO 写主单财务位+裸 SQL 改 refund(§5);approval 提交预扣/refuse 回加/approve 补 balance 行(走 material Service,合法);exports/finance 只读。
- **出向(material 写谁)**:common.servicerecord(`MaterialServiceImpl.java:329`)、information.awardstock/awardrecord(`MaterialActivityServiceImpl.java:191,195,235,299,304`)。
- **data.material 出向**:AoodsServiceImpl 反向依赖 decoration(DecorationDao)与 system CompanyServiceImpl.decode——**基础数据模块耦合业务单据**,军规 2 反面教材(`AoodsServiceImpl.java:29-30,87,102,137,258`)。
- **goods 族被 11 模块消费**(§0-4)——共享基础数据的入向全景,basedata 事件契约的受众清单。

## 10. 错误码段全景

- **130xxx(data 域段)**:130032/130033/130035/130037(选材)/130029/130118/130140/130168/260056(辅材用 260 段混用);**漂移实锤**:addSoodsItem 用 200038(选材单段)报"选材项不存在"而同对象 Soods 用 130033(`SoodsItemServiceImpl.java:131` vs `SoodsServiceImpl.java:117`)。
- **180xxx(material 型号段)**:180002/180016(兼作入参空,`MaterialItemServiceImpl.java:130-132`)/180020/180022(兼作状态错,`MaterialItemServiceImpl.java:220-222`)。
- **150xxx(payment 共用段)**:150008 兼作金额不符(`MaterialPaymentServiceImpl.java:521,731`)与状态不符(`MaterialServiceImpl.java:275`);150021/150036 同状态校验两轮不同码(`MaterialPaymentServiceImpl.java:724,751`)。
- **260xxx(data 公共段)**:260004/014/015/016/020/034/041/043/046/056/062——印证 inventory.md §9「260 段实际是半个商品公共段」。

## 11. 缺陷清单(三路合并精选 18 条)

1. **恒真守卫**:receive/delive/refund 先硬写 isStock=0 再判 `getIsStock()==0`(`MaterialActivityServiceImpl.java:171→181,214→225,282→289`);refund/delive 的 `add 后 num<0` 恒假(`:291-294,227-230`)。
2. **量纲/累加 bug**:updateMaterialItemForPackage 先 setNum 再用新值自差→discountTotal 恒 0,且循环变量只留最后迭代,多行更新总额必错(`MaterialItemServiceImpl.java:276-290`)。
3. **NPE**:addMaterial 未判 decorationPO 空即 getGrade()(`MaterialServiceImpl.java:246-248`);queryGoodsByBarcode/queryGoodsByGId 未判空即 ptv()(`GoodsServiceImpl.java:216-230`);queryMaterialActivityItem 把 long 当源对象 copyProperties(`MaterialActivityServiceImpl.java:69`)。
4. **静默失败**:postBalanceMaterial 守卫不满足裸 return(`MaterialServiceImpl.java:497`);passBalanceMaterial/updateMaterialItemForPrePT 循环内静默跳过(`:398`,`MaterialItemServiceImpl.java:334`);updateMaterialModel 查不到静默跳过(`MaterialModelServiceImpl.java:155-163`);pageGoods 无品牌权限时空列表无解释(`GoodsServiceImpl.java:124-141`)。
5. **信任前端**:balance 的 vbalance/post 直接收 VO 值(`MaterialBalanceServiceImpl.java:113,151`);coupon/maling/venderMaling 直写(`MaterialServiceImpl.java:357-358,385-386`);updateGoods 的 no/company/status/brand 可被 VO 覆写(`GoodsServiceImpl.java:372`);addGoods 允许 VO.company 解密覆盖会话公司(`:259-261`)。
6. **竞态/幂等**:collectMaterialPayments 两轮循环无版本控制(`MaterialPaymentServiceImpl.java:716-780`);addMaterialPaymentForDeposit 先查后插无唯一约束(`:299-307`);活动物品动作可重复提交重复扣库存。
7. **事务内外部调用**:decorationService.sendMessage 收款事务内(`MaterialPaymentServiceImpl.java:608,786,900`);messageService.sendMessage 审批事务内(`ApprovalMaterialRefundServiceImpl.java:220`)。
8. **死代码**:finance.buildMaterialFinance 全仓无调用方(`FinanceServiceImpl.java:424`)——主材收付款从不生成凭证;审批类型 7"场外退款"常量无实现(`ApprovalPO.java:41`);queryColorForEnableByGids 有 DAO 无调用方;materialactivityitem/material.payment/cormrice/remeasureformat 四表生产 0 行。
9. **死过滤器**:pageGoodsForAdvance 的 atttype 过滤在 DAO 查询执行之后才 add 进 filters,永不生效(`GoodsServiceImpl.java:147-152`)。
10. **复制粘贴过滤 bug**:Price/Color/Format 三 DAO 的 queryFilterExcute 三分支全判 `"goods".equals(field)`,后两分支不可达(`PriceDaoImpl.java:73-79` 等)。
11. 查重族缺口:goods no/barcode 无查重/addFormat 无查重/updateGoods 改名不重查重(§6)。
12. Noods update 全字段可覆写+query 静默空(§7)。
13. copySoods/copyAoods 无归属校验;AoodsCost 后端零消费者(§7)。
14. pageAoods companyType 任意属性名透传(§7,注入面)。
15. 错误码漂移五族(§10)。
16. 金额注释漂移(分/厘/元三说不一致,§8)。
17. imports 导入器脏值(pinyin 方括号/不校验 gid,§6)。
18. 镜像表库外同步无代码契约(§6)。

## 12. 方林嫌疑机制级汇总

1. `company==1` 硬编码等级升降(`MaterialServiceImpl.java:248-252`);grade "C"→"B" 魔法串。
2. company int 冗余到每笔收款且由前端覆盖(§5)。
3. 中文支付方式常量(`MaterialPaymentPO.java:59-71`);"正林"第二品牌账户 zhenglinBalance 硬编码(`:81`)。
4. ds/material.json **同文件两套 status 口径互相矛盾**(数组 2=删除 vs map 2=退货);type 枚举黑话"员工自购/老总关系/场内销售场外"。
5. 流水计数器借用装修单计数器(§0)。
6. 商品池级联复制+BrandUserRight 品牌数据权限+ForAdvance 双通道=方林品牌运营模式(§6)。
7. bid+brand 双存名称快照(品牌改名档案陈旧);goodsKind 四级定长挂列(§6)。
8. aoods bompany/company 双公司字段语义口传(§7)。
9. 旧包名 materialOrder 残留;material 模块 CLAUDE.md 字段描述与真实 PO 不符(deliverTime/paidPrice 不存在)——**老文档不可信案例**。
10. Noods 集团/公司两级控权全在前端(§7)。

## 13. 营造落地映射建议(addon 拆分终表,替代 addon-material)

| 老件 | 去向 | 处理 |
|---|---|---|
| material 五表主链 | **不建独立 addon** | 新增已死 5 年,以 addon-sales 为唯一主材销售口径;存量 43 万单读兼容/一次性归档(V2 数据迁移议题) |
| materialrefund 审批线 | addon-sales(退货线)+ 内核轻量审批 | 老审批预扣/回加模式→审批事件+业务侧状态机(sales 退货同构) |
| payment.MaterialPayment 第三收款线 | **addon-payment 款项类型字典** | 59 万行仍活,并入款项单类型(材料款),废除跨模块直连(事件化) |
| materialbalance 结算线 | addon-sales 结算三段式 | 与 salesbalance 同构归并;双轨旁路是反面教材(结算只留一条轨道) |
| materialactivityitem | 不迁 | 生产 0 行;积分/赠品归会员营销域(sales.md §7 同判) |
| goods/color/format/price/goodsKind/goodspackage/accessoriestype | **addon-basedata 商品档案章节**(本晚已增补 PRD) | 查重下沉 DB 唯一约束/砍镜像表/砍池级联(方林特性)/多价格体系保留 |
| materialmodel 型号库 | addon-basedata(与 goods 合并或独立型号价目,待确认) | calcType 底价/点位口径随销售域走 |
| noods 定材 | **addon-basedata** | pnid 两级保留泛化;后端补集团/公司控权 |
| soods/soodsitem 选材 | **addon-quotation** | qiid 挂报价项;价差闸/copy 语义保留;selection 读侧归 addon-sales |
| aoods/aoodscharge/aoodsCost 辅材 | **addon-basedata 或 addon-inventory(待确认)** | 档案归 basedata(与 goods 对称)vs 消费方归 inventory;Cost 施工成本联动若成立随 construction |
| remeasureformat 回尺指标 | addon-quotation | 生产 0 行但模型简单可低成本保留 |
| goodsRecord 使用流水 | 砍或并入通用操作日志 | 无状态无闭环 |

## 14. 待业务方确认清单(8 条)

1. **addon-material 改判不建**(docs/01 过堂表 material 行建议改为"拆分归并:见 docs/rules/material.md §13")——主链不迁、收款线归 addon-payment、档案归 basedata,确认后回写过堂表;
2. material 存量 43 万单(31 万预结算在途,结算长尾 2026 仍在走)的收口策略:保持老系统跑到自然完结(建议)vs 一次性数据迁移进新系统;
3. payment.MaterialPayment 并入 addon-payment 的形态:款项单加"材料款"类型值(建议)vs 独立子域;
4. aoods 辅材档案归属:addon-basedata(与 goods 对称,建议)vs addon-inventory(贴消费方);AoodsCost(辅材-施工成本限值,后端零消费者)是否随施工成本域重做;
5. materialmodel 型号库与 goods 的关系:合并为同一商品域(建议,补外键)vs 保留独立"型号价目"模型(点位计算口径在用);
6. soods 选材写侧归 addon-quotation、读侧(选材单)归 addon-sales 的两侧拆分确认;soods.type=2"辅材型选材"语义是否保留;
7. noods 集团/公司两级(pnid 引用+快照+仅浮动量可改)在单层组织下的等价形态(组织=公司后,"集团定材"以平台级字典承载?);
8. material 优惠三承担方(coupon 公司券/venderMaling 厂商承担/maling 商场让利)与 sales 同名字段口径对齐确认——两代字段同名但承担方恒等式是否完全一致(sales.md §13-13 同题)。
