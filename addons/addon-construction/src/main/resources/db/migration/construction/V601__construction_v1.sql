-- 施工域 V1 真表(addon-construction,错误码段 240000;迁移段 600+)
-- 设计口径:docs/rules/construction.md + docs/prd/addon-construction.md
--   放线 = 开工点火(申请→排期→成功,免审后门改显式补录带审计);
--   节点排期快照式(组织节点模板 → 本单快照行,修老 qualiter.sort 语义漂移:快照存值不存序);
--   单一量纲:比例 ‰(60000=60%)、工期 ‰天(17000=17 天,老 /1e6 漂移不迁)、金额 ‰元(厘);
--   验收 = 可配置检查项模板(修老 22 固定打分列)+ 合格/不合格分支 + 竣工唯一(修老无防重 finishTime 覆盖);
--   工人进出场流水 append-only 正序校验(修老 enter/leave 守卫写反);档案两族合一 + mount 挂载点区分。
--   表前缀 construction_(军规 3);MySQL 5.6 兼容:索引列宽 ≤767 字节。

-- 组织级配置(懒初始化;老 systemconfig per-company 散键收敛,数据驱动零 if)
create table construction_config (
    org_id           bigint not null,
    acceptance_floor int    not null default 800 comment '验收收款比例门槛(‰,800=80%;老 CONSTRUCTION_ACCEPTANCE_LIMIT_INT 百分比口径统一换算)',
    created_at       datetime not null default current_timestamp,
    updated_at       datetime not null default current_timestamp on update current_timestamp,
    primary key (org_id)
) engine=innodb default charset=utf8mb4 comment='施工组织级配置';

-- 组织节点模板(老 data.constructionnode 按公司整表复制 → 组织自有数据;军规 7 单层组织)
-- 快照式纪律:模板只管定义,施工单在点火时拷贝快照,此后独立维护
create table construction_node (
    id              bigint not null auto_increment,
    org_id          bigint not null,
    name            varchar(64) not null comment '节点名(如 放线/水电/瓦工/油工/竣工)',
    sort            int    not null comment '施工顺序(org 内唯一,后端排序红线:老 DAO 无 ORDER BY 依赖插入序不迁)',
    process         varchar(32) not null default 'main' comment '工序组协议键(老 process int 组;协议键灭绝魔法值)',
    precheck        int    not null default 0 comment '开工装修款比例闸(‰,0=不校验)',
    postcheck       int    not null default 0 comment '完工装修款比例闸(‰)',
    stacheck        int    not null default 0 comment '开工销售款比例闸(‰;销售域未就绪 V1 UNCHECK 快照保留)',
    fincheck        int    not null default 0 comment '完工销售款比例闸(‰;同上)',
    time_limit      int    not null default 0 comment '工期(‰天,17000=17 天;老数据口径,代码 /1e6 漂移不迁)',
    salary_limit    bigint not null default 999999000 comment '工费上限(‰元;老哨兵 999999000=¥999,999 无上限语义保留)',
    accessory_limit bigint not null default 999999000 comment '辅料限额(‰元,同上;台账消费方 addon-inventory)',
    start_talk      varchar(200) null comment '开工话术',
    end_talk        varchar(200) null comment '完工话术',
    status          tinyint not null default 1 comment '0停用/1启用(停用不影响已生成快照)',
    created_by      bigint not null,
    created_at      datetime not null default current_timestamp,
    updated_at      datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_node_name (org_id, name),
    unique key uk_node_sort (org_id, sort)
) engine=innodb default charset=utf8mb4 comment='组织施工节点模板';

-- 施工单(与装修单 1:1,放线成功/补录点火时创建;老 ConstructionPO 进度快照+开关集)
-- process:0 未完工/1 竣工交付(判定 = 本单节点排期全部完工,修老按公司节点总数计数方林嫌疑);
-- terminalStatus 按工种终止(方林业务模式)/balanceStatus 结算位(salary 域)/workStatus·scheduleStatus 死字段不迁
create table construction_order (
    id                  bigint not null auto_increment,
    org_id              bigint not null,
    order_id            bigint not null comment '装修单(decoration_order.id,1:1)',
    order_no            varchar(32) not null comment '装修单单号快照(展示,经 DecorationApi 契约)',
    customer_name       varchar(64) not null comment '客户名快照(展示名一律建档快照)',
    house_label         varchar(200) not null comment '房屋展示快照',
    designer_id         bigint not null default 0 comment '设计师(放线失败通知收件人,经契约快照)',
    supervisor_id       bigint null comment '监理(yz_user.id;更换留痕轨迹)',
    supervisor_name     varchar(64) null,
    current_schedule_id bigint null comment '当前节点排期(construction_schedule.id,进度快照仅前推不回退)',
    current_sort        int not null default 0 comment '当前节点序(快照)',
    process             tinyint not null default 0 comment '0未完工/1竣工交付',
    accessory           tinyint not null default 0 comment '辅料库开关:0开启/1锁定(消费方 addon-inventory;老 open/lock 无守卫保留但留痕轨迹)',
    period              int not null default 0 comment '总工期(‰天,点火时按节点模板累加)',
    created_by          bigint not null,
    created_at          datetime not null default current_timestamp,
    updated_at          datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_order (org_id, order_id),
    key idx_construction_org_process (org_id, process)
) engine=innodb default charset=utf8mb4 comment='施工单(装修单执行侧影子)';

-- 放线单(状态机 0排队 → 1已排期 → 2成功/3失败;失败可重申,成功点火)
-- 老免审后门 finishDrawLine 不迁 → is_retroactive 显式补录用例(带审计字段)
create table construction_drawline (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    order_id      bigint      not null comment '装修单(decoration_order.id)',
    status        tinyint not null default 0 comment '0排队/1已排期/2成功/3失败',
    group_name    varchar(64) null comment '放线小组(老 dgid 小组粒度,drawer 全库无写入点,保留小组口径)',
    plan_start    datetime null comment '计划放线开始',
    plan_end      datetime null comment '计划放线结束',
    finish_time   datetime null comment '办结时间(成功/失败)',
    is_retroactive tinyint not null default 0 comment '1=显式补录(免审后门改带审计用例)',
    operator_id   bigint not null comment '申请人/补录人',
    operator_name varchar(64) not null,
    remark        varchar(200) null,
    created_at    datetime not null default current_timestamp,
    updated_at    datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    key idx_drawline_order (org_id, order_id, status),
    key idx_drawline_conflict (org_id, group_name, status, plan_start, plan_end)
) engine=innodb default charset=utf8mb4 comment='放线单(开工点火)';

-- 节点排期(本单快照行,施工期真正运转的表;状态机 0初始→1开工→2完工,+3 工费旁路)
-- 快照存值不存序(修老 qualiter.schedule 依赖各公司 node.sort 的历史漂移);
-- checkout 免检位必须留痕操作人+理由(老完全绕闸无痕不迁);plan/actual 时间族单一 ‰天口径
create table construction_schedule (
    id               bigint not null auto_increment,
    org_id           bigint not null,
    order_id         bigint not null comment '装修单(decoration_order.id;施工单 1:1 由此反查)',
    node_id          bigint null comment '节点模板来源(construction_node.id,模板删除/改序不影响快照)',
    node_name        varchar(64) not null comment '节点名快照',
    sort             int    not null comment '施工顺序快照',
    process          varchar(32) not null default 'main' comment '工序组快照',
    precheck         int not null default 0,
    postcheck        int not null default 0,
    stacheck         int not null default 0,
    fincheck         int not null default 0,
    time_limit       int not null default 0 comment '工期(‰天)',
    salary_limit     bigint not null default 999999000,
    accessory_limit  bigint not null default 999999000,
    status           tinyint not null default 0 comment '0初始/1开工/2完工/3开放工费(1→3 旁路,3 可直接 2)',
    checkout         tinyint not null default 0 comment '免检位:0需校验/1免检(置 1 必带理由,比例闸跳过留痕)',
    checkout_reason  varchar(200) null,
    checkout_operator varchar(64) null,
    work_status      tinyint not null default 0 comment '0未进场/1已进场/2已出场(施工日志自动判定)',
    plan_start       datetime null comment '计划开工(生成时按工期累加,锚点重排可用)',
    plan_end         datetime null comment '计划完工',
    start_time       datetime null comment '实际开工',
    end_time         datetime null comment '实际完工(开工不再误写,修老 begin 无条件 setFinishTime)',
    created_at       datetime not null default current_timestamp,
    updated_at       datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_schedule_sort (org_id, order_id, sort),
    key idx_schedule_status (org_id, order_id, status)
) engine=innodb default charset=utf8mb4 comment='施工节点排期(快照)';

-- 施工人员指派(评分三率;老批量评分后门无状态守卫不迁)
-- status:0初始/1已停用/2已发送评分/3已评分(评分令牌为移动端评分页 V2 预留)
create table construction_worker (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    order_id      bigint not null,
    schedule_id   bigint not null comment '节点排期(construction_schedule.id,未开工节点拒指派)',
    user_id       bigint not null comment '工人(yz_user.id)',
    user_name     varchar(64) not null comment '姓名快照',
    status        tinyint not null default 0 comment '0初始/1已停用/2已发送评分/3已评分',
    work_status   tinyint not null default 0 comment '0未进场/1已进场/2已出场(流水推导最新态)',
    score1        tinyint null comment '质量评分(1-5)',
    score2        tinyint null comment '服务评分(1-5)',
    score3        tinyint null comment '卫生评分(1-5)',
    assessment    varchar(200) null comment '客户评语',
    rate_token    char(36) null comment '评分令牌(移动端评分页 V2)',
    created_at    datetime not null default current_timestamp,
    updated_at    datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_worker (org_id, order_id, schedule_id, user_id),
    key idx_worker_user (org_id, user_id)
) engine=innodb default charset=utf8mb4 comment='施工人员指派';

-- 进出场流水(append-only 只增不改;enter/leave 正序校验,修老守卫写反)
create table construction_worker_record (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    worker_id     bigint not null comment '施工人员(construction_worker.id)',
    order_id      bigint not null,
    schedule_id   bigint not null,
    action        tinyint not null comment '1进场/2出场(正序:出场前置必须是进场)',
    operator_id   bigint not null,
    operator_name varchar(64) not null,
    created_at    datetime not null default current_timestamp,
    primary key (id),
    key idx_wrecord_worker (worker_id, schedule_id, created_at)
) engine=innodb default charset=utf8mb4 comment='工人进出场流水';

-- 施工日志(挂节点排期;首笔自动判进场、次笔出场,之后再登记为普通日志不隐藏不静默)
create table construction_diary (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    order_id      bigint not null,
    schedule_id   bigint not null comment '节点排期(construction_schedule.id)',
    user_id       bigint not null,
    user_name     varchar(64) not null,
    content       varchar(500) not null,
    atttype       tinyint not null default 0 comment '0普通/1进场/2出场(登记时按 work_status 自动判定)',
    has_drawing   tinyint not null default 0 comment '是否带图(文件存储基建就绪前仅标记)',
    created_at    datetime not null default current_timestamp,
    primary key (id),
    key idx_diary_schedule (schedule_id, created_at),
    key idx_diary_order (org_id, order_id)
) engine=innodb default charset=utf8mb4 comment='施工日志';

-- 工程档案(老 drawlineprofile/constructionprofile 复制粘贴双胞胎合一,mount 挂载点区分;
-- order_id 为空 = 组织模板行,实例由模板拷贝生成;status=2 已完成补全老无写入点缺陷)
create table construction_profile (
    id         bigint not null auto_increment,
    org_id     bigint not null,
    mount      tinyint not null comment '挂载点:0放线档案/1施工档案/2验收检查项模板',
    name       varchar(64) not null,
    order_id   bigint null comment '装修单(实例行;NULL=组织模板)',
    status     tinyint not null default 0 comment '0初始/1已发送(实例化)/2已完成',
    created_by bigint not null,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    key idx_profile_tmpl (org_id, mount, order_id),
    key idx_profile_order (order_id)
) engine=innodb default charset=utf8mb4 comment='工程档案头(模板/实例)';

create table construction_profile_item (
    id         bigint not null auto_increment,
    profile_id bigint not null comment '档案头(construction_profile.id)',
    name       varchar(200) not null,
    type       tinyint not null default 0 comment '0文本域/1输入框/2单选/3多选/4日期(文本/输入/日期无选项)',
    `require`  tinyint not null default 0 comment '0选填/1必填',
    content    varchar(500) null comment '填写内容(模板行为预置说明)',
    sort       int not null default 0,
    created_at datetime not null default current_timestamp,
    primary key (id),
    key idx_pitem_profile (profile_id, sort)
) engine=innodb default charset=utf8mb4 comment='工程档案项';

create table construction_profile_option (
    id         bigint not null auto_increment,
    item_id    bigint not null comment '档案项(construction_profile_item.id)',
    title      varchar(200) not null,
    checked    tinyint not null default 0 comment '0未勾选/1已勾选',
    sort       int not null default 0,
    primary key (id),
    key idx_poption_item (item_id, sort)
) engine=innodb default charset=utf8mb4 comment='工程档案选项';

-- 验收单(一单一次竣工;存在不合格项置整改 0 可重提,全部合格置完工 1 触发竣工链,
-- 修老无防重 + finishTime 覆盖 + 前端约定完成态三缺陷)
create table construction_acceptance (
    id           bigint not null auto_increment,
    org_id       bigint not null,
    order_id     bigint not null comment '装修单(decoration_order.id,唯一——一单一次验收闭环)',
    status       tinyint not null default 0 comment '0整改中(存在不合格项,可重新提交)/1完工(全部合格,竣工)',
    checker_id   bigint not null,
    checker_name varchar(64) not null,
    check_time   datetime null comment '竣工确认时间(完工态才写,不覆盖整改稿)',
    remark       varchar(200) null,
    created_at   datetime not null default current_timestamp,
    updated_at   datetime not null default current_timestamp on update current_timestamp,
    primary key (id),
    unique key uk_acceptance_order (org_id, order_id)
) engine=innodb default charset=utf8mb4 comment='施工验收单';

create table construction_acceptance_item (
    id            bigint not null auto_increment,
    acceptance_id bigint not null comment '验收单(construction_acceptance.id)',
    name          varchar(200) not null comment '检查项名(模板项快照,存值不存序)',
    pass          tinyint not null default 0 comment '0不合格/1合格',
    remark        varchar(200) null,
    sort          int not null default 0,
    primary key (id),
    key idx_aitem_acceptance (acceptance_id, sort)
) engine=innodb default charset=utf8mb4 comment='施工验收检查项';

-- 施工单操作轨迹(append-only 审计:点火/免检/竣工/监理更换/辅料开关等关键动作留痕)
create table construction_order_track (
    id            bigint not null auto_increment,
    org_id        bigint not null,
    order_id      bigint not null comment '装修单(decoration_order.id)',
    action        varchar(32) not null comment '动作协议键:ignite/retroactive/supervisor/accessoryOpen/accessoryLock/begin/finish/openSalary/checkout/scheduleDelete/scheduleReset/accept/acceptRectify',
    detail        varchar(200) null comment '动作详情(免检理由/监理变更等)',
    operator_id   bigint not null,
    operator_name varchar(64) not null,
    created_at    datetime not null default current_timestamp,
    primary key (id),
    key idx_ctrack_order (order_id, created_at)
) engine=innodb default charset=utf8mb4 comment='施工单操作轨迹';
