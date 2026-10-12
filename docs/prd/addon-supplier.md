# PRD - addon-supplier 厂商主数据

> 状态:已确认(2026-10-13,按 2026-10-12 全量确认门闸推荐口径执行,待确认项见文末 6 条,不阻塞写码)
> 老系统参考:personel Supplier 族 + communion brandSupplier + data supplierSignet + production Outsource(无独立模块,域考证见 `docs/rules/supplier.md`)
> 定位:**行业插件套件 V2 首个候选**。V1 十二插件(inventory 外辅/销售结算/选材)已在消费侧预留厂商引用(inventory 外辅"厂商名称快照"接口),本 addon 把"厂商"从字符串快照升级为一等主数据。

## 业务闭环(一句话)

运营人员建厂商档案并把它与品牌关联(供货目录+结算条款可选录入),此后外辅/销售结算/选材等业务单据按"品牌关联厂商"正确归口与结算;数据终态落在 `supplier_supplier`(启停态)与 `supplier_brand`(品牌关联)。

## 用户与场景

| 角色 | 场景 | 高频动作 |
|---|---|---|
| 运营/采购 | 建厂商、维护品牌关联与供货条款 | 新建/编辑/启停、绑定品牌、录结算条款 |
| 业务插件(代码) | 外辅挂厂商、结算单按厂商归口 | 经 SupplierApi 查厂商摘要/校验启用 |
| 管理员 | 厂商台账总览 | 检索、启停、看关联与引用 |

## 核心流程(状态机文字版)

1. 新建厂商 → status=启用(DB 唯一查重:name org 内 / ext_no 全局 / creditCode 可空唯一)→ 发 `SupplierCreatedEvent`;
2. 编辑(白名单:name/联系人/电话/地址/creditCode/ext_no;no/status/org 不可被 VO 覆写)→ 发 `SupplierUpdatedEvent`;
3. 启停:启用⇄停用(条件 UPDATE;**停用只挡新挂单,存量单据不受影响**;有未完结引用时可配置拦截,默认不拦)→ 发 `SupplierStatusChangedEvent`;
4. 品牌关联:`supplier_brand` 多对多(org+supplier+brand 唯一,可设 is_primary 直属厂商)→ 关联/解绑各发事件;解绑不删历史(结算/选材快照自持);
5. 供货条款(可选,合同从表思想拆出):品牌级结算类型(底价/点位)+结算值+品类细化+商品供货价/结算价,挂 `supplier_term*` 表族,**与"合同签署流"显式分成两个实体**——V1 只落数据模型+CRUD,签署流(V2+/扩展点)不做;
6. 外部同步通道(收编 seiya 思想):`ext_no` 幂等 upsert 端点(命中更新/否则新建),供 SRM/金蝶类外部系统对接,认证走 RBAC capability。

## 闭环校验

- 闭环起点:运营建厂商 → 绑品牌;
- 闭环终点:业务单据(外辅/结算/选材)经 SupplierApi 归口到正确厂商,条款可被结算消费;
- 异常路径:查重冲突定位到 name/ext_no/creditCode 各自错误码;停用厂商被新单挂引用→显式报错;ext_no upsert 并发→DB 唯一键兜底重试回显。

## 非目标(本期不做)

- **厂商登录账号/自助门户**(老系统"厂商即用户"双通道不迁;V2 按需加账号绑定);
- **电子签**(印章 supplierSignet+合同签署流+法大大/e签宝,沉淀为合同签署 Provider 扩展点排期 V2+);
- **金蝶对接**(老系统 dormant,KO 孤儿代码);
- **Merchant/Avenger 分类**(僵尸族,只出不进);
- **外协加工单**(production Outsource 随 addon-construction V2 再议);
- **厂商对账单/付款**(归 addon-balance 已有厂商账户闭环);
- **存量数据迁移工具**(V2 数据迁移统一排期)。

## 验收标准

- [ ] 建厂商三路查重(DB 唯一键下沉),冲突错误码定位到字段;
- [ ] 停用厂商:新挂单被拒(业务插件侧经 SupplierApi 校验),存量单展示正常;
- [ ] 品牌关联:同 org 同 supplier 同 brand 唯一;is_primary 同品牌唯一;解绑后历史结算/选材快照不受影响;
- [ ] SupplierApi 契约(querySupplier/queryBrandSuppliers/checkActive,实现在 addon-supplier)供 inventory/sales/balance 注入,零反向依赖;
- [ ] 三事件(SupplierCreated/Updated/StatusChanged)经总线发布,eventKey 幂等锚点,出站箱留痕;
- [ ] ext_no upsert 幂等:重复同参请求回显同一条;并发同 ext_no 仅一条落库;
- [ ] 供货条款 CRUD:品牌级+品类级+商品级三层,商品级服务端校验品牌归属;
- [ ] 错误码 340000 段独占,i18n 中英双语;迁移 V1500 段,Flyway 全程管 schema;
- [ ] Web:厂商台账页(检索/新建编辑/启停/品牌关联/条款管理抽屉)+ addon.yml 菜单 dicts;mvn+npm 双绿+冒烟全绿。

## 端覆盖

- Web 后台:Suppliers.vue 台账页;
- 移动端(uni-app):V1 不做(厂商管理是运营后台场景);
- 企微/钉钉:不做(老系统外辅厂商通知走钉钉,yingzao 端走内核通知中心,addon-inventory 已闭环)。

## 老系统回溯指针(实现者用)

- 主数据:`com.fanglin.personel.service.impl.SupplierServiceImpl`(addSupplier 编号/上限/角色/默认密码)、`dao.impl.SupplierDaoImpl`(type=1 过滤);同步:`com.fanglin.seiya.service.impl.A20260404ServiceImpl`(ext_no 幂等 upsert+唯一 type 校验);
- 品牌关联:`com.fanglin.communion.service.impl.BrandSupplierServiceImpl` + `BrandPO.ocuid`(直属厂商→is_primary);
- 供货条款:`com.fanglin.personel.service.impl.SupplierContract{Brand,Category,Goods,Product,Service}ServiceImpl`(五从表=价格目录,注意 Service 族挂 sid 不挂 scid);
- 消费面样板:`com.fanglin.inventory.service.impl.OccessoriesServiceImpl`(bind 不验类型的反面教材/厂商看单 uid 过滤)、`com.fanglin.sales.service.impl.SalesBalanceServiceImpl`(按品牌分组/厂商确认 vpost);
- 已知坑:全在 `docs/rules/supplier.md` §7 缺陷 18 条与 §9 错误码八段漂移;引用四形态(A/B/C/D)并存、balance 按登录名字符串过滤、fadadaCallback 空壳、Contract.STATUS_FILING_INT=1 语义冲突哑弹。

## 码段与迁移

- 错误码:**340000 段独占**(340001 起;300-330 已被 sales/inventory/balance/customerprofile 占用);
- Flyway:**V1500+**(customerprofile 1400+ 之后),migration location `addon-supplier`;
- 表前缀:`supplier_`(supplier_supplier / supplier_brand / supplier_term_brand / supplier_term_category / supplier_term_goods)。
