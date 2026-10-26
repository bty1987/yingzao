# PRD - addon-service 售后三件套(投诉/回访/保修)

> 状态:**已确认(2026-10-26,用户拍板「按推荐」——5 项开放问题全按推荐口径)**;第 89 次运行写码兑现(V1801/错误码 380001-380008)
> 实现勘误:①联动锚事件为 ConstructionAcceptedEvent(验收竣工,实际存在的事件;PRD 初稿误写 ConstructionFinishedEvent);②退单(terminate)联动归 V2;③新增 380008 配置非法
> 老系统参考:construction 域 CustomerServiceComplaint/BackVisit/Guarantee 三件套(裸 CRUD,无真实状态机,docs/rules/construction.md §9)+ fl-yy 退单回访体系(yy_return_visit_task/_result/_reason,docs/07 §2.5)+ decoration 域 DecorationBackVisit(通知型)
> 建议码段:**380000**(target 370000 之后顺延)/ 迁移段:**1800+**(V1801)
> 表前缀:service_(军规 3)

## 业务闭环(一句话)

客服/售后人员对已交付装修单做**投诉处理、定期回访、保修登记**三类售后动作,经「任务化流转 + 处理留痕 + 保修期到期提示」,最终形成可分析的售后闭环台账(满意度/时效/超期率),数据终态落在 service_complaint / service_visit_task / service_guarantee 三表。

## 双参照合并建模(本 PRD 的核心决策)

| 维度 | R 本体(construction 域) | fl-yy 退单回访体系 | **营造采纳** |
|---|---|---|---|
| 投诉 | status 0/1 两态,无回访联动 | yy_order_feedback 推送链 | 状态机扩展+**处理完可选生成回访任务** |
| 回访 | 无状态机的表单孤行 | **任务/结果/原因字典三表+草稿→提交→无法完成闭环** | fl-yy 闭环为主体,**场景化挂 biz**(装修回访/退单回访/投诉回访/保修回访) |
| 保修 | 单表登记,String 自由日期,零计算 | 无对应 | **全新定义保修期规则**(见下) |

合并判断:R 本体的回访只是"记一笔",fl-yy 的任务闭环才是可运营形态——**以 fl-yy 语义为骨架,R 本体的装修单回访场景作为 biz_type 之一挂入**,不建两套回访。

## 用户与场景

| 角色 | 场景 | 高频动作 |
|---|---|---|
| 客服 | 客户来电投诉 → 登记投诉单 → 处理 → 处理完生成回访任务 | 登记投诉/填处理意见/标记已处理 |
| 客服 | 定期回访(竣工验收后 N 天)→ 领任务 → 电话回访 → 记结果 | 领任务/保存草稿/提交完成/标记无法完成(选原因) |
| 客服 | 保修来电 → 查保修单是否在保 → 登记保修记录 | 查保修期/登记保修 |
| 店长/管理员 | 售后台账查看、超期投诉/超期回访催办 | 过滤查看/统计卡 |

## 核心流程(状态机文字版)

### 投诉 service_complaint

1. 登记(挂装修单,经契约校验)→ status=0 待处理;type/level 走 yml 字典单源(老前端字典不迁);
2. 开始处理(可选)→ status=1 处理中(登记处理人+时间);
3. 标记已处理(处理结果必填)→ status=2 已处理;**组织级开关「投诉回访联动」开→自动生成回访任务(biz=投诉)**;
4. 超期标记:登记时可选承诺时限,超时未处理列表置超期徽章(纯查询期计算,不落状态列)。

### 回访 service_visit_task(+result 并入主表,V1 不拆)

1. 任务来源三路:手工登记/投诉处理联动(上)/**竣工验收联动**(组织级开关+天数配置,由 construction 完工事件驱动——addon-construction 已发事件,零反向依赖);
2. 状态机:**0 待回访 → 1 已完成 / 2 无法完成(原因必填,reason 字典组织级 yml)**;草稿=内容可反复保存不改状态(fl-yy 草稿语义,不单设状态列);
3. 结果字段:满意度(rating 1-5)/回访内容/回访时间/回访人;
4. 退单回访(biz=退单)随装修终止事件生成(可选开关)。

### 保修 service_guarantee(老系统零规则,全新定义——rules/construction §15-6)

1. **保修期起点 = 竣工验收完成日**(construction 工程线完工,经契约取;手工登记可改——补录场景);
2. **保修时长 = 组织级配置**(默认 24 个月;V1 单一时长,按项目类型差异化归 V2);
3. 到期日 = 起点+时长(查询期计算不落列);列表「在保/即将到期(30 天内)/已过保」徽章;
4. 保修登记 = 来电/上门记录(append-only,挂装修单+保修单);
5. 到期提醒:V1 查询期徽章;定时推送归 Plan 内核(V2,不占本域)。

## 闭环校验

- 闭环起点:投诉登记/回访任务生成/保修登记(均挂装修单,经 DecorationApi 只读契约);
- 闭环终点:投诉已处理(+回访完成)/回访任务完成或无法完成(原因在案)/保修过保或结单;
- 异常路径:回访无法完成必须选原因(数据可分析);投诉重开(reopen)→ V1 不做(老系统无,行业少见于系统化),标记已处理后如需重开走新投诉单;
- 对账红线:回访任务完成后不可改结果(append 修正走备注);保修单 append-only。

## 表设计(三表,迁移 V1801)

```
service_complaint:org_id/order_id(装修单)/order_no/customer_name 快照/type/level(字典)/
                  content/handle_result/handler_id/status(0/1/2)/promise_time(承诺时限,null=无)/
                  created_by/created_at + idx(org,status)/idx(org,order)
service_visit_task:org_id/order_id/order_no/customer_name/biz_type(manual/complaint/acceptance/
                  terminate)/biz_id/status(0/1/2)/reason(无法完成原因,字典值)/rating(1-5,null=
                  未评)/content/visitor_id/visit_time/draft_content/plan_time(计划回访日,逾期判定)
                  /created_by/created_at + idx(org,status)/idx(org,order)
service_guarantee:org_id/order_id/order_no/customer_name/start_time(保修起)/months(时长,月)/
                  status(0 在保登记有效/1 作废,append-only 只插不改)/remark/created_by/created_at
                  + uk(org,order)(一单一保,V1)
```

- 错误码段 380000:380001 参数/380002 不存在/380003 状态不可操作/380004 原因必填/380005 满意度非法/380006 装修单不存在(契约)/380007 保修已登记(uk 幂等提示)/380008 配置非法;
- 字典单源:complaintType/complaintLevel/visitBizType/visitReason/visitStatus/complaintStatus 全走 addon.yml dicts;
- 事件:ComplaintHandledEvent/VisitFinishedEvent(报表/通知消费方 V2 接);监听 ConstructionFinishedEvent(验收联动开关)。

## 非目标(本期不做)

- 老系统 String 日期/operaterId+Name 字符串冗余/无守卫 CRUD——全部废弃重写,不迁;
- DecorationBackVisit(decoration 域通知型回访,receiverIds+content)——归 addon-decoration V3,与本域任务化回访不同形态;
- yy_order_feedback(_daily/_log/_push_config)报表推送链——归经营看板专题;
- 回访满意度分析看板/投诉时效统计——归报表插件(本域只留 rating 与时间戳原料);
- 保修到期定时推送(Plan 内核 V2)/按项目类型差异化保修时长(V2)/投诉 reopen;
- 微信/短信触达客户——随渠道适配(C 批凭证)。

## 验收标准(冒烟 ≥20 步)

1. 投诉:登记(契约校验装修单 380006)/字典校验/处理流转 0→1→2/处理结果必填/超期徽章(承诺时限)/联动生成回访任务(开关开);
2. 回访:任务三来源(手工/投诉联动/完工事件联动)/草稿保存不改状态/提交完成(rating 校验 380005)/无法完成原因必填(380004)/完成不可改;
3. 保修:登记(uk 幂等 380007)/起点手工可改/在保与过保徽章判定/append-only;
4. 边界:无装修单契约拒绝;组织间数据隔离(org_id 全链过滤);
5. RBAC:端点归 /api/service prefix 菜单闸,零新增 require 类型。

## 开放问题(需业务方拍板——拍板前不写码)

1. **回访场景三来源是否齐全**:验收完工联动/投诉处理联动/退单(终止)联动之外,是否还有"签单后 N 天"等时点?(推荐:V1 三路,时点开关组织级配)
2. **投诉 type/level 枚举值**:行业中性初始集建议 type=质量/服务/设计/其他,level=一般/紧急——是否认可?(yml 可改,不影响结构)
3. **保修默认时长 24 个月**是否符合行业惯例?(组织级可配,只影响默认值)
4. **无法完成原因字典初始集**:客户不在家/电话未接/拒绝回访/其他——是否认可?
5. **addon-service 独立插件 vs 并入 addon-construction**:独立(推荐,售后域横跨装修/施工/客户,避免 construction 膨胀)还是并入?码段 380000/迁移 1800+ 是否认可?
