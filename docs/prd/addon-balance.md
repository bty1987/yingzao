# PRD - addon-balance 结算

> 状态:草稿(2026-10-11,待业务确认)
> 老系统参考:balance 模块(实现者回溯指针见文末;实现口径见 `docs/rules/balance.md`,二者同日产出)
> 依赖:addon-payment(付款/收据模型)、上游结算源 addon-sales(销售明细/外销)与 addon-quotation(选材项)——**balance 零上游知识,只消费「明细可结算」事件**

## 业务闭环(一句话)

结算员把上游送来的「可结算明细」(销售结算行/选材项/外销单)按**品牌+供应商**聚合成结算单,经锁定(可选审核)确认后**服务端重算金额**生成厂商应付,经扣款冲抵与付款收尾,终态落在「结算单已结算 + 厂商结算账户台账(应付/实付/净头寸) + 全程操作流水」。

## 用户与场景

| 角色 | 场景 | 高频动作 |
|---|---|---|
| 结算员 | 厂商应付结算 | 勾选可结算明细聚桶(或确认自动吸附)→ 锁定 → (送审)→ 结算入账 |
| 结算员 | 差异处理 | 挂起异常明细(pending)、解绑、改折扣(仅锁定前) |
| 财务/出纳 | 付款收尾 | 按结算账户台账腿入账核销应付、出纳现金腿付款、批量打付款标 |
| 结算员/财务 | 扣款管理 | 维护厂商扣款池(保证金/合作费/年返…)→ bind 冲抵结算单 → unbind 回补 |
| 供应商(外部视角,V2) | 对账确认 | 查看自己名下结算单/账户流水(V1 先内部) |
| 审计 | 流水追溯 | 按结算单查全生命周期操作流水与账户明细 |

## 核心流程(状态机文字版)

1. **结算单(统一模型,一张表按 settlement_type 区分销售/选材/外销)**:初始(可增删明细/改折扣)→ 锁定(金额快照冻结,**快照落行而非动态聚合**——老系统动态 SUM 是金额漂移根源)→ [选材线:待审→审核,走内核轻量审批可选] → 结算(终态;生成/同步资金头,生成厂商应付);解锁仅锁定态可退回初始;
2. **明细挂接(事件化,营造核心改造)**:上游插件在明细就绪时发布「明细可结算」事件(携带**金额快照**:应收/让利/应付/数量/单价),balance 监听后按「品牌+供应商+等级配置」聚桶落快照行;解绑回发「明细退结算」事件,上游回置自己对账状态——balance 零读上游表、零直写上游表(修老跨域 JOIN+回写三字段);
3. **金额口径(服务端强重算)**:income/cost/payment/profit 由快照行聚合得出,**不信任前端**(修老三处信任前端+A 级按 income 结的隐前端规则——等级口径显式化为账户/结算配置);减项类型取负规则在快照生成侧完成,balance 只做加总;
4. **资金头与账户**:结算动作生成资金头(amount=应付,surplus=amount−cutpay)并联动归属人**结算账户**;入账双腿——结算账户(应付台账腿:referMoney+=,surplus+=,结算单 surplus−=,原子 SQL 守卫)/出纳现金腿(付款:realMoney+=,surplus−= 原子守卫,支出单懒开户同步台账,允许预付透支);
5. **扣款池**:建池(初始额度)→ 扣款单 bind 冲抵结算单(池 surplus−= 原子守卫)→ deduct 拆子流水留痕 → unbind 回补;**全链金额 >0 校验 + 状态守卫**(修老负数反转守卫/未绑先解绑/池扣负三缺陷);
6. **完结(唯一定义,营造裁定)**:surplus==0 **且** 已付款 → 完结,单向无逆向(修老条件/无条件两写点冲突);付款标(批量)幂等,重复提交拒绝;
7. **拆分**:V1 不迁(待拍板 §rules/balance.md 16-5);若迁,明细级留痕+已锁定明细禁止拆。

## 闭环校验

- 闭环起点:第一条「明细可结算」事件被聚桶(结算单诞生);
- 闭环终点(终态):结算单已结算 + 资金头完结(余量清零且已付款)+ 账户台账余额可追溯;
- 异常路径:解绑/挂起退回上游(上游回置对账状态);扣款 unbind 精确逆操作;付款拒绝(余额不足/账户停用/提款单类禁入账)全部显式错误码;
- 原子性:全部资金字段走 `UPDATE … WHERE surplus>=:money` 原子守卫(含扣款池,修老漏网);完结/付款幂等守卫防重复;
- 组织隔离:结算单/账户/池/流水全链 org_id 过滤(老 queryBalanceAccount 跨公司可见缺陷不迁)。

## 非目标(本期不做)

- 提款单(账户间资金调拨)——V2,随多账户体系需求;
- 分摊尾差(apport)与凭证图(balancepicture)——尾差被快照化聚合天然消灭,凭证图归通用附件;
- comcost 公司成本(生产 0 行死功能)不迁;
- 拆分功能 V1 不迁(待拍板);选材/外销线各自的行业差异化聚合逻辑归上游插件,balance 只收快照;
- 供应商外部对账门户(uni-app/Web)——V2 随 addon-supplier;
- 渠道返点/厂结三段式(vbalance/pbalance 双值)——归 addon-sales 结算模型(rules/balance.md 只管「已就绪明细」之后的应付闭环);
- 月份结转/月末快照——账户余额实时可查,报表走流水聚合。

## 验收标准

- [ ] 上游事件→自动聚桶:同品牌+供应商+配置等级的明细落入同一初始结算单;并发同键只建一个桶(唯一约束);
- [ ] 结算单 0→1→(2→3 选材)→结算全流转+解锁回退;终态后所有写操作拒绝(显式错误码);
- [ ] 金额服务端重算:前端篡改 income/payment 被覆盖;锁定后金额不随后上游变化(快照行);
- [ ] 资金头完结唯一路径=surplus 清零且已付款;重复入账/重复付款/重复打标全部拒绝(幂等);
- [ ] 双腿:结算账户入账后 referMoney/surplus/结算单 surplus 三方一致;出纳付款原子守卫,余额不足拒绝;懒开户自动建账;
- [ ] 扣款池:bind→deduct→unbind 全链金额守卫,负数/超扣/未绑先解绑全部拒绝;池原子 SQL 守卫;
- [ ] 全程操作流水(含结算动作)可查,中文文案零硬编码(走 i18n);
- [ ] 错误码 320000 段双语零漂移,字典走 dicts.yml(修正老 balanceOales 字典 5 态 vs 实体 3 态类漂移);
- [ ] balance 代码零出现 sales/quotation 表名与上游 service 引用(军规 2,事件契约走 addons-api);
- [ ] Web 页面:结算工作台(三来源 tab)/结算单详情(明细快照+流水)/扣款池管理/结算账户台账四页,菜单由 addon.yml 下发。

## 端覆盖

- Web 后台:结算工作台(初始桶处理/锁定/送审/结算)、结算单详情(快照明细+资金头+操作流水 timeline)、扣款池(建池/bind/unbind/deduct)、结算账户(台账+实付+流水,出纳付款入口);
- 装修单/销售单详情页槽位:该单关联结算单状态卡(slot 挂载,capability 门控);
- 移动端 V1 不做;付款/入账动作产生的事件由 addon-payment/上游插件自消费落通知(沿 addon-customer LeadAssignedNotifier 先例)。

## 老系统回溯指针(实现者用)

- 类:`com.fanglin.balance.service.impl.BalanceServiceImpl`(资金头+双腿入账,f384101d3 后为当代正确写法)/`BalanceSalesServiceImpl`+`BalanceSalesBalanceServiceImpl`(销售线聚合与自动吸附,SALES_AUTO_BALANCE 开关)/`BalanceSelectionServiceImpl`(选材线,含 audit 档与 record 流水)/`BalanceOalesServiceImpl`(外销线,防重缺失反面教材)/`BalanceCutpayServicerImpl`+`BalanceDccountServiceImpl`(扣款池)/`BalanceAccountServiceImpl`(账户+懒开户)
- 表:`balance.balance/balancesales/balancesalesbalance/balanceselection(+item/record)/balanceoales(+item)/balanceaccount/balancecutpay/balancedccount`
- 正面教材直接抄:原子 UPDATE 资金守卫(BalanceAccountDaoImpl.java:54-59)/懒开户 locateOrCreateAccount(BalanceServiceImpl.java:254-273)/bind-unbind 对称闭环/DTO 兼容旧客户端零前端改动;
- 已知坑逐条:`docs/rules/balance.md` §12 缺陷 39 条精选清单(动态聚合漂移/信任前端三处/负数反转守卫/池 SQL 无守卫/gid 当 bid/外销防重缺失/字典三处漂移/CLAUDE.md「分」口径文档错误——金额一律以 tranargs 千元制契约为准,营造统一最小单位显式换算)。
