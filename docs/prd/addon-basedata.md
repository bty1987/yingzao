# PRD - addon-basedata 基础数据(商品档案 + 主材计划)

> 状态:草稿(主材计划设计完毕待业务确认;商品档案/辅材档案/定材章节 2026-10-11 增补,规则底座 = docs/rules/material.md)
> 老系统参考:data 模块(过堂拆分:商品/材料档案、整装定材包、材料计划规则归本 addon,见 docs/01 过堂表)+ 施工域主材计划实例层(2026-10-11 addon-construction PRD 已将其剥离到本 addon)

## 定位与边界

- **行业套件共享基础数据模块**:商品/材料档案(goods)、品牌品类字典、整装定材包(cormrice/cormood)、主材计划两层模型的配置层与实例层;
- **被依赖而不依赖**:quotation(定额/规格)、sales、material、inventory、construction 都会消费它;它只提供数据与领域事件订阅,不反向依赖任何插件(军规 2);
- 老系统 data 模块拆分定案(2026-10-10):normadd/quotationformat → addon-quotation,**商品/材料/定材/材料计划规则 → 本 addon**。

## 业务闭环(一句话,主材计划)

主材负责人把"**什么品类/品牌,在哪个施工节点前后多久,该下单/配送/到货**"配置成组织级规则;放线成功(开工点火)后系统按规则为装修单**快照生成主材计划行**;计划时点由节点锚点时间+偏移**读时推算**;履约档位由销售/配送插件的领域事件回填;逾期未履约的计划行在计划页持续亮灯,直到人工确认完成——数据终态 = `basedata_material_plan` 行 status=1 已完成 / 2 已取消。

## 用户与场景

| 角色 | 场景 | 高频动作 |
|---|---|---|
| 主材负责人 | 排查哪些主材该下单/催配送 | 按节点分组看计划列表、确认完成 |
| 组织管理员 | 维护"品类/品牌 × 节点 × 动作"规则 | 规则增删改、调偏移天数 |
| 设计师/项目管家 | 放线后自动出计划 | 查看、手工补行、取消误行 |
| sales/inventory 插件(事件) | 销售下单/配送/到货发生 | 发领域事件,回填履约档位 |

## 核心模型(两层,老系统优秀设计保留泛化)

### 1. 配置层 `basedata_material_plan_rule`(组织级规则字典)

- 维度:**类型(type 0 品类/1 品牌)× 目标(category_id/brand_id)× 施工节点(node_id)× 动作(action 0 下单/1 配送/2 到货)× 锚点(anchor 0 计划开工/1 计划完工/2 实际开工/3 实际完工)× 偏移天数(offset_days,可为负=节点前)**;
- 判重 = 同组织 + 类型 + 目标 + 节点 + 动作 **五元组唯一**;品牌行必须归属品类(品牌行带 category_id)、品类行 brand_id=0(老规则保留,`MaterialPlanNodeServiceImpl:151-183`);
- 规则是**组织自有数据**(老系统按 company 复制,营造单层组织下自然归属 org_id,零分叉)。

### 2. 实例层 `basedata_material_plan`(订单快照)

- **生成**:放线成功(开工点火)后按组织规则全量快照生成,一规则一行;幂等 = `(org_id, order_id, 五元组)` **数据库唯一约束**——替代老系统 count-then-insert 竞态(老缺陷 §13-17);无规则则零行,合法;
- **快照存值不存序**:节点 id+名称、品类 id+名称、品牌 id+名称全部落快照列——品类改名不断链(修老"品类按名称字符串 trim 匹配"缺陷)、节点 sort 调整不漂移(老 §12-9 生产实锤);
- **plan_time 不落库**:读时推算 `plan_time = manual_time(手工指定优先)否则 anchor_time + offset_days`;锚点时间取本单节点排期:**实际锚点优先、实际为空回退计划锚点**(老口径 `MaterialPlanServiceImpl:127/:349-364` 保留);
- **状态机**:`0 未完成 → 1 已完成(confirm + actual_time)/ 2 已取消`;
  - 修正 1:取消仅对 status=0 可操作,已完成行**不可取消也不可复活**(老系统取消是开关式切换,2↔0 复活对已完成无守卫);
  - 修正 2:删除仅 status=0 可删(老 delete 全态可删);manual_time 编辑不隐式清空其他字段(老 update copyProperties 盲覆盖);
- **手工补行**:同一表单录入,唯一约束兜底判重(老 120052 语义);
- **履约档位 auto_status** `0 未履约/1 已下单/2 已配送/3 已到货`:**事件驱动回填**——sales/inventory 插件在销售下单/配送/到货节点发领域事件(契约经 yingzao-addons-api),本 addon 监听(AFTER_COMMIT+吞异常,通知分层同款姿势)按"订单+品类/品牌"匹配更新快照列;**不做老系统的读时跨表聚合**(魔法数字 SQL + 跨插件表依赖,双重不可取);
- **逾期判定**(读时计算,不落库):`status=0 且 auto_status < action+1 且 plan_time < 今天 → overdue`;计划页/排期页按节点汇总 total/confirm/overdue(老口径 `ConstructionScheduleServiceImpl:108-135` 保留)。

## 闭环校验

- 起点:组织管理员配置规则(零规则合法——只是放线后不生成计划行);
- 终点:一个装修单的计划行全部进入 1 已完成 / 2 已取消;
- 异常路径:**后补规则不回填存量订单**(老口径:仅放线时点生成;"按订单重跑生成"作为运维动作 V1 不做,排入待确认);锚点时间缺失(排期行无计划时间)→ plan_time 为空,只列出不判逾期;履约事件回填失败不阻断销售主流程(吞异常仅日志);
- 回滚:无(取消/删除仅 0 态)。

## 商品与辅材档案章节(2026-10-11 增补,规则底座 docs/rules/material.md §6-§7)

### 模型族与改法

| 模型 | 老表(生产行数) | 营造表 | 改法 |
|---|---|---|---|
| 商品主档 | data.goods(70.8 万) | `basedata_goods` | 保留泛化:no/name/barcode/category/brand/unit/类型(商品/套餐)/状态;金额保留厘;pieceNum 改语义化倍率并精确定义;**no/barcode 加组织内唯一约束**(老系统全程无查重,260046 仅 name);品类改单表树(废四级定长挂列 zerokind~thrkind);砍品牌名快照双存(bid+brand) |
| SKU 属性 | data.color 26.7 万/format 29.7 万 | `basedata_goods_sku_attr` | 合并为"商品 SKU 属性值"概念(颜色/规格同构);统一启用过滤(老 addColor 有查重 260034、addFormat 无);管理页全量/选择页仅启用 |
| 多价格体系 | data.price 30.6 万 | `basedata_goods_price` | gid 外键化(老裸 long);ctype/cvalue 与销售结算枚举统一;**补时间有效性**(老模型缺失);add 补查重 |
| 商品套餐 | data.goodspackage(393) | `basedata_goods_package` | pid 语义正名(实为主商品 gid,老注释相反);加 status 软删替代物理删;成员不可再为套餐保留 |
| 主材型号 | data.materialmodel(4.6 万) | `basedata_material_model`(归属待确认 §14-material-5) | 与 goods 补外键或独立价目模型;calcType(0=底价/1=点位)口径随 addon-sales |
| 辅材档案 | data.aoods(4.9 万)+aoodscharge 2877 | `basedata_auxiliary_material`(+`_charge` 费用模板) | 定额/库房双单位(normunit/normnum/storeunit/storenum)保留;**归属 addon-basedata 或 addon-inventory 待确认**(rules/material.md §14-4);Cost 成本限值后端零消费者暂不迁 |
| 定材包 | data.noods(1680) | `basedata_material_package` | pnid 两级(0=平台级主数据,>0=引用行存快照、仅浮动量可改)保留泛化;**组织化改写:老 company=0 即集团行的隐式约定改显式 owner_org**;后端补控权(老全靠前端) |
| 商品使用流水 | data.goodsRecord | 不迁 | 无状态无闭环,并入通用操作日志 |
| 回尺指标 | data.remeasureformat(0 行) | 不迁(归 addon-quotation) | 生产 0 行,模型随复尺业务走 |
| inventory 三镜像表 | inventory.color/format/price | 砍 | 老系统靠库外触发器同步双源,营造单源读本 addon |

### 横切修正(对老缺陷的显式修正,验收必测)

- **查重下沉 DB**:goods.no/barcode、format、price 补组织内唯一约束,消 check-then-act 竞态(老 GoodsServiceImpl.java:263-295 等);
- **导入器全量校验**:gid/bid 存在性、批内查重、pinyin 统一算法(老 imports 存 `List.toString()` 带方括号脏值,GoodsImportService.java:67);
- **启停语义统一**:status 0 启用/1 停用软开关,状态变更留操作日志(老 updateGoodsForStatus 直落 VO 值无校验);
- **company→org_id 单层化**:goods/price/materialmodel 的 company int 分叉与"颜色规格无 company 随主表"的不一致,统一 org_id 归属;商品池级联复制(communion 池位与开关)为方林运营特性不迁,如需集团分发走组织级共享字典;
- **错误码段**:复用本 addon 独占段(声明于 addon.yml),消灭老 130/180/260 三段混用与漂移(rules/material.md §10)。

### 端覆盖增补

- Web 后台:商品/辅材/定材档案管理页(列表+启停+导入);多价格体系随商品详情页;
- 移动端:商品查询/条码扫码(typeahead 场景),V1.1;
- 事件契约:商品启停/价格变更领域事件(消费方 addon-quotation/sales/inventory 的规格、销售、库存面)。

## 非目标(本期不做)

- 供应商/采购协同 → addon-supplier;销售单/配送单本体 → addon-sales / addon-inventory(本 addon 只消费事件);
- 采购建议/自动派单等智能化;多组织集团节点归集(老 pcnid,压后);
- 选材(soods/soodsitem)→ addon-quotation(写侧)/addon-sales(读侧),见 docs/rules/material.md §13;
- 旧 material 主链五表(material/materialitem/materialrefund/materialbalance/materialactivityitem)不迁——1.x 代主材销售,以 addon-sales 为唯一口径(rules/material.md §0-§1,改判待确认)。

## 验收标准

- [ ] 规则 CRUD + 五元组唯一约束 + 品牌行归属品类校验(dev 冒烟);
- [ ] 放线生成:无规则零行、并发重放不重复(唯一约束)、快照列存 ID+名称;
- [ ] plan_time 推算:manual_time 优先、实际锚点回退计划锚点、负偏移=节点前;
- [ ] 状态机守卫:已完成不可取消/复活、仅 0 态可删;
- [ ] 履约事件回填:档位单调递增、事件重放/乱序幂等;
- [ ] 逾期判定与确认后的口径一致;按节点汇总三数(total/confirm/overdue);
- [ ] i18n 中英双语、错误码独占段、Flyway 独立迁移 location + out-of-order。

## 端覆盖

- Web 后台:规则配置页(组织管理员)+ 订单计划页(按节点分组亮灯);
- 移动端(uni-app):计划列表+确认完成(巡场场景),V1.1;
- 企微/钉钉:逾期提醒走内核通知中心,免登点开即计划页。

## 待业务方确认清单(开工前必答)

1. 履约回填的事件粒度:sales 插件按行发事件、本 addon 自行聚合档位(建议),还是按单聚合?
2. 匹配键:品类 ID 优先、品牌 ID 兜底?销售明细挂不上品类档案时(老品类自由文本)忽略还是只按品牌?
3. 锚点四族(计划/实际 × 开工/完工)是否全保留?老 reset 排期生产疑未在用,是否砍掉"计划完工"锚?
4. 逾期口径 `plan_time < 今天`(自然日)确认?是否要"临近逾期"提前 N 天预警(走通知中心)?
5. 取消是否接轻量审批(老系统无审批直接切;内核审批底座已就位,可选)?
6. 确认完成是否强制填 actual_time 与上传凭证(老系统只点确认,无凭证)?
7. 错误码段建议 **260000**、Flyway 迁移段 **700+**(紧随 construction 600+),addon.yml 声明时定案。
8. (2026-10-11 增补)商品档案章节共 8 条确认项见 **docs/rules/material.md §14**(addon-material 改判不建/aoods 辅材归属/型号库与 goods 合并/noods 集团两级形态/优惠三承担方对齐等)——本 addon 的商品/辅材/定材章节随其一并确认。

## 老系统回溯指针(实现者用)

- 配置层:`com.fanglin.data.common.service.impl.MaterialPlanNodeServiceImpl`(判重 :151-183);表 `data.materialplannode`
- 实例层:`com.fanglin.construction.service.impl.MaterialPlanServiceImpl`(生成 :167-222 / 推算 :127 / 履约回填 :398-479)+ `MaterialPlanDaoImpl.java:44-68`(履约聚合 SQL 魔法数字);表 `construction.materialplan`
- 口径细节:`docs/rules/construction.md` §7(规则)/§13-17(已知缺陷)/§15-7(待确认);锚点时间来源 = addon-construction 节点排期(经 yingzao-addons-api 扩展点/事件取数,禁止跨插件读表)
