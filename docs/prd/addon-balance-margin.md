# PRD - addon-balance 毛利与管理线子域(管理线版本化发布)

> 状态:已确认(2026-10-28 用户拍板「按推荐」五项)+ 已实现(第 96 次,docs/15 R1;冒烟 2174/2174+enforce 95/95;实现定案回写 rules/balance.md §17)
> 来源:fl-yy 毛利率模块管理线版本化发布(docs/14 反哺归档 §四 的转正;docs/07 §2.6 版本化机制;docs/06 §2.3 口径治理/§2.4 空态原则)
> 老系统参考:fl-yy `cycx/grossmargin` 管理线族(回溯指针见文末);老 Rhadamanthus 无对应模块(纯增量域)
> 落点:**addon-balance 新增「毛利与管理线」子域**(推荐,见拍板清单①)/迁移 **V1302**/错误码 **320100–320150** 小段(沿 quotation 231200 子段先例)/表前缀 `balance_margin_`

## 业务闭环(一句话)

集团经营层与分公司管理层把「毛利率考核线」按业务年度(17 固定周期)配置并**发布成版本**——发布即固化快照、变更逐条留痕、历史只读不回改,最终落在「最新已发布版本 = 当前生效线;历史版本 = 当时考核口径的一手证据」,供后续毛利率看板(F1)/订单额度(F3)/兑现预估(F4)取线,报表不因口径调整漂移。

## 谁与场景

| 角色 | 场景 | 高频动作 |
|---|---|---|
| 集团经营层 | 定集团管理线 | 整年 17 周期填三口径线值 → 发布 → 看变更记录 |
| 集团经营层 | 督办分公司配置 | 配置进度看板(NONE/PARTIAL/COMPLETED)→ 代发分公司线(显式选组织) |
| 分公司管理层 | 定本组织要求线 | 参照当期集团线填本公司线 → 发布 |
| 审计/财务 | 追溯考核口径 | 发布记录分页 → 版本修改详情(before→after→change) |

## 核心模型

### 线的主体与周期

- **两类线**:①集团管理线——主体=**根组织**(营造单棵组织树,树根即集团视角,docs/08);②分公司要求线——主体=**根的直接子组织**(推荐,见拍板②),**直接配置、不由实际数据反算**,与集团线分别展示比较;
- **周期集**:每业务年度固定 **17 周期**=年 1(`yyyy`)+季 4(`yyyy-Qn`)+月 12(`yyyy-MM`);服务端生成,发布必须整年 17 项完整提交(缺项/重复/不属于该年度全拒);
- **三口径线值**:综合/基装(工程)/主材,**万分比整数**(0<x≤10000,5000=50%——对齐 A6 三口径表「比例制式统一 ppm 万分比」全局定案;fl-yy 百分比 3 位小数收窄为万分比无损域,见拍板③);
- **实际值快照列就位、V1 恒空**(诚实边界):三口径利润/结算金额源未接入(F1 域),发布时实际毛利率与数据时点落 null,F1 落地后新发布起填充、存量不回补(见拍板④)。

### 版本化三表(V1302)

1. `balance_margin_line_version`(版次):org_id(主体)/biz_year/line_type(1 集团管理线,2 分公司要求线)/created_by(BIGINT yz_user)/created_time;一主体一年可多版本,无 uk,latest=id 最大;
2. `balance_margin_line_change`(变更明细):version_id/cycle_type/period_value/rate_type(1 综合,2 基装,3 主材)/before_value/after_value(万分比);**仅 before≠after 记行**,change=after−before 读取期计算;idx version_id;
3. `balance_margin_line_publish`(生效快照,**append-only 每版本全量 17 行**,营造定案,见差异表):version_id/org_id(主体)/cycle_type/period_value/comprehensive_line/project_line/main_material_line/group_comprehensive_ref/group_project_ref/group_main_ref(分公司行冗余拷贝发布时点的当期集团线值;集团行该三列 null)/actual_comprehensive_rate(可空)/actual_data_as_of(可空);uk(version_id, period_value)。

> **当前生效线 = 该主体该年最新版本的快照行;历史版本行 = "当时按什么线考核"的直接证据**——历史复现不靠变更链回放(fl-yy 单行 upsert 形态的收敛,理由见差异表)。

## 发布流程(状态机文字版)

1. **提交发布**(集团/分公司同一管道,line_type 分流):守卫链=①17 周期完整且匹配年度、周期值/类型合法不重复;②三线值各 0<x≤10000(万分比);③分公司线须显式选目标组织(集团身份不得拿根发分公司线,fl-yy 语义保留),且**前置=同年度集团管理线已发布且 17 周期完整**(「请先发布该年度集团管理线」);④scope 守卫见下节;
2. **乐观并发闸**:读该主体该年最新版本并比对提交方 baseVersionId,不一致 → 「配置已被其他人更新,请刷新后重试」——多人同时发布不静默覆盖;
3. **事务内落库**:插 version 行 → 17 行 publish 快照(append-only)→ 与上一版本逐周期逐口径 diff 记 change(首发无 diff 零变更行);发布无删除、无反发布、历史版本只读;
4. **周期状态**:未结束周期标 PENDING(实际值待更新)、已结束 NORMAL——空态原则(docs/06 §2.4),禁假 0。

## 查询面与端点(八枚,归 /api/balance/margin-lines prefix,菜单闸 require /balance/margin-lines)

| 端点 | 语义 |
|---|---|
| GET /group?year= | 集团当前线:17 周期(线值+当期实际 null+周期状态)+当前版本 id |
| POST /group/publish | 发布集团管理线(整年 17 项+baseVersionId) |
| GET /group/versions?year=&page= | 集团发布记录分页(操作人+时间) |
| GET /company-options?year=&keyword= | 分公司选项+配置进度(每组织三线全非空计数,NONE/PARTIAL/COMPLETED+汇总) |
| GET /branch?orgId=&year= | 分公司当前线(本公司线+引用的集团线对照) |
| POST /branch/publish | 发布分公司要求线(orgId+整年 17 项+baseVersionId) |
| GET /branch/versions?orgId=&year=&page= | 分公司发布记录分页 |
| GET /versions/{id} | 版本修改详情(变更明细行:周期×口径 before/after/change) |

## 组织与权限(S2 基座映射,零公司硬编码)

- **集团线读/发守卫=操作者 yz.orgScope 含根组织**(S2 数据范围基座天然承载:登录组织即根才含根)——替代 fl-yy `orgId==222` 硬编码(方林嫌疑收口,军规 1);
- **分公司线读/发守卫=目标组织 ∈ 操作者 scope 且非根**;分公司身份只许操作本组织;集团身份(scope 含根)必须显式选目标组织;
- 发布权限细分(canPublishGroupLine/canPublishBranchRequirement 两开关)V1 以菜单闸+上述 scope 守卫承载,角色级细分随 RBAC 演进(诚实边界,见拍板⑤)。

## 契约与事件

- **V1 纯本域零跨域**:发布/查询不触任何其他 addon 表与契约(管理线是配置数据,不是经营数据);
- **F1 预留**(不在本 PRD):毛利率看板取线=读本域最新版本快照;三口径利润源映射(docs/14 §九.1)在 F1 PRD 拍板;F3 额度取「该公司当月综合管理线」同源。

## 错误码段(320100–320150,双语零漂移)

320101 发布参数不能为空 / 320102 业务年度不正确 / 320103 发布必须完整包含 17 个周期 / 320104 发布周期格式不正确或不属于业务年度 / 320105 发布周期不能重复 / 320106 管理线必须大于 0 且小于等于 100% / 320107 配置已被其他人更新,请刷新后重试 / 320108 请先发布该年度集团管理线 / 320109 集团管理线周期快照不完整 / 320110 集团操作分公司管理线时必须选择分公司 / 320111 无权操作该分公司管理线 / 320112 无权操作集团管理线 / 320113 发布记录不存在 / 320114 发布记录类型不正确 / 320115 当前版本周期快照不完整(以实现轮微调为准,登记 error 段台账)

## 划界(本期不做)

- **F1 毛利率看板**(三口径实际计算/趋势/排名/达标数)——独立轮次,前置=三口径数据源映射拍板(docs/14 §九.1);
- **F3 订单额度明细/F4 兑现预估**——依赖 F1 计算器+发包价率主数据(归 basedata)+订单过滤口径拍板(docs/14 §九.2);
- **毛利提升奖**(增长额×档位系数)——不立项(docs/14 §一 定性:考核奖励域参考);
- **发布审批流**——V1 操作人+时间+版本链留痕即审计(fl-yy 原样无审批,见拍板⑤);
- **实际值快照填充**——列就位 V1 恒 null,F1 接入后新发布起填充、存量不回补;
- **多根组织/集团嵌套**——营造单树模型无此形态;线值反算/按实际数据推线——fl-yy 与 docs/14 均明确「直接配置不反算」。

## 验收标准(实现轮逐条对)

- [ ] V1302 三表迁移成功;17 周期集生成正确(含闰年 2 月;`yyyy`/`yyyy-Qn`/`yyyy-MM` 格式与年度匹配);
- [ ] 集团线首发:version+17 行快照落库、零变更行;非 17 项/重复周期/越界线值/非法周期值/空参数全拒(对应码双语);
- [ ] 再发布:baseVersionId 过期拒 320107;diff 仅非零行;版本详情 before/after/change 精确;
- [ ] 分公司线:未发集团线拒 320108;发布后快照行含当期集团线引用值;集团身份不选目标拒 320110;目标不在 scope 拒 320111/320112;
- [ ] append-only 断言:发布 v2 后 v1 的快照行逐列不变;
- [ ] 配置进度:三线全非空计数与 NONE/PARTIAL/COMPLETED 状态、汇总数精确;
- [ ] scope 守卫:分公司用户发集团线拒;keyword 过滤生效;
- [ ] i18n:周期 label(年度/季度/月度)/线类型/口径/进度态/错误码中英双语零硬编码;
- [ ] balance 域零读 decoration/sales/quotation 表(本域纯配置,军规 2 自证);
- [ ] Web:管理线页(集团 tab 17 行编辑+发布+记录抽屉;分公司 tab 进度表+选中编辑+发布;版本详情 diff 抽屉);菜单 addon.yml 下发;
- [ ] 冒烟 ≥25 步(守卫族+发布链+diff+append-only+进度+scope 四向越权)。

## 建议实施节奏

**1 轮可完**(纯本域零跨域契约):V1302+服务+八端点+Web 页+字典(periodStatus/lineType/rateType/configStatus)+i18n+冒烟;F1 看板另轮另 PRD。

## 待业务方拍板清单(拍板前不写码,可整体回复「按推荐」)

1. **落点**:addon-balance 毛利与管理线子域(推荐——docs/14 归档动作两选项取「独立 PRD+balance 落点」;管理线与后续 F1-F4 同域)/独立 addon-grossmargin(第 18 插件)/addon-target 扩展;
2. **分公司线主体**:根的直接子组织(推荐,对应「分公司」层,配置进度看板清单可控)/任意非根组织(分区/部门也可立线,灵活但管理面发散);
3. **线值制式**:万分比整数(推荐,对齐 A6 全局定案;输入百分比两位小数无损)/百分比 3 位小数(fl-yy 原样,十进制列);
4. **实际值快照**:V1 建列恒 null、F1 后新发布起填充(推荐,免 ALTER)/V1 不建列(F1 再迁移);
5. **发布审批**:V1 不接、留痕即审计(推荐,fl-yy 原样)/接内核轻量审批(可选前置闸)。

## fl-yy 回溯指针(实现者用)+ 营造收敛差异表

- 代码:`D:\Git\fl-yy\fl-yy-biz\...\cycx\controller\grossmargin\GrossMarginManagementLineController`(八端点)/`service\impl\GrossMarginManagementLineServiceImpl`(发布管道 557 行)/`dal\entity\GrossMarginLine{Version,ChangeItem,PublishItem}`/`enums\GrossMargin{LineType,CycleType,RateType,PeriodStatus}`;口径主文档 `D:\Git\fl-yy\docs\毛利率与利润计算说明.md`;
- 跨源时序教训(照抄):**实际值聚合等跨数据源查询必须在本地写事务开始前完成**——营造 V1 虽无实际值,该时序纪律随 F1 兑现;

| fl-yy 原样 | 营造收敛 | 理由 |
|---|---|---|
| requireGroup 硬编码 orgId==222 | scope 含根组织守卫(S2 基座) | 方林嫌疑收口,军规 1 |
| 线值 BigDecimal 百分比 ≤3 位小数 | 万分比整数 INT | 对齐 A6「比例制式统一 ppm」全局定案 |
| publish_item 单行 upsert+变更链复现历史 | append-only 每版本全量 17 行快照 | 历史一手证据免链回放;行语义单义化 |
| 集团发布行把 branch 列填同值(一行两用) | 分公司行冗余拷贝集团线;集团行引用列 null | 快照行不再一表两读 |
| creator 存 staffId 字符串+staffMapper 反查名 | created_by BIGINT yz_user+既有操作人惯例 | 内核既有能力,免名字解析层 |
| 发布前跨库实时算实际值 | V1 恒 null,F1 契约后填充 | 三口径利润源未接,诚实边界 |
| 表名 yy_gross_margin_line_* | balance_margin_line_*(V1302) | 插件前缀军规 3 |
