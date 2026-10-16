# PRD - addon-quotation 报价管理

> 状态:**草案**(2026-10-11 夜间自动任务起草,**待业务方确认**;确认后才进入实现)
> 老系统参考:quotation 模块(实现口径见 `docs/rules/quotation.md`,老系统错误码段 2000xx)
> 营造声明:错误码段 **230000**,Flyway 迁移 **400+**(对齐码段约定)
> 依赖边界:装修单归 addon-decoration(本 addon 只持 order_id 引用);款项归 addon-payment(事件衔接);材料档案归 addon-basedata

## 业务闭环(一句话)

设计师/预算员对装修单做**报价**(功能区→功能项明细 + 管理费/搬运费/税费,费率取自报价版本),报价**定稿(锁定)**后经**审核线(提交→审核→完成)**成为合同价格基准;合同后的价格变更走**增减项**形成差量单并触达款项;报价全生命周期留痕。数据终态 = quotation.status=1(定稿)+ attstatus=3(审核完成),增减线 = add_reduce.status=3(已确认)。

## 用户与场景

| 角色 | 场景 | 高频动作 |
|---|---|---|
| 设计师/销售 | 客户到店,量房后出报价 | 建报价、调明细、定稿 |
| 预算员 | 报价审核与价格维护(量房→报价是分配派单场景,V3) | 审核报价、保价重算 |
| 店长/老板 | 看报价与折扣 | 查列表、切默认报价 |
| 客户 | 确认价格 | (签字/电子签归电子签插件) |

## 核心流程(状态机文字版)

1. 建报价:`create(orderId)` → status=0(编辑中),数量上限可配(默认 3);自动生成"生成项目"功能区 + 搬运/管理费项(版本启税则加税项);费率从报价版本带出;
2. 编辑:仅 status=0 可改(明细/费率/折扣,discount 万分比语义文档化,10000=不打折);
3. 定稿:`lock` → status 0→1(前置:无未关联选材项;lockTime 首次幂等),`unlock` → 1→0;
4. 审核:`submit` → attstatus=1 → `audit` → 2 → `finish` → 3(记 finishTime),三步各发领域事件(通知归通知中心订阅);
5. 切默认:`setDefault` → 同单唯一默认,默认报价变更对外可见价格以查询期汇总为准(不回写装修单,v1 不双写);
6. 增减(V2):对已确认价格线做差量单(增/减/漏项枚举位),`submit` → 生成差量聚合(正补负退)→ 发 `QuotationAddReduceSubmittedEvent`,addon-payment 承接款项 → 付款后 status=2 → 确认 3; **→ 详细设计已出:[addon-quotation-addreduce.md](addon-quotation-addreduce.md)(2026-10-16,含状态机/守卫/端点/冒烟验收,按推荐口径可进写码)**;
7. 保价重算(V2):按时间点取价格快照回填明细(价格快照 quotation_price 表)。

## 闭环校验

- 闭环起点:一份 status=0 的报价被创建;
- 闭环终点:status=1 且 attstatus=3(定稿+审核完成);增减线 = 差量款项已确认;
- 异常路径:上限拒绝 / 锁定后编辑拒绝 / 无未关联选材拒绝 / 非法流转拒绝;增减单提交是单事务(校验+聚合+事件),款项生成失败全回滚;
- 金额红线:**十进制分存储**,禁老系统 1e6 缩放与万分比黑盒(系数字段语义必须文档化);

## 非目标(本期不做)

- 成本/利润线(consPrice/comsPrice/unachePrice)——归经营分析插件【方林嫌疑,待业务确认】;
- 装修单域(状态机/合同)——addon-decoration;
- 款项/收付款——addon-payment(事件衔接);
- 定额三来源全量重算(V2)、拓展项/选材/测量复测(V2/V3)、整装产品报价公式(V3)、报价分配(V3,复用客源 V2 策略接口模式);
- 分表(v1 单表+org_id 索引;老系统按配置五分表是化石层,不迁);
- 旧代报价 Quotetion/克隆跨代克隆(历史包袱,不迁)。

## 验收标准(实现时逐条对)

- [ ] 建报价→编辑→定稿→解锁 状态机可用(仅 status=0 可编辑);
- [ ] 审核线 submit→audit→finish 与 status 正交,各自发领域事件;
- [ ] 自动生成"生成项目"功能区 + 费率项,版本费率带出;
- [ ] 数量上限/锁定前置/非法流转错误码(230000 段)全走 i18n 双语;
- [ ] 金额十进制分存储,聚合口径有单测;
- [ ] mvn clean package 全绿 + dev 库冒烟通过。

## 端覆盖

- Web 后台:报价列表/编辑器(功能区树+明细表,重交互,**走 huashu-design 风格流程**)、审核页;
- 移动端:报价查看/分享(V2+);
- 企微/钉钉:审核待办通知(通知中心订阅,V2)。

## 老系统回溯指针(实现者用)

- 类:`com.fanglin.quotation.service.impl.QuotationServiceImpl`(状态机/自动生成/复制保价)、`AddReduceServiceImpl`(增减→款项)、`NormtionServiceImpl`(定额重算)、`QuotationAssignServiceImpl`(分配);
- 表:quotation / quotation_function_area(_item 0-4 分表化石) / quotation_price / quotation_version(在 data 模块!)/ add_reduce(_item) / normtion 系 / quotation_assign 系;
- 已知坑:金额 1e6 缩放与万分比折扣(updateQuotationForPrice 快照回填)、裸状态位写入口(updateQuotationForStatus 无流转校验)、功能区项按配置选实体类分表、跨域回写装修单价格(切默认)——全部记录于 `docs/rules/quotation.md`,实现时逐条对照。
