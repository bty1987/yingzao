-- 报价项库 + 价格快照时间线 + 明细挂接(A4 保价重算,docs/11 批次 A;错误码 230160-230164)
-- 设计口径:docs/rules/quotation.md §4/§10(价格快照时间线 + 保价重算 V2)
--   老语义:data.quotationItem(报价项主数据/单价库)+ quotationPrice(价格历史,queryQuotationPriceLast 按
--   时间点取最后一条)+ updateQuotationForPrice(遍历明细挂接项按时间点回填三价并刷新报价时间);
--   老 addQuotationPrice 初始快照 createTime 硬编码 2015-01-01(保证任意时点可取到首条)不迁——营造建项即插首条
--   快照(生效时点=建档时刻),历史 append-only(老 updateQuotationPrice 可改历史价是审计坑,不迁);
--   老三价(addPrice/unitPrice/subPrice)营造明细行收敛单 price 列,快照同步单列;
--   老三价在明细行的语义 = 基础单价±增减,聚合只用等价合成价——行业中性收敛单列后量纲不变;
--   营造报价明细为自足快照行(V401),挂接报价项库为可选(catitem_id null=自由行,老 quotationItem 挂接同语义);
--   重算守卫:仅编辑态可重算(老 checkQuotationForStatusByEnabled 同族);自由行/无快照行跳过(老 continue 同语义)。
--   表前缀 quotation_(军规 3);金额一律厘;MySQL 5.6 兼容。

-- 报价项库(组织级单价主数据:常见项目定额单价;报价明细从中套用)
create table quotation_item (
    id          bigint not null auto_increment,
    org_id      bigint not null,
    name        varchar(128) not null comment '项目名(组织内唯一——单价库是模板数据,同名单价必为维护事故)',
    unit        varchar(16) null comment '计量单位(m²/项/套…)',
    subtype     tinyint not null default 0 comment '0工程/1材料(对齐明细行 subtype)',
    price       bigint not null default 0 comment '当前单价(厘;= 最新快照价)',
    status      tinyint not null default 0 comment '0启用/1停用(停用拒新挂接,存量行重算照常——快照在)',
    remark      varchar(200) null,
    created_by  bigint not null,
    created_at  datetime not null default current_timestamp,
    updated_at  datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_qitem_name (org_id, name),
    key idx_qitem_org (org_id, status)
) engine=innodb default charset=utf8mb4 comment='报价项库(单价主数据)';

-- 价格快照时间线(append-only:建项插首条,调价插新条;重算=按 item+时点取最后一条)
create table quotation_item_price (
    id          bigint not null auto_increment,
    org_id      bigint not null,
    item_id     bigint not null comment '报价项(quotation_item.id)',
    price       bigint not null comment '单价快照(厘)',
    operator_id bigint not null,
    created_at  datetime not null default current_timestamp comment '生效时点(即快照写入时刻)',
    primary key (id),
    key idx_qitemprice_item (item_id, created_at)
) engine=innodb default charset=utf8mb4 comment='报价项价格快照时间线';

-- 明细行挂接报价项库(可空=自由行;挂接行重算时按快照回填)
alter table quotation_function_item
    add column catalog_item_id bigint null comment '报价项库挂接(quotation_item.id;null=自由行)' after subtype,
    add key idx_fitem_catitem (catalog_item_id);
