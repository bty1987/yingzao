# L2 规则清单 - 客户/楼盘档案域(addon-customerprofile)

> 第十二份 rules / 第十个活域(2026-10-11 第 15 次运行)。来源:老系统 `D:\ERP\Rhadamanthus` **communion 模块档案半区**(客户主档案族 + 楼盘房屋档案族 + 命名规则字典),三路子代理并行深读(楼盘+命名规则 / 房屋+户型+换房审批 / 客户档案族)+ 生产只读库 9 表复核;每条结论带 file:line。老系统只读,零改动。

## 0. 模块定性

- **这是全 ERP 的主数据底座**:79.4 万客户 / 87.3 万房屋 / 4.9 万楼盘 / 80.7 万客户电话,被 decoration(hid 挂接率 **100%**,769,535/769,535)、customer 客源域(ManyToOne 持久关联)、sales、approval、seiya 全域引用;
- **唯一写点原则(全系统最干净的主数据纪律)**:客户档案全仓仅 `CustomerServiceImpl.addCustomer/existCustomer` 两处 save;房屋仅 `addHouse/existHouse`;楼盘 communion + seiya A20260303 导入通道;其余模块一律只读或 `new XxxPO(id)` 造引用占位——**新系统必须保住这条纪律**(对照:sales/inventory 域的跨模块直写灾难,见各自 rules);
- communion 模块另一半(通知/constant 字典/升级链/chart 任务)归内核与 basedata,不在本文档;**客源过程数据**(线索/机会/分配)在 `docs/rules/customer.md`,本文档只管**档案主数据**;两族"Customer"的边界见 §13;
- **归属裁决(建议,待业务确认)**:docs/01 过堂表写「客户/楼盘档案并入 addon-customer」,但 AGENTS.md 军规 5 码段示例预留了独立的 `customerprofile 300+`——本文按**独立 addon-customerprofile** 组织(理由:档案=主数据被全域引用,客源=过程数据,生命周期与依赖方向完全不同;并入则 decoration/sales 等插件被迫依赖客源插件的 api),冲突与证据见 §19-1;
- **生产考古四惊人**:①楼盘库是**新建的**——48,897 行 createTime 全部落在 2025-12-18 之后(P- 代两周批量导入 3.8 万),更早的楼盘库已被清空重建,L- 代 2026-03 起经 seiya 通道持续导入(company 全 0);②customer.status=2(AUTHED)生产 84,387 行,但当前代码**零写入点**——考古证明它是 2015-2025 年历史电子签通道的写入产物(provider 全=1),**2026 年起切换为 status=1**(2026 年 status1 新增 18,559、status2 零新增),是**退役态而非死态**;③houselayout 户型库生产仅 **8 行**(house 87 万),零外部消费,死功能;④village 表 cpid/bnrid 两列 PO 无映射、生产全 0,死列实锤。

## 1. 域模型总览(数据流)

```
city(377) → cityproper(5028) → village(48897, 树: pvid 自引用 81%)
                                   │  N-N buildingnamerule(180, 正则字典) via villagerule(48349)
                                   ↓ vid
                                house(873281, houseNumber 密文, 楼牌号四段)
                                   ↓ hid (decoration.hid 100% 挂接, 单房单有效单)
customer(794405, no=yyMMdd+4流水 UNIQUE)
   ├─ customerphone(807304, status+defaults 双轴, phone=身份锚点, AES)
   ├─ customeraccount(18886, 收款银行账户档案, 对私/对公, 非余额)
   └─ customerrecord(350045, 电话查询审计, 双写 log.customerLog 迁移中)

引用方:decoration.hid→house | customer客源域 CustomerSource/Origin/Chance→village+CustomerPO(快照并存)
       | sales ManyToOne CustomerPO | approval 4 类审批读 house.village | seiya A20260103 建档建房/A20260303 导楼盘
```

## 2. 客户主档案 customer

实体 `communion/po/CustomerPO.java`(表 `communion.customer`,DDL dbs/communion.sql:358-394):

- **编号 `no` = yyMMdd+4 位流水**(CustomerServiceImpl.java:236-242,counting 表 TYPE_CUSTOMER_INT=1 取号),表级 `UNIQUE uniqueone(no)`;生产实证 2015→2026 编号连续无断代;
- 加密字段:`identity` 身份证(建档加密 CustomerServiceImpl.java:248/读 112/更 402-404/电子签回写 627)、`phone` 主电话(密文,冗余镜像 customerphone 默认号);**明文残留:officer 军官证/passport 护照/passhkm 港澳通行证**(:53-57);
- 电子签五件套:`signno` 注册号/`provider`(1 法大大 2 e签宝)/`token` 交易号/`authurl` 实名链接 + 四路径照片(photoPath 人像/signPath 手签/identityPathF/B 身份证正反,CustomerPO.java:75-89,confirmCustomer 回写 :632-634);
- 对公六要素冗余列:companyNo~companyBankno(信用代码/户名/地址/电话/开户行/账号,6 列),由默认 customeraccount **双向同步**(见 §4);
- `relationshipType` 客户关系类型——**值即中文字符串**(普通客户/企业高端客户/企业关系客户/领导关系客户/员工关系,customerInfo.json:2-8);
- 死常量:`BALANCE_TYPE_BALANCE/PROJECT/MATERIAL/ZHENGLIN_INT`(0-3,**"正林"=方林子品牌硬编码**,CustomerPO.java:21-27;全仓唯一引用是 AdvancePaymentDao.java:29 的一句注释);`PRIVACY_PROTECT_INT=0` 与 `PRIVACY_OPENESS_INT=0` **同值**,"保护级"形同虚设(:35-37);
- 表无 create/updateTime 审计列,建档时间只能靠 no 前缀推断(生产考古即靠此)。

**生命周期(全仓唯一写点)**:
- 入口一 `addCustomer`(CustomerServiceImpl.java:222-268):查重=**纯手机**(姓名不参与),加密三形态 IN 查 customerphone 且 status=0,重复抛 130006「客户电话已存在」;
- 入口二 `existCustomer` get-or-create(:272-313):只查 `defaults=1 and status=0` 的默认有效号码,**查不到就建新档+新号码**——客源机会提交/转单(CustomerChanceServiceImpl.java:277-278,996-1002)与 seiya 线索接入(A20260103ServiceImpl.java:178,429-433,无客户以"未知客户"建档)都走它;**签单/装修合同不建档**,decoration 换绑只引用已有档(DecorationServiceImpl.java:1249-1257);
- 竞态:customerphone.phone **全局无唯一键**(DDL:410 普通索引),check-then-insert 并发同号可产生双档案;existCustomer 口径更宽(禁用/非默认号码视同"不存在"),同一自然人合法多 cid;编号 counting 读改写并发靠 uniqueone(no) 兜底,冲突抛原始约束异常非业务码。

## 3. 客户电话 customerphone(手机号 = 身份锚点)

实体 `communion/po/CustomerPhonePO.java`(表 customerphone,5 字段:cpid/cid/phone/status/defaults;status 0 启用 1 停用,defaults 1 默认):

- **加密**:AES→`ENC(V1:base64)` 新格式,解密三层回退(新格式→旧纯 Base64→兜底当明文),查询用 `getAllEncryptedForms` 返回[明文,旧密文,新密文]三形态 IN(core/security/SensitiveDataHelper.java:27-79,126-160);
- **多号码管理 = 抢默认语义**:addCustomerPhone 先 `clearCustomerPhoneForDefault(cid)` 全清再插 defaults=1,并同步改 customer.phone(CustomerServiceImpl.java:331-347);默认切换有状态白名单校验(:498-500)与"默认号不可禁用"守卫(:461-463);
- **「取第一个有效」口径**全系统依赖:`queryCustomerPhoneByCid(cid, ENABLE)` 后取 `get(0)`(DecorationServiceImpl.java:379-382/SecretServiceImpl.java:54-56/A20260103ServiceImpl.java:499-505),但该查询**无 order by**(CustomerPhoneDaoImpl.java:28-37),顺序仅靠主键隐式序——口径脆弱;
- 生产分布:defaults=1 占 98.5%(79.5 万/80.7 万),status=1(停用)仅 8 千;
- 写点全在 communion(五条路径);读方:decoration/secret/seiya/customer 会员/合同模板 8 个(全部经 queryCustomerForPhone 留痕接口取解密值)。

## 4. 客户银行账户 customeraccount(收款账户档案,非余额)

实体 `communion/po/CustomerAccountPO.java`(表 customeraccount,生产 18,886 行):

- **业务定性:客户收款银行卡档案**——type 0 对私(16,496 行)/1 对公(2,625 行),字段 bank/bankno(卡号,生产实测非 base64 密文)/no/name/phone/address + status(0 启用 1 停用)+ defaults + **contentHash(6 字段 MD5 防重指纹,CustomerAccountPO.java:209-223)**;无任何金额字段,积分账户是 customer 模块 PointAccountPO、余额在 payment.advancepayment,互不相干;
- 状态机:软删(delete 置 DISABLE+清默认,CustomerAccountServiceImpl.java:129-133)/首账户自动默认(:70-76)/删默认后"第一个可用"顶上(:135-142)/设默认先全清再设(:163-175);
- **双向同步机制(关键且高危)**:默认账户六要素回写 customer.company*(:80-92,177-187);反向,updateCustomer 改了 company* 字段则按 contentHash 比对自动补建对公账户(matchCustomerAccount,CustomerServiceImpl.java:699-753)——两端互触发,无 cid+hash 唯一约束,并发可重复;
- 错误码漂移:communion 实体却用 payment 段 230030/230002(:104,124,154,158)。

## 5. 电话查询审计 customerrecord(审计流水,非跟进记录)

实体 `communion/po/CustomerRecordPO.java`(表 customerrecord,生产 350,045 行):

- **定性:记录"谁、何时、查看了哪个客户的哪个电话"的敏感操作审计**——type 唯一值 0=电话查询(DDL 注释即"0: 电话查询"),Service 强制覆盖 VO 传入的 type(CustomerRecordServiceImpl.java:84);自动写在 queryCustomerForPhone 里(CustomerServiceImpl.java:162),`addCustomerRecord.do` 只是手工补录;
- 与客源域跟进记录(CustomerOriginRecord/CustomerSourceRecord,销售业务数据)完全两物;前端只读页 views/data/customerRecord.html,无业务新增按钮;
- **双写迁移中(Phase 7)**:每次查电话同时写旧表 customerrecord 与新表 `log.customerLog`(CustomerServiceImpl.java:162-166,注释"双写过渡期…后续废弃原表后可移除";log/po/CustomerLogPO.java:8-15 头注释确认迁移,operater 由 ManyToOne 改裸 userId)——审计量翻倍等收口;
- 生产:逐年翻倍(2023:3.4 万→2024:4 万→2025:10.6 万→2026:17 万),cpid/ouid 全填,高频活功能。

## 6. 楼盘 village(编号三代考古 / 父子树 / 拼音检索 / 地图)

实体 `communion/po/VillagePO.java`(表 village,生产 48,897 行):

- 字段:no(业务编号 UNIQUE,`{公司短名}P-{5位序号}` 生成,VillageServiceImpl.java:119-123)/name/pinyinAB+pinyinAll(检索)/cityProper(EAGER ManyToOne→cityproper)/cid 冗余城市(**查询从不按它过滤,疑冗余**,VillageDaoImpl.java:136-138)/parentVillage(**pvid 自引用树**:期/区挂大盘,新增为空时**自指**,:147-150;生产 81% 有父级)/developer/salesPhone/deliveryTime(**PO 用 String,DB 是 date**,:191-198)/rule 推荐楼牌规则文本/content/x,y(经纬度 **×1,000,000 存 int**,联合索引)/status(0 启用 1 禁用)/url 楼盘封面图/mileage(**存米显公里**,×1000)/company;
- **死列**:cpid/bnrid 无 PO 映射、生产全 0(bnrid 系早期"楼盘直挂命名规则"遗留);
- **编号三代考古(生产)**:P- 代 39,763 行生于 2025-12-18~2026-04-07(其中 **2025-12-18 起两周批量导入 3.8 万**,2025 全年 createTime 行数即 38,307);L- 代 9,725 行 2026-03-16 起持续写入至今(最后记录=考证当天),**company 全 0**=seiya 导入通道不留公司上下文;另有 1 行"1"开头脏数据;前端遗留校验正则 `^P_\d{4}$`(下划线代)与后端现行格式不符(village.js:73,死校验);
- **查重**:addVillage **完全没有查重**(:106-156,同名同城区可无限插);updateVillage 仅在名称/城区变更时 count 查重抛 130020(:168-173)且是 TOCTOU,DB 无 (name,cityProperId) 唯一键(唯一键只有 no);no 取号 counting 无锁,并发同号靠唯一键兜底报原始 500;
- **潜伏 SQL 炸弹**:countVillageByNameACpid 拼 SQL 缺前置空格,`":cityProperId" + "and company"`→multicompany 开启时语法错误(VillageDaoImpl.java:99-104);
- **拼音检索**:pinyin4j 生成,多音字**笛卡尔积组合取第 0 个**(:137-138,PinYin4Util.java:140-150);查询 `ilike(name,%kw%) OR ilike(pinyinAB,%kw%)` 前后通配只能吃 status 前缀索引,硬上限 20 条,排序靠 `sqlRestriction("1=1 ORDER BY CASE WHEN this_.company=%s...")` SQL hack(VillageDaoImpl.java:59);`queryVillageForNameByPinyin` 组装 typeahead 数据,消费方遍布 decoration/customer/payment/production/construction 七八个查询条件组件;
- **地图**:腾讯 TMap,**API-Key 明文硬编码**(VillageServiceImpl.java:266-269),默认中心沈阳坐标(village.js:448);locateVillage **在写事务里同步调外网 HTTP**(:271);updateVillageForStatus/Position/Url/locateVillage/VillageRule 全家**不校验楼盘归属公司**(越权面);
- 事件外发 ESTATE_CREATED/UPDATED 全仓无消费侧(:153,196,308)。

## 7. 房屋 house(楼牌号四段 / 密文门牌 / 单房单有效单)

实体 `communion/po/HousePO.java`(表 house,生产 873,281 行,DDL 无 (vid,houseNumber) 唯一键——生产实锤):

- 字段:village(ManyToOne vid)/houseNumber(**楼牌号全文,密文**)/built 栋/unit 单元/floor 楼层/door 门牌(**四段结构化存储,前端按楼盘绑定规则拼接**,houseUtils.creatHouseNumber 8 段规则)/type 房产类型(constant 字典驱动,生产非 0 仅 107 行)/面积五件 buildArea/freeArea/innerArea/quoteArea/totalArea(**一律 ×1000 定点 int**,tranargs data.js:420-472)/through 越层/roomNum 几室(传 6 语义"≥6",HouseDaoImpl.java:67-72)/houseType 户型文本"X室X厅X厨X卫"(前端拼)/orientation 朝向/feature"三不"(方林业务术语)/waterVapour/parlourType/heatingType/isLift/company;
- **死字段/死常量**:innerFloor、quoteArea 全仓无业务读写;`COLUMN_STATUS_STR`+STATUS_ENABLE/DISABLE 常量残留但 **PO 根本没有 status 字段**(HousePO.java:29-35,删字段残留)——而生产表 status 列存在且 87 万行全 0,DB 死列;**死雷:HouseDaoImpl.java:81-82 仍对该不存在属性加过滤,前端传 field=status 即 Hibernate QueryException 500**;
- **创建三路径**:①房产资料页手工建档(HouseAction.addHouse:63-73);②装修单建档页内嵌调 addHouse(decoration2create.js:511);③**existHouse get-or-create**(HouseService.java:20):客源登记/客户机会(CustomerChanceServiceImpl.java:301-302)与 seiya 线索接入(A20260103ServiceImpl.java:159,447-448);**建装修单不建房**,只按 hid 引用;
- **updateHouse 契约漂移(缺陷)**:ignores 表忽略 `village,hid,company,no,houseNumber,built,unit,floor,door,buildArea,innerArea`(HouseServiceImpl.java:142)——**楼牌四要素/楼盘/门牌文本均不可改**,但前端编辑表单照常提供这些输入(:58-74)提交即"没生效";buildArea/innerArea 还要求非 null 非 0 才生效(:144-145),**面积无法清零**;房屋**无删除端点**(不可删);
- **与装修单**:decoration.house 标准 ManyToOne(nullable=false,DecorationPO.java:265-267);`checkOverDecoration(cid,hid,company)` 一房同时只允许一张有效装修单(过滤废单/终止/终审/完工+未结算,命中抛 140063 带设计师名,DecorationServiceImpl.java:1811-1838)——**单房单有效单**是客源转单/审批通过的前置守卫;客源转装修单会用客源 buildArea **覆盖** house.buildArea(CustomerChanceServiceImpl.java:687-691);
- 竞态:addHouse/existHouse 先 queryHouseByNoAHnum 再 save,无唯一键兜底,并发同 vId+houseNumber 可重复建档;multicompany=0 时该查询不加公司过滤且 `setMaxResults(1).uniqueResult()` 取任意一条(HouseDaoImpl.java:46-50)。

## 8. 户型库 houselayout 与换房审批 ApprovalDecorationHouse(两个半死件)

- **houselayout = 死功能实锤**(生产 8 行 vs house 87 万):只挂楼盘不挂房屋(与 house 零关联);图片/CAD 复用 DataDrawing 附件体系(sid=hlid,type=1 图片/2 CAD),不落库;**全仓零外部消费**(报价/施工均不读);CRUD 不对称只有 add/delete 无 update,物理删不级联清附件(HouseLayoutServiceImpl.java:82-85);addHouseLayout 直接 `new VillagePO(no)` 不校验楼盘存在(:75);
- **ApprovalDecorationHouse = 装修单换房审批,基本死亡**:五件套在 approval 模块(orderId+shid 旧房+thid 新房);前端 api 已注释(js/api/approval.js:126),**后端审批分发 case 也注释**(ApprovalServiceImpl.java:93,265-266)——submit 端点仍可被直接 POST 生成审批单,但通过后换房动作永不执行,只产脏数据;双房源校验还有单边 NPE(旧房不存在新房存在时 :99 炸)且**审批内容直接拼密文门牌**(密文泄漏,:99);
- **现行换房实际走法**:客源"直接初审"时前端直接传 house.hid 换绑(CustomerChanceServiceImpl.java:957-977),不走审批。

## 9. 命名规则 buildingnamerule + villagerule(正则字典)

- **buildingnamerule = 楼牌号命名规则字典**(生产 180 行活数据):字段 rule 正则/ruleExample 示例("1A-门2座-3甲")/ruleContent 人类可读说明/rulefill/buildingNameRuleId UNIQUE;**纯字典证据**:queryBuildingNameRule 全量无过滤、DAO 开二级缓存 cacheable=true(BuildingNameRuleDaoImpl.java:29)、无 company/status 全租户共享、前端只读消费(page/add/update/delete 五端点无页面调用=死接口族,且 update 实现是错的——见 §16);
- **villagerule = 楼盘×规则绑定表**(生产 48,349 行≈每楼盘一条):vid+brid 两列挂接,复合 PK (brid,vrid) **不防同楼盘重复绑同一规则**;表列 rule 无 PO 映射(死列);
- **消费方 = 客源页前端正则校验**:选楼盘拉 queryVillageRule 成下拉,`new RegExp(villageRule).test(houseNumber)` 校验拼接门牌,失败报「楼号格式错误」(customerChance.js:1470-1481,customerSource.js:269-285)——**校验只活在前端**;
- 创建路径:绑定已有规则(addVillageRule 传 rule.id)或**内联创建**(传 rule 文本,先查不存在则建规则再绑,VillageRuleServiceImpl.java:74-104);前端"创建规则"=5 组「输入段(空/数字字母/字母数字/数字/字母/#)×连接符(空/-//门/门-/门#/座/座-/座#/甲/甲-/甲#)」下拉实时拼正则(village4rule.js:32-91);
- 绑定/解绑各写一条 MessageQueuing(BUILDING_RULE_CREATED/DELETED),全仓无消费侧;deleteBuildingNameRule 无引用检查可产孤儿绑定。

## 10. 敏感数据加密体系(横切红线)

- 算法:AES/CBC/PKCS5Padding+Base64(AESHelper.java:33-46),新密文封装 `ENC(V1:base64)`(SensitiveDataHelper.java:14-16);
- 覆盖字段:user.phone/user.identity/**house.houseNumber**/customer.phone/customer.identity(git aff66ccfd "加密三个敏感字段"+a24dba77a "查询统一改 getAllEncryptedForms 三格式混合");
- 解密三层回退(新格式→旧 Base64→兜底当明文),查询三形态 IN——**为存量明文/旧密文共存的迁移设计**,新系统应以此为由定存量策略(生产 houseNumber:明文 94% 82.3 万/ENC(V1:) 6.4 万持续增长/空 937);
- **密钥四级兜底**:JVM -D → 环境变量 → sysconfig encryptKeyV1/encryptIvV1 → 兜底旧密钥(KeyManager.java:110-152);🔴 **houseNumber 加密密钥与会话 cookie 密钥同源**(BaseAction.java:113-115 同用 encryptKey/encryptIv)——方林嫌疑,新系统必须分钥;
- PII 明文残留:officer/passport/passhkm/customeraccount.bankno/审批与量房消息直接拼密文门牌(ApprovalDecorationHouseServiceImpl.java:99、DecorationMeasureServiceImpl.java:163,163-183 两处未解密下发——同类场景 HousePO.address():321 却正确解密,实现漂移);AESHelper/SensitiveDataHelper 的 main() 里留有生产密钥与真实手机号样例。

## 11. 状态机汇总

| 实体.字段 | 值 | 写入点/生产证据 |
|---|---|---|
| customer.status | 0 INIT(建档)→1 AUTH(confirmCustomer 实名回写,:618-634);2 AUTHED **当前代码零写入点**,生产 84,387 行全带 token/signno,2015-2025 持续写入(provider=1 老电子签通道),**2026 年起归零新增**→退役态 | resetCustomer 只会打回 0(:560);regist/auth/confirm/update 均要求 status==INIT(529,577,618,365) |
| customerphone.status | 0 启用/1 停用 | 默认号不可禁用守卫 :461-463 |
| customerphone.defaults | 0/1,**抢默认语义**(加号即清全默认) | :331-347 |
| customeraccount.status | 0 启用/1 停用(软删) | :129-133 |
| customeraccount.type | 0 对私/1 对公 | 生产 16,496/2,625 |
| customerrecord.type | 0 电话查询(强制覆盖,唯一值) | :84 |
| village.status | 0 启用/1 禁用,直接 set 无迁移校验 | VillageServiceImpl.java:203-216;生产 0:1≈97:3 |
| house.status | **不存在**(PO 无字段),生产死列全 0 | 死雷:DAO 按 status 过滤即炸 |
| 换房审批 | 状态集合 INIT/APPROVE/CONFIRM/CONFIRMED/REJECT 防重入 | ApprovalDecorationHouseServiceImpl.java:69-75 |

## 12. 金额与数值口径(红线)

| 字段 | 量纲 | 证据 |
|---|---|---|
| house 五面积(build/free/inner/quote/total) | **×1000 定点 int**(毫米口径, tranargs ÷1000) | data.js:420-472;innerArea÷1000=计价面积(与 decoration 口径 memory 互证) |
| houselayout.area | ×1000(服务端过滤 `Long.valueOf(area[0])*1000` 佐证) | HouseLayoutDaoImpl.java:57-58 |
| village.mileage | **存米显公里**(×1000) | VillageServiceImpl 入参×1000,data.js:315-364;seiya `companyDistance*1000` |
| village.x/y | 经纬度 **×1,000,000 存 int**,不走 tranargs 前后端各自硬编码倍率 | VillageServiceImpl.java:303-304;生产值域 0~1.33 亿,808 行 0 |
| customeraccount | 无金额字段 | — |

无 tranargs 隐式换算陷阱的例外:queryVillageRule 的 tranargs `args:["price"]` 是**无效字段错配**(VillageRuleVO 无 price,data.js:381-388 复制残留)。

## 13. 跨模块读写表面全景(与客源域的边界)

| 方向 | 模块 | 性质 | 证据 |
|---|---|---|---|
| 客户档案 ← 写 | 仅 communion(两入口) | **全仓唯一写点** | CustomerServiceImpl.java:250,297 |
| 客户档案 ← 写(外部) | seiya 线索接入 existCustomer("未知客户"兜底) | 唯一外部建档通道 | A20260103ServiceImpl.java:426-440 |
| 客户档案 ← 读 | decoration(按手机查单)/secret/seiya/customer 会员/合同模板 8 个(留痕接口) | — | §3 |
| 房屋 ← 写 | communion(add/exist);客源转单**回写 house.buildArea** | 客源数据覆盖主数据面积 | CustomerChanceServiceImpl.java:687-691 |
| 楼盘 ← 写 | communion + **seiya A20260303 批量导入**(建城市/城区/楼盘/坐标拼音/重绑规则) | L- 代 company=0 来源 | A20260303ServiceImpl.java:56,114-245 |
| house/village ← 读 | customer 客源域(CustomerOriginPO/CustomerSourcePO **ManyToOne 持久关联** village)、decoration、approval 4 类、exports、kingdee | — | VillagePO 引用矩阵(Agent A 附录) |
| system→village 守卫 | 删城区前置 countVillageByCpid(有楼盘拒删) | 反向引用守卫好范本 | CityProperServiceImpl.java:50,221 |
| 两族 Customer 边界 | communion.customer=一人一档主数据;com.fanglin.customer=客源过程数据**自带姓名电话快照副本**;转化点=existCustomer 落主档回填 cid | **快照并存三重冗余**:customer.no / customerChancePO.customerno / 装修单关联;主档改名不改客源快照(改名唯一通道 updateCustomerNameAPhone.do 后端不存在=断链,见 §16);查询口径分裂(decoration 走 customerphone,客源查自己的加密快照)——同一人两族数据可呈不同电话 | CustomerSourcePO.java:17;CustomerChanceServiceImpl.java:998;ApprovalDecorationCustomerServiceImpl.java:118-125 |

## 14. 错误码全景(漂移重灾区,与 error.md 对照)

| 码 | 含义 | 位置 |
|---|---|---|
| 130003/130002/130178 | 「楼盘不存在」/「客户不存在」同义三码三处(decoration 130002、communion 130178) | VillageServiceImpl.java:79 等;DecorationServiceImpl.java:1255;CustomerServiceImpl.java:106 |
| 130006/130021/130022/130023/130176/130184 | 客户/电话族散码 | CustomerServiceImpl 各处 |
| 130020 | 楼盘已存在 | VillageServiceImpl.java:171 |
| 130161/130162/130173 | 命名规则族 | VillageRuleServiceImpl.java:70,106,90 |
| 130181/130022/130165/130177/130166 | 地图 API 五连散码 | VillageServiceImpl.java:274-299 |
| 230002/230030 | 客户银行账户(**communion 实体用 payment 段**) | CustomerAccountServiceImpl.java:104,124 |
| 230015 vs 510000 | 「公司不存在」同义两码(communion vs seiya) | VillageServiceImpl.java:112;A20260303ServiceImpl.java:146 |
| 240003/240004 | 换房审批族 | ApprovalDecorationHouseServiceImpl.java:74,124 |
| 320001/320002/320004/320005 | 房产族(320004 处残留"//130019 房产已存在"旧码注释) | HouseServiceImpl.java:56,68,81,139 |
| 140013/140063 | 装修单不存在/一房一有效单 | ApprovalDecorationHouseServiceImpl.java:84;DecorationServiceImpl.java:1838 |
| -1 | 模块开关守卫裸码(非标准段,CustomerAction 12 处/VillageAction 6 处/HouseAction 3 处) | CustomerAction.java:118 等 |
| 400005 | (seiya)楼盘编号已存在 | A20260303ServiceImpl.java:91-95 |

## 15. 字典枚举

- house 八枚举(type 房产类型/through 越层/orientation/waterVapour/parlourType/heatingType/isLift/houseClass)**全部 DB constant 表驱动**(module='building'),经 queryConstant 下发,代码不可见——具体键值待业务方/生产核对;🔴 ds 静态字典 houseInfo.json 仅 1 键 houseClass(清水/二手/别墅/阁楼/地下),且**它实为 DecorationPO 的字段**(DecorationPO.java:155),挂在 house 名下系字典摆放错位;
- relationshipType 5 值 key=value 中文(customerInfo.json);customerType 0 个人/1 公司(前端动态改标签"公司名称/公司电话");
- village/house 无静态 ds 枚举(楼盘零枚举,连 type 都没有);
- 前端缺陷:楼盘状态查询条件「全部」与「启用」key 都是 "0"(condition/data.js:76-83)——选全部实为只查启用。

## 16. 缺陷清单(三路合并精选,每条 file:line)

**A. 客户族**
1. updateCustomer 拼音条件**反转 NPE**:`if (name == null || empty)` 为真才算拼音→null 名进 PinYin4Util `.get(0)` 炸(CustomerServiceImpl.java:371-374),语义应为 `!= null`——**凡不带 name 的客户更新必触雷**;
2. PRIVACY_PROTECT 与 OPENESS 两常量同值 0,传"保护"期望脱敏将得明文(CustomerPO.java:35-37);
3. 幽灵字段过滤:CustomerPhoneDaoImpl 对无 company 字段的 PhonePO 加 company 过滤,multicompany=1 时必炸(CustomerPhoneDaoImpl.java:54-56)——现存配置 0 未爆的埋雷;
4. checkCustomerForPhone 语义混乱:命中同 cid 也抛"已存在";他人禁用号码静默放行可登记到自己名下;返回 int 恒 0 全靠异常表达(CustomerServiceImpl.java:204-215);
5. existCustomer 查重只认 defaults=1&status=0,换绑/禁用旧号后同号可再建档→同一自然人多 cid(:276);
6. **updateCustomerNameAPhone.do 死 API 断链**:前端已注册、后端无 @Action,而"客户信息变更审批"通过时恰恰调它(ApprovalDecorationCustomerServiceImpl.java:118-125)——**客户改名审批流是断的**,连带主档改名后客源快照/合同页永远显示旧名;
7. confirmCustomer 用电子签回传 mobile 直接改 customer.phone 并抢默认,不走查重,外部数据可覆盖用户主号(:630,643-665);
8. customerrecord↔log.customerLog 双写审计翻倍,旧表待废弃(:162-166);
9. 死代码族:STATUS_AUTHED 无写入点(退役态,生产考古见 §0)/BALANCE_TYPE_* 死常量/ pageCustomerRecord filters 空壳(CustomerRecordDaoImpl.java:42-46)/queryCustomerForName phones IN 兜底传 [pinyinName] 占位(CustomerDaoImpl.java:73-77);
10. addCustomerPhone 抢默认副作用无确认(:331-347);
11. 客户编号 counting 读改写并发,靠 uniqueone(no) 兜底报原始异常(CountingServiceImpl.java:32-51);
12. customerphone.phone 全局无唯一键,check-then-insert 并发双档(DDL:410)。

**B. 房屋族**
13. HouseDaoImpl 按 status(不存在的字段)过滤即 500 死雷(HouseDaoImpl.java:81-82);
14. updateHouse ignores 契约漂移:四要素/楼盘/门牌可提交不生效;面积非 0 才生效无法清零;前端无提示(HouseServiceImpl.java:142-145);
15. addHouse/existHouse 查后插无唯一约束,并发重复建档(:78-95,104-130);
16. 换房审批:后端分发注释死亡但 submit 仍可 POST 产脏审批单;旧房缺失 NPE;审批内容拼密文门牌(ApprovalDecorationHouseServiceImpl.java:86,99;ApprovalServiceImpl.java:265-266);
17. 量房消息下发密文门牌(DecorationMeasureServiceImpl.java:163,170,183);
18. queryHouseByNoAHnum multicompany=0 无公司过滤+uniqueResult 取任意一条(HouseDaoImpl.java:46-50);
19. houselayout:addHouseLayout 不校验楼盘存在/删户型不级联清附件/无 update/物理删(HouseLayoutServiceImpl.java:75,82-85);
20. house 无删除端点(业务上"不可删"是合理纪律,但无任何注释说明,新人易当缺陷补刀)。

**C. 楼盘/字典族**
21. addVillage 无查重;updateVillage TOCTOU 无 (name,cityProperId) 唯一键(VillageServiceImpl.java:106-156,168-173);
22. countVillageByNameACpid SQL 缺空格炸弹(VillageDaoImpl.java:99-104);
23. no 取号并发靠唯一键兜底原始 500(VillageServiceImpl.java:117-123);
24. village.company 守卫缺失面:ForStatus/ForPosition/ForUrl/locateVillage/VillageRule 全家不校验归属公司(:203-247,251-313);
25. updateVillageForUrl 无 null 守卫 NPE(:238-247);
26. locateVillage 写事务内同步调腾讯地图 HTTP(:271);API-Key 明文硬编码(:266-269);
27. BuildingNameRule:queryBuildingNameRule 连写两次 setMaxResults(1)(BuildingNameRuleDaoImpl.java:44-48);**updateBuildingNameRule 实际是再插一条新行**(saveOrUpdate 名不副实,IDENTITY 下带 id 也被忽略,必产重复规则,BuildingNameRuleServiceImpl.java:69-74);delete 无引用检查产孤儿;
28. addVillageRule 允许同 vid+brid 重复绑定(复合 PK 不拦,VillageRuleServiceImpl.java:109-115);
29. deleteVillageRule 消息 type 仍写 "addVillageRule"(事件名漂移,:132);
30. 死代码:api.data.queryVillage→queryVillage.do 全仓无此 @Action(data.js:324-331);queryVillageRule tranargs ["price"] 错配(data.js:381-388);village.js 编号正则 ^P_\d{4}$ 死校验(village.js:73);PinYin4Util main() 残留+死语句(PinYin4Util.java:198-207,123);ESTATE_*/BUILDING_RULE_* 事件无消费侧;
31. VillagePO 双 @Id(vid @GeneratedValue+no @Id)用 no 当 Hibernate ID,save 后 flush+refresh 才拿 vid+自指 pvid 二次 update——**一写变三 SQL**(VillagePO.java:97-115;VillageServiceImpl.java:144-150)。

## 17. 方林嫌疑

1. "正林"子品牌常量挂在客户 PO(BALANCE_TYPE_ZHENGLIN_INT,CustomerPO.java:27);
2. 地图默认中心沈阳坐标硬编码(village.js:448);腾讯地图 API-Key 明文(全公司共享一把钥匙,还写死在源码);
3. relationshipType 值即中文枚举("领导关系客户"等,纯方林客户关系学);
4. house.feature 列名"三不"(方林业务术语直接进表结构);
5. 手机号正则硬编码大陆号段 `1[3-9]\d{9}`(CustomerDaoImpl.java:73);
6. 加密密钥与会话 cookie 密钥同源(BaseAction.java:113-115),一把钥匙开两把锁;
7. 楼盘编号前缀 {公司短名}P-(公司身份进数据格式);
8. 开关式多租户:multicompany=0 时全公司共享一个客户池/楼盘池,跨公司可见可改(公司隔离是配置开关而非硬边界);L- 代导入 company=0 即后果实锤;
9. communion 模块开关(SystemConfig "communion")当权限守卫,任何登录用户可建房/改房/删户型(无角色校验);
10. village.rule「推荐楼牌号规则」文本挂楼盘主表(应属规则字典的展示冗余)。

## 18. 营造 addon-customerprofile 落地映射建议

- **模型**:city→cityproper→village(树,归属 org 必填,消灭开关式多租户)→house(楼牌号四段结构化+全文密文);customer(no 中性流水/一人一档)→customerphone(手机=身份锚点,默认号显式切换)→customeraccount(收款账户,单一事实源)→phone_access_audit(查询审计);
- **唯一键全部下沉 DB**:village(org_id+name+city_proper_id 唯一;no 唯一)/house(org_id+vid+house_number 唯一——老系统生产没有,补上)/customerphone.phone 全局唯一(配 existCustomer 幂等回显,呼应 addCustomerLead 400008 修复三选的方案 A);counting 取号改 DB 原子序列;
- **四条核心规约直接收编**:手机号=客户身份锚点/existCustomer·existHouse get-or-create(幂等化)/电话读取必留痕(事件化,接内核审计)/主数据唯一写点(其余插件只准经 addons-api 事件或契约读);
- **加密**:沿用 ENC(V1:)+三形态兼容思想,但**密钥独立于会话令牌**;officer/passport/passhkm/bankno 纳入同一 SensitiveDataHelper;消息/审批下发一律解密后输出或走脱敏视图(消灭两处密文泄漏);
- **事件**:customer_created/customer_updated/house_created/village_changed(供客源/装修单/营销插件订阅),替换老 MessageQueuing 无消费侧死事件;
- **淘汰**:户型库 houselayout(8 行死功能,不迁)/换房审批(改"直接换绑+审计事件+单房单有效单守卫 140063 思想保留")/对公六要素双向同步(V1 以 customeraccount 为单一事实源,砍 customer.company* 冗余列)/relationshipType 中文枚举(字典化)/正林常量/腾讯 Key 硬编码(地图 provider 配置化,坐标存 ×1e6 口径可留);
- 命名规则(buildingnamerule 正则字典+拼正则器)建议归 **addon-basedata 字典域**(呼应 building.md §7-3),customerprofile 只消费;
- **码段 330000 建议 / Flyway 迁移 1400+**(300000/310000/320000 已被 sales/inventory/balance 占用;军规 5 的"customerprofile 300+"系早期示例,已被顺延,建议随本 PRD 确认一并更新);
- 验收必含老缺陷显式修正:无名字段过滤死雷(16-13)/updateHouse 契约漂移(16-14)/纯手机查重并发(16-12)/断链改名审批(16-6,新系统改名走内核轻量审批或直接事件通知客源插件更新快照)/「全部」=「启用」条件缺陷(15)。

### 18.1 营造实现定案回写(第 68 次巡检补,细节见 docs/prd/addon-customerprofile.md)

- **addon-customerprofile V1 落地**(第 17 次,V1401 七表,码段 330000):CryptoBox(AES-256-GCM 随机 IV+HMAC 等值检索双轨,**密钥独立于会话令牌**——§18 加密定案兑现)/唯一键全下沉 DB/get-or-create 幂等/多号码默认号显式切换(330008/330009 守卫)/reveal 解密强制留痕/收款账户软删+默认守卫;
- **客源转化自动落主档**:LeadConvertedEvent 消费 get-or-create(第 17 次起,取代 lead 级镜像表);**C 端登录自动建档**(第 49 次,addon-portal 经 CustomerProfileApi.ensureCustomerByPhone 契约——主数据唯一写点纪律保持);
- 楼盘房屋 Web 管理页(第 45 次:城市→城区树+楼盘台账+房屋抽屉+门牌 HMAC 检索);
- 淘汰清单兑现:houselayout/换房审批/对公六要素双向同步均未迁 ✅;命名规则归 addon-basedata 未做(待真实诉求);
- 新插件接入已「零登记」(第 64 次 AddonConventionPostProcessor 约定推导 i18n/迁移位置,enabled 一行激活)。

## 19. 待业务方确认清单

1. **归属定案**:独立 addon-customerprofile(本文推荐,军规 5 预留段+主数据独立演化)vs 并入 addon-customer(docs/01 过堂表现行口径)——影响依赖方向(decoration/sales 引档案是否连带客源插件);
2. **存量门牌加密策略**:生产明文 82.3 万(94%)+ENC 6.4 万——新系统是否强制全量加密?迁移时明文存量直接加密还是保留三形态兼容读?
3. **customer.status=2 退役态 84,387 行**:迁移归并为 1(已实名)还是保留历史值?电子签状态与档案状态是否解耦(推荐解耦,见 4);
4. **电子签五件套去留**:signno/provider/token/authurl+四路径照片留在客户档案,还是电子签独立插件、档案只存引用 id?(推荐:电子签独立 addon,档案存 provider+外部 id);
5. **对公六要素**:砍 customer.company* 冗余列、customeraccount 为单一事实源(推荐),是否接受;
6. **默认号语义**:保留"加号即抢默认"还是改显式切换(推荐显式,防 §16-10 误操作);
7. **楼盘树与编号**:pvid 父子树保留(生产 81% 使用,推荐保留);编号 {公司短名}P- 格式含公司身份(方林嫌疑),改中性流水还是保留可读前缀;
8. **共享楼盘池**:老系统 company=0 共享池占 35% 且 L- 代导入全落 0——新系统楼盘归属 org 必填,还是保留"平台共享楼盘池+组织私有"两层?(推荐两层,org_id=0 为平台池);
9. **命名规则归 basedata**(正则字典+拼正则器)是否接受;
10. **户型号型与换房**:确认 houselayout 不迁、换房走"直接换绑+审计事件"(审批流废弃);
11. **电话查询审计**:接内核通知中心/审计设施还是本域自有表(推荐内核审计设施,双写迁移教训收口);
12. **客源快照瘦身**:客源域是否改为只存 cid 引用(消灭三重编号冗余与查询口径分裂)——涉及 addon-customer 契约,需两 PRD 联合拍板。
