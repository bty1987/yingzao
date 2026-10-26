-- 礼品采购流(A7;错误码 350030-350032;迁移段 1600+)
-- 设计口径:docs/rules/member.md §5(AwardPurchase 采购流考证)+ docs/prd/addon-member.md(V2 兑现)
--   老状态机原样回归:0 初始→1 采购中(登记采购人)→2 已入库(入库批)→3 已结算 / 4 已失效(仅 0 可失效)/ 5 已完成(父单全部入库);
--   **父单链分批入库**(老 fapid 同构):父单(status=1)按批入库,每批新建子单(father_id=父,status=2,surplus=0);
--   父 surplus 条件扣减(WHERE surplus>=本次,修老 get→内存算→update 的并发丢更新);
--   本次==父剩余 → 父置 5 完成(老「减前判断」语义:条件更新命中且扣后 surplus=0);
--   库存联动:gift is_stock=0(算库存)→ 入库回加 stock_num + 记流水(subtype=purchase,type=入库);
--   老钉钉群通知不迁(投递器随内核通知中心,收件人模型未定);老 activity/dept 申请标签不迁(remark 收敛);
--   金额一律厘(inprice 进价/baprice 结算价);表前缀 member_(军规 3);MySQL 5.6 兼容。
create table member_gift_purchase (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    gift_id       bigint not null comment '礼品(member_gift.id)',
    gift_no       varchar(32) not null comment '编号快照',
    gift_name     varchar(64) not null comment '名称快照',
    father_id     bigint null comment '父采购单(分批入库链;null=申请主单)',
    num           bigint not null comment '数量(本批/申请量)',
    surplus       bigint not null default 0 comment '剩余待入库量(主单;子单恒 0)',
    inprice       bigint null comment '进价(厘,入库时登记)',
    baprice       bigint null comment '结算价(厘,结算时登记)',
    status        tinyint not null default 0 comment '0初始/1采购中/2已入库/3已结算/4已失效/5已完成',
    remark        varchar(255) null,
    creater_id    bigint not null,
    purchaser_id  bigint null,
    instorer_id   bigint null,
    purchase_time datetime null,
    instore_time  datetime null,
    balance_time  datetime null,
    created_at    datetime not null default current_timestamp,
    primary key (id),
    key idx_purchase_org (org_id, status),
    key idx_purchase_gift (org_id, gift_id),
    key idx_purchase_father (father_id)
) engine=innodb default charset=utf8mb4 comment='礼品采购单(父单链分批入库)';
