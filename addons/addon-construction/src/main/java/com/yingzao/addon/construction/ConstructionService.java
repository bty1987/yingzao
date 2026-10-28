package com.yingzao.addon.construction;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.addons.api.DecorationApi;
import com.yingzao.addons.api.NormApi;
import com.yingzao.addons.api.PaymentApi;
import com.yingzao.addons.api.QuotationApi;
import com.yingzao.addons.api.event.ConstructionAcceptedEvent;
import com.yingzao.addons.api.event.ConstructionDrawlineFailedEvent;
import com.yingzao.addons.api.event.ConstructionDrawnEvent;
import com.yingzao.addons.api.event.ConstructionScheduleStatusChangedEvent;
import com.yingzao.addons.api.event.ConstructionStartedEvent;
import com.yingzao.core.eventbus.YzEventBus;
import com.yingzao.core.web.BizException;

/**
 * 施工主链:放线(申请→排期→成功,免审后门改显式补录)→ 点火(施工单 1:1 + 节点排期快照生成)
 * → 节点推进(0→1→2,1→3 工费旁路;开工/完工装修款比例闸,免检必须留痕操作人+理由)
 * → 验收收口(检查项快照 + 整改分支 + 全部合格竣工唯一)。
 * 对老缺陷的显式修正(rules/construction.md §13):
 *   放线时间冲突查重修老「SQL 硬编码 status=0」——真查 status=1 的同小组区间重叠;
 *   开工不再误写完工时间(老 begin 无条件 setFinishTime);
 *   计划时间单一 ‰天口径(老 /1e6 与数据差 1000 倍不迁);
 *   竣工(process 0→1)= 验收全部合格唯一入口(老按公司节点总数计数,方林嫌疑);
 *   金额/比例不从 VO 回传覆盖(老 updateForNorm 信任前端红线不迁)。
 * 销售款比例闸(stacheck/fincheck)随 addon-sales 就绪启用,V1 快照保留 UNCHECK 不拦
 * (同 decoration 决算 V1 口径)。
 */
@Service
public class ConstructionService {

    // ---- 错误码(240000 段,同码同消息见 i18n/construction.properties) ----
    public static final int CODE_NOT_FOUND = 240001;
    public static final int CODE_DUP_ACTIVE = 240002;
    public static final int CODE_NO_QUOTATION = 240003;
    public static final int CODE_DECORATION_NOT_FOUND = 240004;
    public static final int CODE_BAD_STATE = 240005;
    public static final int CODE_PAY_RATIO = 240006;
    public static final int CODE_TIME_CONFLICT = 240007;
    public static final int CODE_WORKER_BEFORE_BEGIN = 240008;
    public static final int CODE_WORKER_DUP = 240009;
    public static final int CODE_RECORD_ORDER = 240010;
    public static final int CODE_ACCEPT_RATIO = 240011;
    public static final int CODE_ACCEPT_DONE = 240012;
    public static final int CODE_SCHEDULE_DELETE = 240013;
    public static final int CODE_PARAM = 240014;
    public static final int CODE_NODE_DUP = 240015;
    public static final int CODE_PROFILE_ILLEGAL = 240016;
    public static final int CODE_IGNITE_NO_NODES = 240017;
    public static final int CODE_PROFILE_REQUIRED = 240018;
    public static final int CODE_SALES_RATIO = 240025;

    // ---- 状态机 ----
    /** 放线:0 排队 → 1 已排期 → 2 成功 / 3 失败(失败可重申)。 */
    public static final int DL_QUEUED = 0;
    public static final int DL_SCHEDULED = 1;
    public static final int DL_SUCCESS = 2;
    public static final int DL_FAILED = 3;
    /** 节点排期:0 初始 → 1 开工 → 2 完工;3 = 开放工费旁路(1→3,3→2)。 */
    public static final int SC_INIT = 0;
    public static final int SC_BEGIN = 1;
    public static final int SC_FINISH = 2;
    public static final int SC_SALARY = 3;
    /** 施工单 process:0 未完工 / 1 竣工交付。 */
    public static final int PROCESS_RUNNING = 0;
    public static final int PROCESS_ACCEPTED = 1;
    /** 验收单:0 整改中(存在不合格项,可重提)/ 1 完工。 */
    public static final int AC_RECTIFY = 0;
    public static final int AC_ACCEPTED = 1;
    /** 免检位。 */
    public static final int CHECKOUT_OFF = 0;
    public static final int CHECKOUT_ON = 1;
    /** 工费上限哨兵(老语义保留:999999000‰元 = ¥999,999 视同无上限)。 */
    public static final long LIMIT_SENTINEL = 999999000L;

    public static final String BIZ_ADDON = "construction";
    public static final String BIZ_TYPE = "order";
    /** 装修单款线业务三元组(比例闸口径:该单全部应收款线)。 */
    private static final String DECORATION_ADDON = "decoration";
    private static final String DECORATION_TYPE = "order";

    private static final long MILLIS_PER_DAY = 86_400_000L;

    private record ConstructionRow(long orderId, String orderNo, int process, int currentSort,
            long designerId, Long supervisorId) {
    }

    private record ScheduleRow(long id, long orderId, String nodeName, int sort, int precheck, int postcheck,
            int status, int checkout, int stacheck, int fincheck, Integer workStatus) {
    }

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final YzEventBus eventBus;
    private final DecorationApi decorations;
    private final QuotationApi quotations;
    private final PaymentApi payments;
    private final com.yingzao.addons.api.SalesApi sales;
    private final NormApi norms;
    private final ConstructionTeamService teams;
    private final ConstructionProfileService profiles;

    public ConstructionService(JdbcTemplate jdbc, MessageSource messages, YzEventBus eventBus,
            DecorationApi decorations, QuotationApi quotations, PaymentApi payments,
            com.yingzao.addons.api.SalesApi sales, NormApi norms,
            ConstructionTeamService teams, ConstructionProfileService profiles) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.eventBus = eventBus;
        this.decorations = decorations;
        this.quotations = quotations;
        this.payments = payments;
        this.sales = sales;
        this.norms = norms;
        this.teams = teams;
        this.profiles = profiles;
    }

    // ---- 组织配置(懒初始化) ----

    private Map<String, Object> ensureConfig(long orgId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select acceptance_floor from construction_config where org_id = ?", orgId);
        if (rows.isEmpty()) {
            jdbc.update("insert into construction_config(org_id, acceptance_floor) values (?, ?)",
                    orgId, 800);
            return jdbc.queryForMap(
                    "select acceptance_floor from construction_config where org_id = ?", orgId);
        }
        return rows.get(0);
    }

    public Map<String, Object> getConfig(long orgId) {
        return ensureConfig(orgId);
    }

    @Transactional
    public void updateConfig(long orgId, Integer acceptanceFloor) {
        require(acceptanceFloor != null && acceptanceFloor >= 0 && acceptanceFloor <= 1000,
                CODE_PARAM, "construction.param.invalid");
        ensureConfig(orgId);
        jdbc.update("update construction_config set acceptance_floor = ? where org_id = ?",
                acceptanceFloor, orgId);
    }

    // ---- 节点模板(组织自有数据;快照式纪律:模板只管定义) ----

    public record NodeCmd(String name, Integer sort, String process, Integer precheck, Integer postcheck,
            Integer stacheck, Integer fincheck, Integer timeLimit, Long salaryLimit, Long accessoryLimit,
            String startTalk, String endTalk, Integer account) {
    }

    public List<Map<String, Object>> listNodes(long orgId, boolean includeDisabled) {
        String filter = includeDisabled ? "and status in (0, 1)" : "and status = 1";
        return jdbc.queryForList("""
                select id, name, sort, process, precheck, postcheck, stacheck, fincheck,
                       time_limit, salary_limit, accessory_limit, start_talk, end_talk, account, status
                from construction_node where org_id = ? %s order by sort
                """.formatted(filter), orgId);
    }

    @Transactional
    public long createNode(long orgId, NodeCmd cmd, long operatorId) {
        require(cmd != null, CODE_PARAM, "construction.param.invalid");
        NodeCmd norm = normalizeNode(cmd);
        validateNode(norm);
        requireNodeUnique(orgId, norm.name(), norm.sort(), null);
        jdbc.update("""
                insert into construction_node(org_id, name, sort, process, precheck, postcheck, stacheck,
                    fincheck, time_limit, salary_limit, accessory_limit, start_talk, end_talk, account, status, created_by)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,?)
                """, orgId, norm.name(), norm.sort(), norm.process(), norm.precheck(), norm.postcheck(),
                norm.stacheck(), norm.fincheck(), norm.timeLimit(), norm.salaryLimit(),
                norm.accessoryLimit(), norm.startTalk(), norm.endTalk(), norm.account(), operatorId);
        return jdbc.queryForObject("""
                select id from construction_node where org_id = ? and name = ?
                """, Long.class, orgId, norm.name());
    }

    @Transactional
    public void updateNode(long orgId, long nodeId, NodeCmd cmd) {
        require(cmd != null, CODE_PARAM, "construction.param.invalid");
        loadNode(orgId, nodeId);
        NodeCmd norm = normalizeNode(cmd);
        validateNode(norm);
        requireNodeUnique(orgId, norm.name(), norm.sort(), nodeId);
        jdbc.update("""
                update construction_node set name = ?, sort = ?, process = ?, precheck = ?, postcheck = ?,
                    stacheck = ?, fincheck = ?, time_limit = ?, salary_limit = ?, accessory_limit = ?,
                    start_talk = ?, end_talk = ?, account = ?
                where id = ? and org_id = ?
                """, norm.name(), norm.sort(), norm.process(), norm.precheck(), norm.postcheck(),
                norm.stacheck(), norm.fincheck(), norm.timeLimit(), norm.salaryLimit(),
                norm.accessoryLimit(), norm.startTalk(), norm.endTalk(), norm.account(), nodeId, orgId);
    }

    /** 缺省归一:比例/工期空 = 0(不设闸),限额空 = 哨兵(无上限),工序组空 = main。 */
    private NodeCmd normalizeNode(NodeCmd cmd) {
        return new NodeCmd(cmd.name(), cmd.sort() == null ? 1 : cmd.sort(),
                blankToNull(cmd.process()) == null ? "main" : cmd.process().trim(),
                cmd.precheck() == null ? 0 : cmd.precheck(),
                cmd.postcheck() == null ? 0 : cmd.postcheck(),
                cmd.stacheck() == null ? 0 : cmd.stacheck(),
                cmd.fincheck() == null ? 0 : cmd.fincheck(),
                cmd.timeLimit() == null ? 0 : cmd.timeLimit(),
                cmd.salaryLimit() == null ? LIMIT_SENTINEL : cmd.salaryLimit(),
                cmd.accessoryLimit() == null ? LIMIT_SENTINEL : cmd.accessoryLimit(),
                blankToNull(cmd.startTalk()), blankToNull(cmd.endTalk()),
                cmd.account() == null ? 0 : cmd.account());
    }

    @Transactional
    public void updateNodeStatus(long orgId, long nodeId, Integer status) {
        require(status != null && (status == 0 || status == 1), CODE_PARAM, "construction.param.invalid");
        require(jdbc.update(
                "update construction_node set status = ? where id = ? and org_id = ?",
                status, nodeId, orgId) > 0, CODE_NOT_FOUND, "construction.notfound");
    }

    private void validateNode(NodeCmd cmd) {
        require(notBlank(cmd.name()) && cmd.name().trim().length() <= 64, CODE_PARAM, "construction.param.invalid");
        require(cmd.sort() != null && cmd.sort() >= 1, CODE_PARAM, "construction.param.invalid");
        require(inRange(cmd.precheck(), 1000) && inRange(cmd.postcheck(), 1000)
                && inRange(cmd.stacheck(), 1000) && inRange(cmd.fincheck(), 1000),
                CODE_PARAM, "construction.param.invalid");
        require(cmd.timeLimit() != null && cmd.timeLimit() >= 0, CODE_PARAM, "construction.param.invalid");
        require(cmd.salaryLimit() == null || (cmd.salaryLimit() > 0 && cmd.salaryLimit() <= LIMIT_SENTINEL),
                CODE_PARAM, "construction.param.invalid");
        require(cmd.accessoryLimit() == null || (cmd.accessoryLimit() > 0 && cmd.accessoryLimit() <= LIMIT_SENTINEL),
                CODE_PARAM, "construction.param.invalid");
    }

    private void requireNodeUnique(long orgId, String name, Integer sort, Long excludeId) {
        Integer dup = jdbc.queryForObject("""
                select count(*) from construction_node
                where org_id = ? and (name = ? or sort = ?) and id <> coalesce(?, 0)
                """, Integer.class, orgId, name.trim(), sort, excludeId);
        require(dup == null || dup == 0, CODE_NODE_DUP, "construction.node.dup");
    }

    private void loadNode(long orgId, long nodeId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from construction_node where id = ? and org_id = ?",
                Integer.class, nodeId, orgId);
        require(count != null && count > 0, CODE_NOT_FOUND, "construction.notfound");
    }

    // ---- 放线(开工点火三段:申请 → 排期 → 成功;免审后门改显式补录) ----

    @Transactional
    public long applyDrawline(long orgId, long orderId, long operatorId) {
        requireDecoration(orgId, orderId);
        requireNoActiveDrawline(orgId, orderId);
        if (!quotations.hasLockedQuotation(orgId, orderId)) {
            throw new BizException(CODE_NO_QUOTATION, msg("construction.drawline.noQuotation"));
        }
        jdbc.update("""
                insert into construction_drawline(org_id, order_id, status, operator_id, operator_name)
                values (?,?,?,?,?)
                """, orgId, orderId, DL_QUEUED, operatorId, displayName(operatorId));
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    /** 排期:0→1 指派小组+起止时间;时间冲突查重真查 status=1 区间重叠(修老硬编码 status=0 双失效)。 */
    @Transactional
    public void scheduleDrawline(long orgId, long drawlineId, String groupName, String planStartText,
            String planEndText) {
        LocalDateTime start = parseTime(planStartText);
        LocalDateTime end = parseTime(planEndText);
        require(notBlank(groupName) && groupName.trim().length() <= 64, CODE_PARAM, "construction.param.invalid");
        require(start != null && end != null && end.isAfter(start), CODE_PARAM, "construction.param.invalid");
        Map<String, Object> row = loadDrawline(orgId, drawlineId);
        require(((Number) row.get("status")).intValue() == DL_QUEUED, CODE_BAD_STATE,
                "construction.state.illegal");
        Integer conflict = jdbc.queryForObject("""
                select count(*) from construction_drawline
                where org_id = ? and group_name = ? and status = ?
                  and plan_start < ? and plan_end > ?
                """, Integer.class, orgId, groupName.trim(), DL_SCHEDULED, end, start);
        require(conflict == null || conflict == 0, CODE_TIME_CONFLICT, "construction.drawline.timeConflict");
        jdbc.update("""
                update construction_drawline set status = ?, group_name = ?, plan_start = ?, plan_end = ?
                where id = ? and org_id = ? and status = ?
                """, DL_SCHEDULED, groupName.trim(), start, end, drawlineId, orgId, DL_QUEUED);
    }

    /** 成功:强校验 status=1(老 120017)→ 2 + 点火(施工单+节点排期快照)。 */
    @Transactional
    public void successDrawline(long orgId, long drawlineId, long operatorId) {
        Map<String, Object> row = loadDrawline(orgId, drawlineId);
        require(((Number) row.get("status")).intValue() == DL_SCHEDULED, CODE_BAD_STATE,
                "construction.state.illegal");
        jdbc.update(
                "update construction_drawline set status = ?, finish_time = now() where id = ? and status = ?",
                DL_SUCCESS, drawlineId, DL_SCHEDULED);
        long orderId = ((Number) row.get("order_id")).longValue();
        ignite(orgId, orderId, operatorId, false, null);
        // 放线成功事件(定额子域重算触发点;核对清单 1——成功路径此前无事件)
        eventBus.publish(new ConstructionDrawnEvent(drawlineId, orgId, orderId, false, operatorId),
                "drawline:" + drawlineId + ":drawn");
    }

    /** 失败:0/1 → 3(已成功 2 为终态不回败,收紧老「任意状态可置败」);事件通知设计师。 */
    @Transactional
    public void failDrawline(long orgId, long drawlineId, String remark, long operatorId) {
        Map<String, Object> row = loadDrawline(orgId, drawlineId);
        int status = ((Number) row.get("status")).intValue();
        require(status == DL_QUEUED || status == DL_SCHEDULED, CODE_BAD_STATE, "construction.state.illegal");
        jdbc.update(
                "update construction_drawline set status = ?, finish_time = now(), remark = ? where id = ? and status = ?",
                DL_FAILED, blankToNull(remark), drawlineId, status);
        eventBus.publish(new ConstructionDrawlineFailedEvent(drawlineId, orgId,
                ((Number) row.get("order_id")).longValue(), blankToNull(remark), operatorId),
                "drawline:" + drawlineId + ":failed");
    }

    /** 显式补录(老免审后门 finishDrawLine 的带审计正名):一步到位建 status=2 放线单并点火。 */
    @Transactional
    public void retroactiveDrawline(long orgId, long orderId, String groupName, String planStartText,
            String planEndText, String remark, long operatorId) {
        requireDecoration(orgId, orderId);
        requireNoActiveDrawline(orgId, orderId);
        LocalDateTime start = parseTime(planStartText);
        LocalDateTime end = parseTime(planEndText);
        require(notBlank(groupName), CODE_PARAM, "construction.param.invalid");
        require(start != null && end != null && end.isAfter(start), CODE_PARAM, "construction.param.invalid");
        jdbc.update("""
                insert into construction_drawline(org_id, order_id, status, group_name, plan_start, plan_end,
                    finish_time, is_retroactive, operator_id, operator_name, remark)
                values (?,?,?,?,?,?,now(),1,?,?,?)
                """, orgId, orderId, DL_SUCCESS, groupName.trim(), start, end, operatorId,
                displayName(operatorId), blankToNull(remark));
        ignite(orgId, orderId, operatorId, true, blankToNull(remark));
        eventBus.publish(new ConstructionDrawnEvent(null, orgId, orderId, true, operatorId),
                "drawline:order:" + orderId + ":retroactive");
    }

    // ---- 点火:施工单 1:1 + 节点排期快照(计划时间按工期 ‰天正向累加) ----

    private void ignite(long orgId, long orderId, long operatorId, boolean retroactive, String remark) {
        Integer exists = jdbc.queryForObject(
                "select count(*) from construction_order where org_id = ? and order_id = ?",
                Integer.class, orgId, orderId);
        if (exists != null && exists > 0) {
            return; // 已点火(补录幂等/事件重放)
        }
        DecorationApi.OrderSummary order = decorations.queryOrder(orgId, orderId)
                .orElseThrow(() -> new BizException(CODE_DECORATION_NOT_FOUND,
                        msg("construction.drawline.decorationNotFound")));
        List<Map<String, Object>> nodes = listNodes(orgId, false);
        require(!nodes.isEmpty(), CODE_IGNITE_NO_NODES, "construction.ignite.noNodes");
        long totalLimit = 0;
        LocalDateTime cursor = LocalDateTime.now();
        List<Object[]> planRows = new ArrayList<>();
        for (Map<String, Object> node : nodes) {
            long timeLimit = ((Number) node.get("time_limit")).longValue();
            LocalDateTime planStart = cursor;
            cursor = cursor.plus(Duration.ofMillis(timeLimit * MILLIS_PER_DAY / 1000));
            planRows.add(new Object[] { orgId, orderId, node.get("id"), node.get("name"),
                    ((Number) node.get("sort")).intValue(), node.get("process"),
                    ((Number) node.get("precheck")).intValue(), ((Number) node.get("postcheck")).intValue(),
                    ((Number) node.get("stacheck")).intValue(), ((Number) node.get("fincheck")).intValue(),
                    timeLimit, ((Number) node.get("salary_limit")).longValue(),
                    ((Number) node.get("accessory_limit")).longValue(),
                    ((Number) node.get("account")).intValue(), planStart, cursor });
            totalLimit += timeLimit;
        }
        jdbc.update("""
                insert into construction_order(org_id, order_id, order_no, customer_name, house_label,
                    designer_id, period, created_by)
                values (?,?,?,?,?,?,?,?)
                """, orgId, orderId, order.orderNo(), order.customerName(), order.houseLabel(),
                order.designerId(), totalLimit, operatorId);
        jdbc.batchUpdate("""
                insert into construction_schedule(org_id, order_id, node_id, node_name, sort, process,
                    precheck, postcheck, stacheck, fincheck, time_limit, salary_limit, accessory_limit,
                    account, plan_start, plan_end)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, planRows);
        track(orgId, orderId, retroactive ? "retroactive" : "ignite", retroactive ? remark : null, operatorId);
        eventBus.publish(new ConstructionStartedEvent(constructionId(orgId, orderId), orgId, orderId, operatorId),
                "order:" + orderId + ":started");
    }

    private long constructionId(long orgId, long orderId) {
        return jdbc.queryForObject(
                "select id from construction_order where org_id = ? and order_id = ?",
                Long.class, orgId, orderId);
    }

    // ---- 节点推进 ----

    /** 开工:0→1;装修款比例闸 precheck(免检位跳过但必须已留痕);进度快照仅前推不回退;不写完工时间。 */
    @Transactional
    public void beginSchedule(long orgId, long scheduleId, long operatorId) {
        ScheduleRow row = loadSchedule(orgId, scheduleId);
        require(row.status() == SC_INIT, CODE_BAD_STATE, "construction.state.illegal");
        checkGate(orgId, row, row.precheck(), operatorId);
        checkSalesGate(orgId, row, row.stacheck(), operatorId);
        int updated = jdbc.update(
                "update construction_schedule set status = ?, start_time = now() where id = ? and status = ?",
                SC_BEGIN, scheduleId, SC_INIT);
        require(updated > 0, CODE_BAD_STATE, "construction.state.illegal");
        advanceProgress(orgId, row.orderId(), row.sort(), scheduleId);
        track(orgId, row.orderId(), "begin", row.nodeName(), operatorId);
        publishScheduleStatus(orgId, row, SC_INIT, SC_BEGIN, "begin", operatorId);
    }

    /** 完工:{1,3}→2;装修款比例闸 postcheck;全部节点完工不自动竣工(竣工唯一入口 = 验收合格)。 */
    @Transactional
    public void finishSchedule(long orgId, long scheduleId, long operatorId) {
        ScheduleRow row = loadSchedule(orgId, scheduleId);
        require(row.status() == SC_BEGIN || row.status() == SC_SALARY, CODE_BAD_STATE,
                "construction.state.illegal");
        checkGate(orgId, row, row.postcheck(), operatorId);
        checkSalesGate(orgId, row, row.fincheck(), operatorId);
        int updated = jdbc.update(
                "update construction_schedule set status = ?, end_time = now() where id = ? and status = ?",
                SC_FINISH, scheduleId, row.status());
        require(updated > 0, CODE_BAD_STATE, "construction.state.illegal");
        advanceProgress(orgId, row.orderId(), row.sort(), scheduleId);
        track(orgId, row.orderId(), "finish", row.nodeName(), operatorId);
        publishScheduleStatus(orgId, row, row.status(), SC_FINISH, "finish", operatorId);
    }

    /** 工费旁路:1→3(未完工先开放工费,3 可直接 2;salary 域就绪后由其消费 salary_limit)。 */
    @Transactional
    public void openSalary(long orgId, long scheduleId, long operatorId) {
        ScheduleRow row = loadSchedule(orgId, scheduleId);
        require(row.status() == SC_BEGIN, CODE_BAD_STATE, "construction.state.illegal");
        int updated = jdbc.update(
                "update construction_schedule set status = ? where id = ? and status = ?",
                SC_SALARY, scheduleId, SC_BEGIN);
        require(updated > 0, CODE_BAD_STATE, "construction.state.illegal");
        track(orgId, row.orderId(), "openSalary", row.nodeName(), operatorId);
        publishScheduleStatus(orgId, row, SC_BEGIN, SC_SALARY, "openSalary", operatorId);
    }

    /** 免检位:置 1 必带理由(留痕操作人;置回 0 不补校验,比例闸在后续动作照常生效)。 */
    @Transactional
    public void checkout(long orgId, long scheduleId, String reason, long operatorId) {
        ScheduleRow row = loadSchedule(orgId, scheduleId);
        require(notBlank(reason) && reason.trim().length() <= 200, CODE_PARAM, "construction.param.invalid");
        require(row.status() != SC_FINISH, CODE_BAD_STATE, "construction.state.illegal");
        jdbc.update("""
                update construction_schedule set checkout = ?, checkout_reason = ?, checkout_operator = ?
                where id = ? and status <> ?
                """, CHECKOUT_ON, reason.trim(), displayName(operatorId), scheduleId, SC_FINISH);
        track(orgId, row.orderId(), "checkout", row.nodeName() + ":" + reason.trim(), operatorId);
    }

    /** 删除:仅 0 初始态(老 120030 已开工不可删)。 */
    @Transactional
    public void deleteSchedule(long orgId, long scheduleId, long operatorId) {
        ScheduleRow row = loadSchedule(orgId, scheduleId);
        require(row.status() == SC_INIT, CODE_SCHEDULE_DELETE, "construction.schedule.delete");
        jdbc.update("delete from construction_schedule where id = ? and status = ?", scheduleId, SC_INIT);
        track(orgId, row.orderId(), "scheduleDelete", row.nodeName(), operatorId);
    }

    /** 锚点重排:锚点取实际开工时间优先(参数兜底),锚点后正向累加、锚点前反向回填;单一 ‰天口径。 */
    @Transactional
    public void resetSchedules(long orgId, long orderId, Long anchorScheduleId, String anchorTimeText,
            long operatorId) {
        loadConstruction(orgId, orderId);
        List<Map<String, Object>> schedules = jdbc.queryForList("""
                select id, node_name, sort, time_limit, status, start_time, plan_start, plan_end
                from construction_schedule where org_id = ? and order_id = ? order by sort
                """, orgId, orderId);
        require(!schedules.isEmpty(), CODE_NOT_FOUND, "construction.notfound");
        LocalDateTime anchorTime = parseTime(anchorTimeText);
        Map<String, Object> anchor = schedules.get(0);
        if (anchorScheduleId != null) {
            anchor = schedules.stream().filter(s -> ((Number) s.get("id")).longValue() == anchorScheduleId)
                    .findFirst().orElseThrow(() -> new BizException(CODE_NOT_FOUND, msg("construction.notfound")));
        }
        Object startTime = anchor.get("start_time");
        LocalDateTime base = toDateTime(startTime) != null ? toDateTime(startTime)
                : anchorTime != null ? anchorTime : toDateTime(anchor.get("plan_start"));
        require(base != null, CODE_PARAM, "construction.param.invalid");
        int anchorSort = ((Number) anchor.get("sort")).intValue();
        // 锚点及其后:正向累加
        LocalDateTime cursor = base;
        for (Map<String, Object> row : schedules) {
            int sort = ((Number) row.get("sort")).intValue();
            if (sort < anchorSort) {
                continue;
            }
            long limit = ((Number) row.get("time_limit")).longValue();
            LocalDateTime start = sort == anchorSort ? base : cursor;
            LocalDateTime end = start.plus(Duration.ofMillis(limit * MILLIS_PER_DAY / 1000));
            jdbc.update("update construction_schedule set plan_start = ?, plan_end = ? where id = ?",
                    start, end, ((Number) row.get("id")).longValue());
            cursor = end;
        }
        // 锚点前:反向回填
        LocalDateTime backward = base;
        for (int i = schedules.size() - 1; i >= 0; i--) {
            Map<String, Object> row = schedules.get(i);
            int sort = ((Number) row.get("sort")).intValue();
            if (sort >= anchorSort) {
                continue;
            }
            long limit = ((Number) row.get("time_limit")).longValue();
            LocalDateTime end = backward;
            LocalDateTime start = end.minus(Duration.ofMillis(limit * MILLIS_PER_DAY / 1000));
            jdbc.update("update construction_schedule set plan_start = ?, plan_end = ? where id = ?",
                    start, end, ((Number) row.get("id")).longValue());
            backward = start;
        }
        track(orgId, orderId, "scheduleReset", (String) anchor.get("node_name"), operatorId);
    }

    private void checkGate(long orgId, ScheduleRow row, int threshold, long operatorId) {
        if (threshold <= 0 || row.checkout() == CHECKOUT_ON) {
            return;
        }
        long ratio = collectedRatio(orgId, row.orderId());
        if (ratio < threshold) {
            throw new BizException(CODE_PAY_RATIO,
                    msgArgs("construction.gate.payRatio", row.nodeName(), threshold, ratio));
        }
    }

    /** 销售款比例闸(开工 stacheck/完工 fincheck):经 SalesApi 契约聚合装修单名下销售款进度。 */
    private void checkSalesGate(long orgId, ScheduleRow row, int threshold, long operatorId) {
        if (threshold <= 0 || row.checkout() == CHECKOUT_ON) {
            return;
        }
        long ratio = sales.collectedRatio(orgId, row.orderId());
        if (ratio < threshold) {
            throw new BizException(CODE_SALES_RATIO,
                    msgArgs("construction.gate.salesRatio", row.nodeName(), threshold, ratio));
        }
    }

    /** 收款比例(‰)= Σ已收款线 / Σ未作废应收款线;经 PaymentApi 契约,禁直查他域表。 */
    private long collectedRatio(long orgId, long orderId) {
        long denom = 0;
        long numer = 0;
        for (PaymentApi.BillSummary bill : payments.listBills(orgId, DECORATION_ADDON, DECORATION_TYPE,
                String.valueOf(orderId))) {
            if (bill.direction() != 0 || bill.status() == 3 || bill.status() == 4) {
                continue;
            }
            denom += bill.amount();
            if (bill.status() == 1 || bill.status() == 2) {
                numer += bill.amount();
            }
        }
        return denom == 0 ? 0 : numer * 1000 / denom;
    }

    /** 进度快照仅前推不回退(老 schedule/cnid 语义:sort 更大才前移)。 */
    private void advanceProgress(long orgId, long orderId, int sort, long scheduleId) {
        jdbc.update("""
                update construction_order set current_schedule_id = ?, current_sort = ?
                where org_id = ? and order_id = ? and current_sort < ?
                """, scheduleId, sort, orgId, orderId, sort);
    }

    // ---- 施工单:查询/监理/辅料开关 ----

    public Map<String, Object> page(long orgId, String orderNo, String customerName, Integer process,
            Long supervisorId, int pageNo, int size) {
        int safePage = Math.max(pageNo, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(" where org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        if (notBlank(orderNo)) {
            where.append(" and order_no like ?");
            args.add("%" + orderNo.trim() + "%");
        }
        if (notBlank(customerName)) {
            where.append(" and customer_name like ?");
            args.add("%" + customerName.trim() + "%");
        }
        if (process != null) {
            where.append(" and process = ?");
            args.add(process);
        }
        if (supervisorId != null) {
            where.append(" and supervisor_id = ?");
            args.add(supervisorId);
        }
        Long total = jdbc.queryForObject(
                "select count(*) from construction_order" + where, Long.class, args.toArray());
        List<Object> allArgs = new ArrayList<>(args);
        allArgs.add(safeSize);
        allArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, org_id, order_id, order_no, customer_name, house_label, supervisor_id,
                       supervisor_name, current_schedule_id, current_sort, process, accessory, period, created_at
                from construction_order""" + where + " order by id desc limit ? offset ?", allArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    public Map<String, Object> detail(long orgId, long orderId) {
        Map<String, Object> order = new LinkedHashMap<>(loadConstructionMap(orgId, orderId));
        long constructionOrderId = ((Number) order.remove("id")).longValue();
        List<Map<String, Object>> schedules = jdbc.queryForList("""
                select id, node_id, node_name, sort, process, status, checkout, checkout_reason, checkout_operator,
                       work_status, additem_status, plan_start, plan_end, start_time, end_time,
                       precheck, postcheck, time_limit, salary_limit, accessory_limit
                from construction_schedule where org_id = ? and order_id = ? order by sort
                """, orgId, orderId);
        // 节点工费/工日列(NormApi 契约,定额子域 R3):读取期聚合零落库;无定额实例=0
        for (Map<String, Object> schedule : schedules) {
            if (schedule.get("node_id") != null) {
                long nodeId = ((Number) schedule.get("node_id")).longValue();
                schedule.put("labor_fee", norms.laborFeeByNode(orgId, orderId, nodeId));
                schedule.put("workdays", norms.workdaysByNode(orgId, orderId, nodeId));
            }
        }
        order.put("schedules", schedules);
        order.put("drawlines", jdbc.queryForList("""
                select id, status, group_name, plan_start, plan_end, finish_time, is_retroactive,
                       operator_name, remark, created_at
                from construction_drawline where org_id = ? and order_id = ? order by id desc
                """, orgId, orderId));
        List<Map<String, Object>> acceptance = jdbc.queryForList("""
                select id, status, checker_name, check_time, remark, created_at
                from construction_acceptance where org_id = ? and order_id = ?
                """, orgId, orderId);
        order.put("acceptance", acceptance.isEmpty() ? null : acceptance.get(0));
        if (!acceptance.isEmpty()) {
            order.put("acceptanceItems", jdbc.queryForList("""
                    select name, pass, remark, sort from construction_acceptance_item
                    where acceptance_id = ? order by sort
                    """, acceptance.get(0).get("id")));
        }
        order.put("tracks", jdbc.queryForList("""
                select action, detail, operator_name, created_at from construction_order_track
                where org_id = ? and order_id = ? order by id desc limit 100
                """, orgId, orderId));
        order.put("constructionId", constructionOrderId);
        return order;
    }

    /** 更换监理(老六处联动中的合伙人三角解析系方林嫌疑沉插件,V1 只更本域+轨迹)。 */
    @Transactional
    public void updateSupervisor(long orgId, long orderId, long supervisorId, long operatorId) {
        loadConstruction(orgId, orderId);
        String name = displayName(supervisorId);
        jdbc.update("""
                update construction_order set supervisor_id = ?, supervisor_name = ?
                where org_id = ? and order_id = ?
                """, supervisorId, name, orgId, orderId);
        track(orgId, orderId, "supervisor", "→ " + name, operatorId);
    }

    /** 辅料库开关(消费方 addon-inventory;老 open/lock 无守卫,营造留痕轨迹)。 */
    @Transactional
    public void switchAccessory(long orgId, long orderId, boolean lock, long operatorId) {
        loadConstruction(orgId, orderId);
        jdbc.update("update construction_order set accessory = ? where org_id = ? and order_id = ?",
                lock ? 1 : 0, orgId, orderId);
        track(orgId, orderId, lock ? "accessoryLock" : "accessoryOpen", null, operatorId);
    }

    // ---- 验收(整改分支 + 全部合格竣工唯一) ----

    public record AcceptanceItemCmd(String name, Integer pass, String remark) {
    }

    @Transactional
    public void submitAcceptance(long orgId, long orderId, List<AcceptanceItemCmd> items, String remark,
            long operatorId) {
        loadConstruction(orgId, orderId);
        Integer done = jdbc.queryForObject("""
                select process from construction_order where org_id = ? and order_id = ?
                """, Integer.class, orgId, orderId);
        if (done != null && done == PROCESS_ACCEPTED) {
            throw new BizException(CODE_ACCEPT_DONE, msg("construction.accept.done"));
        }
        Map<String, Object> config = ensureConfig(orgId);
        int floor = ((Number) config.get("acceptance_floor")).intValue();
        long ratio = collectedRatio(orgId, orderId);
        if (ratio < floor) {
            throw new BizException(CODE_ACCEPT_RATIO,
                    msgArgs("construction.accept.ratio", floor, ratio));
        }
        require(items != null && !items.isEmpty(), CODE_PARAM, "construction.param.invalid");
        for (AcceptanceItemCmd item : items) {
            require(item != null && notBlank(item.name()) && item.name().trim().length() <= 200
                    && item.pass() != null && (item.pass() == 0 || item.pass() == 1),
                    CODE_PARAM, "construction.param.invalid");
        }
        boolean allPass = items.stream().allMatch(i -> i.pass() == 1);
        List<Long> found = jdbc.queryForList(
                "select id from construction_acceptance where org_id = ? and order_id = ?",
                Long.class, orgId, orderId);
        Long acceptanceId = found.isEmpty() ? null : found.get(0);
        if (acceptanceId != null) {
            Integer status = jdbc.queryForObject(
                    "select status from construction_acceptance where id = ?", Integer.class, acceptanceId);
            require(status == null || status == AC_RECTIFY, CODE_ACCEPT_DONE, "construction.accept.done");
            jdbc.update("delete from construction_acceptance_item where acceptance_id = ?", acceptanceId);
        } else {
            jdbc.update("""
                    insert into construction_acceptance(org_id, order_id, status, checker_id, checker_name)
                    values (?,?,?,?,?)
                    """, orgId, orderId, AC_RECTIFY, operatorId, displayName(operatorId));
            acceptanceId = jdbc.queryForObject(
                    "select last_insert_id()", Long.class);
        }
        for (int i = 0; i < items.size(); i++) {
            AcceptanceItemCmd item = items.get(i);
            jdbc.update("""
                    insert into construction_acceptance_item(acceptance_id, name, pass, remark, sort)
                    values (?,?,?,?,?)
                    """, acceptanceId, item.name().trim(), item.pass(), blankToNull(item.remark()), i + 1);
        }
        if (allPass) {
            jdbc.update("""
                    update construction_acceptance set status = ?, check_time = now(), remark = ?
                    where id = ? and status = ?
                    """, AC_ACCEPTED, blankToNull(remark), acceptanceId, AC_RECTIFY);
            int updated = jdbc.update("""
                    update construction_order set process = ? where org_id = ? and order_id = ? and process = ?
                    """, PROCESS_ACCEPTED, orgId, orderId, PROCESS_RUNNING);
            require(updated > 0, CODE_ACCEPT_DONE, "construction.accept.done");
            track(orgId, orderId, "accept", null, operatorId);
            eventBus.publish(new ConstructionAcceptedEvent(constructionId(orgId, orderId), orgId, orderId,
                    acceptanceId, operatorId), "order:" + orderId + ":accepted");
        } else {
            jdbc.update("update construction_acceptance set status = ?, remark = ? where id = ?",
                    AC_RECTIFY, blankToNull(remark), acceptanceId);
            track(orgId, orderId, "acceptRectify", null, operatorId);
        }
    }

    public Map<String, Object> getAcceptance(long orgId, long orderId) {
        loadConstruction(orgId, orderId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, status, checker_name, check_time, remark, created_at
                from construction_acceptance where org_id = ? and order_id = ?
                """, orgId, orderId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        result.put("items", jdbc.queryForList("""
                select name, pass, remark, sort from construction_acceptance_item
                where acceptance_id = ? order by sort
                """, rows.get(0).get("id")));
        return result;
    }

    // ---- 档案/团队委托(profile/team service 持表,编排入口在本域) ----

    public ConstructionTeamService team() {
        return teams;
    }

    public ConstructionProfileService profile() {
        return profiles;
    }

    // ---- 内部 ----

    private void requireDecoration(long orgId, long orderId) {
        decorations.queryOrder(orgId, orderId).orElseThrow(() -> new BizException(
                CODE_DECORATION_NOT_FOUND, msg("construction.drawline.decorationNotFound")));
    }

    private void requireNoActiveDrawline(long orgId, long orderId) {
        Integer active = jdbc.queryForObject("""
                select count(*) from construction_drawline
                where org_id = ? and order_id = ? and status in (?, ?)
                """, Integer.class, orgId, orderId, DL_QUEUED, DL_SCHEDULED);
        require(active == null || active == 0, CODE_DUP_ACTIVE, "construction.drawline.dupActive");
    }

    private Map<String, Object> loadDrawline(long orgId, long drawlineId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, order_id, status from construction_drawline where id = ? and org_id = ?
                """, drawlineId, orgId);
        require(!rows.isEmpty(), CODE_NOT_FOUND, "construction.notfound");
        return rows.get(0);
    }

    private ScheduleRow loadSchedule(long orgId, long scheduleId) {
        List<ScheduleRow> rows = jdbc.query("""
                select id, order_id, node_name, sort, precheck, postcheck, status, checkout,
                       stacheck, fincheck, work_status
                from construction_schedule where id = ? and org_id = ?
                """, (rs, rowNum) -> new ScheduleRow(rs.getLong("id"), rs.getLong("order_id"),
                rs.getString("node_name"), rs.getInt("sort"), rs.getInt("precheck"), rs.getInt("postcheck"),
                rs.getInt("status"), rs.getInt("checkout"), rs.getInt("stacheck"), rs.getInt("fincheck"),
                rs.getObject("work_status") == null ? null : rs.getInt("work_status")),
                scheduleId, orgId);
        require(!rows.isEmpty(), CODE_NOT_FOUND, "construction.notfound");
        return rows.get(0);
    }

    private ConstructionRow loadConstruction(long orgId, long orderId) {
        List<ConstructionRow> rows = jdbc.query("""
                select order_id, order_no, process, current_sort, designer_id, supervisor_id
                from construction_order where org_id = ? and order_id = ?
                """, (rs, rowNum) -> new ConstructionRow(rs.getLong("order_id"), rs.getString("order_no"),
                rs.getInt("process"), rs.getInt("current_sort"), rs.getLong("designer_id"),
                rs.getObject("supervisor_id") == null ? null : rs.getLong("supervisor_id")),
                orgId, orderId);
        require(!rows.isEmpty(), CODE_NOT_FOUND, "construction.notfound");
        return rows.get(0);
    }

    private Map<String, Object> loadConstructionMap(long orgId, long orderId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, order_id, order_no, customer_name, house_label, designer_id, supervisor_id,
                       supervisor_name, current_schedule_id, current_sort, process, accessory, period, created_at
                from construction_order where org_id = ? and order_id = ?
                """, orgId, orderId);
        require(!rows.isEmpty(), CODE_NOT_FOUND, "construction.notfound");
        return rows.get(0);
    }

    private void publishScheduleStatus(long orgId, ScheduleRow row, int from, int to, String action,
            long operatorId) {
        eventBus.publish(new ConstructionScheduleStatusChangedEvent(
                        constructionId(orgId, row.orderId()), orgId, row.orderId(), row.id(),
                        row.nodeName(), from, to, action, operatorId),
                "schedule:" + row.id() + ":" + action);
    }

    private void track(long orgId, long orderId, String action, String detail, long operatorId) {
        jdbc.update("""
                insert into construction_order_track(org_id, order_id, action, detail, operator_id, operator_name)
                values (?,?,?,?,?,?)
                """, orgId, orderId, action, blankToNull(detail), operatorId, displayName(operatorId));
    }

    private LocalDateTime parseTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text.trim().replace(' ', 'T'));
        } catch (DateTimeParseException e) {
            throw new BizException(CODE_PARAM, msg("construction.param.invalid"));
        }
    }

    /** JDBC 读回 datetime 为 Timestamp,统一转 LocalDateTime。 */
    private LocalDateTime toDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime dt) {
            return dt;
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        return null;
    }

    private String displayName(long userId) {
        return jdbc.queryForObject("select display_name from yz_user where id = ?", String.class, userId);
    }

    private boolean inRange(Integer value, int max) {
        return value != null && value >= 0 && value <= max;
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private String msgArgs(String key, Object... args) {
        return messages.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    private void require(boolean cond, int code, String msgKey) {
        if (!cond) {
            throw new BizException(code, msg(msgKey));
        }
    }
}
