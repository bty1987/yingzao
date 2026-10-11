# information 域 L2 规则清单(通知件 + 出站事件队列 + 调度平台 + 活动抽奖,全域覆盖收官域)

> 提取自 Rhadamanthus 老系统 2026-10-12(第 16 次夜跑)。范围 = `com.fanglin.information`(144 文件/13,973 行/21 ServiceImpl/24 PO,catalog `information`)。三路子代理并行深读 + 生产只读库 24 表全量复核。**过堂表两处预判被推翻:①Plan/Worksheet 不是"删除节"而是活的生产设施;②Activity/Award 不是"营销噱头"而是积分商城心脏——本域最终裁决为拆分归并,不建 addon-information(§16/§17)。**

## 0. 模块定性

1. **information = 五个互不相通的机制拼盘**:站内信(Message)+出站事件队列(MessageQueuing/Cueuing/Dueuing)+公告板(Notice)+个人日历(Events)+调度平台(Plan)+内部工单(Worksheet)+电话回拨(PhoneCall)+活动主档与礼品域(Activity/Award)+外部调用审计(Assassin)+会销工具(V 系六表)。彼此零代码交集,必须逐族裁决。
2. **生产活性矩阵(2026-10-11 只读库复核,14 表 COUNT+MAX(createtime))**:
   - **秒级活跃**:message 877.1 万(最后 2026-10-11 20:20)/messagequeuing 86.9 万(20:20)/messagecueuing 32.2 万(20:20)/plan 46.6 万(19:07)/events 41.2 万(20:10)/awardrecord 25.4 万(18:19)/awardpurchase 1.12 万(16:52)/activity 3509(10-10);
   - **低频在用**:notice 2199(10-09)/worksheet 234(10-05);
   - **死亡**:vpayment 1.94 万(**止于 2022-09-12,会销停办**)/vticket 14.9 万(止于 2022-09-05)/**phonecall 仅 17 行(止于 2019-06-16)**/**assassin 936 行(止于 2018-03-11,断链 8 年)**;messagedueuing 0 行。
   - **克隆版裁决实锤:payment.activitypayment = 0 行从未投产**,V 系才是现役实现(修 sales.md §13 存疑)。
3. **两条出站队列的判决性数据**:messagequeuing status 分布=1 已处理 48.4 万/**2 失败 38.5 万(44% 事件失败无重试=近半丢失)**;**messagecueuing 32.2 万条全部 status=0 从未被消费——只写不读的假队列**(写入方约 20 个场景以为在通知外部系统,实际无人消费;2026-08 修复的 30188 条 args 缺引号坏数据修了也白修)。
4. 模块内另有 communion.upgrade(升级链,属 communion 模块)与 information.task(54 行,**历史残留**;现役 task 在 communion 库 208 行,TaskPO `@Table(catalog="communion")` 正确)。

## 1. Message 站内信(information.message,877 万行,绝对主通道)

- 表结构(`MessagePO.java:47-71`):receiver/sender(→user)、type/subtype/ident(业务定位三元组)、contents、status(0 未读/1 已读/**2 未下发=死枚举全仓零使用**)、company、dresult(钉钉下发结果,**死列**——钉钉返回 errcode 被丢弃从不回写,`MessageServiceImpl.java:100,109,137` 无赋值);args 死列(历史审批 serial,`MessageDaoImpl.java:71-74` 清理方法零调用)。
- **type 枚举 7 值=全系统站内通知场景字典**(`MessagePO.java:31-43`):0 收款完成提醒/1 审批流通知(13 个 Approval*ServiceImpl 共约 40 处)/2 死枚举/3 施工族(放线失败/进度/辅料到货)/4 人事·报价·签约(改密码下发新密码 `UserServiceImpl.java:1000`)/5 辅料回访(配 subtype=1+ident=aid)/6 客源分配提醒。
- 写入模式:外部模块调 `MessageService.sendMessage` 三个重载(`:96-138`),save 后**同事务内**同步推钉钉 OA 消息(缺陷见 §14-D1);读取=本人按 company+userId 分页。
- 营造映射:**主体即 yz_notification**(receiver→user、type→source_type、ident→source_id、status→read_flag 映射现成);钉钉/微信推送剥离为可插拔投递器,禁进内核。

## 2. MessageQueuing / MessageCueuing / MessageDueuing 出站事件队列(营造事件总线的反面教材)

- 三表结构完全相同(orderId/operation/args/status/createTime/finishTime,`sqls/information/10_create_information_messagequeuing.sql:7-17`),共用一个 DAO 写入;**queue/cue 之别在代码层零语义**,仅消费端不同:Queuing 由仓内 Python 消费器处理(`scripts/crond_message_queue_processor.py`,每分钟扫 status=0 LIMIT 100,sha256 签名 POST 到 systemconfig `PM_URL_STR` 指向的 PM 系统),**Cueuing 消费方不存在**(32.2 万全 status=0 实锤),Dueuing 纯预留零写入。
- **33 种 event 全是业务域事件**(非通知):装修单建/改/状态(DECORATION_CREATED/CUSTOMER_STATUS_CHANGED…)、合同签/改签/预签(CUSTOMER_SIGNED,双写两表 `DecorationContractServiceImpl.java:652-654,707-709,793-795`)、收款(PAYMENT_COLLECTED,仅 Cueuing)、客源分配四级(CUSTOMER_CHANCE_AUTO/REGION/DEPT/USER_ASSIGNED)、客户/楼盘建档(CUSTOMER_CREATED/ESTATE_CREATED)、施工节点与开工完工(PROJECT_STATUS_CHANGED/NODE_CREATED)、归属转移(TEAM_DESIGNER/SALESMAN_CHANGED)、放线(SNAPLINE_DONE/DRAWLINE_FINISHED)、活动核销(ACTIVITY_ITEM_VERIFIED)、转介绍(REFER_ADDED/DELETED)等,全清单见消费场景表(约 50 写入点)。
- 写入模式:各业务 Service **直接 DAO save + 字符串手拼 JSON**(无 service 封装,`args` 固定含 `"event"` 键)——历史缺引号事故 30188 条(`MESSAGECUEUING_ARGS_QUOTE_FIX.sql`)即此模式的必然产物;归属审批甚至对已拼好的 args 做 `String.replace` 复制出第二条消息(`ApprovalDecorationBelongServiceImpl.java:344-347`)。
- 缺陷四件套:Java 状态枚举只有 0/1 而 Python 消费器写 2=失败(读侧无常量,状态值漂移)/失败无重试无死信(38.5 万条)/LIMIT 100 无行锁多实例双消费/DAO 更新方法硬编码表名(`MessageQueuingDaoImpl.java:46-50`,Cueuing 记录无法用同 DAO 置完成)。
- 营造映射:**删三表**;33 种 event 按域还给各插件做领域事件(与 RFC"插件消费事件→调内核通知 API"分层定案一致),外部系统同步=独立外发适配器插件(订阅事件→签名 POST),必须带消费确认+重试+死信三件套。

## 3. Notice / NoticeDepartment 公告板(2199 行,外部零引用确认独立)

- NoticePO(`:95-231`):theme/type(1 通知/2 活动)/priority(0 普通/1 重要/2 紧急)/**startTime/endTime/publishTime 全是 String 型日期**(字典序比较过滤,`NoticeDaoImpl.java:58`)/isPublic(0 全员/1 部门)/exPublic(过期后公开或隐藏)/**depts 部门串冗余列**(与 noticedepartment 桥表双源,先删后插+回写字符串非原子易漂移,`NoticeDepartmentServiceImpl.java:81-92`)/附件/company;另有全仓未用的中文状态常量 `"有效"/"失效"`(`:33-35`)。
- 可见性=桥表白名单 ∪ 全员公开(`NoticeDaoImpl.java:45-58`);无状态列,失效只靠时间窗。
- 营造映射:公告=广播+部门可见性+有效期+优先级+附件,超出内核通知中心"点对点收发"——建议作通知中心之上的插件(归属待确认 §17);dept 串改关联表、String 日期改 DATE、company→org_id。

## 4. Events 个人日历(41.2 万行,设计师节点提醒)

- EventsPO:orderId/title/content/**holder(归属人)**/department/region 冗余/type(1 系统生成/2 用户自建)/atttype(1=施工图关联)/**showDate int yyyyMMdd**/isSend 死列;**无 company 列**(多租户漏配,靠 holder 隔离)。
- 双写源:①施工域录入放线/领钥匙/设计完成时间时给**设计师**挂提前 10 天(设计完成 5 天)倒计时提醒(`ConstructionInfoServiceImpl.java:171-234`);②用户自建(每天每人上限 5 条,check-then-act 竞态 `EventsServiceImpl.java:79-93`;删除守卫:系统事件或非创建人禁删)。
- 营造映射:系统提醒还原为施工插件订阅节点日期变更后自行生成通知;用户自建日程去留待确认;showDate int 格式不进任何新表。

## 5. Plan / PlanParam / communion.task 调度平台(46.6 万行,🔴 过堂表预判错误,活设施必迁)

- **定性:生产级异步导出/导入任务调度平台**(非个人日程、非营销计划)。模型=**Plan("在 executeTime 执行某脚本,带 args")**:pid/tid→communion.task(脚本注册表:name/filepath/type 0=python 1=shell/keyWord/server/周日期时间)/args(参数串)/executeTime(**varchar yyMMddhhmm**)/resultFilepath/resultFileSize/schedule/status/company。
- 状态机(`PlanPO.java:32-40`):0 初始→(外部 Python 调度器按 executeTime 执行)→2 成功/3 失败;0→1 取消(仅创建人);4 立即。生产分布:2 成功 41.2 万/3 失败 4.66 万(**失败率约 10%**)/1 取消 5862/4 立即 922/0 初始仅 1——队列不积压,调度健康。
- **真正的消费全在前端 HTTP,Java 侧零外部引用**:14 个前端功能点调 createPlan.do——收据导出(`receipt.js:108`)、预签报价导入(`presignPrice.js:263`)、data 模块 8 个主数据导出(aoods/aoodsCharge/goodsPool/goods/normrice/normtime/normood/soods)、计划页自身;完成通知走钉钉(`PlanServiceImpl.java:311-316`,2025-12-28 刚从 MessageService 迁 DingTalkMessageService)。
- PlanParam(35 行)=一次性的导出字段/条件模板(fields/conditions,由 plan2create.html 向导构造);**PO 主键映射 bug 实锤:`@Column(name="tpid")` vs 生产列 `ppid`**(`PlanParamPO.java:33`),凡走该映射的查询必炸——35 行存量即功能已死的墓碑。
- 活跃度:2018-07 出生,**2025-12-28 仍在迭代**,持续 7 年。
- 营造映射:**必迁**。归属两选:内核调度设施(定时任务/导出队列属平台能力)或 addon-system;task 脚本注册表+keyWord 契约+plan/planparam 两表+6 接口(pagePlan/existPlan/createPlan/cancelPlan/updatePlanForAdvance+回写);迁移时裁掉必崩死接口 updatePlan(`:245` `Integer.parseInt(yyMMddhhmm)` 自 2022 年起溢出)、修 userPO/planPO 复制粘贴笔误(`:219` 拿用户状态比计划常量)、executeTime 换标准时间戳、updatePlanForAdvance 补鉴权(现无来源校验且 status/resultFilepath 全可覆写,`:292-319`)。

## 6. Worksheet 内部工单(234 行,2024-05 刚改版,过堂表预判错误)

- WorksheetPO:code(单号,**addWorksheet 从不生成,恒 null 摆设**)/module(标题)/content/path(附件,2024-05 新增)/cuid 创建人/huid 处理人/status/result。
- 状态机(`po:22-28`):0 初始→(handle 填处理人+结果)→2 已处理→(finish)→3 完成;0→1 取消。守卫仅校验前置状态,**不校验操作人身份**(任何登录用户可处理/完成/取消他人工单)。
- 消费:首页"工单"tab(`home-antv.js:317-321`)+处理视图;**company 形参三层签名接收但 DAO 完全不用且表无 company 列=全公司共享池**(半截子多公司改造);updateWorksheet.do 死端点(前端零调用)。
- 营造映射:保留迁移(addon-system 内部工单或并入通知中心插件族);补 code 生成+处理权限+org 归属三件;死端点裁掉。

## 7. PhoneCall / PhoneCallRecord 电话回拨(17 行,2019 死亡,确认删除)

- **定性修正:不是"外呼电话营销任务派发",是 ISV 话务平台的点击回拨+录音审计**:phoneCall.do 落库→HTTP GET ISV 回拨双方→ISV 异步回调 updatePhoneCallForInfo(起止/挂断原因/时长)+updatePhoneCallForRecord(录音 URL)回填;PhoneCallRecord 与 PhoneCall **1:1**(按 pcid 查单条);stopReason 16 值=ISV 协议码非业务状态机。
- 与客户/装修单**零关联**(orderId 死列全仓无写入,靠 caller/callee 裸号码);前端仅只读查询列表,无外呼 UI。
- 死亡证据:生产 17 行止于 2019-06-16;代码最后业务修改 2020-09/2022-10(格式化);缺陷=同 caller+callee 拨通中守卫 TOCTOU 竞态+**ISV 回调丢失即该号码对永久无法再拨**(stopReason=0 无超时清理)。
- 营造裁决:**整族删除**(8 个 Java 文件+2 前端页),数据归档;若未来需要拨号录音,在 addon-customer 客源跟进里按新架构重做(ISV 对接独立 adapter)。

## 8. Activity / ActivityItem 活动主档(3509 行,🔴 过堂表预判错误,积分商城心脏)

- **ActivityPO = 全系统活动主档**,被 sales/payment/decoration/material/customer/approval 六域消费(11 处 import + 20+ SQL join):
  - 三域订单活动 FK 目标:建销售单取默认活动写 `sales.activityId`(`SalesServiceImpl.java:370-371`)/建主材单写 `material.activityId`(`MaterialServiceImpl.java:254-256`)/装修单绑活动 `DecorationActivityServiceImpl.java:110-137`(解绑时把该单余额款 aid 全刷 0);
  - **积分倍率与有效期唯一来源**:type 1=装修/2=主材/3=销售,multiple=每 1 元钱款得 multiple 积分,validays=收款后有效天数——customer.MemberDaoImpl 9 条积分 SQL 全部 join activity 过滤 `atttype=1`;
  - **积分商城活动主档实锤**:`MemberServiceImpl.java:615-683` addMemberActivity 批量创建 atttype=ONLINE 的装修/销售活动(与记忆"积分商城四处三模块无单一 owner"互证,sales.md §13 联动)。
- 状态机:status 0 有效⇄1 失效;defaults 默认(每 type+company 唯一,切换先原生 SQL 全表重置,`ActivityDaoImpl.java:58-64`;默认活动禁停用 160047);**startTime/endTime 无任何查询消费=时间窗形同虚设**;paytype/exchange 死字段只写不读。
- ActivityItem(3.6 万行)=早期"活动-赠品"配置表,**仅 information 内部读写**(三域已各自演化出 decorationActivityItem/salesActivityItem/material 侧挂单);对 asid=0 的券/红包行静默跳过=残缺功能;非法 asid 直接 NPE。
- 营造映射:**Activity 整表随积分商城剥离 addon-member**(type/atttype/multiple/validays/defaults 必迁,paytype/exchange/startTime/endTime 弃);ActivityItem 归档后删。

## 9. Award 三件套礼品域(流水 25.4 万行活跃,礼品兑换物资底账)

- AwardStock(1.2 万)=礼品主档:no 编号/num 库存/unitPrice/storeroom(1 装修/2 销售/3 主材仓库)/subtype 9 类/三开关 isPrefor(算业绩)/isStock(算库存)/isPayment(计金额)(0=算/1=不算)/status 0 可用 1 禁用。
- AwardRecord(25.4 万)=流水:type 0 入库/1 出库/2 盘盈/3 盘亏 × subtype 15 种业务动作 × onetype 归属域;totalPrice=Arith.mul(unitPrice,num)。
- AwardPurchase(1.12 万)=采购流:0 初始→1 采购→2 入库→3 结算/4 失效/5 完成;fapid 父单链分批入库,入库满额自动置完成并回加库存+记流水;下单推钉钉群(MESSAGE_DING_GROUP_AWARD_PURCHASE 配置)。
- **库存扣减方全景**:装修/销售/主材三域的 ActivityItem ServiceImpl 各自领用/配送/退回直改 AwardStock(9 处,全链 get→比较→setNum **check-then-act 竞态无锁可超卖**);积分商城按编号查**总部仓**(硬编码 TYPE_HEADQUARTER_INT,不扣量);审批域只同步三开关属性。
- 营造映射:**两表随 addon-member 走**(积分商城兑换的物资底账);三域 ActivityItem 的 asid 关联改 addon API;isPrefor/isStock/isPayment 三开关语义必须保留;AwardPurchase 随 Stock 走或先留(纯内部采购流,待确认)。

## 10. Assassin 外部调用审计(936 行,2018 断链)

- **定性:跨系统 HTTP 代理调用审计表**(与营销无关):`AssassinServiceImpl.proxy()` 从 systemconfig 取 IM 系统 URL/tokenkey/tokensecret→GET requestAuthCode 换 authentication→GET 目标 api→**成功才落一条记录**(失败只写日志,审计单向)。
- 外部唯一引用=客户更名/改电话审批同步 IM(api 硬编码 `updateCustomerNameAPhone.do`,注释"接口暂定",`ApprovalDecorationCustomerServiceImpl.java:119-123`)。
- **生产止于 2018-03-11=断链 8 年实锤**,与 customerprofile.md"updateCustomerNameAPhone.do 死 API 断链——客户改名审批流是断的"完全互证(审批走到同步步骤一直在静默失败或被吞)。
- 营造裁决:删除;缺陷两条入红线——远程 errorCode 直接当本地 FLException 码抛出(必与本地段冲突)/凭证 tokenkey/tokensecret 明文拼 GET URL;"外部调用留审计"思想归未来 infra 组件(成功失败都记)。

## 11. V 系会销六表(Vactivity/Vticket/Vpayment/Vreceipt/Vprize/Vaward,现役至 2022-09 后停办)

- **定性修正:不是"微信大转盘抽奖",是 2019 年线下营销会(厂购/家博会)全流程闭环**,无任何随机抽奖代码——Vaward 是活动现场人工登记发奖(status 恒 0 无核销闭环):建活动→从装修单生成发票 vticket(tno 唯一键,自带归属销售/部门/区域,可抢单 take/释放 free)→微信 H5 报名(EmemberServiceImpl 必须票号命中,回写姓名电话置 isregist)→到场 comeVticket(校验先报名+防重)→现场交款 vpayment(type 装修款/定金/主材款/定金,**票须已到场+票面金额与收据三分严格相等**,写 vreceipt 收据+paytran TYPE_ACTIVITY 流水)→发奖品 vaward(快照奖品名/价,num ×1000 存储)+战报大屏(report OtherAction 三个 count 端点)。
- **克隆版裁决(修 sales.md 存疑)**:payment.ActivityPaymentServiceImpl 与 VpaymentServiceImpl 逐行同构(同 160026/160033-35/160046 错误码),落 payment.activityPayment/activityReceipt——**生产 activitypayment 0 行从未投产,V 系才是现役**;V 系自身 2022-09 后停办(会销不再举办)。
- V 六表**全部无 company 列**(单公司运营期快速上线,多公司化后未回填);发奖无限量无防重(同票可反复登记任意数量不扣库存);已收款可重复收款无状态守卫;物理删除无守卫。
- 营造裁决:**整族删除**(6 表+6 PO+6 Service+6 Action+10 前端页+report 3 count 端点+克隆版两表两 Service);若未来恢复会销,归 addon-campaign 按活动营销插件重做(收款统一走 addon-payment,废独立 vreceipt 收据)。

## 12. 金额与数值口径

- vpayment.money **厘**(展示 `%.3f` div 1000);vaward.num **×1000**(展示 div 1000.0)——全域千元制惯例延续。
- **缩放口径分裂事故温床实锤(第三案,呼应 sales.md addOales 元当厘)**:同一 activity.multiple,装修积分 SQL 除以 1000(`MemberDaoImpl.java:22`),销售积分 SQL 在 Java 侧除以 1,000,000(`SalesActivityDaoImpl.java:58 vs :68`)——**两域积分天然差 1000 倍量纲**;addOales 的 award/profit/total/cost 经 copyProperties 原样落库无任何缩放(`OalesServiceImpl.java:157`),而展示口径 div 1000。
- long→int 截断:服务款交款生成 PaymentActivityPO 时 `(int) serviceMoney` 强转(`ServicePaymentServiceImpl.java:298`)。
- AwardRecord.totalPrice 用 Arith.mul(long,long) 无精度坑(正面教材)。

## 13. 错误码段全景

- information 主用 **16xxxx 段**(160014 日历上限/160016 日历禁删/160023 电话拨通中/160024 回调缺失/160025 回拨失败/160026/160033-35/160046/160047 默认活动禁停/160053 计划超时/160061 工单);漂移:Plan 同文双码(130164 与 160028 并存,2026-07 清漂移后仍残留);PhoneCallAction catch(Exception) 一律报 160023(NPE 也说"拨通中")。
- **跨段借用**:AssassinServiceImpl 把远程系统 errorCode 直接当本地码抛(段冲突);营造映射:通知/公告/调度/工单若进内核用 12xxxx,活动礼品归 addon-member 用其声明段。

## 14. 缺陷清单(三路合并精选 23 条)

- **D1 事务内同步发钉钉 HTTP**:每次 sendMessage 在 @Transactional 内调钉钉(HTTP POST+取 token),拖长事务占连接,失败不回滚不记录(`MessageServiceImpl.java:96-138`)——营造红线:投递器必须 AFTER_COMMIT。
- **D2 假队列**:messagecueuing 32.2 万全 status=0 零消费(§0-3)。
- **D3 事件半数丢失**:messagequeuing 44% 失败态无重试无死信。
- **D4 手拼 JSON 注入面**:40+ 处字符串拼 JSON 入 args,已产 30188 条坏数据;String.replace 复制消息(`ApprovalDecorationBelongServiceImpl.java:344-347`)。
- **D5 状态值漂移**:Java 只定义 0/1,Python 消费器写 2=失败,读侧无常量;DAO 更新硬编码表名。
- **D6 updatePlan 必崩死接口**:Integer.parseInt(yyMMddhhmm) 2022 年起溢出(`PlanServiceImpl.java:245`)。
- **D7 复制粘贴笔误**:`:219` userPO.getStatus() 比计划常量,计划状态校验缺失。
- **D8 updatePlanForAdvance 无鉴权可任意改写**:status/resultFilepath 全可覆写,双端点暴露(`PlanServiceImpl.java:292-319`/`CommonAction.java:105-111`)。
- **D9 PlanParamPO 主键映射 bug**:@Column(name="tpid") vs 生产列 ppid,凡走映射必炸(`PlanParamPO.java:33`)。
- **D10 12/24 小时制混用**:DateUtil.current("yyMMddhhmm") 的 hh 是 12 小时制,前端用 24h timepicker→午后建上午计划被误判超时(`PlanServiceImpl.java:135,231`)。
- **D11 礼品库存全链竞态**:三域 9 处扣减 check-then-act 无锁可超卖(§9)。
- **D12 V 系收款无状态守卫**:已收款可重复收款/已付款可物理删除/发奖无限量无防重(§11)。
- **D13 Assassin 远程码当本地码抛+凭证明文 GET+只记成功**(§10)。
- **D14 Events 自建日程 check-then-act**:count 后 save 无唯一约束,并发超 5 条上限(`EventsServiceImpl.java:79-93`)。
- **D15 updateMessageForRead 无属主校验**:按 mid 列表置读不校验 receiver(`MessageDaoImpl.java:63-68`)。
- **D16 sendMessage 无守卫**:receiver 查不到照样 set 非空列(`MessageServiceImpl.java:118,150`)。
- **D17 公告双源漂移**:noticedepartment 桥表与 notice.depts 逗号串并存,先删后插非原子;日期存 String 靠字典序。
- **D18 Worksheet 三假**:code 恒 null/company 形参不用/updateWorksheet 死端点;处理工单不校验操作人。
- **D19 PhoneCall 永久卡死**:拨通中守卫 TOCTOU+回调丢失无清理,号码对永久无法再拨;孤儿 phonecall 让列表整页 NPE。
- **D20 死件全家福**:messagedueuing 零写入/MessagePO status=2·type=2·group·args 死列/Events isSend 死列/Activity paytype·exchange 死字段/Notice 中文状态常量未用/updateApproveMsgSerial·deleteMessageByIdAStatusAUnType 死方法(后者引用不存在列 messageId 必坏 SQL)。
- **D21 活动时间窗形同虚设**:queryActivityForValid 不看 startTime/endTime(`ActivityDaoImpl.java:48-50`)。
- **D22 克隆代码漂移**:VpaymentServiceImpl 与 ActivityPaymentServiceImpl 双活双改(生产已裁决后者死代码)。
- **D23 硬编码**:PlanDaoImpl 裸 SQL 硬编码 catalog/PhoneCall 硬拼 "86" 国别码+ISV 协议进 PO 常量/钉钉 accessToken·agentId·FL-00083 写死/Python 消费器硬编码 root 密码/明文新密码走消息通道(`UserServiceImpl.java:995-1013`)。

## 15. 方林嫌疑机制级汇总

1. **company 分叉贯穿且不对称**:message/notice/activity/awardstock/plan 有 company 列并强制过滤,Events/V 六表/Worksheet 无列(三种形态并存:严格隔离/漏配/故意共享池);积分商城查礼品库存**硬编码总部仓**(MemberServiceImpl 718/814/934)——总部仓即全公司仓的单中心假设。
2. **公司号硬编码**:MaterialServiceImpl.java:248 `company==1` 时 C 单升 B 单(总部特定业务,呼应 material.md §12)。
3. **钉钉/微信双通道深度耦合**:站内信每次发送捆绑钉钉 OA 消息;V 系四处发微信服务通知;AwardPurchase 推钉钉群;离开钉钉微信生态通知链全残——营造投递器必须可插拔。
4. **出站事件最终消费方=方林自研 PM 系统**(PM_URL_STR 配置+签名协议+"orgName:沈阳方林"样例),orderId 方林短号体系——外部同步契约属公司特性,沉插件。
5. **operation=业务方法名**的方言约定( tranCustomerChance 等直接取方法名),event 用大写下划线英文,双轨命名系 2026 年对接外部系统补的方言。
6. 中文枚举样态:Notice"有效/失效"常量/PhoneCall 十四种中文挂断原因注释。

## 16. 营造落地映射(addon 拆分终表,替代 addon-information)

| 老族 | 裁决 | 去向 |
|---|---|---|
| Message 站内信 | **迁** | 内核通知中心 yz_notification(映射现成:type→source_type/ident→source_id/status→read_flag);钉钉/微信推送=可插拔投递器(AFTER_COMMIT,禁进内核) |
| MessageQueuing/Cueuing/Dueuing | **删三表** | 33 种 event 按域还给各插件做领域事件;外部系统同步=独立外发适配器插件(消费确认+重试+死信三件套,禁手拼 JSON) |
| Notice/NoticeDepartment | **迁(归属待确认)** | 通知中心之上的公告插件(广播+部门可见性+有效期+优先级+附件);dept 改关联表/String 日期改 DATE |
| Events | **拆** | 系统提醒还原为施工插件订阅节点日期变更自生成;用户自建日程去留待确认 |
| Plan/PlanParam/task | **迁(🔴改判,必迁)** | 内核调度设施或 addon-system:任务排期+脚本注册(keyWord 契约)+执行回调+完成通知;修 D6-D10 五缺陷;这是 plan_export_*.py 导出脚本全族的调度底座 |
| Worksheet | **迁(改判)** | addon-system 内部工单:补 code 生成+操作人权限+org 归属;裁死端点 |
| PhoneCall×2 | **删**(确认) | 数据归档;未来拨号录音在 addon-customer 重做 |
| Activity/ActivityItem | **Activity 迁 addon-member(🔴改判)/Item 删** | 积分商城心脏(multiple/validays/atttype),与 sales.md §13 积分商城剥离联动 |
| AwardStock/AwardRecord/AwardPurchase | **迁 addon-member(🔴改判)** | 礼品底账(三开关语义保留);三域 asid 关联改 addon API;扣减必须原子 SQL(修竞态) |
| Assassin | **删** | "外部调用留审计"思想归 infra 组件(成功失败都记) |
| V 六表+克隆版 | **删**(确认) | 会销 2022 停办+克隆版 0 行;未来恢复归 addon-campaign 重做 |

## 17. 待业务方确认清单(8 条)

1. **Plan 调度平台归属定案**(🔴过堂表"删除节"改判):内核调度设施(定时任务/导出导入队列属平台能力,推荐)vs addon-system vs 独立 addon-plan;task 脚本资产(python 导出脚本族)是否随迁。
2. **Activity/Award 随积分商城剥离 addon-member 定案**(🔴过堂表"营销噱头"改判):与 sales.md §13-16(积分商城归会员营销插件)联合拍板;AwardPurchase 随 Stock 走或先留。
3. **外部系统同步契约去留**:messagequeuing 对接的 PM 系统在营造中的对等物(若仍需向第三方推业务事件,外发适配器插件的订阅范围与签名协议);38.5 万失败事件与 32.2 万假队列积压的历史处理(归档即弃?)。
4. **Notice 公告板归属**:内核通知中心扩展广播能力 vs 独立公告插件(涉内核边界"通知中心只做点对点收发"是否松动)。
5. **Events 用户自建日程去留**:系统节点提醒已定还原施工插件;个人日历(自建日程)在营造 Web 端是否需要(需要则归哪个 addon)。
6. **Worksheet 共享池定案**:老系统全公司共享池(无 company 列)是否有意设计;营造按 org 隔离还是保留平台级共享工单池。
7. **删除族定案复核**:PhoneCall(17 行)/V 六表(2022 停办)/Assassin(2018 断链)/ActivityItem/messagedueuing 五族删除+数据归档策略(归档库/保留期限)。
8. **通知 type 字典定案**:老 7 值 type(收款/审批/施工/人事/回访/客源)在营造的 source_type 枚举映射(内核枚举 or 插件自声明字符串),涉Addon 事件 schema 契约。
