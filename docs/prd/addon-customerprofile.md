# PRD - addon-customerprofile 客户与楼盘档案

> 状态:草稿(2026-10-11,待业务确认)
> 老系统参考:communion 模块档案半区(Customer/CustomerPhone/CustomerAccount/CustomerRecord + Village/House + BuildingNameRule/VillageRule;实现口径见 `docs/rules/customerprofile.md`,第十一节起的缺陷与方林嫌疑清单是本 PRD 的反面需求来源)
> 归属:独立 addon(与 docs/01「并入 addon-customer」口径的取舍见 rules §19-1,推荐独立——档案是全域主数据,客源是过程数据)

## 业务闭环(一句话)

企业把「城市→城区→楼盘(树)→房屋」的物理世界档案与「一人一档」的客户主档案(手机号锚点+多电话+收款账户)维护成全域唯一事实源,客源/装修单/销售等插件经 get-or-create 与领域事件引用它,最终让每一张装修单都能落到「真实存在的房子 + 唯一的客户档案」上;数据终态落在 customerprofile 自有表(house/customer 两主档 + 三子表)。

## 用户与场景

| 角色 | 场景 | 高频动作 |
|---|---|---|
| 档案管理员 | 档案维护页 | 楼盘增改/绑命名规则/地图定位;房屋建档/改属性;客户建档/多号码管理 |
| 业务员(经客源插件) | 客源登记/装修单建档 | existHouse/existCustomer 幂等 get-or-create,不直接维护档案 |
| 财务/结算 | 收退款 | 维护客户收款账户(对私/对公),设默认 |
| 审计/管理者 | 合规检查 | 电话查询审计(谁在何时看了谁的电话) |
| 其他插件 | 订阅事件 | customer_created / house_created / village_changed |

## 核心流程(状态机文字版)

1. 楼盘建档:选城市→城区→(可选父楼盘)→命名→(可选)地图定位(经纬度 ×10⁶)+ 绑定命名规则 → 唯一键(org+城区+名称)拦截重复;
2. 房屋建档:楼盘 + 楼牌号四段(栋/单元/楼层/门牌,按楼盘命名规则正则校验)→ house_number 全文 AES 密文落库;(vid,houseNumber) 唯一;**房屋不可删**(无装修单引用时可禁用);
3. 客户建档:手机号=身份锚点,addCustomer 按有效号码查重;existCustomer 幂等回显(查到即返回 cid,查不到才建档)→ 一人一档;
4. 电话管理:多号码 status(启用/停用)+ defaults(唯一默认);默认号变更同步主档 phone;读取电话一律走留痕接口写审计;
5. 换房:装修单插件发起直接换绑(校验新房未被有效单占用),本插件记审计事件——不走审批(老系统换房审批已废弃,现行走法即直接换绑);
6. 实名/认证(可选):电子签插件回写实名结果 → 档案仅存 provider+外部引用 id,状态位与电子签解耦。

## 闭环校验

- 闭环起点:城市/城区基础数据就绪(归 basedata 或内核字典),首个楼盘/客户建档;
- 闭环终点(终态):房屋被装修单引用+客户被客源转化引用——档案自身无"完结"概念,是长生命周期主数据;
- 异常路径:get-or-create 并发重入(唯一键兜底+幂等回显)/默认号停用被守卫拒绝/删除城区被楼盘引用守卫拒绝/电话读取无审计不返回。

## 非目标(本期不做)

- 户型图库 houselayout(老系统生产 8 行死功能,不迁);
- 换房审批流(废弃,改直接换绑+审计);
- 对公账户六要素与客户主档的双向同步(V1 以 customeraccount 为单一事实源);
- 电子签注册/实名流程本体(归电子签插件);
- 命名规则字典本体与拼正则器(归 addon-basedata 字典域,本插件只消费);
- 楼盘封面图库/腾讯地图硬编码(地图 provider 配置化,坐标存 ×10⁶ 口径保留);
- relationshipType 中文枚举(字典化后由业务自定义);
- 多租户开关(multicompany):org 归属必填,平台共享池 org_id=0 一层语义。

## 验收标准

- [ ] 建档幂等:并发双击/重试不产生重复楼盘、房屋、客户(全部唯一键在 DB 层,get-or-create 返回已有档);
- [ ] 敏感数据:house_number/customer.phone/identity 及证件类字段 AES 密文落库,密钥独立于会话令牌;所有出参脱敏视图可选,消息下发零密文(老系统两处密文泄漏显式修正);
- [ ] 电话读取审计:任何解密电话的接口自动留痕(谁/何时/谁的),无审计不出数;
- [ ] 单房单有效单守卫:换绑接口对"已被有效装修单占用"的房屋拒绝并返回占用单信息(140063 思想);
- [ ] 主数据唯一写点:客源/装修单插件在本插件代码库零出现,只能经 addons-api 事件与查询契约;
- [ ] 状态机:customer(初始/已实名)/phone(启用/停用+唯一默认)/account(启用/停用+唯一默认)/village(启用/禁用)每步迁移有守卫;无死状态(AUTHED 退役教训);
- [ ] i18n 中英双语;错误码 330000 段,同码同消息;
- [ ] 存量迁移脚本含:门牌明文→加密(94% 存量)、status=2 归并、customerrecord→审计表(§19-2/3 决策后)。

## 端覆盖

- Web 后台:档案管理三页(楼盘[含地图定位+规则绑定]/房屋/客户[含电话与账户管理])+ 电话审计页;客源/装修单页经槽位引用档案选择器;
- 移动端(uni-app):客源登记场景的楼盘/房屋快捷建档;客户档案查看;
- 企微/钉钉:免登后同移动端页面。

## 老系统回溯指针(实现者用)

- 类:`com.fanglin.communion.service.impl.CustomerServiceImpl`(765 行,唯一写点)/`HouseServiceImpl`/`VillageServiceImpl`/`CustomerAccountServiceImpl`/`CustomerRecordServiceImpl`/`VillageRuleServiceImpl`;加密 `core.security.SensitiveDataHelper`
- 表:`communion.customer/customerphone/customeraccount/customerrecord/village/house/villagerule/buildingnamerule`
- 已知坑:全量见 rules §16 缺陷 32 条——重点:HouseDAO status 死雷、updateHouse ignores 契约漂移、existCustomer 窄口径、拼音条件反转 NPE、countVillageByNameACpid SQL 缺空格、密文泄漏两处;量纲:面积 ×1000/经纬度 ×10⁶/mileage 存米显公里(rules §12)
