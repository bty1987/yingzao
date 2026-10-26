# L2 规则清单 - 款项域(addon-payment)

> 提取自:`D:\ERP\Rhadamanthus\src\com\fanglin\payment\`(185 个 Java 文件 / 19 个 ServiceImpl 约 9300 行,四路并行深读:装修款线 / 主材·销售款线 / 通用款项·支付转换·外销 / 外围款项族)
> 提取日期:2026-10-11(自动任务·第 9 次运行)
> 🔴 每条规则标注【方林嫌疑】:行业共性 vs 方林实现习惯,需业务方确认后决定去留
> 老系统款项错误码段 = 1500xx(150001-150099);营造 addon-payment 建议 **270000** 段,迁移 **800+**(顺延 basedata 260000/700+,待定)

## 0. 模块地图与名词表(款项域是"写遍全库"的枢纽域)

| 族 | 表 | 定位 |
|---|---|---|
| 装修款 | payment.decorationpayment + decorationreceipt | 装修合同款收/退/确认(定金→分期→尾款),枢纽中的枢纽 |
| 主材款 | payment.materialpayment + materialreceipt | 挂主材子单(material.mid),定金/补款二段式+退款族；**营造定案(material.md §13-3,B 类#3 收尾):不独立成线——主材销售款经 `sales_full` 协议键挂 sales 单 biz 三元组（addon-sales 唯一口径,material 主链不迁）,类型行名组织自建（展示名建议「材料款」）** |
| 销售款 | payment.salespayment + salesreceipt | 挂销售单(sales.sid)冗余 orderId,同构主材线+微信客户确认+批量收款 |
| 预付款 | payment.advancepayment + payment.payment + advancereceipt | 装修单维度**四科目余额台账**(合同/工程/主材/正林)+ 收付流水;积分域跨库直读 balance |
| 支付转换 | payment.paytran | **第三方渠道(POS/微信/支付宝)→各业务款的中间凭据**(不是内部转账),回调后按 type 分发落账 |
| 服务款 | payment.servicepayment + servicereceipt | 合同外服务费收款,拆"进预付款部分+服务费" |
| 代付款 | payment.proxypayment + proxyreceipt | 员工代客代购代收(refund 接口是空 TODO 桩) |
| 活动款 | payment.activitypayment + activityreceipt | 活动现场按会员号 mno 收款 |
| 外销款 | payment.oalespayment + oalesreceipt | 外销单(sales.oales)款,收款后 oales 置复核态 4 |
| 款项合同 | payment.paymentcontract | 款项环节电子签(法大大/ e签宝双厂商) |
| 款项积分 | payment.paymentActivity | 款项产生积分台账(realMoney×multiple÷1e6) |
| 功能字典 | payment.paymentfunction + paymentfunctionright | 页面配置字典(按钮/条件/列/API),见 §9 |
| 钱包族 | wallet/walletard/walletran/walletres | **半成品**(状态机断裂/银行卡明文/大量空桩),不建议迁移 |
| 化石 | payment.receipt / payment.statement | receipt 无任何写入点(疑似外部 ETL 只读台账);statement=渠道对账单只读展示 |

【方林嫌疑·命名】三线收款实体字段名互不一致(paymentStatus/status、referMoney 同名但守卫各异)、`paySacle`(拼写错误)/`bompany`(双公司维度)/`zaterial`(正林)——化石层,营造统一命名。

## 1. 金额口径(最高优先级红线)

1. **统一底座 = long 厘(1/1000 元)**:Arith.DEF_SCALE=3,展示 `Arith.div(x,1000.0)`+`%.3f` 还原元——四路提取零例外实锤(与装修单域"1e6 与 1e3 并存"的印象不同,**款项域内部全厘**);
2. **例外两处**:活动物品 decorationactivityitem.money 按 **1e6** 存(DAO 桥接折算成厘);paytran.money 由"各渠道各自缩放约定"维持(wxpay 发起 ×10000、alipay 原样、swiftpass 回调 ×1000、postar 回调 ×10000),makePayment 用金额相等断言兜底——**渠道黑魔法,营造禁**;
3. **方向用 type 表达、recharge 恒正**:`payment.payment` 聚合口径 = type∈{1 收款,3 退回}为正、{0 提现,2 付款}为负,仅计 status∈{1,2};balance 是**操作后余额快照**非本笔发生额;
4. **元级截断**:签约生成款线 `mul(div(money,1e6),1e6)` 取整到元;服务款预付部分 `(money/ratio/1e6)*1e6` 双重整除截断到千元整数倍(比率缺省 1100);
5. **比例制式三套并存**:paymentscaleitem.scale=十万分比(100000=100%,多项分摊前 n-1 项向上取整到 100 元倍数、末项兜尾差);装修单 payScale/saleScale=百分比 0-100(超 100 封顶);discount 万分比(10000 不打折);
6. **1918 魔法哨兵**:销售比例默认值 1918(=19.18%),计算路径封顶吞掉、Midpack 路径裸写——同值两种待遇【方林嫌疑】;
7. **收款平衡公式**:单笔 `Σ渠道==referMoney`;补款总额公式 `total==coupon+venderMaling+定金+本次 && total==明细合计`(双校验);**realMoney(实收打印位)客户端传什么存什么、零校验**。

## 2. 通用收款生命周期(全族同构状态机)

```
0 初始/未收 ──collect──▶ 1 已收 ──confirm──▶ 2 已确认(终态)
                 └─refund(退款执行,收据 type=1)─┘        3 已撤销/作废(个别族)
```

- **守卫三件套(全族复用)**:150003 款项不存在 → 150036 款项状态不可操作 → 150008 款项不能进行收款(渠道合计≠应收);主材批量第二轮同条件用了 **150021**(同码双码漂移实锤);
- **幂等 = 状态位读-判-写**:全模块无 @Version、无条件 UPDATE,并发双收款窗口存在;confirm 批量对非目标态**静默跳过**(天然幂等,与 150036 抛错两种风格并存);
- **收据双人留痕**:collecter/confirmer+姓名快照+双时间戳,行业共性保留;**"收款即确认"是前端联动**(decorationPayment.js collect 成功后紧接着 post confirm),服务端无保障【方林嫌疑:财务确认让位于体验】;
- **销售线独有**:客户微信确认 isAffirm(验证码 330012+电话匹配 130115+绑定校验 330017)与财务 confirm 完全独立;落款门槛校验的是 sales 级 isAffirm(两个不同字段,疑漏)。
- 【营造实现定案回写(第 81 次,A1)】**部分退款统一入口(PaymentApi.PartialRefundCmd)**:amount 可空(空=整单退,等价原 RefundCmd 委派);校验 0<amount≤原单额;**RefundApprovalConsumer 审批通过后仅整单退(refundAmount≥originAmount)才置原单终态 3,部分退原单状态不动、净额由聚合口径体现**——修老「退款即清零」家族缺陷(§7 服务款清零常量实锤)的营造回答:原单留痕+净额可算;内核轻量审批(270012 幂等闸)天然防重。验证第 81 次报告。

## 3. 装修款线(decorationpayment,规则最密)

- **双类型位**:oneType(0 收/1 退/2 历史)× twoType(0 基础/1 签约款/2 增减项/3 其他)+ paymentStatus 五态(0/1/2 + 3 审批中 + 4 历史);
- **paymentType 是中文字符串**且承担业务分支(`contains("退款")`、`equals("设计费")`)——改名即坏【方林嫌疑:字典行值当协议】;
- **sort 顺序门控**:sort>1 时前一笔(sort-1)未收 → 150009 前置款项未处理;sort=0(成本/补充利润款)不参与门控也不计入付款比例【方林嫌疑:对内隐蔽成本收款线】;
- **比例双闸**:收款前校验 `decoration.payScale < type.payScale → 120018`、`saleScale < type.saleScale → 120033`——用"销售比例"逼单是家装公司管控玩法【方林嫌疑】;
- **签约生成款线 makeDecorationPayment**:按比例模板拆分(sumMoney=提交额元级取整 − 活动优惠;多项向上取整 100 元+末项兜尾差);存量有已收款 → 150001 拒;未收基础款**硬删重建**;线上活动物品强制核销 state=10;
- **首笔收款**:装修单 INIT(0)→DEPOSIT(1)+depositTime——任何款项首收都触发"视为定金";
- **退款双轨(行为不一致)**:直接退款原地改写 oneType 0→1(历史语义丢失);审批流退款(APPLYING 3→通过)新建退款项+原项转 4 HISTORY;**APPLYING 提交无前置状态校验、驳回一律回写 2 CONFIRM**(PAY 态被顶成 CONFIRM,无状态快照);设计费退款又跳过审批直接退(守卫只挡 UNPAY)——三轨并存【方林嫌疑:审批纪律靠自觉】;
- **增减项联动**:twoType=2 收款 → quotation.addreduce 全部置 2 PAYED+sendTime + 逐条生成定额 + 钉钉群广播 + 小程序客户确认;
- **预付款划扣**:advance>0 时按 advanceType 选池(1 余额/2 工程款),`advancePaymentBalance` 校验后 updateAdvancePayment 扣减,流水 uniqueid=`D{dpid}`/`D{dpid}R`;
- **150040 漂移实锤**:本模块当"设计费金额不能超过%s元"用,error.md 在册为"补款已存在"(同码不同消息)。

## 4. 预付款台账(advancepayment 四科目 + payment 流水)

- **一单一行四科目余额**:balance/projectBalance/materialBalance/zhenglinBalance(合同/工程/主材/正林)【方林嫌疑:正林=关联公司第二品牌,以**列**而非数据维度区分,必须收敛为科目字典】;
- **payment.payment 流水**:type(0 提现/1 收款/2 付款/3 退回)× paymentType(0-3 科目)× status(0 初始/1 已付/2 已确认/3 撤销);collect/withdraw **只允许科目 0/2**(150028);
- **审批锁**:存在预付款提现审批(含 REJECT 态)→ 240076 拒绝收付(驳回也锁,防审批通过瞬间并发——老系统少有的并发意识亮点);
- **🔴 三条路径写同一余额,守卫强度不一**:updateAdvancePayment(无类型白名单无审批锁)/payPayment/withdrawPayment;余额读-改-写无锁,**DAO 备好的原子扣减 SQL(increase/decrease with balance>=delta)是死代码**;addPayment 只写流水**不动余额**(快照脱节);
- 小程序充值链:paytran 回调 → makePayment case0 → collectPayment(status==0 检查 150036 + 金额不符静默 return 等下次回调)。

## 5. 主材/销售款线(同构双线)

- **定金/补款二段式 + 退款族**:type 2 定金/3 补款/4 退款/5 补款退款/6 券退款/7 定金退款(销售线另 8 不可退定金,无 0/1);定金在 → 补款守卫链(150040 已存在/150041 定金状态不可操作/150017 总额不符);
- **批量收款 N:1 收据挂接**:先插一张 receipt,每笔 payment 的 FK(srid/mrid)指向它;srId 三重身份 = paytran 回写锚点 + 小程序收据 URL 参数 + 确认页反查入口;**先 save 收据/先扣预付款、后校验合计**的先写后验反模式(靠事务回滚兜底);
- **直营单特例**(sales.type=1):退款自动生成收据且直接置已确认;销售款统计口径 `amountSalesPayment` 要求直营单 balancestatus=3(结算完成)才计入;
- **厂商结算联动**:确认时 type=8→advSalesBalance、3/5→rev+preSalesBalance(写 sales.salesbalance 置 balanceStatus=1,前置查审批中 → 240007);
- **作废联动**:主材定金退款生成 → material.status=8 作废;销售定金退款 → sales.status=4 作废 + 到账状态表;
- **守卫用错余额字段**:advanceType=正林时应查 zhenglinBalance,前置守卫固定查 materialBalance——六处同错【方林嫌疑:第三科目是后补的,守卫没跟上】。

## 6. 支付转换(paytran,渠道枢纽)

- **定位**:第三方渠道资金 → ERP 各业务款的中间凭据;status 0 初始 → 1 成功(渠道回调)→ 4 已用(落账挂收据)/2 失败/3 失效;makePayment 按 type 分发到六个收款 Service;
- **分次支付**:回调聚合同 (orderId,type,pid) 全部 SUCCESS 的 paidMoney ≠ 应收时**静默 return** 等下次回调(不报错不留痕);
- **在途保护**:pushTime 2 分钟内再查 → 150045;sn 幂等:同 sn 有初始单 → 150048;
- **渠道适配 = systemConfig 四级 key 回退 + 反射加载 `<类名>.Airpos`**【方林嫌疑:约定类名反射,无接口抽象】——营造改造方向:**PaymentChannelProvider 扩展点(addons-api),渠道做成插件**;
- **外销款断链**:makePayment case8 收款路径 ptids 参数全程未用,paytran 永不置 USED(与预付款路径不对称);failPayment 只覆盖 type 0/4(装修/销售/外销支付失败无状态补偿);
- 回调单号靠字符串下标反解 ptid(outOrderNo=yyyyMMdd+ptid+4 位随机)——格式耦合。

## 7. 外围款族(择要)

| 族 | 要点 | 营造取舍建议 |
|---|---|---|
| 服务款 | 拆 advanceMoney/serviceMoney(比率按公司 systemConfig 缺省 1100,双重截断到千元);封顶依赖 decorationholonomy(260086);**退款把已收服务费清零**(清零常量替代减法)是高危缺陷;collect 每次新建无守卫可无限重复收款 | V2,重做拆分与封顶,退款改减法 |
| 代付款 | 收/确认/取消齐,refund 空 TODO 桩;**页面无公司过滤**(跨公司可见) | V3 |
| 活动款 | 收款不校验原状态(可重复收);PO 注释 type 0/1 但统计 SQL 用 type in (2,3)——口径漂移 | 随活动插件 V3 |
| 外销款 | 0→1→2 三态简明;一单仅一笔(150029);收款后 oales→复核 4 | 随外销业务 V3 |
| 款项合同 | 电子签双厂商(法大大/e签宝)按 company×场景路由;状态 0→1 已签→3 归档/2 无效;公司盖章不改状态 | 随电子签插件 |
| 钱包族 | 钱包/绑卡/流水/附件四表;**绑卡状态机断裂(有效态不可达,解绑死路)、银行卡号/CVV 明文入库、三个空桩接口、加款无上限**;转账倒是用了原子 SQL(全模块唯一) | 🔴 不迁移(半成品),需求出现时按"账户中心"重做 |
| 积分台账 | paymentActivity:realMoney×multiple÷1e6;与 salesactivityitem 兑换积分对冲;换绑活动的额度校验是 TODO | 随会员/积分插件【✅已裁决 2026-10-13 member.md】paymentactivity **残缺不迁**(唯一写点=服务款,check* 全死,String.valueOf(null) 缺陷);营造积分记账改**事件驱动**(消费收款确认事件→addon-member 分项账户),对冲台账由 member 兑换单分摊映射承担,见 docs/prd/addon-member.md;activitypayment 死代码(生产 0 行)随 V 系不迁 |
| 对账 statement | 渠道对账单纯只读展示,写入方在 ERP 之外 | 归报表/对账插件,V3 |

## 8. 跨模块写表面(款项域 = 全库枢纽,营造必须切事件)

收款/确认动作直写他模块表(全部同步同事务):

- **decoration.decoration**:orderTotalPrice/factPrice(签约回写)、status+depositTime(首收升定金)、payScale(付款比例);
- **quotation.addreduce**:增减项收款置 status=2 + 生成定额(normtion);
- **sales.sales**:depositpayStatus/fullpayStatus/fullpayTime/ctype/cvalue/**pompany(总部收款改写归属公司)**/status=4 作废;sales.salesbalance(厂商结算);
- **material.material**:depositPayment/depositStatus/fullPayment/fullStatus/status=8 作废;material.materialrefund(裸 SQL);
- **sales.oales**:status=4 复核;
- 装修附属:decorationmidpack/decorationholonomy.materialPaidup(销售比例聚合)、decorationactivityitem(核销 state=10);
- 通知四路:站内信(监理)/小程序推送(客户)/钉钉群(增减项·预付款·审批)/messagequeuing 事件表。【✅第35次核对勘误:原文“messagecueuing 消费端在仓库之外”失实——information.md 考古实证 messagecueuing 32.2 万行零消费假队列,真消费表是 messagequeuing(且 44% 失败无重试);营造侧由内核事件总线 V104 三件套收口】

【营造映射】每族收款/退款/确认发领域事件(PaymentCollectedEvent 等,addons-api 契约),回写归各业务插件自己监听——这是 01 清单"款项是枢纽域"的解耦落法,与通知中心同分层(内核/发布方不认识订阅方)。

## 9. PaymentFunction 功能字典(思想吸收,01 清单 right 域另一半)

- 三层结构:**字典行**(paymentfunction:name/key/value/group 页面位掩码 1预付/2装修/4主材/8销售/16服务/32活动/64代收,type=0 字典行/1 API 行)→ **授权行**(paymentfunctionright:name=页面标识 × type=页面位 × company,按页面+公司勾选即全量替换)→ **前端装配**(getConfig 按 sort 排序,把 value 按 key.split(".") 装进 pageConfig{apiPage/pageBtn/condArr/extraParam/propertyArr}——按钮/查询条件/列/跳转 API 全部 DB 驱动);
- 授权粒度 = **(页面,公司),无用户/角色维度**;用户级控制另靠拦截器——两套口径并存【方林嫌疑:思想好实现散】;
- **营造归属**:功能点=capability 已是 RBAC v1 授权身份(上半场完成);查询条件/列配置化作为 capability 命名空间(`query:<entity>:<field>`)列入 RBAC v2 设计稿 §6 可选段——同源思想收编进统一模型,不再独立建 functionright 族表。

## 10. 缺陷清单(提取实锤,营造红线来源)

1. **金额符号未校验可反转**:collect 对负数 recharge 先加后 abs(负收款实为减钱流水记正);withdraw/payPayment 负数绕过余额检查反向加钱——营造所有金额入参强制 >0 校验;
2. **addPayment 写流水不动余额**(快照脱节);三条路径写同一余额守卫不一;
3. **confirmPayment NPE**:null 判断在 getAdvanceReceipt() 之后(顺序颠倒);
4. **错误码漂移一族**:150040(设计费限额 vs 补款已存在)/150054(更新秘钥 vs 钱包不存在)/150073(两文案)/150021 vs 150036 同条件双码/150059 vs 150094 同文案双码/370001 vs 260021 销售单不存在两套码;
5. **先写后验**(批收先 save 收据/先扣预付款再验合计);**静默跳过与静默 return**(confirm 跳过、分次支付 return、paytran 金额不符 return);
6. **引用比较死代码**(`orderId == ""`)、format 串丢参数、`.get(0)` 取"唯一"记录;
7. **事务内网络调用**(钉钉/微信/渠道 HTTP 在 @Transactional 内);
8. **company 纪律不均**:装修款收款用登录态 company 覆盖款项行、代付款页面无公司过滤、活动款/钱包无 company 字段——多租户隔离靠约定;
9. 服务款退款清零已收服务费(§7);钱包绑卡状态机断裂+卡明文(§7);
10. 明文 AES 公司码由前端回传解码(bompany)——服务端不可信输入当租户路由。

## 11. 方林嫌疑机制级汇总(去留需业务拍板)

| 条目 | 判定 |
|---|---|
| 正林第二品牌预付款科目(列级硬编码)+文案进 switch | 公司玩法,收敛为科目字典 |
| bompany 双公司主体(签约公司 vs 收款公司)+grade A/B 商户等级路由 | 公司玩法(多收单主体) |
| saleScale/payScale 比例双闸逼单 + 1918 哨兵 | 公司管控玩法,行业少见 |
| 成本/补充利润款(sort=0 隐蔽收款线) | 公司玩法(对内双线记账) |
| 钉钉群广播+钉钉审批(预付款提现) | 公司基础设施依赖 → 通知中心+轻量审批替代 |
| "收款即确认"前端联动 | 公司习惯 → 服务端语义化(见 §12 待确认) |
| pompany 总部收款改写业绩归属 | 公司玩法 |
| 收款业绩记设计师名下(codepay 以设计师身份发起) | 公司玩法 |
| 微信客户确认留证链(isAffirm 验证码+手机匹配) | 半共性(电子确认单),实现公司化 |
| 渠道反射适配+四级配置回退 | 公司习惯 → PaymentChannelProvider 扩展点 |
| 状态机三件套/收据双人留痕/审批锁/原子扣减 SQL(虽有) | **行业共性,保留思想** |

## 12. 营造 addon-payment 落地映射建议

| 老概念 | 营造归属 | 状态 |
|---|---|---|
| 统一收据模型(收/退 + 双人留痕 + 渠道行聚合,N:1 批量挂接) | addon-payment 核心 Receipt | V1 |
| 款项单(Bill:类型枚举化禁中文名分支 + 0→1→2 状态机 + 顺序门控) | addon-payment 核心 | V1 |
| 款项类型字典(PaymentType:类型/顺序/比例模板) | addon-payment 配置域(addon.yml/组织字典) | V1 |
| 预付款 = 科目字典化余额表 + 流水表,**原子 SQL 扣减**(老 DAO 死代码复活) | addon-payment | V1 |
| 跨模块回写 → 领域事件(各业务插件自监听) | addons-api 契约 | V1 架构红线 |
| 轻量审批接退款(废三轨并存的退款纪律) | 内核 /api/approvals | V1 |
| 渠道适配 PaymentChannelProvider 扩展点(渠道=插件) | addons-api + 内置微信/POS 插件 | V2 |
| 服务款拆分/封顶重做;客户确认(isAffirm)电子收据 | addon-payment V2 | V2 |
| 批量收款 N:1 收据 + 分次支付聚合 | addon-payment | V2 |
| PaymentFunction 思想 | RBAC capability + 查询条件配置化(RBAC v2) | 已收编 |
| 钱包族/代付款/活动款/对账 | 账户中心插件/活动插件/报表插件 | V3 |
| 错误码段 | **270000 建议**(老 1500xx 不映射) | 待定 |

## 13. 待业务方确认清单

1. 金额单位:全库厘已实锤——营造统一"分(1e2)"还是沿用"厘(1e3)"?(历史数据迁移换算口径随之而定)
2. 款项类型"配置即协议"(PaymentType 字典行+中文名分支)改造为枚举+字典的边界:哪些类型保留可配置(设计费限额类),哪些固化为枚举(定金/补款/退款族)?
3. sort 顺序门控保留(行业共性);**比例双闸(120018/120033)是否保留**——销售比例逼单是公司玩法,建议 V1 不做、归销售提成插件;
4. 退款纪律统一走轻量审批(直接退款仅限初始态小额/已确认款必审批)?设计费跳审批的双轨是否废弃?
5. "收款即确认":服务端 collect 一步到位(收+确认同事务),还是保留 collect/confirm 两步给财务留复核职能?
6. 预付款科目模型:合同/工程/主材三科目 V1 固化,正林类第二品牌场景以"组织自定义科目字典"承载?
7. 客户线上确认(isAffirm 验证码+微信手机号)是否 V1 保留(涉短信/微信凭证依赖)?
8. 批量收款 N:1 收据挂接模型保留确认(收据=财务凭证主档);
9. 钱包族不迁移、代付款/活动款/外销款/对账 V3 的优先级确认;
10. 渠道插件化(微信/支付宝/POS)的 V2 优先级与首批渠道清单(依赖企业凭证,已在全局待决策清单)。

## 15. 款项类型协议键登记(B 类#3 定案,2026-10-13)

`payment_bill_type.code` 是**跨插件协议键**(createBill 硬依赖组织内启用类型行,270004;类型行组织自建、协议键全局约定)。
协议键清单(新增契约在此登记,禁止另起同义键):

| 协议键 | 归属插件 | 语义 | 落点 |
|---|---|---|---|
| `deposit` | addon-decoration | 装修定金(首收升定金线) | DecorationService.TYPE_CODE_DEPOSIT |
| `sales_full` | addon-sales | 主材销售全款线=**材料款唯一口径**(material.md §13-3 定案,materialpayment 不迁);V2 起金额=总额-定金(尾款线) | SalesOrderService.TYPE_CODE_FULL |
| `sales_deposit` | addon-sales | 主材销售定金线(V2,2026-10-18 第 65 次;`deposit` 已被装修定金占用故另起键;sort 建议先于 sales_full=字典顺序门控先定金后尾款;deposit_amount=0 不生成) | SalesOrderService.TYPE_CODE_DEPOSIT |
| `ar_diff` | addon-quotation | 增减差量款(提交时懒预置) | QuotationAddReduceService.TYPE_CODE |
| (签约拆分组) | 组织自建 | 签约款线按比例模板(scale_template JSON ‰)拆分的类型组,code 不进协议、name 组织自定义 | addon-decoration sign() |

约定:协议键只增不改;类型行由组织在「款项类型」页自建(冒烟/部署清单含 deposit/sales_full 两条);
展示名是组织数据,**材料款建议展示名直接用「材料款」**(消费方对账按 code 不按 name)。
**createBill 活单语义(第 67 次修正)**:同组同类型查重只拦活单(status 0 未收/1 已收/2 已确认);终态(3 已退款/4 已作废)不占同类型名额——退款后重开同类型新单是合法业务(退定金重收首踩);建单回取 id 用连接级 last_insert_id(业务键回取会命中终态历史单多行)。
