# L2 规则清单 - building 域考证(结论:不建议建 addon)

> 提取自:`D:\ERP\Rhadamanthus\src\com\fanglin\building\`(49 个 Java 文件 / 7 个 ServiceImpl 共 1435 行,全量深读)
> 提取日期:2026-10-11(自动任务·第 11 次运行)
> 🔴 **本文档是一份"决定性负结果"考证**:building 不是楼盘档案模块,而是**二手房中介业务**,且**生产库零落地面(从未投产)**。它回答了 docs/01 待办"addon-building 独立 vs 并入 addon-customer"——两案都不推荐,建议整域不迁。
> 生产复核:2026-10-11 生产只读库 `information_schema.tables WHERE table_name LIKE '%building%'` → **仅 communion.buildingnamerule(180 行)一张**,无 building catalog 任何表。

## 0. 模块定性

1. **业务定性(与过堂时的假设根本不同)**:这不是"楼盘档案",是完整的**二手房中介业务**——房源挂牌(出售/出租)、客源登记、带看跟进、收钥匙、房源-客源匹配签约、交易款项、按支付方式的房产单流转。151 行 constant 字典全为中介词汇。真正的楼盘(village)/房屋(house)档案在 **communion 模块**,building 模块名是误导;
2. **生产零落地面**:building catalog **0 张表**(生产只读库实锤,2026-10-11);代码引用的权限表 `platform.buildingHouseDepartmentRight` 在生产也不存在 → 房源数据权限功能无落地面。结论:**从未上线(或上线前即废弃),无存量迁移压力**;
3. **代际**:git 可溯首点 2018-01-14(90d197540),活跃期 2018-01→2019-09,此后仅 4 次维护,最后实质改动 2026-07-20 640ad7905(全模块 uniqueResult 补 setMaxResults(1) 的机械修复)。典型"2018 年内部孵化、未投产、遗留死代码";
4. **零反向依赖**:全仓 0 个模块外 Java 类 import com.fanglin.building;装修单 `DecorationPO.hid→communion.house` 链路**不经过 building 模块**,双方仅共用 house/village 物理档案,互不引用;
5. 表 7 张全部 catalog="building":`building`、`buildingHouse`、`buildingCustomer`(camelCase)与 `buildingdrawing`、`buildingnode`、`buildingrecord`、`buildingschedule`(全小写)——命名风格两代并存。

## 1. 七实体速览与状态机

### 1.1 buildingHouse 房源(核心,443 行)
```
                ┌─(advance 通过 :308-317)→ 3=通过 ─(bindBuilding:116 要求==3,签约置1)→ 1=签约
 4=驳回 ─(重新申请,前端置0)→ 0=待审核
                └─(advance 驳回 :327-336)→ 4
 0/3/4 ─(失效按钮,前端置2)→ 2=失效(终态;所有 update 守卫 status!=2)
```
- 字段要点:hid→communion.house(物理房屋)+type(0 出售/1 出租)——**同一物理房可挂出售+出租两条房源,查重按 (hid,type) count→save 无唯一键**(BuildingHousePO.java:166-174, addBuildingHouse:204-208 抛 320004);产权证/议价类型/租金周期/唯一住房/电梯等中介字段全套;
- 认领 takeBuildingHouse:245-267 仅要求 followUser==null,**无数据权限校验**;认领时 department/region 自动取认领人部门及父级;
- 🔴 PO 常量缺陷:`STATUS_SIGN_SHORT=1` 与 `STATUS_DISABLE_SHORT=1` **同值**(BuildingHousePO.java:38-40)→ unbindBuilding:197 实际把房源置 1(签约)而非 2(失效)。

### 1.2 building 房产单(房源×客源匹配签约)
- 0=签约 → 1=失效(bindBuilding:171 置 0 / unbindBuilding:199 置 1,终态无出口);balanceStatus(0 认购/1 成交)仅 status==0 可改(:220);
- 查重:一个 hs/cs 只允许一张房产单(BuildingDaoImpl.java:45-56,软约束);
- 🔴 状态常量(0=签约/1=失效,BuildingPO.java:32-34)与生产字典 `building/status`(0待审核/1签约/2失效/3通过/4驳回)冲突——同一字典被房源页与房产单共用,渲染必错位(设计事故)。

### 1.3 buildingCustomer 客源 / drawing 图纸 / node 节点 / record 记录 / schedule 流转
- **客源**:0=初始→(签约置 1)→2=失效;查重 count(cid,type) 抛 320007;**列表强制只看自己创建的**(BuildingCustomerDaoImpl.java:27 恒加 create.uid=当前用户);price/area 为字符串区间枚举("90-120")【类型债】;
- **图纸**:文件 URL 存储(前端走 common 上传拿 resUrl 落库),软删 status=1,无 update;"设首图"=回写房源 path;type 唯一枚举 0=房源图纸;
- **节点**:按支付方式(payType:0 一手房买卖/1 全款/2 商贷/3 公积金/4 部队公积金/5 租赁月付)分组的流转模板;🔴 `sore`(sort 的 typo)列;sore=size+1 竞态;`queryBuildingNodeByType` sore=null 时**无 orderBy**→"首节点"不确定(BuildingNodeDaoImpl.java:46-58);无删除无排序端点;
- **记录**:type 0 跟进/1 带看/2 自看(字典)+**type=3"收钥匙"后端硬编码不在字典**(BuildingHouseServiceImpl.java:428-441,前端渲染为空);房源/客源二选一挂靠;查询不分页全量拉取;
- **schedule**:0=初始→2=完结;reset=原生 SQL 全部置 1(失效)再重建首节点,**不带 status 守卫**;推进协议=前端传 bsid+下一节点 sore;🔴 NPE 雷:bsid 查无→:91 NPE,链尾 get(0) 越界(:93),非 INIT 跳过完结仍生成下一节点(可重复推进)。

## 2. 金额与数值口径

- **全模块无 ×1000/÷1000**:金额全部 long/Long **原值"元"**(unitPrice/totalPrice/tradePrice/downpayment 等);前端 api/building.js 全文无 tranargs;面积 ㎡ 原值;
- 🔴 **三单号(H/C/B)与装修单共享同一日计数器** `CountingPO.TYPE_DECORATION_ORDER_INT`(BuildingServiceImpl:164 等 3 处),且计数器先查后改非原子(CountingServiceImpl.java:36-54)→ 并发重号+装修单序列空洞;
- 金额区间过滤由前端拼 "min-max" 字符串、DAO split 后 ge/le。

## 3. 跨模块读写表面

| 方向 | 对端 | 读/写 | 用途 |
|---|---|---|---|
| building→communion | customer/customerPhone/house/department | 读 | 业主、房/客代理人、物理房屋、手机号(取 status=0 第一个)、部门大区冗余 |
| building→personel | user | 读 | creater/followUser/keyer/operater |
| building→system | company | 读 | 仅取 shortname 拼单号 |
| building→common | counting | **读写** | 日计数器(与装修单共享) |
| building→right | buildingHouseDepartmentRight | 读 | 房源数据权限(生产无此表,功能不可用) |
| 外部→building | — | **无** | 全仓 0 import、0 XML、0 外部 SQL |

## 4. 缺陷清单(精选 12 条)

1. STATUS_SIGN 与 STATUS_DISABLE 同值 1 → unbind 把房源置"签约"而非"失效"(BuildingHousePO.java:38-40);
2. 房产单状态常量与共享字典语义冲突,渲染错位(BuildingPO.java:32-34 vs 生产字典 building/status);
3. nextBuildingSchedule:bsid 查无 NPE(:91)/链尾越界(:93)/非 INIT 重复推进(BuildingScheduleServiceImpl.java:64-111);
4. 守卫判错对象:判 `getBuildingCustomer()` 却解引用 `getBuildingCustomerAgent()`(BuildingServiceImpl.java:271-273);
5. 按 csid 查跟进 alias 属性不存在必炸;type 用 Long.valueOf 绑 short 列(BuildingRecordDaoImpl.java:34,36);
6. 客源过滤字段 typo "custumerName" 且实际比对 cid(BuildingCustomerDaoImpl.java:36-38);
7. 房源/客源 count→save 查重竞态,无 DB 唯一键;
8. 单号共享装修单计数器且非原子 → 并发重号+装修单序列空洞;
9. 权限表名三处不一致(PO=buildingHouseDepartmentRight/删除 SQL=houseSourceDepartmentright),生产两者皆无 → pageBuildingHouseForAdvance 生产不可用;
10. takeBuildingHouse 无权限校验任意认领;普通 pageBuildingHouse 无权限过滤;filters 为空静默 return;
11. updateBuildingHouse copyProperties 未排除 status/path,可被连带覆写(BuildingHouseServiceImpl.java:285);
12. 前端 5 个 houseSource* 菜单 URL 指向 8.3.004.010 已删除的视图(死链);收钥匙 type=3 不在字典渲染为空。

## 5. 方林嫌疑机制级汇总

| 机制 | 判定 |
|---|---|
| 房源-客源-带看-收钥匙-签约-按支付方式流转的完整中介链 | 【行业共性但与装修正交】标准 brokerage 域模型——**装修 ERP 不需要它** |
| 物理房屋档案(house/village)与业务挂牌(listing)分离,1 house 可挂售+租两条 | 【行业共性,正确建模】重写可借鉴(若做) |
| 联系号码走 customerPhone 主数据(读 cid→phone[0],不冗余存) | 【行业共性】保留为全局规则 |
| 单号=公司短名+字母+yyMMdd+共享装修单计数器 | 【方林嫌疑】全局习惯,且共享计数器是缺陷非设计 |
| company 只进计数器不进业务表行(三主表无 company 列) | 【方林嫌疑】内部试用痕迹 |
| Filter if-else 散落 DAO、"6"=五室以上魔法值、PO 内写 ptv() | 【方林嫌疑】工程习惯 |

## 6. 营造落地映射建议(决定性)

**不建议建 addon-building,也不建议"并入 addon-customer"**——建议**整域不迁**,理由按权重:

1. **生产零落地面**:building catalog 0 表+权限表不存在 → 方林自己从未投产该业务,无存量数据、无用户、无验证过的流程;"提取行业模型"的前提(业务真实运行)不成立;
2. **域错位**:它是中介(brokerage)业务,与装修 ERP 行业模型正交;开源装修 ERP 的目标用户不需要二手房中介;
3. **代码质量不达提取线**:同值状态常量/字典冲突/NPE 雷/守卫判错对象/死链菜单——即便想做中介,这份代码也没有可继承的实现口径,只有领域名词表。

**沉淀进知识资产的三条可迁移规则**(本模块仅存的正面价值):
- 物理房屋档案与业务挂牌分离(1 house : N listing,按 hid+type 查重)——若未来任何域需要"房"维度,照此建模;
- 联系号码走 customerPhone 主数据,业务表不冗余手机号;
- 反面教材三条:状态常量同值、共享计数器、count→save 无唯一键——均已被其他域 rules 立为红线。

**连带修正**:① docs/01 待办"addon-building 独立 vs 并入,滞后定"建议改判为**"不建,模块废弃"**(待业务确认,见 §7);② `communion.buildingnamerule`(楼盘命名规则,生产 180 行活数据)属 communion/data 域,不随 building 走;③ constant 表 building 模块 151 行字典中 `house.*` 60 行实为 communion.house 的字典,应归 house 域;④ 前端 11 页面/31 端点/i18n 18 条全部随域废弃。

## 7. 待业务方确认清单

1. **整域不迁的定案**:building(中介)确认废弃?若保留任何中介能力,按"house 档案+listing 子实体并入 addon-customer"立项(V2+,需业务补节点/字典种子——生产无表无数据);
2. 生产 building 空库是"从未上线"还是"下线清库"?若曾上线,历史数据在备份库?(决定是否存在归档义务);
3. `communion.buildingnamerule`(楼盘命名规则,180 行活数据)确认归 basedata/communion 字典域而非 building;
4. constant 表 `house.*` 60 行字典确认随 house 档案域走(影响 house 相关页面字典下发);
5. docs/01 过堂表 building 行与本结论的措辞同步(过堂表写于深读前,按"楼盘档案"假设)。
