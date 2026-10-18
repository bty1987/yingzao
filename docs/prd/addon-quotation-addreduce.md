# PRD - addon-quotation 增减线 V2(合同后变更/差量单)

> 状态:设计定稿(2026-10-16,按推荐口径推进;用户关注点=增减单逻辑与老系统对齐)。
> 上游依据:rules/quotation.md §6(增减项老口径)、§3.1(量纲红线:统一厘,禁 1e6)、§12(定额三来源重算);
> docs/prd/addon-quotation.md 核心流程第 6 条(V2 预留);docs/prd/addon-payment.md(款项三件套已交付);
> docs/rules/decoration.md §3(重签前置「无增减项」)。
> 码段:230000 段延续(增减子域 230100-230199);迁移 V403+。

## 1. 一句话

合同(签约)之后,价格不能改报价本体,只能开**差量单**:对已确认报价行做增/减/漏项修正,
单事务聚合出**一笔正补负退的差量款项**,经领域事件由 addon-payment 承接生成款项单;
付款→确认两步回写差量单状态;差量行同时作为**定额重算与决算金额的第三来源**。

## 2. 角色与闭环

- **角色**:预算员(开单/提交)、客户(付款)、财务(确认)、装修单状态机(消费差量状态);
- **闭环起点**:装修单 status=3(已签约)且存在已锁定+审核完成的默认报价;
- **闭环终点**:差量单 status=3(已确认)且差量款项=已确认;金额全部反映进该单的「确认价格合计 = 报价折后合计 + Σ已确认差量」;
- **异常路径**:提交时聚合为零拒(230105)、审批中不可再提交(230151)、已付款不可撤(230152)、
  挂定材包的报价被差量引用后不可解绑包(230153)、非签约态拒(230154)、重签前置「存在未确认差量单」拒(230155)。

## 3. 数据模型(V403,单表+明细,零跨域表)

```
quotation_add_reduce(增减单主表)
  id, org_id, order_id(经 DecorationApi 校验), ar_no(AR+yyMMdd+4位流水,uk org+no)
  quotation_id(关联默认报价,锁定快照), status(0草稿/1已提交/2已付款/3已确认)
  type_total_add / type_total_sub(厘,服务端聚合)
  dp_id(差量款项单 id,addon-payment;提交后回写) / ar_time / confirm_time
  created_by / created_at / updated_at
  uk(org_id, ar_no);key(org_id, order_id, status)

quotation_add_reduce_item(明细行)
  id, org_id, ar_id, item_type(0 增/1 减/2 漏项)
  ref_qiid(关联原报价功能项,可空=纯新增行)
  goods_id / goods_name(快照) / unit
  unit_price(厘,原单价快照) / num(千分位,差量:正增负减) / add_price(厘,本次差量金额,服务端算)
  created_at
  key(ar_id);key(org_id, ref_qiid)
```

- **金额红线**:全厘;`add_price = num × unit_price`(服务端算,禁前端传入);主表聚合 Σ正=type_total_add、Σ负绝对值=type_total_sub;
- **差量语义**:增项 num>0(补款);减项 num<0(退款,绝对值计入 sub);漏项=漏报项补录(type=2,语义保留但流程与增项一致,方林追责玩法不迁);
- **paytype(COM/ARO 公司承担)不迁**:方林财务口径,差量全部走客户补/退(行业共性),V2.1 若有真实诉求再加承担方维度。

## 4. 状态机与守卫(显式迁移表)

```
0 草稿 ──submit──► 1 已提交(生成差量款项单,回写 dp_id)
1 ──付款确认事件──► 2 已付款 ──confirm(财务)──► 3 已确认(终态)
0/1 ──cancel──► 删除(草稿可删;已提交仅未付款可撤=置已撤标记后归档,状态回 0 记忆)
```

守卫:
- **submit 前置**:主单 status=3(签约)、默认报价 status=1 且 attstatus=3、明细非空、Σ差量 ≠ 0(零差量拒 230105)、无进行中审批;
- **submit 动作**(单事务):服务端重算全部 add_price → 聚合 → 调 PaymentApi.createBill(typeCode=`ar_diff`,name 快照「增减差量款」)→ 回写 dp_id → 发 `QuotationAddReduceSubmittedEvent`(携 arId/orderId/总额/明细摘要)→ payment 域零知识;
- **付款回程**:PaymentConfirmedEvent(bizAddon=quotation, bizType=ar_diff, bizId=dp_id)→ 差量单 1→2;
- **confirm**:财务在 addon-quotation 端点确认 → 2→3(终态)→ 差量金额并入「确认价格合计」;
- **cancel 语义**:0 草稿=物理删(含明细);1 已提交未付款=撤销(置 withdrawn_at 归档,状态回 0 可改,差量款项单经 PaymentApi.cancelBill 作废);
- **联动闸(双向)**:①重签前置校验加「无未确认差量单」(230155);②默认报价挂定材包后,存在已确认差量单则不可解绑(230153);
- **决算联动(B 类#6 增量)**:决算六项之外,决算金额口径=折后合计+Σ已确认差量(新检查项,组织级开关随决算六项配置)。

## 5. 定额重算(三来源收敛)

老系统定额三来源=①默认报价 ②拓展项 ③增减单(九组展开)。营造 V2 收敛:

- **定额来源 = 默认报价明细 + Σ已确认差量行**(按 ref_qiid 聚合到报价功能项,net = 原量 + Σ差量);
- 拓展项来源不迁(Extension 随 V3);重算触发 = 差量 confirm(事件 `QuotationAddReduceConfirmedEvent`,消费方=未来的定额子域,当前仅留事件不设消费者——与「事件空转留痕」惯例一致)。

## 6. 契约与事件(addons-api)

```java
QuotationAddReduceSubmittedEvent(arId, orgId, orderId, dpId, totalAdd, totalSub, memo)
QuotationAddReduceConfirmedEvent(arId, orgId, orderId, netTotal)   // 定额重算/报表消费预留
```

- 均走事件总线(eventKey=`ar-submitted-{arId}` / `ar-confirmed-{arId}`,uk 幂等锚);
- addon-payment 首版不消费(差量款项单由 quotation 直接经 PaymentApi.createBill 落——**与老系统「提交即生成款项」对齐**;
  事件留给「审批化差量」V2.1 与报表/定额消费方)。

## 7. 端点(230100 段,归 /api/quotation prefix 菜单闸)

```
POST   /api/quotation/ar                          开差量单(草稿;body: orderId+明细)
PUT    /api/quotation/ar/{id}                     改草稿(全量替换明细,重算)
DELETE /api/quotation/ar/{id}                     删草稿
POST   /api/quotation/ar/{id}/submit              提交(生成差量款项)
POST   /api/quotation/ar/{id}/cancel-submit       撤销提交(未付款)
POST   /api/quotation/ar/{id}/confirm             财务确认(2→3)
GET    /api/quotation/ar?orderId=&status=         分页
GET    /api/quotation/ar/{id}                     详情(含明细)
```

## 8. Web(Quotations.vue 增补)

- 报价详情抽屉加「增减差量」页签:差量单列表(状态徽章)+ 开单/编辑弹窗(明细行编辑:类型/原项引用/单价/数量,服务端实时算 add_price)+ 提交/撤销/确认按钮;
- 装修单详情(Decorations.vue)状态四格外加「差量合计」显示(Σ已确认差量)。

## 9. 冒烟验收(全链 ≥15 步)

签约单 → 开差量单(增 1 行+减 1 行引用原报价项)→ 改草稿重算 → 提交(款项单生成断言+dp_id 回写+事件总线登记)→
PaymentConfirmed 回程置 2 → confirm 置 3 → 零差量提交拒 230150 → 未付款撤销 → 重签前置拒绝 230155 →
挂包后解绑拒 230153 → 非签约单拒 230154 → 分页/详情。

## 10. 非目标(V2.1+/不迁)

- paytype COM/ARO 公司承担方(方林财务口径);
- 漏项追责流程(type 枚举位保留);
- 差量审批化(V2.1 走内核轻量审批,当前财务确认即终态);
- 增减单与电子签/合同文本联动。
