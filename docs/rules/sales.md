# L2 规则清单 - 主材销售域(addon-sales)

> 提取自:`D:\ERP\Rhadamanthus\src\com\fanglin\sales\`(141 个 Java 文件 / 18 个 ServiceImpl 共 5038 行,三路并行深读:销售单主链 / 结算·退货·采购线 / 外销·活动族)
> 提取日期:2026-10-11(自动任务·第 11 次运行)
> 🔴 每条规则标注【方林嫌疑】:行业共性 vs 方林实现习惯,需业务方确认后决定去留
> 老系统销售域错误码分裂三段:主体借用 **260xxx(Inventory 段)**(260021/260022/260045/260084/260085…),后加的满意度/退货/外销在 **370xxx**(370001-370033),外销明细另开 **400xxx**(400001-400022,AI 增量自开段)——同义双码三处并存(370001 vs 260021 销售单不存在/370021 vs 400001 外销单不存在);营造 addon-sales 建议 **300000** 段、迁移 **1100+**(290000/900+ 已被 addon-example 示范占用,market 已占 280000/1000+;以实施顺位为准)

## 0. 模块定性

1. **装修单附属的主材销售域**:销售单永远挂装修单(orderId 字符串冗余,无 FK),链路=门店开单→客户确认→收款(payment 域)→出货挂单(inventory 域)→配送安装→厂商结算→满意度回访→完结;它不是独立电商,删它=砍掉装修单的主材变现环节;
2. **至今高频生产维护(六域中与 payment 并列的活域)**:113 次提交,最新两笔恰是行业规则级修复(8.3.030 退货结算防重 370033、空配置套装降级 eeac3c0c1)——不是遗迹,按活业务提取;
3. **跨模块写表面最大域**:44 个模块外文件 / 7 个外部模块(payment/approval/inventory/balance/kingdee/salary/decoration)直接读写 sales 表字段——inventory 直接加减明细五计数、approval 直接改主单状态轴、payment 直接改 pompany/款项状态(§9 全景表),零防腐层【公司内系统特征,营造必须事件化】;
4. **四空壳+三死端点**:SalesManifestServiceImpl/SalesPurchaseServiceImpl 零实现(真实逻辑在 inventory)、SalesDO 同表死 PO、死端点 querySalesForGrade/balanceSalesDeliveryItem/upgradeSalesActivityItem(前端注册后端无映射);
5. 22 张表 catalog="sales";53 个端点/9 个 Action。

## 1. 销售单主单模型与四轴状态机(SalesPO)

- **身份字段**:sid(自增)/no(单号)/orderId(装修单冗余);**三公司字段【方林嫌疑】**:company(创建公司=归属)/dompany(设计公司,前端传 AES 串,CompanyServiceImpl.decode 解密 :240-247)/**pompany(收款公司,可被收款动作改写**:原值=总部 0 时,收款后改写为实际收款公司,SalesPaymentServiceImpl.java:728-729、808-811);
- **人员六元组**:salesman/dept/region(建单时从装修单设计部门取,SalesServiceImpl.java:338-339)/guider/guideDept(导购)/instorer(驻店)/supplier(厂商,列 ocuid);brand+category 快照(从 brand 拷贝);
- **四轴状态(语义正交,行业好设计,保留)**:

```
status 主单轴(常量缺 1!SalesPO.java:30-38,0/2/3/4/5):
  0 初始 ◄─updateSalesForAdvance 管理后门重置(:584)
    ├─terminalSales(:774,守卫=四轴全初始)→ 4 废单(写点全在 payment 退定金:1114/审批 type18:155)
    ├─finishSales(:807,守卫=status=0+fullpayStatus=2+无未完配送项)→ 5 完结
    └─审批终审写:2 全退/3 倒单(ApprovalSalesRefundServiceImpl.java:333,驳回回 0 :389)【主链自身无写点】
balanceStatus 结算轴(0 无结→1 预结→2 厂结→3 结算→4 完结;写点全在结算线 §4)
depositpayStatus/fullpayStatus 款项轴(-1 未录→0 已录→1 已付→2 已确认;3=审批中仅 fullpay 有;
  写点全在 payment 域:录单 0/付 1/确认 2/退款重置;unpaySales 免付款直接双置 2)
isAffirm 客户确认轴(0→1;affirmSales :740,验证码+手机号三重校验,但 :697 仅当两参同时非 null 才校验→单传其一即绕过【缺陷】)
```

- **守卫组合要点**:改单/改券/改补/改归属仅 status==0(SalesServiceImpl.java:465-551);明细增改删=status==0 且 fullpayStatus==-1(**全款确认后明细冻结**,SalesItemServiceImpl.java:87-282);结算改价=fullpayStatus==2 且 balanceStatus==0;免付款=四轴全初始+有明细+无任何款项+补假收款单(:593-670)【方林嫌疑:线下收全款豁免通道】;**配送生成=status∈{0,5} 且 fullpayStatus∈{1,2}——付了钱哪怕未确认就能配送**(:307-313);
- **单号【方林嫌疑】**:`公司短名+yyMMdd+4 位序号`,序号=公司×按日 counting(type=52),offset/increment 可配→**流水可跳号、跨公司同日可撞**,查询靠 maxResults(1)(SalesServiceImpl.java:328-332,SalesDaoImpl.java:166-168)。

## 2. 明细/套餐/挂单

- **价格 7 字段(int,毫 ×1000)**:purchase/selling/discount(**1000=无折**)/disPrice/postDis/price/total(long);**店补/厂商补 maling/venderMaling 用 int 存毫**→上限约 214 万元,溢出隐患;
- **件数五计数(long,×1000=1 件)**:num/initnum/punum(采购占用)/xgnum(出货余量)/trnum(送货数)——**被 inventory 跨模块直接加减**(出货扣 xgnum 不足抛 260008,ManifestServiceImpl.java:318-319;采购核销 punum,PurchaseServiceImpl.java:192-193;退货入库加回 :460-461;送货回写 trnum)【无防腐层,营造改库存事件】;
- **套餐展开(psiid 父子)**:主项按 goodspackage 展开子项,子项 discount/postDis=1000、`num=主num×子initnum`;**主单总额=SUM(total) WHERE psiid=0——套餐子项金额不计入总额**(SalesItemDaoImpl.java:48-59);**空配置套装降级**(goods.type=1 但展开配置空→按普通商品落,否则被"孤儿套餐"过滤误杀致配送/结算缺行,2026-10 修复 eeac3c0c1);
- **manifest = 销售单×出货单 N:M 关联**(sid+mid 两列),唯一写点在 inventory(出货保存后挂接,xgnum 足量为闸);sales 模块内 Service 是死壳。

## 3. 配送履约(salesdelivery/salesdeliveryitem)

- 明细级状态机:0 初始→1 配送(tran)→2 审核(check)→3 确认(confirm)→4 完结(finish),每级带操作人+时间五列;每步守卫"上一级状态",不符**静默 continue**(SalesDeliveryServiceImpl.java:156-271);
- 【方林嫌疑】**品牌跳级权限**:`platform.salesdeliverystatusright` 按 品牌×状态 配 checked+crate,每步推进后递归把后续状态一次连打(:432-485)——厂商对账的"按配送进度免人工"定制;
- 【方林嫌疑】**配送驱动结算分成**:每步推进按 crate 比例克隆一份 salesbalance 行(rate 缩放 allowance/balance/vbalance/pbalance/difference,:487-521),check/confirm/finish 回写主结算单 deliverStatus/verifyStatus/confirmStatus=部分(1)/全部(2)——"按配送批次分成结算"是方林玩法;
- 配送行 company 取 **商品的公司**(goods.company,:351)——跨公司供货归属【方林嫌疑】;配送单头 status 死字段恒 0;完成不回写主单任何字段,方向反了:主单完结以"无未完配送项"为守卫。

## 4. 结算线(salesbalance,932 行,本域资金核心)

```
行状态:salesbalance.status  0 预结 ──vpost(守卫=0)──► 1 厂结 ──post(守卫=1)──► 2 结算(终态)
        0 ──reject(onetype=0 且 status=0)──► 物理删除该行+主单 balanceStatus→0
主单轴:sales.balanceStatus 0 ─pre→ 1 ─vpost(onetype=0&type=0)→ 2 ─post→ 3;passSalesBalance 任意态→4 完结(不守卫 balanceStatus,状态机旁路)
```

- **1:N 按品牌多结**:结算维度=品牌(购货/退货按 salesItem.goods.bid 分组生成多行,SalesBalanceServiceImpl.java:488-495);type 0 购货/1 预结退货/2 结算退货/3 定金/4 分批;**onetype 镜像 sales.status**(0 正常/2 全退/3 倒单,退货行直接取 refunds.type);
- **双值人工修正**:vbalance(厂结值=round(total,FLOOR)−round(balance,CEILING),生成时程序算,厂结态前可人工改 :218)/pbalance(结算值,初始=vbalance,厂结态可改 :243);difference=total−balance;coupon 由 balance 域对账打包回填(本模块恒不写);
- **🔴 让利公式量纲不自洽(本域最大混沌点)**:点位公式 `balance=mul(sub(depositpay,mul(depositpay,sub(1000,cvalue))),1000)`(:362)与底价公式 `mul(sub(depositpay,cvalue),1000)`(:367)、退货反向版又一套(:864)——三套公式三种舍入(FLOOR/CEILING/HALF_UP/ROUND_DOWN 混用),cvalue 真实量纲无法从代码确证,**需生产采样 sales.ctype/cvalue vs salesbalance.balance 验证**(待确认 #1);
- **company 分叉**:定金线行 company=sales.company,购货/退货行 company=**brand.company**(:522,:840),而分页过滤全走 sales.company——跨公司品牌(加盟代卖)时行归属与查询口径漂移;
- **自动对账闸**:公司级配置 `SALES_AUTO_BALANCE_INT` 存在即把 status≠0 且未对账的行自动递 balance 域(:644-651)【方林嫌疑:多公司开关】;**厂结"vbalance 未填写"拦截纯前端**(salesBalance.js:136),服务端无 vbalance≠0 闸——API 直调可带 0 过;
- **创建入口三处**:①收款确认联动(payment 域按款型分发:type=8 不可退定金→advSalesBalance,type=3/5→rev+preSalesBalance,SalesPaymentServiceImpl.java:933-938);②免付款 unpaySales 直接 preSalesBalance;③手工预结批量;审批中前置守卫 240007。

## 5. 退货线(salesrefunds)

```
唯一创建入口 = 审批 type10(销售退款)批准(ApprovalSalesRefundServiceImpl.java:298-330,置 status=0+isAffirm=0)
  → send 仅发微信消息不改状态 → affirm 客户微信确认(isAffirm 0→1,已 1 幂等 return :227-242)
     确认时若无关联款项且非外销:生成 type=4 退款款项 + 联动 refundSalesBalance 退货结算(两条会师路径:
     审批批准时已生成→客户确认发现款项已存在直接 return :244-248)
  → 退款款收讫时 payment 域裸 SQL 回写 refunds.status=1(SalesPaymentServiceImpl.java:833,借 payment 常量)
```

- **退货结算 refundSalesBalance 双闸**:防重 querySalesBalanceByRid 命中抛 **370033**(注释自证历史重复提交事故);主单 balanceStatus 必须∈{1,2,3},**0(未结算)或 4(完结)时整方法静默 return 一行不生成不报错**(:780-821)【静默缺陷】;金额全为负,brand 维度多行;
- 🔴 **"退货→sales.status=4 作废"的假设不成立**:status=4 共 3 个写点全部不在退货线(退定金审批 type18 :155/退定金款项 :1114/手工作废 terminalSales :774)——退货只走 balanceStatus 进阶;
- **退定金两路径不对称**:审批 type18 路径生成反向定金结算行(ApprovalSalesDaymentServiceImpl.java:159-186)且**先 setDepositpay(0) 再读它算反向金额**(恒 0,先写后读 :153-163);线上 refundSalesPaymentForDeposit 路径不生成任何反向结算行(:1100-1122)——哪个是现状主流待确认;
- 审批发起时即预扣销售项数量/金额(ApprovalSalesRefundServiceImpl.java:223-249),驳回回加。

## 6. 外销族(oales,2025-2026 新增增强)

```
0 初始 ─submit(守卫0)→ 1 提交 ─confirm(守卫1|3 且必须有款)→ 2 确认 ─collect收款(payment域,收款即置4)→ 4 复核 ─finish(守卫4)→ 5 完结
  2 ─reject(守卫2)→ 3 驳回(可重回 1);abateOales 任意态可废→6(无门槛!含已完结/已结账,不回滚已收款)
```

- **实体语义**:挂装修单的"厂商(品牌)外购销售单"——与 sales 主单**无外键**,是平行销售线;brand→supplier 默认取品牌厂商;cost/threshold(系数)确认后才可见【保密分级,方林嫌疑】;
- **atttype(0 内购/1 外购=进货方向)× type(0 购货/1 退货=单据方向)正交**,UI 列名都叫"类型"(用户口径=type);退货 refundOales 复制原单 atttype、直接落 2 确认态(跳态出生);**amountOales 汇总不过滤 type**——退货必须靠 total 手填负数才做减法,未强制取负(decoration4account.js:652-657 决算直接全加);
- 🔴 **paytran 断链(与 payment.md 断言互证)**:collectOalesPayment 形参 ptids 全方法零引用(OalesPaymentServiceImpl.java:184,其余 9 个 collect* 均置 USED)→外销渠道支付停在 SUCCESS 可重复核销;makePayment case8 金额不符**静默 return** 吞款(PaytranServiceImpl.java:1330);
- **oalesitem 明细链疑似全断**:前端提交 oalesItem.oales.oid 而 VO 无 oales 属性→oid 恒空必抛 400001;total=price×num 未除缩放,千倍虚增嫌疑;OalesItemPO 注释自称"分"实为厘;
- **oalespay 付款登记无状态机**:addOalesPay 镜像创建 payment.oalespayment(不查重),而 payment 侧入口强制一单一款(150029)——**两入口规则冲突可堆多款**;改 money 不同步镜像款→对账漂移;oalespay.payTime 死列;
- oalesrecord 15 type 操作流水(2026-03 AI 增量),deleteOales 先删单再补记录→孤儿记录。

## 7. 活动族(salesactivity 三兄弟)= 积分兑换链

- **定性:不是特价/满减,是"消费返积分+积分兑换实物"的积分商城**,且**四处三模块无单一 owner**:活动主档=information.activity(multiple 百万分比)/兑换消耗=sales.salesactivityitem/装修侧券核销=decoration.decorationactivityitem/对冲台账=payment.paymentactivity;SalesActivityPO 只是 sales 表的窄投影(sid/activityId/amount 三列);
- **积分两公式(SQL 铁证,SalesActivityDaoImpl.java:52-69)**:总积分=Σ(salespayment.referMoney×activity.multiple×±1)÷1e6(款型 2/3/8 为正余为负,直营单须 balanceStatus=3);已用=Σ(salesactivityitem.integral×num)÷1e6(双 ×1000 抵消);**sales 表无积分余额列**,额度实时算;
- 兑换明细 10 态(0 初始/1 失效/2 已领/3 退货/4 配送/5 未退/7 消耗/8 抵现;**6 升级/9 采购死状态**);领取/配送时扣 information.awardstock 库存(但 receive 路径覆盖 isStock 恒真→不计库存的奖品也被扣,两路径行为不一致 :170);每步落 salesactivityrecord 流水;
- **checkSalesActivity(sid) 口径与 SQL 不一致**(integral 未除 1e6 直接乘 multiple,:128-144)→阈值放大 1e6 倍,积分闸门形同虚设(疑缺陷);审批三链(定金/退款/折扣)都复用它做闸门;
- 【营造归属建议】**活动积分整体归会员营销插件**:主档在 information、对手方是库存域与台账、decoration 侧同构物证明是横向"活动物品"域;sales 只保留 activityId+amount 绑定列与"回款产生积分"记账规则。

## 8. 金额与数值口径

| 字段族 | 口径 | 证据 |
|---|---|---|
| sales/item/balance/refunds/oales 全部金额 | **毫(×1000)**,tranargs 双向转换 | WebContent/js/api/sales.js 全文;金蝶与积分换算 ÷10^6(SalesKingdeeServiceImpl.java:312) |
| 件数 num 族 | **×1000(库内 1000=1 件)** | 同上 tranargs;商品侧 goods.pieceNum 同惯例 |
| 折扣 discount/postDis、rate | ×1000(1000=无折/1.0 倍) | 套餐子项硬编码 setDiscount(1000) |
| 活动倍数 multiple | **百万分比(1e6=1 倍)** | ActivityPO.java:69+DAO SQL |
| 积分 integral/expendPrice | ×1000,乘积 ÷1e6 抵消 | SalesActivityDaoImpl.java:58,68 |
| maling/venderMaling/coupon | int 存毫 | 溢出隐患(≈214 万元上限) |
| 注释漂移 | OalesItemPO 自称"分"实为厘;ds/sales.json 的 balanceStatus 少两态 | 双字典源三处并存(constant 表/ds JSON/constants.json 快照) |
| 缩放缺口 | addOales 的 award/profit 不在 tranargs→**元当厘入库缩小 1000 倍**(api/sales.js:287-293) | 隐式 tranargs 机制的典型事故 |

## 9. 跨模块写表面全景(营造事件化依据,本域是六域+sales 中最大)

| 外部域 | 写什么 | 位置 |
|---|---|---|
| payment | 主单款项轴全写(录/付/确认/退款重置/pompany 改写/退定金废单 status=4/coupon)+按款型分发生成 salesbalance+回写 refunds.status | SalesPaymentServiceImpl.java:282-1171 |
| approval | 退款审批写明细 num/total 扣减+主单 status=2/3+amount 重算;退定金审批 depositpay=0+status=4+反向结算行;折扣审批 fullpayStatus=3+fullpay± | ApprovalSalesRefund/Dayment/DiscountServiceImpl |
| inventory | 明细 xgnum/punum 直接加减+写 salesmanifest 关联 | Manifest/PurchaseServiceImpl |
| balance | 对账打包回写 checker/checkStatus/coupon/venderTime/postTime+读 oales 置 accountStatus | BalanceSalesBalance/BalanceOalesServiceImpl |
| decoration | 装修单账户状态(未完结单数驱动)+销售比例回写(postSalesBalance→updateDecorationForSaleScale) | SalesBalanceServiceImpl.java:696 等 |
| kingdee/salary | 只读(金蝶凭证取 grade/pompany;薪酬挂 SalesPO) | SalesKingdeeServiceImpl/MaterialSalaryServiceImpl |
| information | 活动主档+awardstock 扣减+awardrecord 流水 | SalesActivityItemServiceImpl |

【营造映射】以上全部改领域事件:PaymentCollected(带款型)→sales 侧自生成结算行;RefundApproved→退货单+结算;OutboundShipped/Purchased→计数事件流水(替代直改五计数);DeliveryAdvanced→分成克隆(若保留该玩法)。

## 10. 缺陷清单(合并三线精选 20 条)

1. **信任客户端金额**:deleteSalesItem 主单总额不重算直接 setTotal(VO)(SalesItemServiceImpl.java:288-289),add/update 路径却 SQL 重算——双标准可篡改;
2. **先写后验**:addSalesItem 先 save+flush 再验总额抛 260084;退定金审批先置 0 再读(恒 0 计算);
3. **同义双码三处**:370001 vs 260021(销售单不存在)/370021 vs 400001(外销单不存在)/370022 vs 400002;"不必配"错别字+260084/260085 同条件双文案;
4. 260022 一个码覆盖四种守卫(status/款项轴/结算轴)16 处,排障无法区分;260045 名为"销售项"实判主单;
5. **免付款假收款单**凭空造 SalesReceipt/SalesPayment 全空串(:637-667),守卫与 save 间无锁可并发双击;
6. **验证码绕过**:affirmSales 仅两参同时非 null 才校验(:697);
7. 配送四步状态不符静默 continue;8 个 pageSales 变体 userPO==null 静默空返回;companyType(前端传的字段名字符串)直接当列名无白名单;
8. 图片上限 off-by-one(count>limit 才拒)+代码缺省 4 vs 配置目录 20 不一致;delete 不判属主不判存在(NPE);
9. passSalesBalance 状态机旁路(任意态完结不清理行);rejectSalesBalance 只删当前行,兄弟品牌行残留但主单已回 0(可被"复活");
10. refundSalesBalance balanceStatus∈{0,4} 静默 return;brandPO==null 静默丢弃该品牌;
11. NPE 集:updateSalesDeliveryItem get 不判空;handleSalesProfile 同;配送链式懒加载无判空;`if(a==null && a.getX()==null)`(&& 应为 ||,SalesRefundsServiceImpl.java:200);
12. 跨模块裸 SQL:payment 直接 update sales.salesrefunds 并借 payment 常量;String.format 拼列名;countSalesBalance 硬编码 catalog;
13. **死代码**:两空壳 ServiceImpl/SalesDO/三个死端点/adv 定金结算 allowance 恒 0(:370)/260028 守卫永假/peliveryTime 拼写(应为 delivery)/oalespay.payTime 死列/isActivity 死标志(后端零读点);
14. **同表多 PO**:sales 表 3 PO(SalesActivityPO 把 amount 映射成积分语境)/oales 2 PO/oalespay 2 PO——映射漂移源;
15. addOales 的 award/profit 缩放缺口(元当厘);oalesitem 参数名错位链路断裂+千倍虚增;
16. paytran 断链+makePayment case8 吞款(§6);
17. checkSalesActivity 双口径不一致(闸门失效嫌疑);积分闸门还缺库存扣减幂等;
18. 两套建款入口规则冲突(oalespay 镜像不查重 vs payment 侧一单一款);
19. 类型债:SalesProfilePO.company 是 long(全库唯一);status 缺 1;type=1 Java 叫 PROFIT 字典叫"补贴"(常量与运行时字典不同义风险);
20. 全主链 7 个 ServiceImpl **零 LogUtils 操作日志**;updateSalesForAdvance 管理后门(按单号重置状态)无审计;两处 System.out.println 调试残留。

## 11. 方林嫌疑机制级汇总

| 机制 | 判定 |
|---|---|
| 挂装修单的主材销售链(开单→确认→收款→出货→配送→厂结→完结) | **行业共性,V1 核心骨架** |
| 四轴状态分轴(status/结算/两款项轴) | 行业共性保留(语义正交被证明好用);fullpayStatus=3 审批中建议改审批单引用 |
| 套餐展开落明细(psiid 父子,子项不计总额) | 行业共性(套餐销售),空配置降级补丁思路保留 |
| 三公司字段 company/dompany(AES)/pompany 收款改写 | 【方林嫌疑】多法人+总部代收资金归集 |
| 单号可跳号可撞(公司级日流水+offset) | 【方林嫌疑】改全局唯一流水 |
| 免付款通道(假收款单) | 【方林嫌疑】线下全款豁免;保留须补幂等+真实凭证标记 |
| 品牌跳级权限+按配送批次分成克隆结算行 | 【方林嫌疑】厂商对账定制 |
| 结算三段式(预结→厂结→结算)+vbalance/pbalance 双值人工修正 | 【方林嫌疑】厂结二次确认绑定渠道返点玩法;行业通用=对账单确认一步 |
| 定金即生成厂商应付行+红字反向行(onetype) | 【方林嫌疑】账务 hack,行业多用凭证冲销 |
| 按品牌一单多结(1:N) | 半共性(按品牌对账是主材共性),分批克隆是方林玩法 |
| 退货审批驱动+客户微信确认+370033 防重 | 行业共性(移动化确认+幂等) |
| 五计数被 inventory 直改(无防腐层) | 【公司内系统特征】改事件流水 |
| 满意度问卷按单内品牌动态生成打分题 | 【方林嫌疑】品牌考核体系;建议并入统一 survey 域 |
| 外销线(挂装修单的品牌代销+内购外购二元+isScale 配比+部门奖励分成+成本保密) | 【方林嫌疑】装企供应链特有组织方式;V2 精简版 |
| 活动积分商城(回款返积分+兑换扣库存+跨单据对冲) | 【方林嫌疑】营销插件级机制,建议整体剥离会员营销插件 |
| SALES_AUTO_BALANCE/SALES_MUILT_BALANCE 公司级开关 | 【方林嫌疑】多公司(加盟)差异化开关 |

## 12. 营造 addon-sales 落地映射建议

| 老概念 | 营造归属 | 版本建议 |
|---|---|---|
| 销售单主链(挂装修单/明细/套餐/客户确认/完结)+四轴状态机 | addon-sales 核心 | V1 |
| 结算三段式+双值(sales_settlement,1 单 N 品牌行;服务端强校验 vbalance≠0) | addon-sales | V1(三段式去留随 §13 拍板) |
| 退货单(审批驱动生成)+退货结算(按 rid 幂等) | addon-sales | V1(补 balanceStatus 守卫显式报错) |
| 配送履约四级+预约 | addon-sales | V1(品牌跳级/分成克隆待拍板) |
| 图片(type 常量化+属主校验) | addon-sales | V1 |
| 满意度问卷 | 统一 survey 域(与 customer 满意度合并) | V2 |
| 外销线精简版(砍 oalesitem/record/isActivity,状态机收敛,收款事务内置核销) | addon-sales V2 或独立 addon | V2 |
| 活动积分族 | 会员营销插件(销售仅留 activityId+amount) | V2 |
| manifest/purchase 关联 | 并入 inventory 域(采购单.source_ref+出货关联事件) | 随 addon-inventory |
| salesfunction 功能字典 | 若内核已有统一功能字典则只迁数据 | 随内核 |
| 错误码段 | **300000 建议**(老 26/37/40 三段归一);迁移 **1100+** | 待定 |
| 金额 | 统一最小单位(建议分)+显式换算层,消灭 tranargs 隐式 ×1000 与 ÷1e6 魔法 | 全局红线 |

## 13. 待业务方确认清单

1. **cvalue 真实量纲与三组让利公式的预期结果**(§4 量纲不自洽)——建议生产采样 sales.ctype/cvalue vs salesbalance.balance 回归验证后重写公式;
2. **厂结三段式去留**:预结→厂结→结算+vbalance/pbalance 双值是行业流程还是方林渠道返点定制?V1 保留三段还是一步对账确认?
3. **免付款通道**:适用边界(仅线下全款?)与审计要求;假收款单是否进真实收款流水;
4. **pompany 改写**:仅总部 0 改写的规则外,多级代收是否存在;
5. **倒单(3)/全退(2)** 的前端操作路径与财务含义(倒单=换货重开?);
6. **depositpayStatus=-1 与 0 的业务区分**(未录单 vs 已录未付?);
7. **配送分成克隆与品牌跳级**:确认是方林定制后 V1 是否砍(砍=配送只做履约,不驱动结算);
8. **退定金两路径**(审批 type18 生成反向结算行 vs 款项路径不生成)哪个是现状主流;
9. **rejectSalesBalance 多品牌残留**(行残留主单已回 0)是特性还是 bug;
10. **外销线 V2 精简版立项确认**(含 oalesitem 断链是否修、退货负数约定、atttype 二元法去留);
11. **活动积分族剥离会员营销插件**确认(销售域仅留绑定列);store 已判删除但 salesactivityitem+awardstock 链路是活代码,积分插件 V1 是否保留库存联动;
12. **满意度问卷归属**(并入统一 survey 与 customer 满意度两套并存问题);
13. **maling/venderMaling/coupon 三者的承担方与结算归属**;主单与明细两层 ctype/cvalue 谁是让利准绳;
14. **type=1 语义**(Java PROFIT vs 字典"补贴")与 status=1 缺号是否有历史存量;
15. **sales 与 material 两套主材销售并存**的历史分工(material 模块待过堂细化,影响 addon-sales 边界);
16. **公司级开关**(SALES_AUTO_BALANCE/SALES_MUILT_BALANCE)各公司实际值——决定营造是否必须保留等价开关。
