# L2 规则清单 - 营销获客域(addon-market)

> 提取自:`D:\ERP\Rhadamanthus\src\com\fanglin\market\`(107 个 Java 文件 / 15 个 ServiceImpl 约 1900 行,三路并行深读:核心获客链 / 官网案例库 E 族 / 微信名片族+模块全景)
> 提取日期:2026-10-11(自动任务·第 10 次运行)
> 🔴 每条规则标注【方林嫌疑】:行业共性 vs 方林实现习惯,需业务方确认后决定去留
> 老系统 market 错误码 = 39xxxx 段(390001-390008,获客链)+ 跨段借码(330100/330102/330103 客户段、340009 人事段)——借码本身即缺陷;营造 addon-market 建议 **280000** 段,迁移 **1000+**(900+ 已被 addon-example 示范占用;以实施顺位为准)

## 0. 模块定性(与其它五域根本不同,先读这节)

1. **不是十年遗留,是 2026-07 新增模块**(git log: b5eecb801/9af9a6b84)——自带幂等键/敏感数据加密/REQUIRES_NEW 桥接等现代化手法,是老系统里"最接近重写目标"的代码;但仍按事实提取,禁照抄;
2. **纯叶子模块**:全仓**零反向依赖**(无任何非 market 包 import com.fanglin.market),只能从 HTTP 端点进入;正向依赖仅 customer(承接)/system(配置)/dingding(外呼)三处;
3. **三族结构**:①核心获客链(活动/渠道/来源/进线/归因/承接桥)——真业务流,与 addon-customer 衔接;②官网案例库 E 族(案例/城市/人物/图片/点评/报名)——对客展示 CMS,自闭合;③微信名片族(card/cuser/cnotice)——电子名片+留资通知,前台在外部 ISV 平台(不在本仓库);
4. 62 个端点/13 个 Action/16 张表(catalog 全部独立 = `market`),15 个 ServiceImpl 全覆盖无遗漏。

## 1. 投放物模型(获客链的"弹药")

- **层级不是树,是"两字典一交叉物"**:Campaign(营销活动,6 态)× Channel(渠道,2 态)→ Source(投放源=落地物:渠道码 code/URL/二维码/**UTM 三件套** utmSource/utmMedium/utmCampaign);Source 创建时校验 campaign/channel 各自存在且同 company;
- **Campaign 状态机**:0 draft → 2 enable(publish)→ 3 disable;5 archive(终态,发布/停用时拒);**1 warm / 4 finish 无任何写点(设计半成品死状态)**;
- **Channel/Source 状态**:0 enable / 1 disable,**均无恢复启用接口**——停用即终局【方林嫌疑:状态机不闭环】;
- **归因三键冗余**:进线行直接冗余 channelId/sourceId/campaignId(反范式换统计效率,行业共性保留);
- source.code 渠道码:UUID hex 兜底,company 内查重**仅代码层无 DB 约束**(并发可重)。

## 2. 进线生命周期(marketinbound,幂等是灵魂)

```
0 pending ──承接成功──▶ 1 success(清errorMsg)
    │─缺承接参数──回退 0+errorMsg(滞留无告警)
    └─业务异常──▶ 2 failed+errorMsg(吞掉不上抛,登记接口永远"成功")
3 accepting(承接中,V1 新增中间态供 beginAccept 幂等闸;retry 改为 0/2 态动作不再占状态位)   4 ignore(✅V1 已闭环化:显式端点+操作人+留痕)【✅第35次核对回写:V1 交付口径】
```

- **登记链路**:三重校验(campaign/channel/source 存在+同 company+**source 归属与传入一致** 390001/390003/390004)→ phoneHash=MD5(trim(phone)) → idempotentKey(客户端传入优先,缺省 `MD5(company-campaignId-sourceId-phoneHash)`)→ 按 key+company 查重命中**静默幂等返回**(回填 6 字段,不含 items/承接结果)→ 手机 AES 密文落库明文不存 → save+flush 取 miid → saveItems(EAV)→ acceptInbound → 仅 success 落归因;
- **幂等两处退化**:phone 为空则掺 UUID(重复提交建多条);先查后插无锁(并发双插)——营造须 DB 唯一约束兜底;
- **EAV 动态表单**:marketinbounditem(miid+fieldKey/fieldValue)承接落地页不定字段,fieldKey 空白跳过——行业共性(落地页表单演化快),保留;
- **计数口径**:countMarketInboundForCampaign/Channel **缺省只数 success(1)**="有效进线数";
- 原生 SQL 硬编码 catalog `market.marketinbound` 与 PO @Table 双份维护(化石手法,营造禁);
- 业务单号 inboundNo=`"MI"+UUID前18位`(无日期无流水语义)。

## 3. 承接桥接与归因(与 addon-customer 的衔接,架构重点)

- **MarketCustomerBridge(76 行)= 获客链唯一出口**:优先级协议——VO 内嵌 `customerChance != null` → 走机会(深层转化);否则 `customerSource != null` → 仅建客源;都无 → accepted=false 回 pending,消息"入站记录缺少Customer承接参数"(**硬编码中文当协议**);
- **跨模块写法 = REQUIRES_NEW 直调 customer Service 接口**(非 DAO 直写):addCustomerChance 级联建 客户→房产→客源→派发(按 CustomerDeptRule/CUSTOMER_ISSUE_RULE_INT 决定 auto/manual);addCustomerSource 仅客源+记录;
- **承接是否级联建客户主档/房产(营造口径显式化,B 类联动#2)**:老桥 chance 路径级联建 客户→房产→客源;**营造 V1 = 承接只建客源 lead+派发机会 chance,不级联建客户主档与房产**——主档(客户)在客源**转化**时经 `LeadConvertedEvent` 由 addon-customerprofile get-or-create 建档(见 customerprofile.md),房产维持登记页显式录入;承接/转化两级分离,使无效进线不污染主数据(营销域与档案域的写边界);
- **🔴 事务边界孤儿**:Bridge 独立事务先提交,外层进线事务若在归因/回写阶段失败 → **客源已建、进线消失**——营造必须显式设计(承接幂等+补偿或两阶段确认);
- **归因 = insert-only 事件流水**(marketattribution,无更新无删除):eventType 0 submit/1 customer_source/2 customer_chance/3 order,按 ccid>0→2、csid>0→1、否则 0 推导;**3 order 无写点(成交归因是规划未实现)**——四级漏斗模型行业共性,保留;
- **撞单查重在 customer 侧且是"同录单人"维度**:同 uid+phone 静默返回已有 csid(330103 被注释)、同 uid+楼盘+楼号静默返回(330102 被注释)——**同一手机号换录单人即再建一条客源**,撞单靠事后审批;三个"已存在"异常(390007/330103/330102)全部注释化,从报错退化为静默合并且不可配置【方林嫌疑,与客源域 rules §待确认联动】;
- customer 侧 NPE 地雷:CustomerSourceServiceImpl:272 在 housePO 判空前调 getHouseNumber——桥接走客源路径且不带房产即 NPE,非 FLException 回滚整个进线事务。

## 4. 官网案例库(E 族,对客展示 CMS)

- **实体关系**:`ecity(城市字典:拼音/经纬度)` ←cityId─ `ecase(案例:城市/楼盘/风格/户型/面积/工期/封面)` ──< `epicture(图片:裸long cid 无级联)`;`eperson(人物库:type 0设计师/1监理,拼音检索)` 被 ecase.duid/suid JPA 引用(族内唯一关联);`ecustomer(官网报名线索:姓名/电话/楼盘/面积)`;`eopinions(好评素材:type 0朋友圈/1锦旗/2其他,ecid/esid 悬空列)`;
- **案例上下架 = 布尔开关非审核流**:status 1 上架/0 下架(与 common.status 反逻辑),单字段翻转,无审批【行业共性:官网内容轻量管理】;
- **type 语义**:0 效果图/1 施工图;**cycle(施工工期天)仅施工图有,效果图锁 0**;图片画廊后台硬编码只做效果图(type=0),施工图图片无 UI 入口(半成品);
- **C 端查询形态(行业规则可继承)**:官网按 (cityId, status, type) 三等值 + **硬编码每城市每类型最多露 10 条**;后台筛选白名单=类型/状态/设计师/监理/风格 like/户型 like/面积区间;【✅V1 偏差回写:后台不限条数,“露10条”随官网开放端(RBAC v2 后)V2 再议】
- **报名(ecustomer)**:验证码 AES 解密比对(可选,不传即跳过;条件表达式写错成恒真式)→ 落库(**手机号明文存储+明文进钉钉消息,违加密红线**)→ 推钉钉群(群 key 读配置);
- **地理定位半残**:queryEcityByXandY ±0.5 度经纬度围栏找城市,查不到**硬编码回退"沈阳"**(【方林嫌疑实锤】),且前端绑定的端点在 Action 无映射(调用即 404);
- **PK 用错**:eopinions 按 ecid 当主键 update/delete(真 PK 是 eoid)——落空或错删;全族零查重(城市/人员/案例可重名);删案例/名片不级联图片(孤儿文件记录);
- 拼音字段由 PinYin4Util 落库时自动生成(检索友好,思想保留);【✅V1 偏差回写:拼音自动生成已砍(无拼音库依赖),pinyin 作为可编辑字段传入,检索走 name like】图片二进制走 common 上传模块,market 只存 URL。

## 5. 微信名片族(card/cuser/cardPhoto/cnotice,依赖外部 ISV)

- **定位**:销售员电子名片(微官网):后台制作名片(姓名/电话/微信/行业/职业/地址/简介/头像/相册)绑微信用户;**前台在外部 ISV 白标平台**(unionId 对接,代码不在本仓库)——访客浏览后留资落 cnotice;
- **cnotice = 留资/转介绍通知**(非系统公告):suid 发起者(访客)/ruid 接收者(名片主人)/姓名电话留言,status 0 未读→1 已读(无校验任意写);
- **留资推送**:addCnotice → 读 systemConfig `isv` 当 URL 基址 → **事务内同步 httpGet 把姓名电话明文拼 GET query 外发**(PII 泄漏+未 URL 编码中文名截断+慢响应拖长事务,三重违例);
- **`isv` 配置键两义**:后端当 URL 基址,前端配置目录登记为"白标开关 int"——同键两义,营造必须拆 `isv.enabled`/`isv.notifyUrl`;
- card.cuser nullable 但 pageCard 无条件解引用(未绑卡整页 500);"一 unionId 一卡"规则未实现(queryCardByCuid 静默取第一条);cuser upsert 先查后插无唯一索引;
- **全族零 FLException**:throws 死签名+记录不存在一律静默 return+无操作日志——名片族是安全与工程债务孤岛(对比获客链的新范式);
- 【实现定案 2026-10-29,第 100 次(R6)】**电子名片精简版落地:V1002 四表重做**(market_card 一人一卡 uk(org,user)/market_card_photo 级联/market_card_visitor uk(org,union_id)/market_card_notice 挂卡单义化——suid/ruid 双向语义消亡,notice 直接挂 card_id;PRD addon-market-card.md 五项开放问题全按推荐):手机 AES+hash 双列全域红线(CryptoBox 复用,零新机制);留资链路修老三重违例=**同事务发 CardNoticeCreatedEvent(零网络出事务)+CardNoticeConsumer 经通知中心站内信给名片主人**(事件只携 id,姓名电话零出域);同人同卡重复留资**幂等回显**(phone_hash 锚 duplicate=true 同 noticeId,对齐获客链惯例);频次防刷=同 hash 60s 内 1 条+日 10 条查询期计算零新表(修老无任何闸);已读守卫=名片主人(修老任意写);分享 share_token 重置换串不换卡 id(旧链接失效);C 端匿名端点 /api/c/cards/{token}(内核 AuthWebConfig 白名单+/api/c/cards prefix require:anonymous——path variable 须 prefix,exact 是全 URI 等值);匿名 org 上下文=yingzao.market.card.org-id 配置(portal anonymousOrgId 同款 V1 临时口径);管理端 /api/market/cards 菜单闸+emp_all 授权走「GET 角色回读→PUT /api/roles/{id}/menus 全量替换」(自动逐出 grants 缓存,第 96 次先例);isv 配置键两义拆 enabled/notifyUrl 两键 V1 不启用(扩展点);**web-mp 匿名名片页=R7(huashu-design 三方向初稿先行)**。

## 6. 金额/数量口径

- campaign.budget = **BigDecimal 原值元,无任何缩放**(与款项域 1e3 全库厘形成反差)——营销域无金额流水,budget 仅记录不管控;营造统一金额制式时随全局定案;
- 面积 area = float 原值㎡;计数口径见 §2(有效进线=success);
- 无金额计算链,营销域是六域中唯一"轻金额"域。

## 7. 跨模块写表面与依赖全景(营造事件化依据)

| 方向 | 对象 | 读/写 | 位置 |
|---|---|---|---|
| → customer | addCustomerChance / addCustomerSource | **写**(承接) | MarketCustomerBridgeServiceImpl:35,53(REQUIRES_NEW) |
| → system | systemConfig `isv`/`MESSAGE_DING_GROUP_ECUSTOMER` | 读 | CnoticeServiceImpl:76 / EcustomerServiceImpl:88 |
| → dingding | 群机器人播报(报名线索)/ HttpHelper(ISV 推送) | 外呼 | EcustomerServiceImpl:91 / CnoticeServiceImpl:80 |
| ← 全仓 | 零反向依赖(market 是叶子,仅 MarketInboundAction 一个外部调用方) | — | grep 全仓验证 |

【营造映射】承接直调改**领域事件+回调契约**:market 发 InboundAcceptedEvent(带承接参数载荷,addons-api 契约),addon-customer 监听走自己的建档/派发链,完成后调 market 暴露的回写契约(或经事件回程)确认承接结果——消灭 REQUIRES_NEW 孤儿事务;钉钉/ISV 外呼全部移出事务,改内核通知中心+异步。

## 8. 缺陷清单(提取实锤,营造红线来源)

1. **事务边界孤儿**(§3)——REQUIRES_NEW 先提交后外层回滚,数据半落;
2. **承接失败静默**:catch FLException 转 failed 不上抛(登记永远"成功")、accepted=false 滞留 pending 无告警;
3. **幂等三退化**:phone 空掺 UUID/先查后插无锁/幂等命中返回半截数据;
4. **撞单策略注释化静默合并**(390007/330103/330102),不可配置;
5. customer 侧 housePO 判空序 NPE(回滚整个进线);
6. **PII 明文**:报名手机明文落库+明文进钉钉/ISV URL(GET 未编码);对比同模块 MarketInbound 已加密——同模块两代范式并存;
7. **事务内外呼**:ISV httpGet/钉钉群播报在 @Transactional 内;
8. `isv` 键两义;createTime 客户端可控(不传 NPE、可伪造);
9. **PK 用错**(eopinions 按 ecid 当 eoid)/死端点(queryEcityByXandY 前端绑定 Action 无映射)/Action 名说谎(queryEcase 实为 page)——三种"接口名与行为不符"形态;
10. 级联缺失:删案例/名片留孤儿图片记录;cardPhoto.caid 裸 long 无属主校验;
11. 死状态族:Campaign WARM/FINISH、Inbound IGNORE、Attribution ORDER、source.profileId 悬空;
12. retry 无权限校验(390008 预留未实现);状态翻转/删除零日志;
13. 全族静默 return(名片族零 FLException)与"查重静默合并"两种静默并存——失败不可见是获客域最大共性缺陷。

## 9. 方林嫌疑机制级汇总(去留需业务拍板)

| 条目 | 判定 |
|---|---|
| 四段式获客链(进线→承接→客源/机会→归因漏斗) | **行业共性,V1 核心骨架** |
| Campaign×Channel→Source 投放物+UTM 三件套+归因三键冗余 | 行业共性保留 |
| 幂等键(密文 hash+campaign+source)与手机密文存储分离 | 行业共性,补 DB 约束 |
| EAV 动态表单承接落地页不定字段 | 行业共性保留 |
| 案例展示五件套(城市-案例-人物-图片-报名)+每城每类露10条 | 行业共性(官网获客标准模型) |
| company 全表分叉(每表每查每校验) | 公司特性 → 组织维度收内核 |
| 撞单"同录单人静默合并"硬编码 | 公司策略 → 可配置(与客源域确认联动) |
| 地理定位回退硬编码"沈阳" | 公司特性 → 默认运营城市配置化 |
| 报名/留资推钉钉群播报 | 公司基础设施 → 通知中心+线索池 |
| 名片前台依赖外部 ISV 白标平台 | 公司合作方式 → V2 待定,凭证在全局清单 |
| 承接协议 = Java VO 内嵌(跨模块 VO 当协议) | 公司习惯 → addons-api 显式契约 |
| 状态枚举半成品(4 死状态+无恢复接口) | 设计未完成 → 状态机闭环化 |

## 10. 营造 addon-market 落地映射建议

| 老概念 | 营造归属 | 版本建议 |
|---|---|---|
| Campaign/Channel/Source 投放物(UTM/渠道码/二维码) | addon-market 配置域 | V1 |
| 进线(幂等键+EAV item+手机加密)+状态机闭环(补 ignore 语义/恢复接口) | addon-market 核心 | V1 |
| 承接 = InboundAcceptedEvent + customer 回写契约(废 REQUIRES_NEW 直调) | addons-api 契约 | V1 架构红线 |
| 归因事件流水(四级漏斗,order 级待拍板) | addon-market | V1 |
| 漏斗统计(按活动/渠道,有效进线口径) | addon-market 查询 | V1 |
| 案例库五件套(城市/案例/人物/图片/报名)+上下架 | addon-market(或独立 addon-showcase,待拍板) | V1.5/V2 |
| 名片族(unionId 唯一约束/PII 加密/isv 拆键/级联修复) | addon-market V2(依赖微信凭证) | V2 |
| 钉钉/ISV 外呼 | 内核通知中心+异步任务 | 落地即改 |
| 错误码段 | **280000 建议**(老 39xxxx 不映射) | 待定 |

## 11. 待业务方确认清单

1. **addon 边界**:案例库+名片留在 addon-market,还是案例库独立 addon-showcase、名片归会员/客户域?
2. **承接协议**:进线→客源走"领域事件+回写契约"(推荐,消灭跨插件直调)还是 addons-api 同步 API?承接失败补偿语义(孤儿预防)确认;
3. **撞单策略**:全局查重+可配置合并策略(与客源域 5 条待确认联动),还是保留"同录单人合并"默认?
4. **成交归因(order 级)**:老代码未实现,V1 是否只要 进线→客源→机会 三级漏斗?
5. **案例库优先级**:V1 同批做(纯内容零外部依赖)还是延后到获客链稳定后?
6. **名片族**:确认 V2+依赖微信/ISV 凭证;若做,cuser-card 一对一、unionId 唯一约束范式确认;
7. **地理定位**:±0.5 度围栏模型保留?默认运营城市配置化(去"沈阳"硬编码)确认;
8. **进线手机号范式**:密文存储+MD5(建议升级带盐)hash 查重+幂等键,行业范式确认(应无争议)。
