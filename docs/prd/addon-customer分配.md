# PRD - addon-customer 分配与机会(客源 V2)

> 状态:**草案**(2026-10-11 夜间自动任务起草,**待业务方确认**;确认前实现代码以本草案为口径)
> 老系统参考:customer 模块 customerChance 系(规则清单 `docs/rules/customer.md` §2/§3/§4/§5)
> 基座:本 PRD 是 `addon-customer客源.md`(已确认)的 V2 子域扩展,同一 addon 内新增 chance 域

## 业务闭环(一句话)

客源负责人把未转化客源**分配**给责任人(手动指定 / 自动轮询)生成**机会(chance)**,责任人**确认**跟进直至**完成**,分配→确认→完成全程留痕;重复分配被幂等拒绝,分配瞬间发布 `LeadAssignedEvent` 供通知订阅;数据终态 = customer_chance.status=4(完成)或客源转化进入档案。

## 用户与场景

| 角色 | 场景 | 高频动作 |
|---|---|---|
| 客源负责人/店长 | 线索进来了,指派给设计师跟进 | 手动分配(选人) |
| 系统(无人工介入) | 渠道线索直接进池,按策略自动落到责任人 | 自动轮询分配 |
| 设计师/责任人 | 接到分配通知,判断线索有效性 | 确认机会、跟进、完成 |

## 核心流程(状态机文字版)

1. 分配 `assign(mode, assigneeId?)` → 前置校验(lead 存在且 status=0、无在途机会)→ 插入 customer_chance(status=0 已分配)→ 事务内发布 `LeadAssignedEvent`;
2. 确认 `confirm` → chance status 0→3(已确认),记 confirmed_at;
3. 完成 `complete` → chance status 3→4(已完成),记 completed_at(必须先确认——跟进闭环不可跳步);
4. 客源转化与机会本期解耦:lead.convert 行为不变(chance 不联动),联动归 V3 评审。

### 分配策略(策略接口化,开闭原则)

- `LeadAssignStrategy` 接口(mode + resolve),按 Spring bean 注册,入口按 mode 路由;新增策略 = 新增 bean,入口零改动;
- **manual**:必传 assigneeId,校验其存在且属于本组织;
- **auto(轮询,spike 级)**:候选池 = 本组织全部用户;选**在途机会(0/3)最少**者,同数取用户 id 最小(确定性轮询);正式版候选池 = 设计师池规则引擎(见规则清单 §4,÷1000 语义已剔除);
- 老系统三层决策链(CustomerDeptRule → company systemConfig → 默认手动)为 company 分叉【方林嫌疑】,营造 v1 由 API 显式传 mode,组织级缺省策略归 V3 配置化。

## 闭环校验

- 闭环起点:一条 status=0 的客源被分配;
- 闭环终点:chance.status=4,或客源转化(lead.status=1 + customer_profile 存在);
- 异常路径:lead 不存在 210001 / 已转化 210002 / 已有在途机会 210003(幂等拒绝) / 分配目标不在本组织 210004 / 非法状态流转 210005 / 自动池为空 210006 / 未知 mode 210007;
- 回滚:分配为单事务(校验+插入+发事件),失败全回滚;每客源生命周期内唯一机会(unique lead_id),删除/释放归 V3。

## 非目标(本期不做)

- 保护期/公海回收/跨客源手机号查重(老系统 §4.1/§6,归 V3 规则模块);
- 设计师池规则引擎与数量配额(轮询池暂用全组织用户);
- 罚款 isFine【方林嫌疑,剥离】、审批态【方林嫌疑,v1 剔除,待自研工作流】;
- 装修单号 orderId 挂接(归 addon-decoration);
- 分配通知(LeadAssignedEvent 订阅方:内核通知中心 V3)。

## 验收标准

- [x] manual/auto 分配 → 确认 → 完成 状态机可用(dev 库冒烟 8/8,2026-10-11);
- [x] 同一客源重复分配幂等拒绝(210003);非法流转拒绝(210005);
- [x] auto 轮询公平性:连续分配均匀落池(在途数最少优先,确定性);
- [x] `LeadAssignedEvent` 走 yingzao-addons-api 契约,零反向依赖;
- [x] i18n 中英双语同步;mvn clean package 全绿;
- [ ] Web 客源页"分配"操作 + 机会列表(待风格选定后随正式视觉落地);
- [ ] 分配通知(依赖内核通知中心)。

## 端覆盖

- Web 后台:客源列表加"分配"操作 + 机会列表页(工程实现走既有骨架,视觉待选定);
- 移动端(uni-app):责任人查看/确认自己的机会(V3);
- 企微/钉钉:分配通知推送(依赖通知中心 V3)。

## 老系统回溯指针(实现者用)

- 类:`com.fanglin.customer.service.impl.CustomerSourceServiceImpl#issueCustomerSource`(分配决策链与四分支);
- 表:customer_chance / customer_source / customer_user_rule(设计师池)/ customer_config_rule(轮询配置);
- 已知坑:三手动分支漏 setBusitype 生产事故(分支共享逻辑必须抽公共方法)、数量 ÷1000 语义、防撞单轮询 100 次上限——全部记录于 `docs/rules/customer.md` §3/§4/§5,实现时逐条对照。
