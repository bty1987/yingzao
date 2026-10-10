# PRD - addon-customer 客源管理

> 状态:已确认(2026-10-10,闭环经 spike 真实验证)
> 老系统参考:customer 模块;楼盘/案例归 addon-building、addon-market,不在本 addon

## 业务闭环(一句话)

获客渠道把线索登记为**客源(lead)**,跟进后**转化**为客户,转化瞬间由事件自动生成**客户档案**(addon-customerprofile 承接)——客源自此进入正式经营流程,数据终态 = customer_profile 建档 + lead.status=1。

## 用户与场景

| 角色 | 场景 | 高频动作 |
|---|---|---|
| 市场人员/客服 | 广告、转介绍、自然进店来的线索 | 登记客源 |
| 客源负责人 | 判断线索有效性 | 跟进、转化 |
| 设计师/门店 | 接手已转化客户 | 查看客户档案 |

## 核心流程(状态机文字版)

1. 登记:`create` → status=0(已登记),记录来源(advertisement/referral/walkin);
2. 转化:`convert` → status 0→1,事务内发布 `LeadConvertedEvent`;
3. 建档:addon-customerprofile 在事务提交后监听事件,自动 upsert customer_profile(lead_id 唯一,幂等);
4. 重复转化:拒绝,210002;不存在:210001。

## 闭环校验

- 起点:一条线索被登记(lead.status=0);
- 终点:转化完成(lead.status=1 + customer_profile 存在);
- 异常路径:转化失败回滚(状态更新与事件同事务);重复转化幂等拒绝;建档失败不回滚转化(AFTER_COMMIT 新事务,失败仅日志——**设计取舍:档案可人工补建,客源状态不回退**);
- 回滚:本期无"退回客源"功能,终态不可逆(V2 评估)。

## 非目标(本期不做)

- 跟进记录/保护期/公海(老系统 customerChance 系;分配子域已拆出 V2 PRD:`addon-customer分配.md`);
- 客源查重、公海池;
- 楼盘关联(归 addon-building)。

## 验收标准

- [x] 登记→转化→建档全链路可用(dev 库冒烟 7/7,2026-10-10);
- [x] 两 addon 零反向依赖(事件走 yingzao-addons-api 契约);
- [x] 客源分配(V2 子域 PRD 已起草:`addon-customer分配.md`,dev 库冒烟 12/12,2026-10-11);
- [ ] Web 后台页面(走 huashu-design 审美流程);
- [ ] 移动端登记表单(uni-app)。

## 端覆盖

- Web 后台:客源列表 + 登记表单;客户详情页留槽位 `customer.lead.detail.footer`(演示插件注入);
- 移动端:登记客源表单(店头场景);
- 企微/钉钉:免登进入移动端同一表单。

## 老系统回溯指针(实现者用)

- 模块:`src/com/fanglin/customer/`(customerChance 系:分配/跟进规则在规则清单阶段提取);
- 本期已提取:转化→建档的事件化拆分(老系统 issueCustomerSource 是耦合实现,营造改为事件解耦);
- 已知坑:老系统 issueCustomerSource 三手动分配分支漏 setBusitype、跨公司查重绕过——规则清单产出时逐条核对。
