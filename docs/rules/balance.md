# L2 规则清单 - 结算域(addon-balance)

> 提取自老系统 `src/com/fanglin/balance/`(108 文件 / 13 ServiceImpl / ServiceImpl 共 3103 行),三路并行深读(主链+账户扣款 / 销售结算线 / 选材+外销结算线),2026-10-11。
> 生产只读库复核同日完成(15 表全量体量+逐年分布+状态分布)。file:line 以 `SVC = src/com/fanglin/balance/service/impl` 缩写。
> **本域是 sales 结算明细的汇聚点**(sales.md §4 三段式下游)。【✅第35次核对收窄:inventory 采购结算(purchasebalance)生产 0 行从未投产、material 存量自然完结,均不进新链;实际 V1 仅接入 sales post 事件(第 26 次)】

## 0. 模块定性

**一句话:结算汇聚域**——把三个上游(销售三段式明细 salesbalance / 报价域选材项 quotation.selectionItem / 外销单 sales.oales)中"已就绪可结算"的明细,按「品牌+供应商+等级+公司」吸附进三张结算单(balancesales / balanceselection / balanceoales),落到通用资金头 balance(结算单),再经账户(结算/出纳双腿)与扣款池完成「应付登记→扣款冲抵→付款→完结」的厂商应付闭环。**它自己不产生任何业务金额——所有金额都是上游明细的聚合**。

三个结构性事实(理解全域的钥匙):

1. **明细表全是三列挂接表**(balanceselectionitem = bsiid+bsid+siid / balancesalesbalance = bsbid+bsid+sbid / balanceoalesitem = boiid+boid+oid),**零冗余快照**——结算单上的金额字段全部是**查询时动态聚合**,锁定只锁"人不许操作",锁不住上游改价。这是全域金额漂移、四套舍入并存的根源(§8);
2. **单向吸附 + 回写三字段**:balance 对上游只回写 accountStatus(对账状态)/accounter/accountTime(销售线多回写 checker/checkTime/coupon/venderTime/postTime),上游主状态(oales.status / selectionitem.status / salesbalance.status)全程不碰;全仓唯一跨模块服务调用 = sales.SalesBalanceServiceImpl → balance.submitBalanceSalesBalance(SalesBalanceServiceImpl.java:650/:900);
3. **资金头是死账本**:balance 主表 7 万行 status 全 0、type 全 0,startTime/endTime/balanceTime 从未写入,唯一在用收尾 = payedBalance 打 isPay——代码里的入账/付款/完结状态机在生产**零执行**(§2.3),账户/扣款下游从未投产(§6.4)。

### 0.1 代际与活跃度

- 模块 2020-01-14 建成(首提交 3.3.010.005);生产数据 2023 年才起量(balance 主表 2023 年突增 30,363 行,2016/2022 各 1 行——**代码闲置 3 年后启用,2023 批量疑似补录/迁移**,待业务确认);
- 2026 年 9 次提交,持续高频活跃:2026-09-19 f384101d3(账户/扣款闭环投产改造,全模块最新最干净批次)、2026-10-03 ef96286a0(拆分功能)、2026-07 日志/漂移码治理;
- 四条线全活(生产最新记录:balance/balancesales 2026-10-11 当天、balanceselection 2026-10-10、balanceoales 2026-10-01)。

### 0.2 生产表清单与定性(2026-10-11 只读复核,balance catalog 全部 15 表)

| 表 | 行数 | 逐年/状态实证 | 定性 |
|---|---|---|---|
| balance | 70,206 | 2023 起 30,363→2026 10,741;status **全 0**、type 全 0、isPay 0=2,230/1=67,976(97% 已付)、payedTime 仅 13,825 填、startTime/endTime/balanceTime **全 NULL** | 活(资金头;状态机生产零执行) |
| balancesales | 55,202 | 2021 起稳定 7.5k~13k/年;status 0=228/1=1,032/2=22/**3=56,308/4=0** | 活(销售结算单;确认态几乎不用,完结态从未到达) |
| balancesalesbalance | 1,474,924 | 三列桥表(bsbid+bsid+sbid),无时间列 | 活(销售明细挂接) |
| balanceselection | 14,710 | **2024 起新线**(6,832/4,525/2,803);status 0=465/1=1,437/2=54/3=12,204 | 活(选材结算单) |
| balanceselectionitem | 1,443,522 | 三列桥表(bsiid+bsid+siid) | 活(选材明细挂接) |
| balanceselectionrecord | 40,191 | **operateTime 全 NULL**(记了流水不记时间) | 活(流水时间字段死) |
| balanceoales | 380 | **2024 起**(97/219/110) | 活(外销结算,小) |
| balanceoalesitem | 15,829 | 三列桥表 | 活 |
| balancepicture | 9 | bpid 自增=8 | 准死(凭证图) |
| balancecutpay | 3 | 全部 2024-04/07,「合作费/活动费」手工扣款 | 闲(闭环改造 SQL 待生产) |
| balanceaccount | 0 | f384101d3 新表,**生产未部署**(SQL 待生产) | 死(待投产) |
| balancedccount | 0 | | 死 |
| balancedetail | 0 | | 死 |
| apportdetail | 0 | PO 映射表名 balanceapport 与建表 SQL apportdetail 不一致(§12-A1) | 死 |
| comcost | 0 | 代码无前端消费、无跨模块引用 | 死功能 |

## 1. 域模型总览(数据流)

```text
sales.salesbalance(三段式明细, sales.md §4)
   └─vpost/post 后(SALES_AUTO_BALANCE 开)→ balance.submitBalanceSalesBalance   ← 全仓唯一跨模块服务调用
        └─挂入 balance.balancesalesbalance(桥)→ 按 品牌+供应商+grade"A"+初始态 聚桶 balancesales
quotation.selectionItem(选材项, status∈{5核对,6结算,7失效} 且 accountStatus=0)
   └─balance.submitBalanceSelection/batchBalanceSelection 主动吸附
        └─挂入 balance.balanceselectionitem(桥)→ 按 品牌+grade"C"+初始态 聚桶 balanceselection
sales.oales(外销单, accountStatus=0)
   └─balance.submitBalanceOales 吸附(无 accountStatus 守卫,§12-D1)
        └─挂入 balance.balanceoalesitem(桥)→ 手工聚桶 balanceoales

三张结算单 ──(结算动作 balanceBalanceXxx)──► balance.balance 资金头(amount=surplus=payment)
                                                     │
              balanceaccount 结算账户(应付台账) ◄──── balanceBalance 台账腿
              balanceaccount 出纳账户(现金)     ◄──── payBalance 现金腿 payCash
              balancedccount 扣款池 ──bind──► balancecutpay 扣款单 ──► 冲结算单 surplus/cutpay
```

读侧:聚合 SQL **直 JOIN 他域表**(sales.salesbalance/sales.sales/sales.oales/quotation.selectionItem),零防腐层;写侧:仅回写上游 accountStatus 族三字段。外围:personel(user)/system(company 短名、SystemConfig 图片上限)/common(CountingService 单号流水)。

## 2. 结算主单 balance(资金头)

**BalancePO**(表 `balance`):bid PK / no(单号=公司短名+yyMMdd+4 位流水,`SVC\BalanceServiceImpl.java:106`,计数器 common CountingService TYPE_BALANCE_INT) / cuid→creater、buid→belonger(归属人,通常是厂商)、bluid→balancer、pyuid→payeder / createTime、balanceTime、payedTime / **amount(总额)、surplus(余量)、cutpay(扣款)** / type / status / isPay / company。

### 2.1 状态机(status:0 初始→1 完结,生产全 0)

| 值 | 写点 | 守卫 |
|---|---|---|
| 1 完结 | balanceBalance:174 `if (surplus == 0) setStatus(1)`(台账腿把余量清零才完结) | 无前置状态检查 |
| 1 完结 | payBalance:216 `setStatus(1)`(**无条件**——出纳腿付过即完结,不管 surplus) | 无(可重复付款) |

两写点语义冲突(条件完结 vs 无条件完结)且均无状态守卫——完结单可重复入账/重复付款;payedBalance(:296-307)可对任意单重复打标。

### 2.2 type(写入即定型):0 支出 / 1 收款 / 2 提款

0 支出=三张结算单建头+手工单;1 收款=手工单;2 提款=withdrawBalanceAccount(BalanceAccountServiceImpl.java:187)生成,提款单禁止台账入账(:150-152→350009)。**三张结算单建头时用 `BalanceSalesPO.STATUS_INIT_INT` 充当 type 实参**(Sales:198/345/484、Selection:177/463/665、Oales:357 用 BalanceOalesPO.STATUS_INIT_INT)——值恰等于 TYPE_PAYOUT_INT(0),跨域常量语义错位(§12-E)。

### 2.3 isPay(0→1,唯一在生产活跃的旧链)

写点全仓唯一:payedBalance:302,无守卫、可重复、批量(对 null bid 静默 continue)。生产 97% 已付=这条链是唯一真实收尾。

## 3. 销售结算线(balancesales,55,202 行,主力线)

### 3.1 表关系定案

`balance.balancesales`(结算销售单)**≠** `sales.salesbalance`(销售结算明细,sales.md §4)——两张表两个 catalog。关系=聚合:一张 balancesales 按「brand.bid + supplier.uid + **grade="A"** + atttype=0 + status=0 + company=sales.pompany」聚合同键的 N 条 salesbalance(BalanceSalesBalanceServiceImpl.java:129/137;BalanceSalesDaoImpl.java:77-78);桥表 balancesalesbalance(bsbid+bsid+sbid,sbid FK→sales.salesbalance)。BO 与 PO 同表双映射(BalanceSalesBO 只读分页用)。

### 3.2 状态机(4 态;生产 3=事实终态,4=完结从未到达)

```text
0 初始 ──lock(守卫=0)──► 1 锁定 ──affirm(守卫=1)──► 2 确认 ──balance(守卫=2)──► 3 结算(终态)
   ▲                        │
   └────── unlock(1→0) ────┘
```

所有守卫违反统一抛 350018「结算销售不可操作」。**BO 常量漂移**:BalanceSalesBO.STATUS_ENABLE_CHECK_INT=2,而 PO 里 2=AFFIRM/3=ENABLE_CHECK(BalanceSalesPO.java:42-44 vs BalanceSalesBO.java:42)——BO 常量无人引用,埋雷。

### 3.3 挂接与自动吸附

- 提交入口 submitBalanceSales(页面勾选)与**自动吸附**:sales 域 vpost/post 完成后,若公司开关 `SALES_AUTO_BALANCE_INT` 开(SalesBalanceServiceImpl.java:634/895),对该销售单所有 status≠0 且 checkStatus=0 的 salesbalance 逐条调 submitBalanceSalesBalance(:644-651);
- submitBalanceSalesBalance:校验 350017/260037 → 找同键初始桶,无则 createBalanceSales+flush+反查(:133-138,**并发下可建重复桶**,§12-B3)→ 挂桥 → 回写 salesbalance.checkStatus=1/checker/checkTime/**coupon**(从 payment.SalesPayment 收据聚合定金/补尾/退款券,:165-183;type=3 定金行与退货行是替换语义,正常行是累加);
- unbind 回置 checkStatus=0,**但不清 coupon/venderTime/postTime**(:265-267,时间戳残留,§12-B9)。

### 3.4 聚合公式(原样,BalanceSalesDaoImpl.java:84)

```sql
select sum(if(sb.onetype = 0, if(sb.type = 3, s.depositpay, s.depositpay+s.fullpay),
              if(sb.onetype = 1, sb.total, if(onetype in (2,3), sb.total, 0))) * sb.rate/1000) as 'income',
       sum(pbalance) as 'cost',
       sum(if(sb.onetype = 0, sb.pbalance - round(s.venderMaling, 0),
              if(sb.onetype = 1, sb.pbalance, if(onetype in (2,3), sb.pbalance + round(s.venderMaling, 0), 0)))) as 'payment'
from balance.balancesalesbalance bsb, sales.salesbalance sb, sales.sales s
where bsb.bsid = :bsid and bsb.sbid = sb.sbid and sb.sid = s.sid
```

- income(应收)=正常行取 **sales 单级** depositpay(+fullpay)×rate/1000;退款行(onetype 1/2/3)取行级 total——单级字段×行级 rate,同单多行会重复计(§12-B6);
- cost(让利/国补)=Σpbalance;
- payment(应付)=正常行 pbalance−round(venderMaling,0),退全款/退定金行 pbalance+round(venderMaling,0)——**venderMaling 是 sales 单级字段却逐行加减**(sales.md「让利公式量纲不自洽」在本线的具体落点);
- profit = income − payment(Arith.sub,写回 :587-590);
- 结算时 balance 头 surplus = payment − amountBalanceCutpay(bid)(:466;amountBalanceCutpay 不分 type 全加,deduct 子流水双计,§12-A8)。

### 3.5 拆分(2026-10-03 ef96286a0)

splitBalanceSales(:305-401):守卫 status==0 + 明细数<500 抛 350034(门槛常量 500)→ 按 bsbid 升序前 floor(n/2) 条改挂新单(奇数原单多留)→ 新单内联建(name=`yyMMdd-品牌-grade-毫秒%1000`)+新建 balance 头 → 两单各重算四项 + balance 头 amount=payment/surplus=payment−cutpay。**已勾对(checkStatus=1)明细可被整体搬走**(对账口径突变);sales 线拆分无留痕表(选材线有 record,不对称)。

## 4. 选材结算线(balanceselection,14,710 行,2024 新线)

### 4.1 数据源勘误(纠正两处先前口径)

- **真实数据源 = quotation.selectionItem(报价域选材项,表 quotation.selectionItem)**,经只读镜像实体 SelectionItemPD 同表双映射懒加载;**不是 data.soods/soodsitem**(那是 material.md 考证的商品库存资料)。即"选材结算"的结算对象是**报价域选材项**,与 material.md 的 soods「选材单据」是两族同名不同物——两族关系待业务确认(§16);
- **聚合除数 = 10^6 而非先例口头流传的 1000**:selectionitem.num/refnum/postprice 三者均千倍存储,`SUM(±(num−refnum)×postprice)` 后走 `Arith.div(x, 1000000)`(=x/10^6×10^3,落方林千元制,BalanceSelectionDaoImpl.java:87-93)。爱林30修数先例的 `DIV 1000` 公式基于彼时所用列的量纲,**修数迁移时必须按列实际量纲重推**,勿照抄。

### 4.2 状态机(4 态,含审核档;生产 0/1/2/3 = 465/1,437/54/12,204)

```text
0 初始 ──lock──► 1 锁定 ──audit──► 2 待审 ──balance(守卫=2)──► 3 审核(终态)
   ▲               ▲   │
   └─unlock(1→0) ──┘   └─unaudit(2→1,驳回)
```

守卫统一 350028「结算选材不可操作」;delete 仅 status=0 且明细数=0(:692-698)。明细绑定前置:selectionitem.status∈{5,6,7} 且 accountStatus==0,不满足**静默 continue**(:233-234/:295-296)。

### 4.3 聚合与单头四项

- cost = payment = 动态聚合(§4.1 公式,减项类型 {2 拓展减,4 减项,14 减项(未退)} 取负,聚合不过滤 selectionitem.status——失效明细仍参与);
- **income/profit 完全信前端 VO**(submit:256/259、batch:333/336、unbind/pending 同)——服务端不重算(§12-C10);
- discount 独立接口直写,无任何状态守卫(已结算可改折扣,:735);amount 重算同样无守卫(:707-723);
- **grade 硬编码 "C"**:自动聚桶恒按 品牌+grade"C"+status=0+本公司 找单/建单(:205/:214/:279/:286/:310);
- batch 自动建单把**商品 gid 当品牌 bid** 写入(:304 `new BrandPO(getGoods().getGid())`,应 getBid())——挂错品牌的数据污染路径(§12-C3);
- submit 的"自动建单"分支创建后未把新 PO 赋回局部变量,:217 必抛 350027 连带回滚——**自动建永不成功**(§12-C2);
- 拆分对称 sales 线(350035),两单各写 record「拆分」;结算动作本身**无 record**(流水链最后一步断档,§12-C9)。

### 4.4 record 流水(操作流水,非财务确认)

balanceselectionrecord(bsrid/bsid/ouid/operateTime/type/comment):type 存**操作后的状态值**,comment 存中文文案——create(0,"")/submit(0,"提交")/batch(0,"批量")/lock(1,"锁定")/unlock(0,"解锁")/audit(2,"待审")/unaudit(1,"驳回")/split(0,"拆分");unbind/pending/update/amount/discount/delete/**结算** 均不写。生产 operateTime 全 NULL(4.1 万行)。Service 方法名错叫 queryBalanceAccount(BalanceSelectionRecordServiceImpl.java:24)。

## 5. 外销结算线(balanceoales,380 行,2024 起)

- 状态机 3 态(比选材线少审核档):0 初始 ──lock──► 1 锁定 ──balance(守卫=1)──► 2 结算(终态);守卫统一 350024;
- 聚合(原样,BalanceOalesDaoImpl.java:63):`select sum(if(oi.type = 0, oi.profit, -oi.profit)) from balance.balanceoalesitem boi, sales.oales oi where boi.boid = :boid and boi.oid = oi.oid`——购货利润正计/退货负计,**无缩放**(oales.profit 已千元制),只写 balanceoales.profit;**没有 cost/payment,应付=利润**(外销结算语义=给厂商结利润);
- **绑入无 accountStatus 守卫**(:183-185 只判 null)——同一 oid 可绑多张结算单,利润重复累加;6 废单/0 初始外销单也能绑入;oales.status(7 态)全程不读不写(结算完成不改外销主状态);
- **结算建 balance 头不写 amount/surplus**(:356-362)——财务拿到零金额空壳账户行(§12-D4);
- 回写:submit 写 oales.accounter/accountTime/accountStatus=1(借 quotation.SelectionItemPO 常量,域错),unbind 回置 0 并清时间。

## 6. 账户与扣款闭环(2026-09-19 f384101d3 重写;生产未部署)

### 6.1 结算账户(balanceaccount)= 归属人维度钱包

referMoney(应付登记累计)/realMoney(实付累计)/surplus(净头寸);type **0 结算/1 出纳**(写后不可变);status 0 有效/1 无效;utype 0 员工/1 厂商(**不落库**,运行时由 belonger→user.type 回填,:94-99)。

### 6.2 入账双腿(balanceBalance,按账户 type 分流,公式原样)

- **台账腿(type=0)**:`update balanceaccount set referMoney=referMoney+:money, surplus=surplus+:money where baid=:baid`(BalanceAccountDaoImpl.java:48);同时 `update balance set surplus=surplus-:money where bid=:bid and (type<>0 or surplus>=:money)`(BalanceDaoImpl.java:52,**原子守卫**);写一条 balancedetail;
- **现金腿(type=1,payCash:224-251)**:收款单→referMoney+=money(入金);支出/提款单→出纳账户 realMoney+=money、surplus−=money(`surplus>=:money` 原子守卫,失败 350030);**支出单额外**懒开户归属人结算账户 realMoney+=money/surplus−=money(**无守卫版**,:244,允许透支成负=预付)+写两条 detail;现金腿不消耗 balance.surplus;
- 每次入账尾写 apport 一条(money=剩余 surplus 快照,comment 硬编码「支出/收款」,:178-186);
- 完结:台账腿 surplus==0 条件完结(:174)/出纳腿无条件完结(:216)。

### 6.3 扣款池(balancedccount+balancecutpay)

- 池:cutpay=初始额度=surplus;扣款单:可先建池记录(bid 空),bind 到结算单后冲其 surplus/cutpay;type 0 扣款/1 返款(bind 全取反);deduct=子流水+父 total 核减;unbind=回补池+逆向冲正+total=0;
- **守卫缺口**:unbind/deduct 无 status 守卫(未绑定单解绑会凭空加钱,:205-239/:243-289);池原子 SQL 无 `surplus>=:money` 条款(BalanceDccountDaoImpl.java:60-65,与主链原子 SQL 形成正反教材对比);unbind VO 不带 balance 时 NPE(:213)。

### 6.4 生产未部署实锤(2026-10-11 复核,与 f384101d3 自述一致)

balanceaccount/balancedccount/balancedetail/apportdetail 仍 0 行、balancecutpay 仅 2024 年 3 行手工合作费、balance 主表 status 全 0(结算入账/出纳付款/完结零执行)——**改造 jar(balance-8.3.031.000)与配套 SQL(BALANCE_ACCOUNT_CUTPAY_CLOSURE.sql)至今未上生产**。存量 6.4 万结算单不入账、surplus 不回填的既定策略未变。

### 6.5 提款(withdrawBalanceAccount)

先扣源账户 surplus(:169)→生成 type=2 提款单(amount=surplus=提款额)→出纳腿执行时扣**出纳账户**;提款单走台账腿被 350009 拒绝。

## 7. 状态机汇总(八实体)

| 实体.字段 | 枚举与流转 | 终态语义 | 生产实证 |
|---|---|---|---|
| balance.status | 0 初始→1 完结(两写点:条件/无条件) | 无逆向 | **全 0**(零执行) |
| balance.type | 0 支出/1 收款/2 提款,定型 | — | 全 0 |
| balance.isPay | 0→1,唯一写点 payedBalance 无守卫 | — | 97% =1(唯一活链) |
| balancesales.status | 0→1 锁定→2 确认→3 结算;unlock 1→0 | 3 | 3=事实终态(98%),**4 完结枚举不存在**(PO 无 4) |
| balanceselection.status | 0→1 锁定→2 待审→3 审核;unaudit 2→1 | 3 | 3=83% |
| balanceoales.status | 0→1 锁定→2 结算 | 2 | — |
| balanceaccount.status | 0 有效/1 无效(任意 short 无校验),type 0 结算/1 出纳定型 | — | 表 0 行 |
| balancecutpay.status | 0 初始⇄1 绑定(bind/unbind,均无 status 守卫) | — | 3 行 |
| balancedccount.status | 0 有效/1 失效(任意值) | — | 表 0 行 |
| selectionitem.accountStatus(跨域写) | 0→1(submit/batch)→0(unbind);2=挂起(pending 同时摘明细) | — | — |
| oales.accountStatus(跨域写) | 0⇄1(submit/unbind) | — | — |

## 8. 金额与数值口径(红线)

1. **balance 域全族金额 = 千元制(元×1000),与其他业务域同口径**。实锤链:三线聚合公式落点千元制(选材线 Arith.div(…,10^6)=×10^3;销售线 income `*rate/1000`);`api/balance.js` tranargs 对 balance.amount/surplus/cutpay、account.referMoney/realMoney/surplus、cutpay.money 全族 ÷1000 显示/×1000 提交(WebContent/js/api/balance.js:58-61/74-76/81-83/99-102;app 版 105-107 一致);生产 balanceselection 重算量级(爱林30:16,863→51,618,322)吻合千元制。**模块内 CLAUDE.md 自述"金额单位分"与 API 文档示例(money=100000=1000元)系文档层错误**——营造迁移动以 tranargs 契约为准,文档漂移记缺陷(§12-E);
2. **四套舍入并存**(承接 sales.md §4):定金 vbalance=round(FLOOR)−round(CEILING)(SalesBalanceServiceImpl.java:377);正向按品牌 vbalance 同式(:568);退货 vbalance 双 round(DOWN) 且多品牌 total/balance 用 HALF_UP(:875-885);SQL 侧 `*rate/1000` 整除截断+`round(venderMaling,0)` 四舍五入——同一笔让利按入口不同结果可差 1;
3. **动态聚合=金额可漂移**:锁定只锁操作,上游改 postprice/num/refnum(报价域任意时刻可改)直接改变下次重算值;lock 快照一次(:541-544)后 amount/discount/unbind 前的重算点会刷新;
4. **信任前端金额三处**:选材线 income/profit 全套、两线结算动作建 balance 头 amount=前端传值(Selection:670-671/Sales:489)、前端 `grade=="A"` 时把 income 当 payment 结(balanceSales.js:257-258/balanceSelection.js:245)——业务规则只活在前端,服务端零校验;
5. rate 千分比(1000=100%),仅销售线 income 使用;扣款/入账/付款金额全链无 `>0` 校验,负数入参可反转全部守卫(`surplus>=:money` 对负 money 恒真,§12-A3)。

## 9. 跨模块写表面全景(营造事件化依据)

**本域 → 他域**(全部为对账状态回写,金额零触碰):
- quotation.selectionItem.accountStatus/accounter/accountTime(4 处);
- sales.oales.accountStatus/accounter/accountTime(2 处);
- sales.salesbalance.checkStatus/checker/checkTime/coupon/venderTime/postTime(6 字段,BalanceSalesBalanceServiceImpl.java:158-185/BalanceSalesServiceImpl.java:265-267)。

**他域 → 本域**:
- sales.SalesBalanceServiceImpl → balance.submitBalanceSalesBalance(:650/:900,全仓唯一跨模块服务调用,SALES_AUTO_BALANCE 开关驱动);
- 无任何模块直写 balance 自有表(裸 SQL/PO 引用 grep 零命中)。

**读侧跨域 JOIN**(聚合 SQL 直连他域表):sales.salesbalance/sales.sales/sales.oales/quotation.selectionItem 四表。

> 营造判定:读 JOIN 与回写三字段都改为**事件+快照**——上游明细就绪时发布「明细可结算」事件(携带金额快照),balance 落快照行;这同时消灭动态聚合漂移(§8.3)与跨表 JOIN(军规 2)。

## 10. 错误码段 350xxx 全景(error.md:1067-1386,35 个码)

350001 帐户不存在/350002 deprecated/350003 账户金额不足(池)/350004 扣款账户不存在/350005 reserved/350006 结算单不存在/350007 结算归属不一致/350008 仅允许操作出纳类型账户/350009 提款单不能结算入账/350010 reserved/350011 成本不存在/350012-350016 reserved/350017 结算销售不存在/350018 结算销售不可操作/350019-350020 reserved/350021 销售明细存在/350022 reserved/350023 结算外销不存在/350024 结算外销不可操作/350025 结算外销明细存在/350026 reserved/350027 结算选材不存在/350028 结算选材不可操作/350029 结算选材明细存在/350030 帐户余额不足(实际多守卫结算单 surplus)/350031 结算选材单不存在/350032 扣款不存在(对象实为扣款池)/350033 结算扣款不存在/350034 结算销售明细不足500/350035 结算选材明细不足500。

- 2026-07 漂移码治理(3b05c3f45/5d8415315)后**与 error.md 零漂移**——全模块受益区;
- 语义挪用三处待定案:350030 实守结算单 surplus、350032 对象是池不是扣款、350004 第二 throw 点表达归属人缺失(:121);
- 跨段借用:260037(销售结算信息不存在,inventory 段)在销售线使用;110017(资源数上限)用于图片上限。

## 11. 字典枚举(communion.constant 表 module='balance' 55 键,生产实拍)

- **balance**:isPay 0 未付/1 已付;status 0 初始/1 完结;type 0 支出/1 收款/2 提款;
- **balanceAccount**:status 0 有效/1 无效;type 0 结算/1 出纳;utype 0 员工/1 厂商(f384101d3 新增);
- **balanceCutpay**:atttype 0 扣款/1 入场费(PO 未映射,死字典);status 0 初始/1 绑定;type 2 活动费/3 管理费/4 售后费/5 年返/6 场外返利/7 合作费/8 场地租赁费/9 推广费/10 装修费/11 保证金/12 服务费(**缺 0 键,生产 3 行数据全是 type=0**——字典与数据脱节);
- **balanceDccount**:status 0 有效/1 失效;type 0 扣款/1 返款;
- **balanceSales**:grade A/B/C;status 0 初始/1 锁定/2 确认/3 结算/**4 完结(生产 0 行)**;
- **balanceOales**:grade A/B/C;status 0 初始/1 锁定/2 确认/3 结算/4 完结——**字典 5 态 vs PO 3 态(0/1/2),3/4 是死键,真实终态 2 被字典显示成「确认」**;
- **balanceSelection**:status 0 初始/1 锁定/2 待审/3 审核;grade 三条全挂 **GRADE_A_INT 同一字段名**(key=A/B/C,字段错位,JS keys 映射互相覆盖);
- **balancePicture**:status 0 有效/1 删除。

**前端双版漂移实录**:老版 WebContent 走运行时 queryConstant(`WebContent/ds/balance.json` 是空壳 stub);balanceOales.js 借用 balanceSales 枚举显示 balanceoales(:44/107);app 版(未入库)枚举内联且大面积漂移——balanceOales.js 缺 2(结算后显示 '-')、balanceSelectionItem.js 的 SI_STATUS{0,1已拆,2已结,3已退} 与 selectionitem 真实 8 态对不上(纯显示层臆造)、balanceOalesItem.js 把「驳回」显示成「复核」。**一切以 Java PO 为准**。

## 12. 缺陷清单(合并三路 39 条,按族精选)

**A. 主链+账户**
1. BalanceApportPO 映射 `balanceapport` 与建表 SQL `apportdetail` 表名脱节(po/BalanceApportPO.java:19),部署必错一边;
2. balanceBalance/payBalance/payedBalance 全族无 status/isPay 守卫:完结可重复入账、重复付款重复扣出纳、重复打标;
3. 金额全链无 `>0` 校验:负 money 使 `surplus>=:money` 恒真→凭空虚增账户;
4. 扣款池原子 SQL 无守卫(BalanceDccountDaoImpl.java:60-65)与 :138-142 检查构成经典 check-then-act 竞态;
5. 托管实体全字段覆写(bind/deduct/unbind/payBalance)与原子 UPDATE+内存回写(balanceBalance:166-176)互相丢失更新,无版本号;
6. amountBalanceCutpay 不分 type 全加、deduct 子流水双计(BalanceCutpayDaoImpl.java:48-57)→拆分/重算后 surplus 偏大;
7. unbind NPE(:213)+未绑定单解绑凭空加钱(:233-234);deduct 对 0 态池记录把父 total 核成负(:274);
8. 死列:balance.startTime/endTime 从未写入;balancecutpay.atttype 前端仍在发、PO 未映射被 Struts 静默丢弃;
9. addBalance 不校验 belonger 存在(null→裸 SQL 异常);懒开户 locateOrCreateAccount 先查后建可并发双开。

**B. 销售结算线**
1. BO 常量漂移:BalanceSalesBO.STATUS_ENABLE_CHECK_INT=2 vs PO 2=AFFIRM/3=ENABLE_CHECK(埋雷,现无人引用);
2. submitBalanceSalesBalance 判空顺序混乱(:120 先用 getBsid、:124 才判 VO null)+pompany 拼写错误+supplier null NPE 链;
3. 聚合桶无唯一约束,create→flush→反查并发可建重复"A"桶;
4. createBalanceSales 恒返 0(name 用毫秒%1000 易撞);
5. income 公式单级字段(depositpay/fullpay/venderMaling)×行级 rate/逐行加减,同单多行重复计/重复扣;
6. deleteBalanceSales 不清理伴生 balance 头(孤儿);unbind 不清 coupon/时间戳;
7. submit N+1:逐条挂接每条内部聚合一次+外层再 total,同批重复聚合 N+2 次;
8. 拆分可搬走已勾对明细且无留痕表。

**C. 选材结算线**
1. batch 自动建单把商品 gid 当品牌 bid 写入(:304)——挂错品牌;
2. submit 自动建单分支创建后未赋回局部变量必抛 350027 回滚——「自动建」永不成功(:210-219);
3. discount/amount 无状态守卫,已结算可改折扣/重算冲掉锁定金额;
4. 明细绑定前置不满足静默 continue(:233-234)——应报错的场景无声吞掉;
5. batch 空转 NPE(items 全 continue 且 brand null 时 :331);
6. delete 不清伴生 balance 头(结算时又新建覆盖 bid,create 那张永远悬空);
7. record 流水:结算动作不写、operateTime 全 NULL;
8. 数据源侧连带:refundSelectionItem 二次无条件 setRefnum,null 拆箱 NPE(SelectionItemServiceImpl.java:689-693)直接影响聚合。

**D. 外销结算线**
1. **绑入无 accountStatus 守卫**——同一 oid 可绑多张结算单利润重复累加(ca535f1e6 修过选材侧同类问题,外销侧未修);
2. 结算建 balance 头不写 amount/surplus——零金额空壳;
3. 废单/初始单可绑入(oales.status 全程不读);
4. app 版字典缺 status=2(结算后显示 '-')。

**E. 文档与字典**
1. 模块 CLAUDE.md「金额单位分」+API 文档示例与 tranargs 千元制契约矛盾(§8.1,以 tranargs 为准);
2. balanceCutpay 字典缺 type=0 键(生产数据全是 0);balanceOales 字典 5 态 vs PO 3 态;balanceSelection grade 字段错位(GRADE_A_INT 三条);
3. app 版三页枚举内联臆造(§11);
4. comcost 全家死代码(pageComcost 静默丢弃 filters,comment 列 varchar(11))。

## 13. 方林嫌疑

1. **grade 硬编码**:销售桶恒 "A"(ServiceImpl:129/137/182/186+前端)、选材桶恒 "C"(:205/:214/:279/:286/:310)、A 级按 income 结算的业务规则只活在前端——供应商分级的行业语义待业务确认,营造应配置化;
2. **SALES_AUTO_BALANCE_INT 公司级开关**(sales 域)驱动自动吸附——多公司差异化开关,行业化=改插件配置;
3. **跨域常量语义挪用**:BalanceSalesPO.STATUS_INIT_INT 充当 BalancePO.type(六处)、SelectionItemPO.ACCOUNT_STATUS_ACCOUNT_INT 用于 oales——值对语义错,重构地雷;
4. **零权限门控**:全族 Action 无任何 pageBtn/角色校验,任何登录用户可对任意公司结算单入账/付款/解绑;queryBalanceAccount 按 belonger 查还跨公司可见;
5. 中文直埋:record comment(提交/锁定/待审/驳回/拆分)、apport comment(支出/收款)、懒开户账户名「%s结算账户」、单号=公司短名前缀(借 common 计数器,跨公司合并报表 no 会重号);
6. 拆分门槛 500、图片上限 4(BALANCE_PICTURE_LIMIT)等阈值硬编码。

## 14. 正面教材(f384101d3,全模块最新最干净批次)

1. **账户类型分流双腿**:一个入账动作按账户 type 走台账腿(登记应付+消耗结算单余量)/现金腿(记实付+不消耗余量+支出单懒开户同步台账)——业务分支收敛在 service 层,以 DTO 兼容旧客户端,**存量前端零改动**从死流程变合法;
2. **原子 UPDATE 守卫资金字段**:`update … set surplus=surplus-:money where … and surplus>=:money` 受影响行数判 0 抛码——把余额守卫下推进 SQL(池表漏做,正反两面都是教材);
3. **懒开户 locateOrCreateAccount**:付款时无账户自动开户,下游零配置跑通;
4. **bind/unbind 对称闭环**:bind 置状态+双向冲正+total 快照,unbind 精确逆操作+回补,deduct 子流水留痕不改史;
5. **配套纪律**:错误码先登记后使用(reserved 收编)、PO 加宽与生产列对齐、SQL 幂等且先于 jar、commit 自带生产实证与未完成项。

## 15. 营造 addon-balance 落地映射建议

| 老机制 | 营造去向 | 备注 |
|---|---|---|
| 三张结算单+三列桥表+动态聚合 | **一张结算单模型 balance_settlement + 结算明细快照行**(上游「明细可结算」事件携带金额快照落行) | 消灭动态聚合漂移(§8.3)与跨域 JOIN(§9);销售/选材/外销以 settlement_type 区分或三个插件各自聚合后提交结算单 |
| balance 资金头(amount/surplus/cutpay) | balance_settlement 三字段保留,服务端重算不信前端 | §8.4 三处信任前端显式修正 |
| 结算账户双腿(台账/出纳)+懒开户 | V1 直接收编 f384101d3 设计(§14.1-3) | 生产尚未部署=无存量包袱,直接按终态实现 |
| 扣款池 bind/unbind/deduct | 收编+补三守卫(池原子 SQL/unbind-deduct 状态守卫/金额>0) | §12-A3/A4/A7 |
| 四态状态机(销售)/审核档(选材) | 统一:初始→锁定→(确认/审核,接内核轻量审批可选)→结算 | §16-7 |
| 拆分功能 | V1 不迁(方林运营特性待拍板 §16-5);若迁必须明细级留痕 | |
| record 操作流水 | 统一内核审计流水(ActionLog 模式),结算动作必留痕 | |
| 选材/外销/销售三线 | sales 线随 addon-sales、选材线随 addon-quotation(或 balance 统一吸附)、外销线随 addon-sales V2 | 上游插件发布事件,balance 零上游知识 |
| 提款单/凭证图/apport 分摊/comcost/月结 | 不迁(提款 V2;其余死件) | |
| 码段/迁移 | **错误码 320000 建议;迁移 1300+**(310000/1200+ 已被 addon-inventory 占用) | 对齐 error.md 分段红线 |

## 16. 待业务方确认清单

1. **金额量纲定案**:balance 域千元制(tranargs 实锤)vs 模块文档「分」——迁移与对账以哪个为准?(营造统一最小单位后自动消失,仅影响存量数据迁移)
2. **账户/扣款闭环投产策略**:老系统改造(双腿+池)生产未部署;营造 V1 直接按终态实现,还是先做「应付台账+付款流水」最小闭环、扣款池 V2?
3. **grade A/C 语义**:销售桶恒 A/选材桶恒 C/A 级按 income 结算——供应商等级的行业定义与分级结算规则?
4. **确认(2)态去留**:balancesales 确认态生产仅 22 行(锁定→确认两步人工流程几乎不用)——V1 砍确认直通结算?
5. **拆分功能**:一拆二/门槛 500 是行业需求还是方林运营特性?已勾对明细可被拆走(对账口径突变)是否可接受?
6. **两族「选材」关系**:结算对象=报价域 selectionItem,材料域 soods「选材单」与它的业务关系(soods→quotation/sales 两侧归属结论是否需同步修正)?
7. **审核线形态**:选材线 audit/unaudit(待审/驳回)是否接内核轻量审批,还是保持单处理人状态机?
8. **完结规则**:balance 头两写点冲突(余量清零条件完结 vs 付款无条件完结)+生产零执行——V1 完结唯一定义(建议=余量清零 AND 已付款,单向无逆向)?
9. **2023 年 3 万行突增**:balance 主表 2023 批量数据是启用还是补录迁移?影响存量数据迁移口径。
10. **死件四件定案**:comcost/apport/balancedetail/balancepicture + balanceoales 字典 3/4 死键——确认不迁。

## 15. grade 语义收口(B 类#4 定案,2026-10-15)

老系统「grade 同名异义」两处:**balance 桶级 grade**(balancesales 按 brand+supplier+grade 聚桶,
销售桶恒 "A"/选材桶恒 "C" 硬编码)与 **material 单等级 grade**(company==1 硬编码等级升降,
"C"→"B" 魔法串)。营造收口:

- **唯一存续 = balance 桶级 grade,已 V2 终态落地**(V1301 `balance_settlement.grade`):
  上游事件携带、本域不解释、缺省 "A"、参与聚桶键(uk 维度)——老硬编码嫌疑的配置化改造兑现;
- **material 单等级 grade 不迁**:material 主链不迁(以 addon-sales 为唯一口径,material.md §13),
  company 硬编码等级升降随之消亡,同名异义在营造不存在;
- **当前实况**:上游(addon-sales 结算 post)恒发 "A"(`GRADE_DEFAULT` 常量),枚举位预留——
  多级分桶(如按客户等级 A/B/C 分结算桶)待真实业务出现再启用,不预设枚举字典;
- 消费方注意:grade 是字符串维度非布尔,聚桶键含 grade 意味着不同 grade 永不共桶。

## 17. 毛利与管理线子域实现定案(V1302,第 96 次回写)

- **两线两主体**:集团管理线主体=组织树根(单树一根,parent_id is null);分公司要求线主体=**根的直接子组织**(parent=根校验,320111);直接配置不反算(docs/14 铁律);
- **17 周期集**:年 `yyyy` 1+季 `yyyy-Qn` 4+月 `yyyy-MM` 12,服务端 standardCycles 生成,发布整年完整(320103/320104/320105);线值万分比 0<x≤10000(320106,对齐 A6 ppm 定案);
- **发布管道**:乐观并发闸(baseVersionId vs latest=id 最大,320107)→插版次→**append-only 全量 17 行快照**(历史一手证据免链回放)→diff 仅记 before≠after 行——🔴**首发(无前版本)跳过 diff**(第 96 次冒烟实锤:漏判时首发插 51 行 before=NULL 脏行);发布无删除无反发布;
- **集团线引用**:分公司快照行 group_*_ref 冗余拷贝**发布时点当期(最新)集团线值**(冒烟断言动态对照当期版本,修静态预期错位);集团行引用列 null(修 fl-yy 一行两用);
- **周期状态**:PENDING/NORMAL 查询期计算(年/季/月与当前日比较,空态原则禁假 0);实际值两列就位恒 null(F1 后新发布起填充);
- **权限=S2 基座**:集团守卫=操作者 scope 含根(替代 fl-yy orgId==222,320112);分公司=目标∈scope 且非根且 parent=根(320111);集团身份操作分公司线必须显式选组织(320110);前置=同年度集团线已发布且快照完整(320108/320109);
- **配置进度**:发布制无草稿态→NONE/COMPLETED 二态(PARTIAL 枚举位保留不可达);**冒烟姿势**:跨组织用户(example_mb)API 绑角色被同组织校验拒(122005)→DB 直插 yz_user_role+重写 menus 逐出权限缓存;
- PRD:docs/prd/addon-balance-margin.md(2026-10-28 按推荐拍板);八端点归 /api/balance/margin-lines 菜单闸;错误码 320100-320115。
