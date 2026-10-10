# L2 规则清单 - 客源域(addon-customer / addon-customerprofile)

> 提取自:`D:\ERP\Rhadamanthus\src\com\fanglin\customer\`(CustomerSourceServiceImpl / CustomerChancePO / CustomerSourcePO / CustomerProtectRuleServiceImpl 等)
> 提取日期:2026-10-10(自动任务·夜间推进)
> 🔴 每条规则标注【方林嫌疑】:该规则是行业共性,还是方林实现习惯/公司差异,需业务方确认后决定去留

## 1. 客源(CustomerSource)状态机

| status | 含义 | 流转触发 |
|---|---|---|
| 0 | 初始 | 登记后 |
| 1 | 失效 | 作废操作 |
| 2 | 审批一 | 提交审批 |
| 3 | 确认 | 审批通过 |
| 4 | 审批二 | 二次审批 |
| 5 | 转换 | 已生成装修单(终态) |

- 可操作白名单:更新房源仅 status∈{0,2}(老代码 `updateCustomerSourceForHouse`);
- 分配拦截:status∈{1,5} 不可分配(⚠️ 老代码该拦截被注释掉,是已知坏味道);
- 每次关键操作写 `CustomerSourceRecord`(操作留痕,type: 0 常规/1 特殊)。

【方林嫌疑】状态 2/4 的两级审批是方林审批流固化;行业通用做法应走可配置审批(营造已拍板轻量自研工作流)。

## 2. 客源机会(CustomerChance)状态机

| status | 含义 |
|---|---|
| 0 | 初始(已分配未确认) |
| 1 | 失效 |
| 2 | 审批 |
| 3 | 确认 |
| 4 | 完成 |
| 5 | 对应装修单(废单/终止/决算/完工/内决) |

标志位族(行业共性,保留语义):isRead 已读 / isMeet 到店(2=到店)/ isTrue 有效 / isFine 罚款 / isCheck 验证|忽略 / isIssue 0 未分配 1 已分配 / isGroup。

【方林嫌疑】isFine(罚款)是方林管理制度,行业未必有 → 建议剥离为插件。

## 3. 分配决策链(issueCustomerSource 核心)

```
输入:csid + company(页面选择) + chanceVO(可能是 region/dept/belonger 指定)
① 查 CustomerDeptRule(创建人部门 did + busitype)→ 命中:用其 atttype + busitype
② 未命中:查 systemConfig["CUSTOMER_ISSUE_RULE_INT"](按 company)→ 命中:覆盖 atttype
③ 都未命中:atttype 默认 1(user,手动指定归属人)
→ 按 atttype 分四分支:0=auto / 1=user / 2=dept / 3=region
```

【方林嫌疑】CustomerDeptRule 按"创建人部门"决定分配模式是方林组织玩法;systemConfig 公司级开关是 company 分叉 → 营造做法:分配策略做成 addon-customer 的可配置规则(策略插件化:manual/round-robin/rule-engine),org 维度配置。

## 4. auto 分支(自动轮询分配)细则

1. **查重**:已存在机会则 330076"客户机会已存在";但若既有机会对应的装修单已废单/终止/决算/完工 或已决算(`checkCustomerChance`)→ 视为不存在,可重新分配;
2. **设计师池**:CustomerUserRule(按 company+面积段)+ Item(接单明细),两层数量语义:**机会数/剩余数 ÷1000 为页面值**(老系统惯例,营造一律十进制,禁 ×1000);
3. **五种 focus 视角**(拉单规则):COM 公司 / AREA 面积段 / DEPT_TYPE 部门类型 / DEPT 部门 / CITY 城区,无匹配分别报 330105-330109;
4. **防撞单**:轮询中若该设计师名下已存在同手机号+同公司客源(countCustomerSource)→ 跳过换下一个,最多 100 次(330081);
5. **数量扣减**:isSurplus 启用 → rule.surplus -= 1000;isDelete 启用 → 明细禁用;type=6(面积轮训)→ item.chance -= 1,归 0 后由 SQL 排除;
6. **轮询池**:CustomerConfigRule.surplus 归 0 → 执行 pollingCustomerUserRule 重置,再扣;
7. **company 兜底**:入参 company 为空 → 取归属人公司(⚠️ 营造:organization 由页面必传,无兜底分支);
8. **orderId 生成**:`公司短名 + yyMMdd + 4位流水`(counting 按 company+日期)→【方林嫌疑】短名前缀行业常见,保留但改 organization.shortname;流水计数器保留;
9. **通知**:站内信(部门经理+分区经理)+ 钉钉推送 + messageQueuing 埋点(addCustomerLead,CUSTOMER_CHANCE_AUTO_ASSIGNED)→【营造】全部改领域事件(LeadAssignedEvent),通知由内核通知中心订阅;
10. **留痕**:CustomerSourceRecord × 2(分配明细 + 命中规则)。

## 5. region / dept / user 分支(手动分配)

- region:归属=分区经理;dept:归属=部门经理;user:归属=指定人;
- department/region 均从归属人部门推导(belonger.dept / fatherDept,无上级则自身);
- orderId 生成同 auto;通知/埋点同 auto(event 后缀 REGION/DEPT/USER_ASSIGNED);
- busitype 四分支都要 set(历史 bug:2026-09-09 曾漏 user/dept/region 三分支,生产事故——**提取教训:分支间共享逻辑必须抽公共方法,禁止复制粘贴分支**)。

## 6. 保护期规则(CustomerProtectRule)

- 触发:`checkCustomerProtectRule(用户, company)`;
- 豁免:装修单已终态/已决算不保护;当前用户已是装修单归属人(业务员/设计师)不保护;
- 规则取最大保护天数(多条规则取 max);异岗位+无规则 → 跳过;异岗位+有规则 → 查保护期;
- 保护期内撞单:需审批后"合尺"(⚠️"合尺"是方林术语);
- 配套:CustomerCityProperRule(城区)/CustomerCompanyRule(公司)/CustomerConfigRule(公司轮询配置)。

【方林嫌疑】保护期概念行业共性(防撞单),但"罚款/合尺"挂钩是方林管理特色 → 营造保留保护期+审批,剥离罚款。

## 7. 字典枚举(源头)

- 客源来源(source):advertisement/referral/walkin 等(ds 字典);
- busitype(业务类型):DecorationBusitype,行业共性保留;
- 手机号:SensitiveDataHelper 加密存储(AES)→【营造】通用加密抽象,行业需求保留。

## 8. 营造 addon-customer 落地映射建议

| 老概念 | 营造归属 | 状态 |
|---|---|---|
| CustomerSource 登记/失效 | lead(已实现) | ✅ spike |
| CustomerSource 转换 → 装修单 | lead.convert → 事件 → 客户档案(spike 已验证;老系统转装修单属 decoration 域,V2) | ✅/V2 |
| CustomerChance 机会/分配 | addon-customer V2(lead→chance 二段) | 待 PRD |
| 分配策略(auto/manual/dept/region) | 策略接口 + 配置(行业共性) | 待 PRD |
| 设计师池/轮询/数量扣减 | 规则引擎 addon(去除 ÷1000) | 待 PRD |
| 保护期 | addon-customer V2 规则模块(剥离罚款) | 待 PRD |
| 通知/埋点 | 领域事件 + 内核通知中心 | 架构已定 |

## 9. 待业务方确认清单

1. 两级审批(客源 2/4)是否行业化保留,还是接入自研工作流可配置?
2. 罚款(isFine)是否进营造核心?(建议:插件)
3. 自动分配的五种 focus 视角是否全保留?(建议:先 COM+AREA 两种,V2 按需扩)
4. 保护期"合尺"审批是否保留原语义?
5. 客源→装修单的链路(老系统 chance.orderId 直连 decoration)在营造的拆分点:客源转化即建客户档案(已定),装修单建档归 addon-decoration V2。
