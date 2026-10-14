package com.yingzao.addon.member;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.addons.api.CustomerProfileApi;
import com.yingzao.addons.api.DecorationApi;
import com.yingzao.addons.api.SalesApi;
import com.yingzao.core.web.BizException;

/**
 * 积分分项账户服务(事件驱动记账,废除老系统"实时 SQL 聚合 9 条"口径)。
 * 红线:①量纲——积分=整数,唯一换算点=记分(款项厘 × multiple ÷ 1000,HALF_UP),
 * 消灭老 Arith.DEF_SCALE=3 陷阱(mul(x,1e6) 实际=×1000、div(v,1000) 恒等)与两域 1000 倍差;
 * ②余额变动一律条件 UPDATE(修老 read-modify-write 双写互踩);③流水只追加,
 * uk(org,src_addon,src_type,src_id,type)=事件幂等锚(总线重放不重复记账);
 * ④过期语义=每笔记分批次自带 expire_time(收款确认+活动 validays 天),过期只拦兑换/核销不清零
 * (沿老系统无清零任务,流水保留将来清零钩子)。
 * 消费器入口(award/refundDeduct)抛 IllegalStateException = 可修复基础设施类失败:
 * 交总线退避重试、超限死信可见,运营修复(配默认活动)后 requeue 补记——
 * 不用 BizException(那是"重放不会变好"的守卫失配语义)。
 */
@Service
public class MemberPointService {

    private static final Logger log = LoggerFactory.getLogger(MemberPointService.class);

    public static final int CODE_INSUFFICIENT = 350006;
    public static final int CODE_EXPIRED = 350009;
    public static final int CODE_REASON = 350014;
    public static final int CODE_CUSTOMER = 350015;
    public static final int CODE_NOT_ALLOWED = 350017;
    public static final int CODE_DEDUCT = 350019;
    public static final int CODE_PARAM = 350020;

    /** 积分流水类型。 */
    public static final int REC_AWARD = 1;
    public static final int REC_EXCHANGE = 2;
    public static final int REC_REVOKE = 3;
    public static final int REC_REFUND = 4;
    public static final int REC_ADJUST = 5;
    /** 过期清算负行(V1.1):src_type=expire_clear,src_id=被清算正行 id(uk 幂等锚)。 */
    public static final int REC_EXPIRE_CLEAR = 6;

    public static final int TYPE_DECORATION = 1;
    public static final int TYPE_MATERIAL = 2;
    public static final int TYPE_OPERATION = 3;

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final MemberActivityService activities;
    private final DecorationApi decorations;
    private final SalesApi sales;
    private final CustomerProfileApi profiles;

    public MemberPointService(JdbcTemplate jdbc, MessageSource messages, MemberActivityService activities,
            DecorationApi decorations, SalesApi sales, CustomerProfileApi profiles) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.activities = activities;
        this.decorations = decorations;
        this.sales = sales;
        this.profiles = profiles;
    }

    // ---- 消费器入口(事件驱动记账) ----

    /** 账户定位(懒开户;兑换单扣减用,customerName 传 null 不回填快照)。 */
    public long accountIdFor(long orgId, long customerId, int pointType) {
        return accountOf(orgId, customerId, null, pointType);
    }

    /**
     * 收款确认记分。pointType 由款项 bizAddon 映射:decoration→装修(1)/sales→主材(2),其余域不记。
     * 记分积分 = 款项(厘) × 活动倍率(千分位) ÷ 1000,HALF_UP 取整;倍率/有效期取当前默认活动快照。
     * 幂等锚 src_id = 款项单 id(billId,每笔唯一)——同一业务单(如装修单)的多笔款项
     * (定金/合同款)各自独立记账,锚不能用业务单号(否则第二笔被第一笔吞掉,积分少记)。
     * 客户归属仍按 biz 三元组经只读契约反查。自持事务:余额变动与流水同生共死,重放撞 uk 整笔回滚。
     */
    @Transactional
    public void award(long orgId, String bizAddon, Long billId, String bizType, String bizId, long amount) {
        int pointType = pointTypeOf(bizAddon);
        if (pointType == 0) {
            return;
        }
        requireBill(billId);
        String srcId = String.valueOf(billId);
        if (recordExists(orgId, "payment", "bill", srcId, REC_AWARD)) {
            return;
        }
        String[] ref = resolveCustomer(orgId, bizAddon, bizType, bizId);
        long customerId = Long.parseLong(ref[0]);
        String customerName = ref[1];
        Map<String, Object> activity = activities.activeDefault(orgId, pointType);
        if (activity == null) {
            throw new IllegalStateException(
                    "member default activity missing: org=%d pointType=%d (configure then requeue)"
                            .formatted(orgId, pointType));
        }
        int multiple = ((Number) activity.get("multiple")).intValue();
        int validays = ((Number) activity.get("validays")).intValue();
        long integral = BigDecimal.valueOf(amount)
                .multiply(BigDecimal.valueOf(multiple))
                .divide(BigDecimal.valueOf(1000), 0, RoundingMode.HALF_UP)
                .longValueExact();
        if (integral <= 0) {
            log.info("member award skipped: integral<=0, org={} bill={}", orgId, bizId);
            return;
        }
        long activityId = ((Number) activity.get("id")).longValue();
        LocalDateTime expire = LocalDateTime.now().plusDays(validays);
        long accountId = accountOf(orgId, customerId, customerName, pointType);
        jdbc.update("update member_point_account set balance = balance + ? where id = ?", integral, accountId);
        long balanceAfter = currentBalance(accountId);
        try {
            jdbc.update("""
                    insert into member_point_record
                        (org_id, customer_id, point_type, type, delta, balance_after, expire_time,
                         activity_id, src_addon, src_type, src_id, created_by)
                    values (?,?,?,?,?,?,?,?,?,?,?,?)
                    """, orgId, customerId, pointType, REC_AWARD, integral, balanceAfter, expire,
                    activityId, "payment", "bill", srcId, (Long) null);
        } catch (DuplicateKeyException e) {
            // 并发重放撞幂等锚:整笔事务回滚(balance+= 一并回滚),先提交者已记账,本笔幂等放弃。
            // 异常继续上抛 → 总线重放时 recordExists 命中直接幂等回显。
            throw e;
        }
    }

    /**
     * 退款冲减(整单退款语义:冲减额=原记分批次 delta;原批次不存在=该笔本就未记分,静默跳过)。
     * 自持事务:冲减与流水同生共死。
     */
    @Transactional
    public void refundDeduct(long orgId, String originBillId, String refundBillId) {
        if (recordExists(orgId, "payment", "refund", refundBillId, REC_REFUND)) {
            return;
        }
        List<Map<String, Object>> origins = jdbc.queryForList("""
                select customer_id, point_type, delta from member_point_record
                where org_id = ? and src_addon = 'payment' and src_type = 'bill' and src_id = ? and type = ?
                """, orgId, originBillId, REC_AWARD);
        if (origins.isEmpty()) {
            log.error("member refund deduct skipped: no award record, org={} originBill={}", orgId, originBillId);
            return;
        }
        Map<String, Object> origin = origins.get(0);
        long customerId = ((Number) origin.get("customer_id")).longValue();
        int pointType = ((Number) origin.get("point_type")).intValue();
        long delta = ((Number) origin.get("delta")).longValue();
        if (delta <= 0) {
            return;
        }
        long accountId = accountOf(orgId, customerId, null, pointType);
        int updated = jdbc.update(
                "update member_point_account set balance = balance - ? where id = ? and balance >= ?",
                delta, accountId, delta);
        if (updated == 0) {
            // 账务异常(已兑积分多于可冲额度):可见死信,不静默吞
            throw new IllegalStateException(
                    "member point balance insufficient for refund deduct: org=%d customer=%d delta=%d"
                            .formatted(orgId, customerId, delta));
        }
        long balanceAfter = currentBalance(accountId);
        jdbc.update("""
                insert into member_point_record
                    (org_id, customer_id, point_type, type, delta, balance_after, src_addon, src_type, src_id)
                values (?,?,?,?,?,?,?,?,?)
                """, orgId, customerId, pointType, REC_REFUND, -delta, balanceAfter, "payment", "refund", refundBillId);
    }

    // ---- 运营手工调整 ----

    /** 运营调整(外部商城对账入口):原因必填留痕;正增负扣(下限 0,禁负余额)。 */
    @Transactional
    public long adjust(long orgId, Long customerId, Integer pointType, Long delta, String reason, Long operatorId) {
        require(customerId != null && customerId > 0, CODE_PARAM, "param.invalid");
        require(pointType != null && pointType >= TYPE_DECORATION && pointType <= TYPE_OPERATION,
                CODE_PARAM, "param.invalid");
        require(delta != null && delta != 0, CODE_PARAM, "param.invalid");
        require(reason != null && !reason.isBlank(), CODE_REASON, "point.adjust.reason");
        var customer = profiles.queryCustomer(orgId, customerId);
        require(customer.isPresent(), CODE_CUSTOMER, "customer.notfound");
        String customerName = customer.get().name();
        long accountId = accountOf(orgId, customerId, customerName, pointType);
        if (delta > 0) {
            jdbc.update("update member_point_account set balance = balance + ? where id = ?", delta, accountId);
        } else {
            int updated = jdbc.update(
                    "update member_point_account set balance = balance + ? where id = ? and balance >= ?",
                    delta, accountId, -delta);
            if (updated == 0) {
                throw new BizException(CODE_DEDUCT, msg("point.deduct"));
            }
        }
        long balanceAfter = currentBalance(accountId);
        jdbc.update("""
                insert into member_point_record
                    (org_id, customer_id, point_type, type, delta, balance_after, reason, created_by)
                values (?,?,?,?,?,?,?,?)
                """, orgId, customerId, pointType, REC_ADJUST, delta, balanceAfter, reason.trim(), operatorId);
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    // ---- 过期清算(V1.1 钩子) ----

    /**
     * 过期清算:对已过期正向批次逐行生成清算负行(REC_EXPIRE_CLEAR,src_type=expire_clear,
     * src_id=原正行 id 作 uk 幂等锚,重放撞唯一键跳过),使 balance 向 available 收敛。
     * 负行永不过期(expire_time NULL),清算后批次余额归零但流水全程可溯。
     * 返回 {customers, cleared}:本次触达客户数与清算行数(重放幂等:已清算行计 0)。
     */
    @Transactional
    public Map<String, Object> expireClear(long orgId) {
        List<Map<String, Object>> expired = jdbc.queryForList("""
                select r.id, r.customer_id, r.point_type, r.delta
                from member_point_record r
                where r.org_id = ? and r.delta > 0
                  and r.expire_time is not null and r.expire_time <= now()
                  and not exists (
                      select 1 from member_point_record c
                      where c.org_id = r.org_id and c.customer_id = r.customer_id
                        and c.point_type = r.point_type and c.type = ?
                        and c.src_type = 'expire_clear' and c.src_id = cast(r.id as char))
                order by r.customer_id, r.point_type, r.id
                """, orgId, REC_EXPIRE_CLEAR);
        int cleared = 0;
        Set<Long> customers = new HashSet<>();
        for (Map<String, Object> row : expired) {
            long recId = ((Number) row.get("id")).longValue();
            long customerId = ((Number) row.get("customer_id")).longValue();
            int pointType = ((Number) row.get("point_type")).intValue();
            long delta = ((Number) row.get("delta")).longValue();
            long accountId = accountOf(orgId, customerId, customerNameOf(orgId, customerId), pointType);
            int deducted = jdbc.update(
                    "update member_point_account set balance = balance - ? where id = ? and balance >= ?",
                    delta, accountId, delta);
            if (deducted == 0) {
                // 账面余额已不足以覆盖该过期批次(此前已被兑换消耗)——清算跳过,不产生负行
                continue;
            }
            try {
                jdbc.update("""
                        insert into member_point_record
                            (org_id, customer_id, point_type, type, delta, balance_after,
                             src_addon, src_type, src_id)
                        values (?,?,?,?,?,?,?,?,?)
                        """, orgId, customerId, pointType, REC_EXPIRE_CLEAR, -delta,
                        currentBalance(accountId), "member", "expire_clear", String.valueOf(recId));
                cleared += 1;
                customers.add(customerId);
            } catch (DuplicateKeyException e) {
                // 重放:该批次已清算过(uk 幂等锚命中)——回滚本行扣减不一致风险由事务整体保证
                throw e;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cleared", cleared);
        out.put("customers", customers.size());
        out.put("candidates", expired.size());
        return out;
    }

    private String customerNameOf(long orgId, long customerId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select name from customerprofile_customer where id = ? and org_id = ?",
                customerId, orgId);
        return rows.isEmpty() ? "客户" + customerId : (String) rows.get(0).get("name");
    }

    // ---- 可用额度(兑换/核销闸) ----

    /**
     * 可用积分 = Σ(delta>0 且已过期批次记 0,其余照算)。负向行(兑换/冲减)不参与过期过滤。
     */
    public long available(long orgId, long customerId, int pointType) {
        Long v = jdbc.queryForObject("""
                select coalesce(sum(case when delta > 0 and expire_time is not null and expire_time <= now()
                        then 0 else delta end), 0)
                from member_point_record
                where org_id = ? and customer_id = ? and point_type = ?
                """, Long.class, orgId, customerId, pointType);
        return v == null ? 0 : v;
    }

    /** 余额(含过期批次,展示口径;过期只拦兑换不清零)。 */
    public long balance(long orgId, long customerId, int pointType) {
        Long v = jdbc.queryForObject(
                "select coalesce(sum(delta), 0) from member_point_record where org_id = ? and customer_id = ? and point_type = ?",
                Long.class, orgId, customerId, pointType);
        return v == null ? 0 : v;
    }

    /** 可用批次中最早过期时刻(兑换单落单时快照到明细;无批次/全部永不过期= null)。 */
    public LocalDateTime earliestExpire(long orgId, long customerId, int pointType) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select min(expire_time) as min_expire from member_point_record
                where org_id = ? and customer_id = ? and point_type = ? and delta > 0 and expire_time > now()
                """, orgId, customerId, pointType);
        Object v = rows.isEmpty() ? null : rows.get(0).get("min_expire");
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        return null;
    }

    // ---- 查询 ----

    public Map<String, Object> accounts(long orgId, Long customerId, int pageNum, int size) {
        int safePage = Math.max(pageNum, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(" where a.org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        if (customerId != null) {
            where.append(" and a.customer_id = ?");
            args.add(customerId);
        }
        Long total = jdbc.queryForObject(
                "select count(*) from member_point_account a" + where, Long.class, args.toArray());
        List<Object> allArgs = new ArrayList<>(args);
        allArgs.add(safeSize);
        allArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select a.id, a.org_id, a.customer_id, a.customer_name, a.point_type, a.balance,
                       a.created_at, a.updated_at
                from member_point_account a""" + where + " order by a.id desc limit ? offset ?", allArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    public Map<String, Object> records(long orgId, long customerId, Integer pointType, int pageNum, int size) {
        int safePage = Math.max(pageNum, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(" where org_id = ? and customer_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        args.add(customerId);
        if (pointType != null) {
            where.append(" and point_type = ?");
            args.add(pointType);
        }
        Long total = jdbc.queryForObject(
                "select count(*) from member_point_record" + where, Long.class, args.toArray());
        List<Object> allArgs = new ArrayList<>(args);
        allArgs.add(safeSize);
        allArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, customer_id, point_type, type, delta, balance_after, expire_time, activity_id,"
                + " src_addon, src_type, src_id, reason, created_by, created_at from member_point_record" + where
                + " order by id desc limit ? offset ?", allArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    // ---- 内部 ----

    /** 懒开户(insert ignore + 兜底重查,修老懒开户竞态)。 */
    private long accountOf(long orgId, long customerId, String customerName, int pointType) {
        List<Long> exist = jdbc.queryForList("""
                select id from member_point_account
                where org_id = ? and customer_id = ? and point_type = ?
                """, Long.class, orgId, customerId, pointType);
        if (!exist.isEmpty()) {
            if (customerName != null && !customerName.isBlank()) {
                jdbc.update("""
                        update member_point_account set customer_name = ?
                        where id = ? and (customer_name is null or customer_name = '')
                        """, customerName, exist.get(0));
            }
            return exist.get(0);
        }
        try {
            jdbc.update("""
                    insert ignore into member_point_account (org_id, customer_id, customer_name, point_type, balance)
                    values (?,?,?,?,0)
                    """, orgId, customerId, customerName, pointType);
        } catch (DuplicateKeyException ignored) {
            // 并发开户撞 uk:兜底重查
        }
        List<Long> rows = jdbc.queryForList("""
                select id from member_point_account
                where org_id = ? and customer_id = ? and point_type = ?
                """, Long.class, orgId, customerId, pointType);
        if (rows.isEmpty()) {
            throw new IllegalStateException("member point account open failed: org=%d customer=%d"
                    .formatted(orgId, customerId));
        }
        return rows.get(0);
    }

    /** 款项 bizAddon → 分项:decoration→1 / sales→2,其余域不记分。 */
    private int pointTypeOf(String bizAddon) {
        if ("decoration".equals(bizAddon)) {
            return TYPE_DECORATION;
        }
        if ("sales".equals(bizAddon)) {
            return TYPE_MATERIAL;
        }
        return 0;
    }

    /** 按款项 biz 三元组反查归属客户(走只读契约;销售单经装修单二跳)。 */
    private String[] resolveCustomer(long orgId, String bizAddon, String bizType, String bizId) {
        long bizRef;
        try {
            bizRef = Long.parseLong(bizId);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("member award unparsable bizId=" + bizId);
        }
        var ref = "decoration".equals(bizAddon)
                ? decorations.queryOrderCustomer(orgId, bizRef)
                : sales.queryOrderCustomer(orgId, bizRef);
        if (ref.isEmpty()) {
            throw new IllegalStateException(
                    "member award customer unresolved: org=%s biz=%s/%s/%s (upstream data inconsistent?)"
                            .formatted(orgId, bizAddon, bizType, bizId));
        }
        return new String[] { String.valueOf(ref.get().customerId()), ref.get().customerName() };
    }

    private void requireBill(Long billId) {
        if (billId == null || billId <= 0) {
            throw new IllegalStateException("member award missing billId (payment contract changed?)");
        }
    }

    private boolean recordExists(long orgId, String srcAddon, String srcType, String srcId, int type) {        Integer cnt = jdbc.queryForObject("""
                select count(*) from member_point_record
                where org_id = ? and src_addon = ? and src_type = ? and src_id = ? and type = ?
                """, Integer.class, orgId, srcAddon, srcType, srcId, type);
        return cnt != null && cnt > 0;
    }

    private long currentBalance(long accountId) {
        return jdbc.queryForObject("select balance from member_point_account where id = ?", Long.class, accountId);
    }

    private void require(boolean cond, int code, String msgKey) {
        if (!cond) {
            throw new BizException(code, msg(msgKey));
        }
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
