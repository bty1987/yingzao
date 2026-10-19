# L2 规则清单 - 施工域(addon-construction)

> 来源:老系统 `com.fanglin.construction`(192 文件/26 ServiceImpl,2026-10-11 全量提取)。
> 方法:亲读 service/impl + PO,全部结论带 文件:行号 回溯指针;方林嫌疑与缺陷单列。
> 对照:装修单主域见 `docs/rules/decoration.md`(合同线/工程线/设计线/确认线在装修侧,本篇是施工执行侧)。

## 0. 模块名词表(老系统命名混乱,先辨析)

| 老系统名 | 实际业务 | 状态 |
|---|---|---|
| ConstructionPO | 施工单(装修单的执行侧影子,放线时创建) | 主干 |
| ConstructionSchedulePO | 节点排期(放线后生成,施工期真正运转的表) | 主干 |
| ConstructionInfoPO | 施工信息(1:1 装修单的施工侧扩展) | 主干 |
| DrawLine | 放线(开工点火动作,申请→排期→成功) | 主干 |
| ConstructionPlan | 工程计划(图纸级,准备期蓝图,**与 Schedule 双轨无同步**) | 化石半迁移 |
| ConstructionProfile / DrawlineProfile | 户型/工程档案(模板-项-选项三层,**两族复制粘贴双胞胎**) | 可合并 |
| Acceptance | 验收(一次性完工登记,7 工种 21 项打分) | 主干 |
| Qualiter | 质检(**半成品:只有指派台账,无结果提交**) | 缺陷设计 |
| Rework / InspectionRecord | 返工/巡检(纯日志,无流程无联动) | 空壳 |
| MaterialPlan(+data.MaterialPlanNode) | 主材计划(两层模型,优秀设计) | 保留泛化 |
| ConstructionAccessory | 工程辅料限额台账(真业务在 inventory 出库侧) | 载体 |
| CustomerService{Complaint,BackVisit,Guarantee} | 售后三件套(裸 CRUD,无状态机) | 重设计 |
| Constructer | 施工人员(工人指派) | 主干 |
| ConstructionCost / ConstructionPeriod / ConstructionPace | 面积档限额/工期/节奏模板(**按公司分档,方林嫌疑**) | 沉配置 |

## 1. 放线 = "开工点火"(DrawLineServiceImpl,全模块最重的副作用)

状态机(`DrawLinePO.java:46-52`):`0 排队 → 1 已排期 → 2 成功 / 3 失败`。

- **申请**(`applayDrawLine:372-481`):同单活跃放线查重(120013,排除 status=3)→ 必须已有默认报价(200003)→ 建 status=0;**副作用巨大**:若无施工单则当场创建 ConstructionPO + 按面积×公司档初始化限额/工期(ConstructionCost/ConstructionPeriod)、初始化辅料限额台账、切换分公司(dompany)、按报价+增项全量重算定额 makeNormtion(:414-478)——申请放线实际是开工点火,拆散见 §13;
- **排期**(`resetDrawLine:485-515`):指派放线小组(dgid)+起止时间 → 1;时间冲突查重(120015)——**查重 SQL 硬编码 status=0(排队单无起止时间),真正该查的 status=1 反而不管,形同虚设;company 参数收下但 SQL 未用**(dao/impl/DrawLineDaoImpl.java:72-79,租户穿透);
- **成功**(`successDrawLine:519-619`):强校验必须 status=1(120017)→ status=2 + 生成节点排期 + 装修单 projectStatus 0→1(工程线开工);
- **失败**(`failureDrawLine:623-638`):**任意状态**可置 3,给设计师发站内信;
- **免审后门**(`finishDrawLine:644-777`):注释明言"不判断放线状态",缺记录直接造 status=2 的放线单——补录旁路;
- 排期生成逻辑(遍历节点算前后置/工期、upsert Schedule、回写施工单工期)在 pretreat/success/finish **三处近乎逐行复制粘贴**(:305-348/:557-601/:727-771);
- 放线人字段 drawer 存在但**全库无写入点**——排产只到小组粒度,小组无成员表;
- 守卫码族:120013-120017/120020/120021/130014/130096/140013/200003/330111(见提取底稿)。

## 2. 施工单与节点排期(ConstructionServiceImpl / ConstructionScheduleServiceImpl)

### 2.1 施工单 ConstructionPO = 进度快照 + 开关集(不是状态机)

- **schedule(int)+cnid = 当前节点进度快照**:节点开工时仅前推不回退(条件 `projectStatus.sort < node.sort 且 != 完工`),同步写 construction.schedule/cnid 与 decoration.projectStatus(`ConstructionScheduleServiceImpl.java:322-332`);
- **process** `0 未完工/1 竣工交付`:完工条件 = "该 process 组节点完工数+1 == company 节点总数"(:397-400,计数 SQL dao:75-81)——**组完工按公司节点总数计数,方林嫌疑**;
- **accessory 辅料库开关** `0 开启/1 锁定`:open/lock 两接口**均无前置守卫**(:304-317/:343-360);库存侧消费点 inventory 拒发辅料;
- **balanceStatus** `0 未结算/1 已结算/2 不结算`:balance 0→1 无守卫;驳回 1→0 前置 ==1 否则 280018(:364-406);
- **terminalStatus** `0 无/1 瓦工终止/2 木工终止/3 油工终止`:**按工种分阶段终止合同**(方林业务模式);update 直接覆盖无流转校验,无 1→0 重启接口(:287-300);
- workStatus/scheduleStatus:本模块零写入点(仅 seiya 批量迁移写)→ 死字段嫌疑。

### 2.2 节点排期 ConstructionSchedulePO = 真正的节点状态机

- **status `0 初始 → 1 开工 → 2 完工`,+3 工费旁路**(未完工先开放工费,1→3,3 可直接 2):
  - 0→1 `beginConstructionSchedule:279-353`:守卫 status==0 否则 120025;**款项双闸**(checkout==0 时):decoration.paySacle < precheck/1000 → 120018 装修款比例不足,saleScale < stacheck/1000 → 120033 销售比例不足;
  - 1→2 `finishConstructionSchedule:357-438`:status∈{1,3};postcheck/fincheck 同款双闸;
  - 1→3 开放工费仅 status==1(120025);**删除仅 0 态,否则 120030 已开工不可删**(:631-645);
- **checkout 免检位** `0 需校验/1 免检`:传 1 直接置 1 **完全绕过款项比例守卫**,1→0 时才补校验(:442-471)——谁能免检无审批,待业务确认;
- **workStatus enter/leave 守卫写反**(两者都以 ==LEAVE 为 noop:leave 可未 enter 直达、重复 enter 覆盖时间,:475-511;正确版本在 ConstructionDiaryServiceImpl:141-177);
- **planStatus** `0 标准/1 预估`:预估工费确认后 !=0 抛 120039(:573-590);additemStatus 仅作查询过滤,无状态操作接口;
- **计划/实际时间族**:pre*/actual + enter/leave;`resetConstructionSchedule:515-569` 以锚点重排——锚点后正向累加、锚点前用负除数反向排;
- **🔴 没有前置依赖图**:开工校验只看款项比例,不校验前序节点是否完工;进度无百分比,对外口径 = 当前节点快照 + process。

### 2.3 节点配置 data.constructionnode(快照式)

- 节点字段:precheck/postcheck/stacheck/fincheck(‰ 比例)、startalk/endtalk 话术、startSurvey/endSurvey(开工/完工自动派调研)、timeLimit(‰天)、salaryLimit/salaryFloor/accessoryLimit(‰元)、process 组、sort、proset 预派、mark 开完工标识、account(1=完工触发装修账目状态)、status、**company**、**pcnid 集团归集**;
- **快照式**:`init()` 从 node 快照 name/四比例/限额/sort/period 到 schedule 行,之后独立维护(`ConstructionSchedulePO.java:487-507`);
- **排序**:schedule.schedule=node.sort 快照;DAO 查询无 ORDER BY,前端自行排——营造必须后端排序;
- **🔴 方林嫌疑:节点按 company 整表复制**,每公司一套 sort 序列(生产 company=0/1/2 各 20+ 行);pcnid=集团行归集(多品牌集团结构);
- **🔴 单位漂移(生产实证)**:数据口径 ‰天(constructionschedule.period=17000→17 天、constructionperiod 58000→58 天),**代码却 `Arith.div(x, 1000000)` 整除**(DrawLineServiceImpl:312/736、ScheduleServiceImpl:542/557)——差 1000 倍;连带 `-1000` 魔法偏移(‰天口径=提前一天)。

### 2.4 施工单创建联动(三触发点,全在放线域)

- `pretreatDrawLine:241` / `applayDrawLine:417` / `finishDrawLine:679`:查无施工单则 `new ConstructionPO(decorationPO)`,**与装修单 1:1(orderId 唯一)**;
- 限额初始化三级回退:constructionCost 面积档(生产 0 行**已弃用**)→ systemconfig 键 `accessoryLimit`/`salaryLimit`/`WORKER_SALARY_LIMIT_TOTAL_INT`(per-company)→ 哨兵 **999999000**(=¥999,999,无上限);period ← data.constructionperiod 按内环面积+company 档(如 0-110㎡→58 天);
- **更换监理联动六处**(`updateConstructionByOrderId:176-283`):construction.supervisor、decoration.supervisor、constructionInfo.isIssue=1+issueTime、**materialer 主材合伙人(UserPartner→DepartmentPartner→CityProperPartner 三角解析——方林合伙人制度)**、补 decorationUser、流水+事件。

### 2.5 施工人员 Constructer(评分三率)

- status `0 初始/1 禁用/2 已发送评分/3 已评分`(`ConstructerPO.java:38-44`);
- add:节点未开工拒 120026,同单同人同节点查重 120001,员工不存在 220005;
- send 0→2 发微信评分页;rate 2→3 写 rate/rate1/rate2+assessment(:222-273);**批量评分后门 updateConstructerForRate 无状态守卫**(:191-218);
- delete=软删→1:非 0 态拒 120022,已有工费拒 120023;
- 进出场流水 ConstructerRecord 只增不改;主键命名混乱(人员表主键 crid 与流水表同名不同义)。

## 3. 户型/工程档案两族(模板-项-选项三层)

- 两族完全同构(`drawlineprofile*` 与 `constructionprofile*` 三表 + Service 逐行对应,各 311 行)——复制粘贴双胞胎,营造合并为一族+挂载点区分;
- 三层:Profile(头,挂 tid 模板/dlid 或 cid/orderId/type/status/company)→ Item(name/type/require/content)→ Option(title/checked);Item 为文本/输入/日期类型时无 Option(`DrawlineProfileServiceImpl.java:162`);
- type(`DrawlineProfilePO.java:39-45`):`1 需求交底 / 2 主材 / 3 交接 / 4 标识`;Item.type(`DrawlineProfileItemPO.java:27-35`):`0 文本域/1 输入框/2 单选/3 多选/4 日期`;
- 模板来源 = data 库 SurveyTemplate 族,`ISSEND_AUTOMATIC=1` 自动下发实例化(初始 status=1 已发送,否则 0);
- status(`DrawlineProfilePO.java:35-37`):`0 初始/1 已发送/2 已完成`——**2 无任何写入点**,档案"完成"由前端约定,后端状态机不完整;
- 两种登记:`add`(前端回传 items 直落)与 `make`(模板实例化,option.checked 恒 1);`handle` 逐项回写,**无归属/越权校验**(:276-309);
- 守卫:130096/330087 调研/工程配置不存在,140013,120020。

## 4. 施工日志/图纸(ConstructionDiary 族)

- **日志自动进出场**(`ConstructionDiaryServiceImpl.java:139-175`):登记日志挂 csid(排期行);该 csid 无"进场"日志 → 本条记进场且 schedule.workStatus 0→1;已有 → 记出场并 1→2。**第二次登记即出场,无法多次进出**;workStatus 不匹配时静默 return 不报错;
- 带图登记置 isUpload=1 并同步 schedule.isUpload(:114-119);atttype:`1 进场/2 出场`(常量在 VO,`ConstructionDiaryVO.java:13-15`);
- **无角色权限控制**,任何登录用户可登记/删除;
- 图纸/日志图纸:限额上传(systemConfig `constructiondrawinglimit`/`constructiondiarydrawinglimit`,缺省 5 张,超限 110017);**批量添加图纸查不到环节时静默 continue 且返回恒空列表**(`ConstructionDrawingServiceImpl.java:163-183`);删除按主键裸删无归属校验。

## 5. 验收 = 一次性完工登记(AcceptanceServiceImpl)

- **没有合格/不合格状态机**:一个装修单一条验收记录(upsert by orderId,`AcceptanceServiceImpl.java:129-142`),提交即触发完工链:decoration.projectStatus →2 完工(:144)+ endTime/finishTime 补写 + ConstructionPO.process 0→1(:154-157)+ `PROJECT_STATUS_CHANGED` 事件(手拼 JSON,附带客源商机 ccid——销售管线耦合);
- 打分模型:7 个**硬编码工种**(水/电/瓦/木/油/监理/材料)×(质量/服务/卫生)=21 项 + multiple + internal = 22 个 tinyint 列(`AcceptancePO.java:41-101`),前端 5 星;**不可配置,重写必须改检查项模板**;
- **唯一前置门槛 = 收款比例**:`paySacle < systemConfig("CONSTRUCTION_ACCEPTANCE_LIMIT_INT", company)` 拒 120018,配置缺省 80(百分比,:115-118);
- 无验收图片、无客户签字字段;**无"已完工不可重复验收"守卫**,finishTime 每次覆盖;
- 守卫:140013 装修单不存在(:85,:112)、120020 工程信息不存在(:95,:124)、120018 装修款比例不足(:118);
- 死常量:`ACCEPTANCE_ALLOCATION_WORKER_FLAG_INT=512`、`TYPE_NAME_STR="验收"`(全库零引用,废弃的"验收作为派工节点"残留)。

## 6. 质检/巡检/返工(三个半成品)

- **Qualiter = 质检员指派台账,不是流程**:管理员指派(status 强置 0)/更换监理时批量附带;**rate/assessment/status 全库零更新点**——有人被指派能看到单,无处提交质检结果;status 仅 `0 初始`(借 ConstructerPO 常量);守卫 140013(:100)/220005 员工不存在(:106);
- **InspectionRecord = 监理巡检打卡日志**:无状态无联动;唯一规则:**删除仅限创建者本人**(ouid 比对,:96-124);零守卫(增删改裸奔),NPE 缺陷(先解引用后判空 :100-105);
- **Rework = 纯日志表**:无状态、无审批、**与费用/工期零联动**;update 仅允许改 createTime+reworkDes(:93-101);ReworkDrawing 是返工现场照片存档(原图+标注图);`ReworkPO.createTime` 是 String 日期、@Id 无 @GeneratedValue;
- **验收状态查询条件 acceptanceFlag 前端存在、后端 DAO 无对应分支——筛了等于没筛**(死条件,condition/construction.js:134-150 vs ConstructionDaoImpl)。

## 7. 主材计划(两层模型,优秀设计,保留泛化)

- **配置层** `data.materialplannode`(公司级规则字典):维度 = 品类/品牌(type 0/1)× 施工节点(cnid)× 动作(action `0 下单/1 配送/2 到货`)× 锚点(anchor `0 计划开工/1 计划完工/2 实际开工/3 实际完工`)× 偏移天数;判重(120048);品牌行 cid 强制品牌归属品类、品类行 bid=0(`MaterialPlanNodeServiceImpl.java:151-183`);
- **实例层** `construction.materialplan`(生成时快照规则);**planTime 不落库**:读时 = manualTime(手工指定优先)否则 anchorTime+offsetDay(`MaterialPlanServiceImpl.java:127`);锚点时间实绩优先计划兜底(anchor=2/3 实际为空回退计划,:349-364);
- **生成**(`makeMaterialPlan:167-222`):前置=装修单存在(140013)+已放线(120049,必须有 schedule);幂等=count>0 直接 return(**count-then-insert 非原子,无唯一约束,并发可重;后补规则不会补生成到已有订单**);
- **状态机**(`MaterialPlanPO.java:39-41`):`0 未完成 → 1 已完成(confirm 手工确认+actualTime)/ 2 已取消`;**取消是开关式切换(2↔0 复活),对已完成无守卫可变相 reopen;delete 物理删除全态可删**(:294-346);手工补行判重 120052(五元组);
- **履约回填**(读时计算不落库,`fillFulfillment:398-479`):系统档位 autoStatus `0 未履约/1 已下单/2 已配送/3 已到货`;数据源 = sales(status in (0,5)) + salesdeliveryitem(status>=1 配送/>=3 到货)+ selectionitem(1..6 同分档)——**SQL magic number + tinyint cast as signed 补丁**(`MaterialPlanDaoImpl.java:44-68`);
- **匹配缺陷**:品牌按 bid 精确,品类按**名称字符串 trim 相等**——品类改名即断链(:491-494);
- **逾期判定**(:131):planTime 非空 && status=0 && autoStatus < action+1 && planTime < 今天;
- 与 schedule 关系:cnid 挂 data.constructionnode,锚点时间读本单 schedule 行;排期页按节点汇总 total/confirm/overdue(`ConstructionScheduleServiceImpl.java:108-135`)。

## 8. 工程辅料限额台账(ConstructionAccessory)

- 实质 = 工程单 × 辅料商品(data.aoods)的**限额台账**:numLimit 限额标准 / num 已用量 / total 已用额(long 存分,Arith 精确运算);**无状态机**,只有 checkup 开关位(`0 VERIFY 受限校验/1 PASS 放行不限限`,命名反向易误读,`ConstructionAccessoryPO.java:28-30`);
- 限额标准来自按 ccid(城市)配置的 `data.aoodscost`,放线时 insert-select 自动生成(`DrawLineServiceImpl.java:455-460`);
- 真业务在 inventory 出库/审核路径:checkup=VERIFY 且超限拒 260059,通过累加,作废/拒绝对称扣减(`inventory/AccessoriesServiceImpl.java:203-349`);售后类型辅料单完全不触碰工程辅料;
- construction 侧 ServiceImpl 有 NPE 缺陷:update 查无记录仍 dao.update(null)(:90-95)。

## 9. 售后三件套(全部裸 CRUD,无真实状态机)

- **投诉**:status `0 未处理 → 1 已处理`(handle 置 1+handleTime,`CustomerServiceComplainServiceImpl.java:108-119`);无 reopen、无回访联动;type/level 后端无枚举(字典全在前端,违反字典单源);**零 FLException 守卫**,handle/update 对不存在 id 静默跳过;创建人查无用户直接 NPE(:88-89);
- **回访**:无 status 无状态机;update 对不存在 id copyProperties 到 null → NPE(:80-81);
- **保修**:仅 orderId/customerName/guaranteeTime(**String 自由文本日期**)/guaranteeDes;**后端完全不存在保修期计算**(无起点/时长/到期逻辑);
- 日期普遍 String "yyyy-MM-dd" 存储,operaterId/Name 字符串冗余——重写全部废弃。

## 10. 字典枚举汇总(源头)

| 域 | 枚举 |
|---|---|
| 放线 status | 0 排队/1 已排期/2 成功/3 失败(DrawLinePO.java:46-52) |
| 节点排期 status | 0 初始/1 开工/2 完工/3 开放工费;workStatus 0 未进场/1 已进场/2 已出场(ConstructionSchedulePO.java:49-75) |
| 档案 status | 0 初始/1 已发送/2 已完成(2 无人写);type 1 交底/2 主材/3 交接/4 标识;item.type 0-4 |
| 日志 | atttype 1 进场/2 出场;isUpload 1 已传 |
| 验收 | 无状态;22 打分列 tinyint 1-5 星 |
| 材料计划 | status 0/1/2;action 0 下单/1 配送/2 到货;anchor 0-3;autoStatus 0-3;type 0 品类/1 品牌 |
| 辅料台账 | checkup 0 受限/1 放行 |
| 售后 | 投诉 status 0/1;回访/保修无状态 |

## 11. 金额与数值口径(红线,均经生产只读库实证)

- **金额统一 ‰元(元×1000,厘)**:前端输入三位小数;默认上限哨兵 999999000=¥999,999(无上限);WORKER_SALARY_LIMIT_TOTAL_INT=5000000=¥5,000;工费累计与拦截在 salary 模块(`salaryTotal+money > salaryLimit` 拒,WorkerSalaryServiceImpl:209-229),冲销对称回减;
- **比例统一 ‰**:precheck 60000=60%、fincheck 99500=99.5%;与 decoration.paySacle(short 百分比)比较时 `/1000.0`——**比例两制式(‰ vs 百分比)并存,比较点必须显式换算**;
- **工期数据口径 ‰天(1000=1天)**:period=17000→17 天、constructionperiod 58000→58 天;**但代码 `/1e6` 整除——与数据差 1000 倍(单位漂移,见 §2.3/§13)**;
- 辅料台账金额 long 存分(`total = Σ Arith.mul(num, selling)`),Arith 精确运算——**又一种制式(分 vs ‰元)并存**;
- 面积 ‰㎡(areaLeft/areaRight 档位);`actualTime` 日期存 yyyyMMdd long;售后/返工/验收日期 String "yyyy-MM-dd"——**日期三制式并存**;
- 验收门槛 paySacle=short 百分比整数与配置直接比较(零缩放);评分 1-5 星小整数。

## 12. company 分叉与方林嫌疑(机制级,沉插件/配置清单)

1. **每表冗余 int company 列 + 查询强制过滤** = 多租户隔离手法(DAO company eq);写接口从登录上下文 queryCompany() 落库——营造对应 org_id 列,机制正常非嫌疑;
2. **🔴 节点体系按 company 整表复制**:data.constructionnode 每公司一套 sort 序列(生产 0/1/2… 各 20+ 行),组完工计数按 company 节点总数——节点体系非通用,是公司个性化施工流;营造 = 节点模板归组织自有数据;
3. **配置三级 per-company**:systemConfig(验收门槛 80/图纸限额 5/工费上限)、constructionperiod、constructionCost 全带 company 列——营造 = 组织级配置表;
4. **pcnid 集团节点归集**(集团行 company=0 + 自治行映射)——多品牌集团结构,军规 7"组织单层"之外的场景,压后;
5. **合伙人三角指派**:UserPartner(个人)→DepartmentPartner(设计部)→CityProperPartner(城区)自动定主材合伙人/服务人——组织合伙人制度,沉插件;
6. **按工种终止合同**(terminalStatus 瓦/木/油)——方林施工业务模式;
7. **面积档模板族**(限额/工期/节奏)按 company 分档——数据驱动化;
8. **放线申请可切换装修单归属分公司**(dompany decode)——多分公司运营特性;decoration.conpany 编码字段是加密传输历史 hack;
9. **qualiter.schedule 语义依赖各公司 node.sort**:同字段值在不同公司代表不同工种,节点调整后历史快照漂移(生产实锤)——快照必须存值不存序;
10. **seiya 平台 PM 旁路**:A20251208ServiceImpl 逐行复制验收守卫+完工链、施工单创建/限额/排期 1200+ 行——日期命名定制集成,必须合并为单一实现路径;
11. 钉钉/微信推送、消息队列事件手拼 JSON 带公司字段、survey 调研自动派发——统一走通知中心+领域事件;
12. `PROJECT_STATUS_CHANGED` 事件携带客源商机 ccid——销售管线耦合,应事件订阅插件。

## 13. 已知缺陷清单(重写勿照抄)

**致命(生产已炸或必然错账)**:
1. **工期天数除数错 1000 倍**:完工倒计时用 `86400000000L`(=1000 天的毫秒数)应为 86400000L——倒计时恒"还剩 1 日",三个工期提醒事件**几乎永不创建**(`ConstructionScheduleServiceImpl.java:420`、ConstructionInfoServiceImpl:181/204/228);
2. **开工流程无条件 setFinishTime(now)**——开工写完工时间(beginConstructionSchedule:340);
3. **金额字段由前端回传覆盖**:`updateConstructionScheduleForNorm:216-219` 从 VO 直接覆盖 accessoryTotal/salaryTotal 四个金额——财务红线;
4. **放线时间查重双重失效**(SQL 硬编码 status=0 + company 参数弃用,§1);
5. **enter/leave 守卫写反**(leave 可未 enter 直达,§2.2);
6. **计划时间 /1e6 与数据 ‰天差 1000 倍**(§2.3 单位漂移)。

**结构性**:
7. 排期生成三段复制粘贴(pretreat/success/finish 逐行重复);seiya 平台 PM 整域复制 1200+ 行——双实现必然漂移;
8. DESIGN_STATUS_CHECK 守卫 throw 被注释留空壳 if(:221-223);
9. `dompany != ""` 引用比较恒 true(:461);
10. init 快照覆盖方向两版语义相反(`!=0` vs `==0`,seiya 版漂移);
11. `ConstructionSchedulePO.ptv:517` 自赋值 `setSchedule(getSchedule())`,应为 node.getSort();
12. schedule 查询无 ORDER BY 依赖插入序;DAO filter 引用 PO 不存在的字段(ConstructionDaoImpl:108-109);
13. 命名谎言:queryConstructionDiaryDrawingByOrderId 把 cid 塞进名为 cdid 的变量按 cdid 列查询;人员/流水两表主键同名 crid;拼写化石 applay/conpany/dompany;
14. 批量图纸静默丢数据返回恒空;进出场二态无法重复进出;workStatus 不匹配静默 return;
15. 全域写接口无角色鉴权(仅登录拦截),删除图纸/日志按主键裸删;批量评分后门无状态守卫;
16. 验收无防重、finishTime 覆盖、copyProperties 覆盖 actId=0;NPE 家族(先 copyProperties/解引用后判空:Acceptance:76-84、InspectionRecord:100-105、ConstructionAccessory:90-95、售后三件套);
17. 材料计划 count-then-insert 幂等竞态、品类名称匹配断链、cancel 可复活已完成行、delete 全态可删、manualTime 隐式清空、resolveUserId 查无静默兜底 0;
18. 售后/返工 String 日期 + 盲 saveOrUpdate + 前端字典驱动后端 int type(违反字典单源);
19. 死常量(512/"验收"/COLUMN_ALREADY)/死查询条件(acceptanceFlag)/死字段(workStatus/scheduleStatus)/跨 PO 借常量。

## 14. 营造 addon-construction 落地映射(草案,详见 docs/prd/addon-construction.md)

| 老概念 | 营造归宿 |
|---|---|
| 放线"开工点火" | 拆显式用例:开工准备(施工单+限额初始化)与放线排期分离;免审后门改成"补录"带审计的动作 |
| ConstructionPlan 与 Schedule 双轨 | 合并为一套"计划→排期"模型,节奏模板作计划来源 |
| 档案两族 | 合并 profile 一族 + 挂载点(放线/施工)区分;status=2 补全语义 |
| 验收 22 固定列 | 可配置检查项模板(一行一检查项,工种/节点模板驱动)+ 合格/不合格/整改分支 |
| 质检半成品 | 并入施工团队指派或补全为真质检流(默认前者,V2 再议) |
| 返工/巡检空壳 | 返工=带费用/工期联动的完整单(V2);巡检并入统一日志/签到 |
| 主材计划两层模型 | 保留:组织级规则配置→订单快照→锚点推算→履约回填;匹配改 ID、幂等改唯一约束 |
| 面积档限额/工期/节奏 + systemConfig per-company | 组织级配置表(数据驱动,零 if) |
| 验收门槛(收款比例) | 组织级配置;进度依赖改查款项域事件/API |

### 14.1 营造实现定案回写(第 68 次巡检补,细节见 docs/prd/addon-construction.md)

- **addon-construction V1 落地**(第 22 次,V601 十四表,码段 240000):放线=开工点火三段(排期真查区间重叠 240007 修老 SQL 硬编码 status=0 双失效)/节点排期快照存值不存序(修 qualiter.sort 语义漂移)/计划时间按工期 ‰ 天正向累加(修老 /1e6 千倍漂移)/竣工唯一入口=验收全合格(修老按公司节点总数计数的方林嫌疑)/进出场流水 append-only 正序校验(修老 enter/leave 写反);
- **款项双闸营造实现**(本文档 §0-53 老口径 120018/120033 的对应物):开工/完工比例闸 240006(经 PaymentApi.listBills 聚合已收/应收,零契约扩展;免检位必须带理由留痕——修老完全绕闸无痕);**销售款闸已启用**(第 24 次,240025 经 SalesApi.collectedRatio 聚合销售单名下收款比例,V1 曾 UNCHECK 快照占位后随 addon-sales 就绪切真);
- 工程辅料额度=consume/refund 契约+ledger append-only(第 23 次,修老额度双侧口径漂移与零审计;inventory 领料闸经 ConstructionAccessoryApi 协作);
- 验收=可配置检查项(mount 模板快照)+整改分支+收款比例门槛组织级配置(240011);决算六项检查(第 47 次,B 类#6 兑现)=ConstructionApi.accountNodes 只读快照+SalesApi.countUnfinishedOrders,选材子项预留 UNCHECK。

## 15. 待业务方确认清单

1. **放线是否保留"点火"语义**:申请放线即创建施工单+初始化限额,还是拆成"开工准备"与"放线排期"两个用例?(建议拆分)
2. **免审补录后门**(finishDrawLine 不判状态)是否保留为正式"补录"功能?补录是否需要审批?
3. **验收模型**:按节点验收(每节点合格/不合格)还是整单一次性验收?检查项模板是否需要组织级自定义?
4. **质检线去留**:V1 是否只要"施工团队指派"(合并质检),真质检流放 V2?
5. **返工单**:V1 是否做(带费用/工期联动与审批),还是仅施工日志记一笔?
6. **保修期规则**:起点(竣工验收日?)、时长(按项目类型配置?)、到期提醒——老系统完全没有,需全新定义;
7. **主材计划锚点**:四种锚点(计划/实际×开工/完工)是否全保留?逾期判定口径确认;
8. **放线小组**:保留小组粒度还是精确到人(老系统 drawer 从未启用)?
9. **进出場**:日志自动判定二态(第二次即出场)是否满足业务,还是需要多次进出?
10. **施工计划(Plan)是否要**:老系统 Plan 与 Schedule 双轨半迁移,营造若只留 Schedule 需业务确认 Plan 场景已被覆盖。
11. **按工种终止**(瓦/木/油)是否保留?终止后如何重启(老系统无重启接口,疑线下处理)?
12. **checkout 免检位**由谁授权?是否保留"绕过款项比例闸"的能力(建议改为需审批)?
13. **节点开工门槛**:是否增加"前序节点完工"依赖(老系统无依赖图,只查款项比例)?
14. **工期单位终审**:‰天(数据现状)为营造唯一口径?计划/实际时间是否要支持锚点重排(reset 生产 preStartTime 全 NULL,疑未在用)?
15. **预估工费(planSalary)与 salaryLimitTotal(默认¥5,000)的确切语义**:单工人单节点还是全单工费?消费方是谁?
