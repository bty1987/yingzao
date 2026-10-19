# L2 规则清单 - 库存域(addon-inventory)

> 事实源:老系统 `D:\ERP\Rhadamanthus\src\com\fanglin\inventory\`(140 文件 / 20 ServiceImpl 4841 行),三路并行深读(库存主链 / 外辅·辅材 / 采购·横切面),每条结论带 file:line;关键断言(恒假守卫/原子扣减 SQL/外辅作废闸/punum 核销/计数器竞态/字典缺 5)经主会话逐条抽查复核;生产只读库复核一次(2026-10-11,information_schema)。
> 错误码段:老 **260xxx**(被 7 个模块借用);营造 addon-inventory 建议 **310000**、迁移 **1200+**(300000/1100+ 已被 addon-sales 占用建议,290000/900+ addon-example,280000/1000+ addon-market;以实施顺位为准)。

## 0. 模块定性

1. **装修行业的独立库存域,生产超大活域**:manifest(出入库单)258 万行/manifestitem 1729 万/accessories(辅料领用)254 万/accessoriesitem 1704 万/occcessories(外辅)7.7 万单/purchase 6.7 万——库存流水与销售明细同量级,是全库最大表族之一(2026-10-11 生产只读复核);
2. **至今高频生产维护**:2025-01 以来 60 commits,最新 2026-10-01(425b51fc4 集中修复批:原子扣减防负库存/外辅 total 恒 0 修数/计数器拆分防抢号/错误码统一);2026-09 还在加新功能(外辅作废 status=6,f9b4fec0b;工程验收锁定辅料 260089);**外辅族是活跃创新点,采购族多年稳定只在配套级小修**——重设计自由度:采购域可大胆重构,外辅域须保持业务兼容;
3. **生产零落地四件**(决定性负结果,PRD 可直接砍):`purchasebalance` **0 行**(采购结算从未投产)/`manifesttemplate`+`manifesttemplateitem` **0 行**(出库单模板从未投产)/`storehousestock` 182 行(月结快照,写点全库零调用=死表)/`checklist` 1171 行(盘点孤表,全库无写入方);
4. **定性一句话:inventory = 「单据即库存」的流水账**:库存增减内联在单据创建事务里,冲销=反向类型新单,没有独立的库存事件层——营造必须把「单据」与「库存事件」拆开,这是本域最核心的架构迁移;
5. **拼写地雷**:模块内 `Occessories`(外辅)≠ `Accessories`(辅料领用),O-代 A 是建表人家族性 typo(sales 的 OalesPO、payment 的 OalesPaymentPO 同病);营造正名:**外辅单→外辅采购单(协同域),辅料领用单→领料单**。

### 0.1 生产表清单与定性(2026-10-11 只读复核)

| 表 | 生产行数 | 定性 |
|---|---|---|
| manifest / manifestitem | 2,581,133 / 17,292,450 | 活,主轴 |
| accessories / accessoriesitem / accessoriesmanifest | 2,547,580 / 17,043,037 / 2,454,052 | 活(辅料领用) |
| occcessories / item / charge / cost | 77,406 / 210,008 / 13,636 / 51,430 | 活(外辅,2026 仍在加功能) |
| purchase / purchaseItem / purchasemanifest | 67,549 / 276,728 / 45,551 | 活(采购入库线) |
| **purchasebalance** | **0** | **从未投产** |
| **manifesttemplate / item** | **0 / 0** | **从未投产** |
| storehousestock(月结) | 182 | 死表(写点全库零调用) |
| checklist | 1,171 | 孤表(无写入方) |
| inventorydrawing | 71,788 | 活(通用附件表) |
| storehouse / storehouseuser | 178 / 1,440 | 活 |
| goodsstorehousestock | 49,280 | 活(实时库存) |
| approver / purchaseamount | 0 / 2 | 残留表(代码无对应 PO) |
| (同库共生基础数据:goods 32.5万/color 26.7万/format 29.7万/price 30.6万/supplier 1504/accessoriestype 35/goodspackage 377) | — | 归 data/material/communion 模块所有(addon-basedata),inventory 只是消费方 |

## 1. 出入库单族(manifest)= 库存流水主轴

### 1.1 模型与状态机(仅 2 值,纯账面)

- `inventory.manifest`(mid 主键,code 业务单号,cuid 创建人,**qmid 自引用原单**,company)+ `inventory.manifestitem`(siid 销售明细/piid 采购明细/aiid 辅料明细/qiid 原货单项/gid+cid+fid 商品×色×规/ishid 入库仓/oshid 出库仓;`surplus` 初始=num=剩余可冲量,`ManifestItemPO.java:252`);
- status 仅 2 值:**0 初始 / 1 已确认**(`ManifestPO.java:42-44`)。CG/XG 手工建单落 0,可改可删行;其余 10 类系统建单直接落 1(`ManifestServiceImpl.java:364,420,491,560,658,752,828,876,924,964`);
- 🔴**关键语义:库存增减与状态解耦**——CG/XG 建单(仍 0)时库存与销售侧 xgnum 已即时增减(`MSI:256,303`),confirm 只是账面标记;**不存在「审核通过才动库存」模型**;manifest 一旦建立无作废/删除端点。

### 1.2 type 全枚举(12 类,代码实证)

| type | 常量 | 中文 | 方向 | 建单入口 | 单号前缀/计数器 | 原单(qmid) |
|---|---|---|---|---|---|---|
| 1 | TYPE_CG | 进货 | 入 | `addManifestForCG`(MSI:224,**前端手工**) | CG/27 | 无 |
| 2 | TYPE_CR | 进退 | 出 | `addManifestForCR`(MSI:334) | CR/28 | 必挂 CG 单(MSI:342-350) |
| 3 | TYPE_XG | 出货 | 出 | `addManifestForXG`(MSI:270,**前端手工**,sales2xg 也调) | XG/29 | 无 |
| 4 | TYPE_XB | 出返 | 入 | `addManifestForXB`(MSI:402) | XB/30 | 必挂 XG 单(MSI:414-422) |
| 5 | TYPE_DB | 调拨 | 入+出 | `addManifestForDB`(MSI:477,**仅审批回调调用**) | DB/31 | 无 |
| 6 | TYPE_PU | 采购入库 | 入 | `addManifestForPU`(MSI:524) | PU/32 | 挂 purchasemanifest |
| 7 | TYPE_AS | 辅料出库 | 出 | `addManifestForAS`(MSI:610) | AS/33 | 挂 accessoriesmanifest |
| 8 | TYPE_AR | 辅退 | 入 | `addManifestForAR`(MSI:710) | AR(**复用 AS 计数器 33**,MSI:749) | 无 |
| 9 | TYPE_PM | 套合 | 出零件+入套餐 | `addManifestForPM`(MSI:862,由 GSSI mergePackage 调) | PM/37 | 无 |
| 10 | TYPE_PS | 套拆 | 出套餐+入零件 | `addManifestForPS`(MSI:814,由 GSSI splitPackage 调) | PS/38 | 无 |
| 11 | TYPE_CP | 盘盈 | 入 | `addManifestForCP`(MSI:910,由 GSSI plus 调) | CP/41 | 无 |
| 12 | TYPE_CL | 盘亏 | 出 | `addManifestForCL`(MSI:950,由 GSSI lose 调) | CL/42 | 无 |

- 单号规则全族一致:`公司简称 + 前缀 + yyMMdd + 4位流水`(流水=计数器×增量+偏移可配,`FormatUtil.java:19-23`);
- **前端只开放 1/3 两类手工建单**(ds 字典 `createManifestTypes: [1,3]`),其余 10 类全部由系统动作(采购入库/辅料领用/套餐合拆/盘点/调拨审批)派生——营造中 12 类天然就是「库存事件类型字典」;
- CountingPO 计数器:CG=27/CR=28/XG=29/XB=30/DB=31/PU=32/AS=33/**AO=35(辅料领用单)**/PO=36/PM=37/PS=38/CP=41/CL=42/**O=57(外辅单,2026 拆分防抢号)**(`CountingPO.java:126-158`;PM/PS/CP/CL 注释误写为"采购单/主材单"=注释漂移)。

### 1.3 与 sales 的挂接与五计数(衔接 sales.md §2)

- `sales.salesmanifest`(sid+mid)= 销售单×出货单 N:M 关联,**唯一写点**在 addManifestForXG 尾部按前端传 sids 逐条挂接(`MSI:323-327`);`inventory.purchasemanifest`(pid+mid)= 采购单×PU 货单关联(写点 MSI:601);
- **五计数中 inventory 只碰 xgnum**:出货逐明细守卫 `xgnum < num` 抛 260008 → 扣减(`MSI:311-320`);出返 XB 按原货单项 siid 回加(`MSI:457-462`)。siid 缺省(null→0)跳过销售联动=允许无销售单的纯出货;**num/initnum/punum/trnum 在 inventory 模块零触碰**(punum 例外见 §5.3 采购);
- 明细级回链:CR/XB 带 qiid 复制原单价(`MSI:380,391,448,466`);PU 带 piid+siid(`MSI:589`);AS/AR 带 aiid(`MSI:683,794`)。

### 1.4 守卫链(源单 surplus 消耗,check-then-act)

| 线 | 守卫 | 错误码 |
|---|---|---|
| CR/XB 扣原货单项 surplus | `MSI:383-387,451-455` | 260026 |
| PU 扣采购项 surplus + 按余量回写采购单状态(Σsurplus=0→全入2/部分1) | `MSI:581-585,595-603` | 260076 |
| AS 扣辅料项 surplus | `MSI:675-679` | 260051 |
| AR 反向扣 AS 出库明细 surplus | `MSI:771-775` | 260051 |

### 1.5 审批线:仅 DB 调拨一条

全 12 类中唯一走真审批的是 DB 调拨:前端 `alloting.js:105` 提交审批类型 11,审批通过回调 `ApprovalManifestDBServiceImpl.java:161` 才调 addManifestForDB 落单——**审批期不锁库存、不预占**,通过时双向增减(`MSI:511-512`)。营造映射:这就是内核轻量审批(已落地)的一个标准用例。

### 1.6 冲销 = 反向类型新单,无作废

进退 CR(退进货)、出返 XB(退出货)、辅退 AR(退辅料)都是**新建反向单**:XB 回加 salesItem.xgnum+库存入库;AR 回加库存+冲减工程辅料金额(`constructionAccessoryPO.total/num` 与 `constructionPO.accessoryTotal`,`MSI:777-803`)。没有负数单、没有红字冲销、没有作废。

## 2. 仓库与实时库存

### 2.1 三张表辨析

| 表 | 粒度 | 关键列 | 定性 |
|---|---|---|---|
| `storehouse` | 仓库档案 | shid/name/code/status(0启用1停用) | 活,**无 company** |
| `goodsstorehousestock` | 商品×色×规×仓库 | num int ×1000 | **实时可用库存,出库守卫对象**,唯一键(gid,cid,fid,shid) |
| `storehousestock` | 仓库×月份 | num **float** | 月结快照,**写点全库零调用=死表**(生产 182 行) |

### 2.2 原子出入库 = 全域唯一真守卫

- 入库 `instorehouse`(GSSI:254-287):原子自增 0 行=无库存行→校验商品(260004)/仓库(260005)/色规归属(260053)后建档;
- 出库 `outstorehouse`(GSSI:289-308):**单语句原子扣减 `UPDATE ... SET num=num-:num WHERE ... AND num>=:num`**(`GoodsStorehouseStockDaoImpl.java:69-74`),影响 0 行→回查区分 260011 库存行不存在 / 260008 库存不足;注释明示防并发超扣——**这是老系统并发正确性的孤本**(2026-10-01 才修上,425b51fc4);
- 套餐合拆 PM/PS、盘盈亏 CP/CL 都由 GSSI 层入口落成对应 manifest(`GSSI:170-196,221,249`)。

### 2.3 正负向写点全景(18 处,全部经 instorehouse/outstorehouse)

入:CG/XB/DB-in/PU/AR/PS零件入/PM套餐入/CP + 明细改删回补;出:XG/CR/DB-out/AS/PM零件出/PS套餐出/CL + 明细改删回收。**另存在第二条绕过 ManifestService 的写路径**:`seiya A20260904ServiceImpl.java:296-312`(2026-09 库存同步服务)直接 manifestDao.save + 调同一对出入库方法自建 CG/XG 单。

### 2.4 仓库权限(StorehouseUser)= 只过滤可见,不限制操作

`storehouseuser`(uid×shid)纯多对多,全删全插维护(`SUSI:82-93`);仅用于:①库存分页按授权 shids 过滤(无授权=空列表,GSSI:79-115);②7 个页面的仓库下拉来源(queryStorehouseForSelf)。**出入库写操作全部不校验它**——授权只管"看得到哪些仓",不管"能对哪些仓出入库"。

## 3. 辅料领用单族(accessories)= 内部仓库领料

### 3.1 定性

装修单在施工环节(csid/cnid)向公司辅料仓库领料的内部申请单:type 0 普通/1 售后(`AccessoriesPO.java:49-51`);单号前缀 AO/计数器 35。受**工程辅料额度双闸**:`construction.accessoryLimit/accessoryTotal` 总额闸(260083)+ per-gid `ConstructionAccessory.numLimit` 闸(260059);定材校验走 quotation→normtion→noods 按定额量拦截 260082(`AccessoriesItemServiceImpl.java:105-147`)。

### 3.2 状态机(8 值,`AccessoriesPO.java:53-67`)

| 值 | 含义 | 写点 | 守卫 |
|---|---|---|---|
| 0 | 初始 | addAccessories | 装修单存在且非终态;工程/环节存在;普通单须辅料已开放 120028;总额闸 260083 |
| 1 | 预出 | preoutAccessories(:239) | status==0 |
| 2 | 出货 | `addManifestForAS`(:649,跨族写点) | status∈{0,1} |
| 3 | 撤销 | cancelAccessories(:265) | status==0;回退工程额度与 per-gid 计数 |
| 4 | 拒绝 | refuseAccessories(:318) | status==0;回退逻辑与撤销逐行相同 |
| 5 | 退回 | **无写点=死状态** | — |
| 6 | 到货 | deliveAccessories(:392) | status==2 |
| 7 | 审核 | auditAccessories(:415) | **仅售后单(type=1)要求 status==1;普通单无守卫(见缺陷 §11-A1)** |

出库走 AS 货单联动 goodsstorehousestock 原子扣减;退库走 AR 普通单冲减工程额度、售后单跳过(`MSI:744,781,799`——售后不冲工程是定制业务规则)。`accessoriesmanifest`(aid+mid)是领料单↔货单关联,唯一读端点 queryManifestByAid(金蝶对接也消费)。

## 4. 外辅单族(occessories)= 向外部厂商采购定材辅料的物流+费用+结算

### 4.1 定性

厂商是系统用户(supplier=UserPO,bindOccessories 绑定登录账号,`OccessoriesServiceImpl.java:325`;厂商视角列表 pageOccessoriesForSupplier 按 supplier.uid 过滤)——**「厂商即用户」是方林自有渠道模型**。单号前缀 O/计数器 57(2026-09 从 AO 拆分,终结两族抢号,git 425b51fc4)。物流属性字段一整套:isEnter 可进园区/isLift 电梯/isExceed 超阶/isAttic 阁楼/loadFloor 上料楼层(0一层/1二层/-2负二/-1负一)。

### 4.2 状态机(7 值,含 2026-09 新增作废)

| 值 | 含义 | 写点 | 守卫 |
|---|---|---|---|
| 0 | 初始 | addOccessories | 装修单存在;工程未验收(projectStatus=FINISH 抛 260089"工程已验收,辅料已锁定") |
| 1 | 分配(绑厂商) | bindOccessories(:326,绑定向监理发钉钉) | status∈{0,1} |
| 2 | 送货 | deliveOccessories(:362,逐项写 sennum) | status==1 |
| 3 | 到货 | receiveOccessories(:416,逐项写 recnum) | status==2 |
| 4 | 结算 | balanceOccessories/batchBalance(:477,504) | status==3 |
| 5 | 完成 | finishOccessories(:553) | status==4;🔴**字典缺 5,前端把 4 当终态,该端点前端不可达** |
| 6 | 作废 | cancelOccessories(:610) | **status≥3 抛 260024(即仅 0/1/2 可作废)**;单据保留不可再操作(结构性白名单:各写点等值校验天然锁死) |
| 4→3 | 反结算 | rejectOccessories(:528,清结算字段) | status==4 |
| 0→删 | 物理删除 | deleteOccessories(:631) | status==0 |

### 4.3 三张子表与收费公式

- `occessoriesItem`:明细(gid/aid→data.aoods 辅料定材/nid 定材ID/num/sennum 送货数/recnum 到货数/rbalance 原结算价/vbalance 结算价/charge 项费用/surplus 浮动额×1000);
- `occessoriescharge`:向装修单收的费用(name/price×1000/surplus×1000/num);**收费公式** `费用=(price+(isExceed==0超阶?surplus:0))×num`,单级汇总进 item.charge,全单进主单 charge 且 rcharge=charge(`OccessoriesServiceImpl.java:264-304`);
- `occessoriescost`:成本线(initPrice/price/cost,type 0车运费/1搬运费/2上楼费;status 字段无人写=死列,常量名 STATUS_INIT_VEHICLEFREIGHT 错位命名自认);
- `inventorydrawing`(sid=oid+atttype=0)挂送货/收货凭证照片(生产 7.2 万行,活)。

### 4.4 金额线与结算(自闭环,不走 payment 域)

total=Σ(商品实价 selling×num)(`:260-262`,曾因引用 init 裸对象 selling=0 酿**生产 77280 行 total=0 事故**,425b51fc4 修数);rbalance/vbalance=Σ(item.rbalance×num);结算动作=纯状态机+单据内金额字段(updateOccessoriesForBalance 手调 vbalance/rcharge),**全库无任何 payment/receipt 产生,也不进装修单款项计算**——外辅的钱在系统外线下走。作废不清理子表、不产生冲销单。

## 5. 采购族(purchase)

### 5.1 双轴状态机

- status(入库轴):0 初始 →(PU 入库按 Σsurplus==0 判定)→ 1 部分入库 / 2 全部入库;2 是终态闸(其他操作拦 260018);**无人工回退/取消态**(`PurchasePO.java:36-40`,写点唯一在 MSI:595-603);
- balanceStatus(结算轴):0 初始 / 1 结算 / 2 完结;写点=采购结算单确认(PurchaseBalanceServiceImpl:129)+ 独立端点 updatePurchaseForBalanceStatus 直接拉 2(`PurchaseServiceImpl.java:251-262`,**无一致性联动,可 0→2 跳过 1**);
- 🔴 balanceTime/deliveryTime 两列**全代码库零写点=死列**(grep 实锤);
- 无 type 字段;单号前缀 PO/计数器 36。

### 5.2 挂接

与销售单挂接表是 `sales.salespurchase`(spid+sid+pid,创建时按前端 sids 批量插,`PurchaseServiceImpl.java:197-201`);货单项级 siid 指向销售项,PU 入库时随 manifestitem 传递;purchasemanifest(pid+mid)=采购单×PU 货单回执。

### 5.3 punum 核销全景(占用唯一,永不释放)

全库 setPunum 写点 grep 实锤:销售项创建时 `punum=num` 置初值(sales 侧);**唯一递减写点=PurchaseServiceImpl:192-193**;**无任何 punum+= 写点**——采购项删除、采购退货(CR)均不归还,**取消/删除采购永久烧掉销售项可采购余量**。

### 5.4 采购退货(CR)断链

CR=addManifestForCR 只扣原 CG 货单项 surplus+出库,**全程不触碰 purchaseItem.surplus、不重算采购单 status、不归还 punum**——采购退货后采购单仍停"全部入库",库存与采购账永久错位(`MSI:334-398` 无 purchaseItemDao 调用)。

### 5.5 采购结算(PurchaseBalance)= 生产 0 行的手工台账

关系 1:N(一张采购单挂多条结算记录,无去重无状态校验);结算单状态 0 初始→1 已付款(写点置 1+反写采购单 balanceStatus=1);**付钱哪条线都不走**(payment 模块 grep "purchase" 零命中)——纯线下付款记一笔金额;**生产 0 行=从未投产**。营造重设计自由度最大的一族。

## 6. 孤表与死件清单(生产实证)

| 件 | 证据 | 处置建议 |
|---|---|---|
| checklist 盘点 | 全库唯一读端点 pageCheckList,零写入方;type 0盘盈/1盘亏;num 是全模块唯一 float;按用户绑仓过滤无仓静默空页;与 CP/CL 货单**无代码关联**;生产 1171 行 | 不迁;盘点重做成 CP/CL 事件闭环 |
| manifesttemplate 模板 | 模板→建单带出**不在服务端**(纯前端把模板商品灌进明细表格,manifest2create.js:29-238);生产 0 行 | 不迁 |
| storehousestock 月结 | 写点 updateStorehouseStock 全库零调用;端点 queryStorehouseStock 前端零调用=双重死亡 | 不迁;报表按 manifest 流水聚合 |
| approver / purchaseamount | 生产 0/2 行,代码无对应 PO | 残留,不迁 |
| kingdee SalesOutStock/ReturnKingdeeService | 接口+实现齐全零调用方,且 outStock 裸 getApproverTime().toString() NPE | 死代码;金蝶归商业插件 |
| InventoryDrawing.status | 恒 0,删除=物理删无守卫 | 营造附件走通用文件抽象 |
| OccessoriesCostPO.status | 无人写无人读(常量名错位为 type) | 死列 |

## 7. 金额与数值口径

- **全域 ×1000**:数量与金额一律整型 ×1000=1 真实单位(件/元);前端 `api/inventory.js` tranargs 是口径直接证据(下发×1000/回显÷1000);明细 num/inprice/outprice/surplus、库存 num、采购 inprice/num、外辅 price/rbalance/vbalance/charge/surplus、辅料 selling/normnum/storenum 全在册;辅料定额折算 `sum(num×storenum)/1000000` 是两个 ×1000 相乘(`AccessoriesItemDaoImpl.java:51-54`);
- **例外 float 两处**:storehousestock.num(月结死表)与 checklist.num(孤表)——活域零 float;
- **int 溢出嫌疑**:外辅建单 `Arith.mul(rbalance,num)` 与 `Arith.mul(num,selling)` 都是 int×int→int(`OccessoriesServiceImpl.java:248,262`),×1000 口径下单价 50 万×件数 1 万即 5e9 破 int;addOccessoriesItem:94 更是裸 `*`;
- 注释漂移:occessoriescharge 的 DDL 注释仍写"分",PO 注释已改 ×1000(425b51fc4 修 PO 未修 SQL);OccessoriesItemVO.surplus 注释仍写"(分)"。

## 8. 跨模块写表面全景(营造事件化依据)

### 8.1 外部模块 → inventory 表

| 外部模块 | 表 | 读/写 | 证据 |
|---|---|---|---|
| **seiya** | storehouse(增改)/ manifest+manifestitem(建 CG/XG 单)/ goodsstorehousestock | 🔴写(**直连 DAO 绕过 Service**) | A20260904ServiceImpl.java:204,212,296,302-312(2026-09 新增库存同步) |
| **approval** | manifest(审批过建 DB 调拨单) | 写(走 Service,合法) | ApprovalManifestDBServiceImpl.java:161 |
| kingdee | accessories / manifest | 只读 | SalesOutStockKingdeeServiceImpl.java:22-23(死代码) |
| report | manifest | 只读(跨库 SQL,status=1 归月聚合) | InventoryMetricDaoImpl.java:44,57,70 |
| sales | salespurchase/salesmanifest(在 sales 库) | 只读(关联表 PO 引 inventory PO) | SalesPurchasePO.java:14-17 |
| 采购四表(purchase/purchaseItem/purchasebalance/purchasemanifest) | — | **外部零写者**(inventory 独占) | grep 实锤 |

### 8.2 inventory → 外部模块

| 写点 | 动作 | 证据 |
|---|---|---|
| sales.salesItem.xgnum | 写(−/+) | MSI:318(XG 扣)/:460(XB 加回) |
| sales.salesItem.punum | 写(−) | PurchaseServiceImpl.java:192-193 |
| sales.salesmanifest / sales.salespurchase | 写(挂接) | MSI:325 / PurchaseServiceImpl.java:199 |
| construction.constructionAccessory.total/num | 写(−) | MSI:784-786(AR);Accessories 三文件 |
| construction.construction.accessoryTotal | 写(−) | MSI:801-802;Accessories 三文件 |
| information.message | 写(AS 出库钉钉/站内,事务提交后发) | MSI:695-703,1033-1051 |
| quotation(noods/normtion)/decoration | 只读校验 | AccessoriesItemServiceImpl.java:110-136 |

**营造红线对应**:库存计数(sales 五计数/工程辅料额度)必须全面事件化——发布方写自己的账,订阅方回写自己的账;seiya 式直连 DAO 建单在插件架构下即「插件禁依赖实现模块」红线的反面教材。

## 9. 错误码段 260xxx 全景

- **自有码**(inventory 内 throw):260001/004/005/007/008/009/010/011/017/018/019/023/024/025/026/027/039/040/041/042/051/053/061/070/071/073/075/076/077/081/082/083/088/089/090(外辅族新码集中 260070-260091,系近年增量);
- **被 7 个外模块借用**:sales(260004/014/015/021/022/045/057/060/066/084/085/087)、payment(021/022/060/074/086)、approval(007/012/021/022)、balance(037/038)、communion(004/014/015/016/034/035/046/078/079)、data.material(004/014/015/016/020/034/035/041/043/046/056/062)、personel(004)——**260 段实际是半个商品/销售公共段**,印证 01 清单"错误码分段保留"的同时暴露"段内无主"的漂移温床;
- 漂移实锤:①260008 同码双义("出货数量不符" vs 动态拼接"X商品库存不足",GSSI:304);②260026 在 DB 调拨缺仓库处被语义错用(MSI:508);③260028 死码(守卫恒假永不可达,§11-C1);④260080"辅料单不存在"死登记(重复码,活码是 260023);⑤260084"销售单总额**不必配**"错别字入册 error.md(与 260085"不匹配"并存);⑥260076"采购项**项**数量不符"错别字;⑦deprecated 存量 20+ 码。

## 10. 字典枚举(ds/manifest.json,WebContent 与 app 两份逐字节一致)

| 字典键 | 全集 | 备注 |
|---|---|---|
| manifestType | 1进货/2进退/3出货/4出返/5调拨/6采购/7辅料/8辅退/9套合/10套拆/11盘盈/12盘亏 | =type 全集 |
| manifestStatus | 0初始/1已确认 | |
| createManifestTypes | [1,3] | 前端手工建单仅进货/出货 |
| purchaseStatus | 0初始/1部分入库/2全部入库 | |
| purchaseBalanceStatus | 0初始/1结算/2完结 | constant SQL 白纸黑字承认文案与 PO 不符"保留魔法数" |
| accessoriesStatus | 0初始/1预出/2出货/3撤销/4拒绝/5退回/6到货/7审核 | 5 死状态 |
| accessoriesType | 0普通/1售后 | |
| occcessoriesStatus | 0初始/1分配/2送货/3到货/4结算/**6作废** | 🔴**缺 5=完成**(后端有端点前端不可达);6=2026-09 新增 |
| occcessoriesisEnter / isLift / isExceed / isAttic | 0是(有)/1否(无) | 🔴 0=肯定的反直觉语义 |
| occcessoriesloadFloor | 0一层/1二层/-2负二/-1负一 | |
| occcessoriesisCostType | 0车运费/1搬运费/2上楼费 | |
| priceTypes/statusType/type | 0底价/1点位;0启用/1禁用;0正价/1特价 | 混入 manifest.json 的 sales 字典(字典文件按页面组织而非按域的病灶) |

数据库侧 `sqls/inventory/30_manifest_status_constant.sql` 把枚举补登 communion.constant(module='inventory')。

## 11. 缺陷清单(合并三路精选,按族)

### A. 辅料领用族
1. 🔴**auditAccessories 普通单无状态守卫**(`AccessoriesServiceImpl.java:403-419`):守卫仅覆盖售后单,普通单任意状态(含撤销3/拒绝4/已到货6)可被置 7=审核,死单洗白;
2. **addAccessories 第二循环缺 schedule 过滤**(:201-217 vs :184):与施工节点不匹配而未入库的明细仍进 ConstructionAccessory 累加,限额计数虚增;
3. 🔴**额度记账两侧口径不一致**:add 路径信任客户端 total(:178,传 0 绕闸),cancel/refuse 按 Σ(selling×num) 重算(:286-294,339-348)——额度永久漂移;

### B. 外辅族
4. **费用增删改不回写主单 charge/rcharge**(`OccessoriesChargeServiceImpl.java` 全文件):全删全插费用后单头金额与明细脱钩,结算页看到的金额可能是错的;
5. **商品价未判空 NPE**(addOccessories :260-262 goodsDao.get 直接 getSelling;对比单条路径有 260004 判空);
6. **delive/receive 数量无校验**(:376,429):可超申请数量送货/收货,不回写结算字段;
7. **rateOccessories 无守卫**(:563-577):任意状态可评分、可重复覆盖、无范围校验;
8. **int 溢出嫌疑**(×1000 单价×数量 int×int→int);
9. updateOccessoriesItem 换商品不更新单价与总额脱钩(:121-134);

### C. 采购族
10. 🔴**260028 死校验**(`PurchaseServiceImpl.java:177,190`):setSurplus(num) 后同对象自比恒假,本应校验 salesItem.punum——同一 siid 超额采购把 punum 打穿为负(error.md 已废弃的 260048/260054 即该防线遗痕);
11. 🔴**punum 配额泄漏**(§5.3):取消/删除采购永久烧掉销售项可采购余量;
12. 🔴**CR 不断链**(§5.4):采购退货后采购单状态/余量不回滚;
13. **空明细 PU 入库 NPE**(PurchaseItemDaoImpl.java:37 sum 空结果 NULL→intValue;MSI:599 int 比浮点 0.0f);
14. **SQL 表名小写 `purchaseitem` vs PO 映射 `purchaseItem`**(PurchaseItemDaoImpl 原生 SQL)——Linux 区分大小写 MySQL 上会炸;

### D. 主链(manifest)族
15. 🔴**恒假守卫**:CG/XG 空明细守卫写 `size() < 0`(MSI:232,278;同文件他处 `<= 0` 证笔误)——零明细货单可建;
16. 🔴**salesItem.xgnum check-then-act 竞态**(MSI:311-320):两笔并发出货把 xgnum 扣负;goodsstorehousestock 已原子化,salesItem 未同步修;各源单 surplus 同型竞态(CR/XB/PU/AS/AR 五处);
17. **单号生成竞态**(CountingServiceImpl.java:33-56 读-加-写无锁):并发同日同类型建单重复单号;仅 AS 有落库前查重(260061)且非原子,其余 11 类无查重;
18. **负数可击穿负库存守卫**:全族无 num>0 校验,`num>=:num` 对负 num 恒真,反向无限加库存;
19. **INIT 态删行不回加 xgnum**(MISI:210-245):删出货明细后销售侧出货余量永久多扣;
20. **AR 批量退库错挂工程**(MSI:727-742):循环内反复覆盖 accessoriesPO,结束后用**最后一个**单的 orderId 冲减全部明细的工程额度;
21. addManifestItem 出入库方向判据取自请求参数 manifestVO.getType 而非落库单据(MISI:124-140);
22. 仓库编号查重只查启用仓(StorehouseDaoImpl.java:49-51),停用仓 code 可重复;
23. 首插并发重复库存行(instorehouse check-then-insert 无唯一约束证据,queryGoodsStorehouseStockByShidAGid setMaxResults(1) 静默取一行);
24. NPE 三处:AS 的 accessoriesItemDao.get 裸用(MSI:666-675)/AR 的 manifestItemDao.get 裸用(MSI:761-771)/外辅消息串 getDecoration().getHouse().getVillage() 链式(:334,385);

### E. 横切
25. **260008 同码双义**(§9);
26. storehousestock/checklist/inventorydrawing status 等死列死表(§6);
27. 静默吞没:updateStorehouseUser 无效 shid 静默跳过(SUSI:89)/pageGoodsStorehouseStock 无授权静默空(GSSI:85)/PM/PS 零仓明细静默丢弃(MSI:844-854,892-902)。

## 12. 方林嫌疑机制级汇总

1. **多租户半成品**:单头带 company(manifest/manifesttemplate)强制过滤,**仓库/实时库存/仓库授权/货单明细/销售-出货关联全部无 company**——A 公司操作员可对 B 公司库存出入库,单公司假设残留;
2. **multicompany 配置开关决定隔离粒度**(CountingServiceImpl.java:34):单公司模式计数器 company 强制 0(单号拼公司简称但序号全局共享),多公司开则 per-company 计数——"配置开关决定数据边界"是典型方林单公司演化残留;
3. **钉钉消息 fanglin 域名硬编码兜底**(`OccessoriesServiceImpl.java:57,636-647`):按公司查 systemConfig URL,查不到回退 company=0 全局配置,再查不到用 `https://service-erp.fanglin.vip/...agent=4921021` 常量——新公司不配置就跳方林域名;
4. **厂商即用户**:外辅供应商是 personel.user 账号(supplier=UserPO),厂商视角页只按 supplier.uid 过滤不过滤 company——通用化必须独立 supplier 实体(seiya→addon-supplier 已有承接);
5. **仅辅料出库有消息通知**(AS 钉钉"辅料单已出库"),其余 11 类货单零通知——不对称暗示 AS 是按方林业务后补;
6. **售后类型定制**:辅料 type=1 售后单跳过工程额度联动(AR 不冲工程),是方林售后业务的机制级痕迹;
7. **地址链无硬编码**:外辅列表"城市-城区-楼盘"来自 decoration→house→village→cityProper→city 数据链(生产 72491 单全链零缺失),对多城市天然兼容——**正面教材**,market 案例库的"沈阳"硬编码反面在此形成对照;
8. 0=肯定的反直觉字典语义(isEnter 0=是/其他 0=有)——字典设计红线样本。

## 13. 营造 addon-inventory 落地映射建议

| 老件 | 去向 |
|---|---|
| manifest 12 类 + manifestitem | **库存事件流水**( addon-inventory 核心表):12 类收敛为类型字典;「单据」与「库存事件」分层——事件(增减库存)=唯一记账面,单据=业务面;手工建单仅进货/出货,其余派生 |
| goodsstorehousestock | 实时库存表:保留**原子扣减 SQL**(全域唯一真守卫,老系统孤本)+ 补唯一约束(防首插并发分裂)+ num>0 服务端校验 |
| storehouse/storehouseuser | 仓库档案+授权:授权升格为真闸门(写操作也校验)或显式声明只管可见性 |
| accessories(辅料领用) | 归 addon-inventory,但工程额度双闸(construction.accessoryLimit)经 **addons-api 事件/扩展点**与 addon-construction 协作(禁直写他域表);audit 线接内核轻量审批 |
| occcessories(外辅) | 归 addon-inventory,厂商侧走 addon-supplier 协同(废「厂商即用户」);结算自闭环保留 V1(生产就是线下钱货),V2 评估接 addon-payment;作废语义保留(仅到货前可作废,单据保留) |
| purchase 采购线 | 重设计:status 入库轴保留;**采购结算(purchasebalance)生产 0 行,直接按 addon-payment 统一收据/结算模型重做**,不复刻手工台账;punum 配额模型改「占用-释放-核销」三态或砍掉 |
| salesmanifest/salespurchase 挂接 | 改事件+关联表在己方域(采购单.source_ref+出货关联事件,sales.md §12 已埋) |
| xgnum/punum/construction 额度直写 | 全部事件化:inventory 发事件,sales/construction 订阅回写自己的计数(§8.2 红线) |
| checklist/manifesttemplate/storehousestock/月结 | 不迁(§6);盘点若要做=CP/CL 事件闭环;报表按 manifest 流水聚合 |
| DB 调拨审批 | 内核轻量审批(已落地)+审批期预占/释放语义补全(老系统审批期不占库存) |
| 错误码 | 310000 段,消灭跨段借用;260xxx 七模块借用症状在营造按插件段根治 |
| i18n | ds/manifest.json 全枚举进 dicts.yml;0=肯定反直觉语义全部翻转;补「完成(5)」缺失态或删 finish 端点对应语义 |

**正名表**:Occessories→外辅采购单(英文建议 outsourced purchase / subcontract);Accessories→领料单(material requisition);manifest→出入库单(stock manifest)。

### 13.1 营造实现定案回写(第 68 次巡检补,细节见 docs/prd/addon-inventory.md)

- **addon-inventory V1 落地**(第 23 次,V1201 十四表,码段 310000):单据即库存语义保留+实时库存原子扣减孤本收编+单号计数器 last_insert_id 原子自增(修读改写竞态)+12 类收敛类型字典+调拨走内核轻量审批+冲销=反向类型新单;
- **领料定材校验**(第 57 次,B 类#7 兑现):本文档 260082 老口径的营造实现=V1204 inventory_config 组织开关(默认关)+MaterialQuotaApi 契约(basedata 实现:报价挂定材包 V402→包成员明细 V7002→定额量+浮动校验)+超限 **310067**;先校验后落库(修老闸门先插后查同款);非包成员不拦/售后跳过(老语义同构);
- **厂商引用化两波**(第 45 次外辅 310058/310059、第 48 次采购 310065/310066):assign/create 签名 supplierName→supplierId 必传,经 SupplierApi 两态校验(不存在/停用),supplier_name 收窄官方名称快照(废「厂商即用户」,协同 addon-supplier);
- 计数事件化兑现:xgnum/punum 出入全走事件(第 24 次 sales 订阅回写,超卖死信可见;修老「punum 永不释放」);工程辅料额度=construction 域 consume/refund 契约+ledger append-only(第 23 次,修老额度双侧口径漂移);
- 采购结算 purchasebalance 不迁(统一 addon-payment)✅;模板/月结/盘点孤表未迁 ✅。

## 14. 待业务方确认清单

1. **多租户边界**:库存/仓库是否组织级隔离?(老系统跨公司共享,单号序号全局)——影响所有表是否带 org_id 与授权模型;
2. **「单据即库存」是否保留**:营造是否引入「审核后才动库存」?(老系统 CG/XG 建单即扣,DB 调拨审批期不占)——若引入,须补预占/释放语义;
3. **五计数事件化后 sales 侧口径**:xgnum(出货余量)/punum(待采购余量)由 addon-sales 订阅事件回写,但 punum 老语义(永不释放)是否修正为可释放?
4. **采购结算重做确认**:purchasebalance 生产 0 行,直接按 addon-payment 统一结算重做、不复刻,是否认可?采购付款是否要进 payment 域(老系统纯线下)?
5. **外辅结算去留**:V1 保持自闭环(线下钱货+单据内金额),V2 接 addon-payment?外辅收费(向装修单收运/搬/上楼费)是否要进装修单款项计算?(老系统不进)
6. **外辅「厂商即用户」通用化**:厂商账号模型归 addon-supplier,外辅单厂商视角走协同端点?
7. **辅料领用额度双闸去留**:工程辅料总额/per-gid 限额是行业通用还是方林管控特性?定材定额校验(noods)保留几层?
8. **盘点与模板**:盘点(checklist 孤表)是否要在 V1 补全闭环(若要,按 CP/CL 事件做)?出库单模板(生产 0 行)确认不迁?
9. **12 类货单类型字典收敛**:9-12(套合/套拆/盘盈/盘亏)依赖商品套餐与盘点场景,是否随 V1 全量迁还是先 1-8?
10. **手工建单范围**:createManifestTypes=[1,3] 仅进货/出货开放手工,维持?
11. **审核线**:辅料领用 status 7(审核)在普通单无守卫的乱象下,真实业务是否需要审核环节?若要,接内核轻量审批;
12. **负库存策略**:原子守卫已防超扣,但是否允许极端场景负库存(老系统负数单可击穿)?维持禁止?
13. **作废语义泛化**:外辅 status=6 作废(仅到货前)+结构性白名单锁死,作为「可作废单据」的标准范式推广到领料单/采购单?
14. **seiya 库存同步**(2026-09 新增,A20260904)的业务归属:营造中是 addon-supplier 发事件还是 inventory 提供导入端点?
