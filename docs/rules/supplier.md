# L2 规则清单 - supplier 厂商主数据域考证(V2 首个候选)

> 提取自:`D:\ERP\Rhadamanthus\src\com\fanglin\`——老系统**无 supplier 顶级模块**,厂商主数据寄生四处:personel Supplier 族(主数据+合同七件套,~60 文件)、communion 品牌厂商关联、data 印章族、production 外协;消费面横切 inventory/sales/balance/material/quotation/payment/kingdee/seiya 八模块。三路并行深读(personel 全族+合同电子签 / 消费方横切 / 外围与登录门户),关键断言经主会话逐条抽查复核(9 条全实锤,file:line 见文)。
> 提取日期:2026-10-13(自动任务·第 40 次运行)
> 定性一句话:**厂商=user 表 type=1 的行,"厂商即用户"是全域第一性事实;主数据无独立表、无独立生命周期,合同族七件套里只有主表是"合同",五从表其实是"供货目录/结算条款";电子签回调链已废弃靠人工收口;金蝶对接 dormant;seiya 2026 新同步通道是唯一带 type 校验的写入方。**

## 0. 模块定性

1. **非模块,是横切主数据**:老系统没有 supplier 包,厂商相关代码散布五处——①personel Supplier 族(管理端点+合同七件套);②communion brandSupplier/brand.ocuid(品牌×厂商多对多+直属厂商);③data supplierSignet(电子签章账户);④production Outsource(外协加工单挂 fuid);⑤各消费单据上的 uid 外键列。考证按"域"归档,不按"模块";
2. **"厂商即用户"实锤**:厂商=`communion.user` 表 `type=1` 的行(UserPO.java:73-81,0=员工/1=厂商/2=商户/3=Avenger/4=驻店员),与员工同表同登录通道(doLogin,isLogin 位=1 即放行,LoginAction.java:243-245);DDL 里 userId 列注释仍是"员工号"(dbs/communion.sql:1266)——厂商是后挂到员工表的"外部人员"类型;
3. **代际与活性**:管理端点 2019-10 建(SupplierServiceImpl 文件头);Merchant/Avenger 查询族 2021-01 建(a8e80d6d3)、2023-06 还在重构(8ed37d217);电子签 2026-07 抽象→revert→2026-09 重落地(700f8fe90→4d97a0862);**seiya A20260404(2026-04)= 供应商/驻店员主数据同步 API,是老系统厂商主数据的最新写入方**——域名继续高频演进,活域;
4. **运行时类型校验几乎不存在**:全库唯一 type=1 校验在 seiya syncInstorer(A20260404ServiceImpl.java:257-263);personel addSupplier(创建侧自设)、inventory bindOccessories(:325 直接 userDao.get 落 spuid)、production OutsourceServiceImpl(:115)、sales SalesServiceImpl(:326)全部不验类型——任何 uid 都能被当成厂商挂上单据;
5. **对外稳定键=oldUserId**:金蝶映射 `brand.getSupplier().getOldUserId()`→FNumber(SalesKingdeeServiceImpl.java:164,233),seiya 同步把外部系统编号存 oldUserId(A20260404ServiceImpl.java:177-187)——重写时"外部编号"必须是一等公民。

## 1. 主数据模型(personel Supplier 族)

- **载体**:无独立表,SupplierDao 以 UserPO 为载体查 `type=1`(SupplierDaoImpl.java:32-44);唯一键 loginId/userId(DDL dbs/communion.sql:1295-1296);
- **编号规则**:`multicompany≠0 → "{公司简称}U-"`,否则 `"OC-"`,拼 counting(type=47,date='single',公司级)5 位流水(SupplierServiceImpl.java:139-145);计数器读改写无锁,并发撞 userId 唯一键;删除不回收号段;
- **应用层零查重**:addSupplier 全程无重名/重号/手机号校验(SupplierServiceImpl.java:107-179 通读),兜底全靠 DB 唯一键;唯一带幂等语义的路径=seiya syncSupplier 按 userId/oldUserId 双查 upsert(A20260404ServiceImpl.java:114-119);
- **数量上限**:systemconfig `supplierlimit`(公司级,缺省 100),超限 190008"人员数已达上限"(SupplierServiceImpl.java:117-121,原生 SQL `count(*) where company and type` — SupplierDaoImpl.java:79-85);同步接口同逻辑用 190036(同义双码);
- **默认密码**:systemconfig `password`(公司级),缺省 MD5("helloworld")(SupplierServiceImpl.java:149-151);
- **角色挂载**:systemconfig `supplier`(全局键,注释"游客角色ID",种子值 16),缺 230010"角色不存在";同步接口要求 supplier/supplierDept(1128)/supplierDuty(19) 三键齐备(A20260404ServiceImpl.java:151-175);
- **部门要求**:必须二级部门(fatherDept 非空,310002"部门不可添加人员",SupplierServiceImpl.java:125-129);departmentDao.get 不判空即 NPE;
- **建号写入**:isLogin=FIP(1)、type=1、extranet=WEB+PC(8+16=24)、userInfo 占位 salary="0"(SupplierServiceImpl.java:161-178)。

## 2. 生命周期与状态机(厂商本体)

- **四个端点**:page/queryByPinyin/add/update(SupplierAction.java:32-70)——**无删除、无启停、无密码重置**(后三者借通用员工接口 updateUserForAdvance/resetPassword/defaultPassword,前端 supplier.js 与员工管理同款);
- **update 白名单**:userName/currentName/phone/oldUserId 四字段(SupplierServiceImpl.java:183-197);角色/部门/职位/状态/密码/extranet 均不可经此改;
- **user.status 语义**(借员工表):0 在职/1 离职/2 停职(UserPO.java:61-65),厂商"停用"复用员工离职语义,无专属状态机;
- **Merchant/Avenger(type=2/3)= 只出不进的僵尸族**:查询三件套+前端页面齐全(2023 还在维护),但**全库无创建路径**(addUser 写死 type=0,UserServiceImpl.java:543)——历史分类残留,重写不迁,并入统一账号类型模型即可。

## 3. 合同族七件套:只有主表是合同,五从表=供货目录/结算条款

### 3.1 主表 suppliercontract(电子签载体)

- `communion.suppliercontract`,实现 fadada Contract 接口;状态 **0 初始/1 无效/2 已签/3 归档**,类型常量 1 签章/2 签订/3 查看/4 下载(仅常量,PO/DDL 均无 type 列,VO 的 type 恒 null——幽灵字段,SupplierContractPO.java:27-41 vs SupplierContractVO.java:41);
- **provider 双轨**:1=法大大/2=e签宝(ElecsignProvider),2026-09 起经 ElecsignRouter 按"公司×场景(elecsign.provider.supplier)→公司级→全局→法大大兜底"路由,存量按记录 provider(ElecsignRouter.java:95-132);e签宝 flowid 列渐进占用(upload 后存 fileId,签署建流后覆盖 signFlowId,SupplierContractPO.java:65-69);
- **端点与状态机**(SupplierContractServiceImpl,写操作全部挂 communion 配置开关):add(落初始,不验厂商存在)→make(模板生成 PDF,download 幂等短路,**company 硬编码 0 走全局配置**)→view/signForCompany(公司侧自动签,status==0,写 comsignTime 不改 status)/signForSupplier(返回厂商签署 URL)→**affix(线下补签:当前操作人当 signner,直接置 2)**→expire(0→1)→filing(2→3,仅本地置数)→update(**全字段信任前端,含 status 无任何状态机校验**,:249)→delete(仅 status==0,**物理删**);
- 🔴 **回调链已废弃**:法大大回调端点 fadadaCallback 是**空壳**(FadadaCallback.java:42-46,收参数直接 SUCCESS);returnUrl 已改 `https://www.fanglin.cc`,原 signatureCallback.do 无实现(Signature.java:72);e签宝 autoArchived=true 且 filing 为 no-op(ESignElecsignServiceImpl.java:398-402,445)——**供应商签完系统内 status 停留 0,唯一回写途径=人工 affix 或 update 直改**。

### 3.2 五从表(供货目录/结算条款,全部物理删、基本无 update)

| 族 | 挂接键 | 内容 |
|---|---|---|
| Brand(supplierContractBrand) | scid+sid+bid | 品牌×结算条款:结算类型 0 底价/1 点位、结算值、违约金、质保期、服务费 JSON(SupplierContractBrandPO.java:22-48) |
| Category | scbid(品牌行) | 品牌下品类结算 ctype/cvalue |
| Goods(suppliercontractgoods) | scid+gid | 合同商品价:price 供货价/balance 结算价 |
| Product | scid | 自由文本产品行(全 varchar 无外键,合同模板填充用冗余快照) |
| Service(supplierContractService) | **sid(厂商,非 scid!)** | 厂商服务费:type 0 常规/1 特价/2 专供+服务费 JSON |

- 命名陷阱:Service 族挂厂商不挂合同;Product 与 Category 注释同名"厂商品牌产品表"但结构两物;Brand 有 update 但仅改 servicePrice(SupplierContractBrandServiceImpl.java:121-132);
- 合同模板命名规约 `C-<公司>-<业务>-<类型>-<序号>`:001 方林/半包、002 1918/大宅、003 宜家、004 爱林、005 恩林(C-001-F-0-0001.js:4-8)——公司矩阵硬编码。

## 4. 电子签章账户(supplierSignet,data 模块)

- 一行=一个厂商电子签章:`suid` 裸 long 逻辑外键,带企业信息(corporation/taxNo/bank),状态 INIT(0)→REGIST(1)→AUTH(2)→CONFIRM(3)→CANCEL(5),provider 同双轨(SupplierSignetPO.java:27-35,75);编号唯一 130046;
- 生命周期 13 端点:add/regist/auth/confirm/signate/auto/select/cancel/copy/move(SupplierSignetAction.java:31-136);消费场景=供应商合同签署(公司侧 autosign/供应商侧 extsign 取 signno,SupplierContractServiceImpl.java:87-121);
- 注意:decoration/payment 合同 PO 里的"电子签厂商(provider)"是**签章服务商**而非供应商,同名异义,检索易混淆。

## 5. 消费方矩阵(横切引用面,重写的外键纪律底座)

引用形态四种:**A=ORM uid 外键 / B=userId 登录名字符串 / C=品牌间接(brand.supplier)/ D=裸 long 列**:

| 消费点 | 形态 | 证据 |
|---|---|---|
| inventory 外辅 occessories.spuid | A | OccessoriesPO.java:233-241;bind 不验类型 :325;厂商看单专用 action 按登录 uid 过滤(OccessoriesAction.java:42-48→DaoImpl.java:40-51) |
| production 外协 outsource.fuid | A+B | OutsourcePO.java:154;落库按 userId 换 uid 不验类型(OutsourceServiceImpl.java:115) |
| sales 主材单 sales.ocuid(开单人厂商)+oales.suid | A+B | SalesPO.java:270-272;SalesServiceImpl.java:326,341;OalesPO.java:266-268 |
| sales 结算 salesbalance.vuid(厂商结算人)+bid | A+C | SalesBalancePO.java:290-317;状态 0 待结/1 厂商已结/2 公司已结 |
| balance 结算单 balancesales.suid/主单 belonger.buid | A | BalanceSalesPO.java:221-228;账户懒开户"厂商/员工打通"(BalanceServiceImpl.java:253-263,账户主体 0 员工/1 厂商) |
| **balance 结算单厂商门户查询** | **B(登录名字符串直查!)** | `brand.supplier.userId=:userId`(BalanceSalesDaoImpl.java:57-61,BalanceSelectionDaoImpl.java:60-64)——全域唯一按登录名过滤的消费点,口径漂移 |
| material venderMaling(厂商承担额) | 数值列 | MaterialPO.java:190;material 厂商结算=半成品(pageMaterialBalanceForVender 空方法,MaterialBalanceServiceImpl.java:95-97) |
| quotation 选材 selectionitem.suid | D(裸 long) | SelectionItemPO.java:243-246;改绑校验品牌厂商白名单 200045"厂商不在品牌关联列表中"(SelectionItemServiceImpl.java:344-371) |
| payment 收款校验 venderMaling | 数值 | 客户应付=定金+补款+coupon+**venderMaling 冲抵**(SalesPaymentServiceImpl.java:313-319) |
| customer 投诉 | C(且不透出) | 投诉挂品牌,brand 拷贝时**排除 supplier**(ComplaintServiceImpl.java:59) |
| kingdee 金蝶 | E(oldUserId→FNumber) | SalesKingdeeServiceImpl.java:164,233(标 TODO);BalanceBillKO/PurchaseInStockBillKO/PurchaseReturnMaterialBillKO **全库无调用方=孤儿代码** |
| data 商品(经品牌) | C | 商品必须挂品牌厂商,130168"商品无关联厂商"(SoodsServiceImpl.java:91-95) |
| seiya 同步 | B(外部编号) | A20260404 upsert,oldUserId 存外部编号 |

- **销售结算链厂商维度**(最复杂消费面):preSalesBalance 按 `goods.bid` 分组,每品牌一条 salesbalance(SalesBalanceServiceImpl.java:481-522);厂商可见性=**三路 OR**(brand.supplier.uid / sales.supplier.uid / 明细 brand.supplier.uid,SalesBalanceDaoImpl.java:79-90);厂商自确认 vpostSalesBalance 记 voster/venderTime 置 1(:604-656),SALES_AUTO_BALANCE 开关自动流入 balance;venderMaling 在付款额里冲减(SQL round(venderMaling),BalanceSalesDaoImpl.java:84);
- **外辅状态机**(inventory 侧已详证,inventory.md §外辅):INIT(0)→BIND(1 分配厂商)→DELIVE(2)→RECEIVE(3)→BALANCE(4)→FINISH(5)/CANCEL(6);绑厂商通知监理;验收锁定=装修单完工禁新增 260089。

## 6. 金额与数值口径

- 厂商域自身几乎无金额:合同从表 price/balance/结算值/违约金为 long"元"原值(无 ×1000);**venderMaling(厂商承担)是全域最重要的厂商相关金额**,参与客户收款校验(payment)与厂商付款冲减(balance SQL)两处;
- 结算链金额口径承袭 sales/balance(千元制),见各域 rules,不重复。

## 7. 缺陷清单(精选 18 条)

**主数据**:
1. 编号计数器读改写无锁,并发撞 userId 唯一键直接插入失败;UI 与 seiya 双入口放大竞态(CountingServiceImpl.java:36-54);
2. addSupplier 零查重+departmentDao.get 不判空 NPE(SupplierServiceImpl.java:125-127);
3. 挂单不验类型:bindOccessories/Sales/Outsource 三处只查人不验 type=1(唯一校验在 seiya syncInstorer:257-263);
4. 引用四形态并存(A/B/C/D),balance 两处甚至按登录名字符串过滤——同一"厂商门户"语义三种实现(BalanceSalesDaoImpl.java:61);
5. update 白名单缺 status,但"停用"走员工离职语义——厂商生命周期与员工语义混叠,无法单独停一个厂商;
6. Merchant/Avenger 只出不进(无创建路径),前端页面照常露出;

**合同/电子签**:
7. 🔴 回调链废弃:fadadaCallback 空壳+returnUrl 改官网+e签宝 filing no-op——签署完成靠人工 affix 收口(FadadaCallback.java:42-46;Signature.java:72);
8. updateSupplierContract 信任前端直改 status,绕过全部状态机(SupplierContractServiceImpl.java:249);
9. Contract 接口 STATUS_FILING_INT=1 与供应商语义(1=无效)冲突,法大大 filing 回调 `contract.status(1)` 一旦接通即把"归档"写成"无效"(fadada/bean/Contract.java:18;Signature.java:771)——哑弹;
10. 法大大 filing 失败误判成功:`"1000".equals(response.getInteger("code"))` String.equals(Integer) 恒 false(Signature.java:766-772)——哑弹;
11. 签署方法跑在类级 readOnly=true 事务里写 comsignTime,且 autosign 外部 HTTP 夹在事务内(SupplierContractServiceImpl.java:35,87-106);
12. 归档错误码漂移:filing 用员工合同码 130179/130185,同文件其余用 190028/190029;归档状态借装修常量 STATUS_FILE_INT(语义表不同:装修 1=已签 vs 供应商 1=无效,当前数值巧合 3==3 未爆)(D5/D6,SupplierContractServiceImpl.java:222-234);
13. affix 补签信任前端:signner=当前操作人非真实签署人,status 直升 2(:183-187);
14. addSupplierContract 不验厂商存在,cid/no 无查重;查询 scid 查不到 NPE(:65-72);
15. e签宝 extsign catch FLException return null,前端静默失败(ESignElecsignServiceImpl.java:375-377);
16. 供应商合同创建路由 company 硬编码 0 只能走全局配置(设计文档自认,docs/01-方案设计/电子签扩展方案.md:30);合同表无公司列=多租户半成品;

**消费面**:
17. addOutsourceItem 用 outsourceDao.save 存明细(应为 itemDao,主键错表,OutsourceItemServiceImpl.java:60-67);
18. material 厂商结算半成品:pageMaterialBalanceForVender 空方法但 action 入口仍在;vpostMaterialBalance 只记时间不记操作人(MaterialBalanceServiceImpl.java:95-97,120-136)。

## 8. 方林特性嫌疑(重写须剥离/参数化)

1. **fanglin 域名硬编码**:法大大 returnUrl=www.fanglin.cc(Signature.java:72);外辅钉钉消息 URL 硬编码 service-erp.fanglin.vip+agentId=4921021 作兜底常量(OccessoriesServiceImpl.java:57,可用 systemConfig 覆盖);
2. **DB catalog 写进注解与 SQL**:catalog="communion"/"data" 全线;原生 SQL 再写一遍 communion.user(SupplierDaoImpl.java:81);
3. **公司简称进主键编号** `%sU-`(SupplierServiceImpl.java:142);"OC-"前缀系方林集团口径(sqls/misc/USER_IDENTITY_OC_UPDATE.sql);
4. **生产配置写死进 SQL 种子**:supplier 角色=16/部门=1128/职位=19(sqls/communion/11_systemconfig_instorer_sync.sql:13);
5. **合同模板公司矩阵**:001 方林/002 1918/003 宜家/004 爱林/005 恩林(C-001-F-0-0001.js:4-8);
6. **communion 配置开关当权限**:60 余处 `SystemConfig.getString("communion")==null → -1 无操作权限`,部署开关混作功能权限;
7. **多租户半成品**:角色查询不传 company 但密码/上限传;counting 在 multicompany 关时强制 company=0;pinyin 查询与 page 查询的公司过滤不一致(SupplierDaoImpl.java:43,52-58);
8. **厂商外网门户在本仓库之外**:extranet WEB/PC 位只写不读+dbs 有 supplieruserright 孤儿表(全库无 Java 引用,dbs/communion.sql:1164-1170)——门户系统源码不在仓,open 问题。

## 9. 错误码台账(供应商域,同码同消息治理的反面标本)

| 码 | 文案 | 备注 |
|---|---|---|
| 190008/190036 | 人员/供应商数已达上限 | 同条件同义双码(UI vs seiya) |
| 190012 | 厂商不存在 | update 路径 |
| 190028/190029 | 合同不存在/不可操作 | 合同主流 |
| 130179/130185 | (员工合同码) | 归档路径误借,漂移 |
| 230010/230011/230015/230027/230029 | 角色/职位/公司/配置缺失 | 230015 与 510000 同义双码(seiya 用 510000) |
| 310002/310004 | 部门不可添加人员/供应商部门配置不存在 | |
| 130046/130077/130155/130156/130168/130171 | 印章编号唯一/品牌/产品/服务费/商品无厂商 | 130155 与 130171 同文案双码 |
| 200045 | 厂商不在品牌关联列表中 | quotation 选材改绑 |
| 260024/260089 | 辅料单状态不可操作/工程已验收辅料锁定 | inventory 外辅 |
| 400011-400029/510000 | seiya 新段 | 2026 新代码自开 4xxxxx/5xxxxx 段 |

跨段借用总结:厂商域混用 19/13/23/26/31/20/40/51 八个号段——**一个主数据域没有自己的码段**,是错误码分段治理(军规 4)的最佳反例;yingzao addon-supplier 应声明独占段。

## 10. 可迁移规则(收编进 yingzao 的口径,按已解除的确认门闸执行推荐项)

1. **厂商=独立主数据实体,不再寄生账号体系**:yingzao 建 `supplier_supplier`(org 内唯一,no 编号+name+联系人+电话+creditCode 统一信用代码+status 启停)——修"厂商即用户"的根:账号是账号(enable/disable/密码),档案是档案;inventory.md 外辅"厂商名称快照"接口按此升级为 supplier 引用;
2. **对外编号一等公民**:外部系统编号(老 oldUserId 语义)显式建列 `ext_no` 可空唯一——金蝶/ERP 同步/外部门户统一走它,seiya 同步通道思想收编(幂等 upsert:ext_no 命中更新、否则新建);
3. **类型校验必须落在写点**:所有挂厂商的写操作校验目标实体存在且启用(修老"任何 uid 都能当厂商");引用形态统一为 supplier_id 外键,废四种形态并存;
4. **品牌×厂商多对多保留,直属厂商(ocuid)并进多对多加 is_primary 位**(修双轨并存);选材/结算"厂商可见性三路 OR"收敛为"经品牌关联"单一口径;
5. **结算条款(五从表思想)是 addon-balance/sales 的 V2 依赖**:品牌级结算类型(底价/点位)+品类细化+商品供货价/结算价+服务费,统一挂"供货合同"下;合同主表(签署流)与结算条款(价格目录)**显式分成两个实体**——修老七件套混装;
6. **厂商确认环节保留状态位模式**:结算单"厂商已确认"(voster+venderTime)与"公司已确认"两级,确认人显式记录(修 material 半成品);
7. **电子签不迁 V1**:印章+合同签署全族(法大大/e签宝/回调)是外部依赖+方林门户配套,沉淀为扩展点(合同签署 Provider SPI)排期 V2+;合同状态机(0 初始/1 无效/2 已签/3 归档)与"线下补签显式留痕"思想保留;
8. **启停用替代删除**:厂商档案无物理删(消费面太宽:外辅/结算/选材/合同全挂),停用=不可新挂单、存量单不受影响;
9. **查重下沉 DB**:name(org 内)+ext_no 全局唯一索引+creditCode 可空唯一(修老零查重);
10. **不迁清单**:Merchant/Avenger 僵尸族/金蝶 dormant 对接/厂商外网门户(extranet 位+supplieruserright)/员工离职语义当厂商停用/counting 47 号段(改 supplier 表内原子计数器)。

## 11. 待业务确认(6 条,不阻塞写码按推荐执行)

1. 厂商与登录账号的关系:V2 是否需要"厂商自助门户账号"(老系统走主后台+外部门户双通道)——推荐 V1 纯档案+V2 按需加账号绑定;
2. 结算条款(供货合同价格目录)归 addon-supplier 还是 addon-balance——推荐归 supplier(主数据属性),balance 只消费;
3. venderMaling(厂商承担)在 yingzao 的落点:推荐销售单行级字段(承 sales PRD),supplier 域只存条款模板;
4. 统一信用代码(creditCode)是否必填——推荐可空(存量小厂商可能无码),唯一索引允许多 NULL;
5. 外协加工(production Outsource)域是否随 addon-construction V2 迁移(与供应商域只共享"厂商引用")——推荐后置;
6. 老系统存量迁移:厂商档案+品牌关联+供货目录三层数据迁移工具排期(V2 数据迁移统一处理)。

---
### 关键文件索引
- 主数据:`personel/{action/SupplierAction, service/impl/SupplierServiceImpl, dao/impl/SupplierDaoImpl, po/UserPO}`;`seiya/service/impl/A20260404ServiceImpl`(同步通道)
- 合同七件套:`personel/po,service/impl,dao/impl/SupplierContract*`;电子签:`elecsign/router/ElecsignRouter`、`fadada/{signature/Signature, action/FadadaCallback}`、`esign/service/impl/ESignElecsignServiceImpl`;印章:`data/{po/SupplierSignetPO, common/service/impl/SupplierSignetServiceImpl}`
- 消费面:`inventory/.../Occessories*`、`sales/.../SalesBalance*`、`balance/.../BalanceSales*`、`quotation/.../SelectionItem*`、`production/.../Outsource*`、`communion/.../BrandSupplier*`
