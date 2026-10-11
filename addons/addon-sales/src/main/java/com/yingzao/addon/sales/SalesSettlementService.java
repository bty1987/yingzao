package com.yingzao.addon.sales;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.core.web.BizException;

/**
 * 厂商结算三段式(预结→厂结→结算):行轴 0预结→1厂结→2结算,主单轴 = 行最小态映射
 * (全部行1→主单2,全部行2→主单3)。双值人工修正保留:vbalance 厂结前可改、pbalance 厂结态可改,
 * 服务端强校验非零且与金额同向(修老"厂结值未填拦截纯前端"缺陷,API 直调带 0 过不去);
 * 老三套让利公式量纲不自洽不迁:V1 balance=total(让利源 V2),difference=total-balance 恒 0;
 * 驳回 = 原子删除该单全部预继行+主单回 0(修老只删当前行兄弟品牌残留可复活)。
 */
@Service
public class SalesSettlementService {

    public static final int CODE_NOT_FOUND = 300013;
    public static final int CODE_VBALANCE = 300014;
    public static final int CODE_BAD_STATE = 300015;
    public static final int CODE_REJECT_GUARD = 300031;

    // 行类型
    public static final int TYPE_PURCHASE = 0;
    public static final int TYPE_REFUND_PRE = 1;
    public static final int TYPE_REFUND_SETTLE = 2;
    public static final int TYPE_DEPOSIT = 3;

    private final JdbcTemplate jdbc;
    private final SalesOrderService orders;

    public SalesSettlementService(JdbcTemplate jdbc, SalesOrderService orders) {
        this.jdbc = jdbc;
        this.orders = orders;
    }

    public Map<String, Object> page(long orgId, String saleCode, Integer status, Integer type,
            int pageNo, int size) {
        int safePage = Math.max(pageNo, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(
                " where s.org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        if (SalesOrderService.notBlank(saleCode)) {
            where.append(" and o.code like ?");
            args.add("%" + saleCode.trim() + "%");
        }
        if (status != null) {
            where.append(" and s.status = ?");
            args.add(status);
        }
        if (type != null) {
            where.append(" and s.type = ?");
            args.add(type);
        }
        String w = where.toString();
        Long count = jdbc.queryForObject(
                "select count(*) from sales_settlement s join sales_order o on o.id = s.sale_id" + w,
                Long.class, args.toArray());
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select s.id, s.sale_id, o.code as sale_code, o.order_no, o.customer_name,
                       s.brand_id, s.brand_name, s.type, s.onetype, s.total, s.balance, s.vbalance,
                       s.pbalance, s.difference, s.status, s.vposted_at, s.posted_at, s.created_at
                from sales_settlement s join sales_order o on o.id = s.sale_id"""
                + w + " order by s.id desc limit ? offset ?",
                SalesOrderService.appendArgs(args, safeSize, (safePage - 1) * safeSize));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", count == null ? 0 : count);
        result.put("rows", rows);
        return result;
    }

    /** 改厂结值(仅预继行;非零且与金额同向——服务端强校验,修老前端闸)。 */
    @Transactional
    public void updateVbalance(long orgId, long id, long vbalance, long operatorId) {
        Map<String, Object> row = load(orgId, id);
        requireStatus(row, 0);
        requireSameSign(row, vbalance);
        jdbc.update("update sales_settlement set vbalance = ? where id = ?", vbalance, id);
        trackSale(row, "vbalance", "调整厂结值 " + vbalance, operatorId);
    }

    /** 厂结:行 0→1(条件 UPDATE 并发只成功一次),主单轴随行最小态推进。 */
    @Transactional
    public void vpost(long orgId, long id, long operatorId) {
        Map<String, Object> row = load(orgId, id);
        requireStatus(row, 0);
        requireSameSign(row, ((Number) row.get("vbalance")).longValue());
        int updated = jdbc.update("""
                update sales_settlement set status = 1, vposted_by = ?, vposted_at = now()
                where id = ? and org_id = ? and status = 0
                """, operatorId, id, orgId);
        if (updated == 0) {
            throw new BizException(CODE_BAD_STATE, orders.msg("sales.state.illegal"));
        }
        recomputeBalanceStatus(orgId, ((Number) row.get("sale_id")).longValue());
        trackSale(row, "vpost", "厂结", operatorId);
    }

    /** 改结算值(仅厂继行)。 */
    @Transactional
    public void updatePbalance(long orgId, long id, long pbalance, long operatorId) {
        Map<String, Object> row = load(orgId, id);
        requireStatus(row, 1);
        requireSameSign(row, pbalance);
        jdbc.update("update sales_settlement set pbalance = ? where id = ?", pbalance, id);
        trackSale(row, "pbalance", "调整结算值 " + pbalance, operatorId);
    }

    /** 结算:行 1→2;全部行结算后主单轴 3(终态)。 */
    @Transactional
    public void post(long orgId, long id, long operatorId) {
        Map<String, Object> row = load(orgId, id);
        requireStatus(row, 1);
        requireSameSign(row, ((Number) row.get("pbalance")).longValue());
        int updated = jdbc.update("""
                update sales_settlement set status = 2, posted_by = ?, posted_at = now()
                where id = ? and org_id = ? and status = 1
                """, operatorId, id, orgId);
        if (updated == 0) {
            throw new BizException(CODE_BAD_STATE, orders.msg("sales.state.illegal"));
        }
        recomputeBalanceStatus(orgId, ((Number) row.get("sale_id")).longValue());
        trackSale(row, "post", "结算", operatorId);
    }

    /** 驳回(整单原子):该单全部预继行(0 态)物理删除+主单结算轴回 0;任一行已厂结则拒绝。 */
    @Transactional
    public void reject(long orgId, long saleId, String reason, long operatorId) {
        Map<String, Object> sale = orders.loadSale(orgId, saleId);
        Integer posted = jdbc.queryForObject("""
                select count(*) from sales_settlement
                where org_id = ? and sale_id = ? and status > 0
                """, Integer.class, orgId, saleId);
        if (posted != null && posted > 0) {
            throw new BizException(CODE_REJECT_GUARD, orders.msg("sales.settle.rejectGuard"));
        }
        Integer rows = jdbc.queryForObject(
                "select count(*) from sales_settlement where org_id = ? and sale_id = ?",
                Integer.class, orgId, saleId);
        if (rows == null || rows == 0) {
            throw new BizException(CODE_REJECT_GUARD, orders.msg("sales.settle.rejectGuard"));
        }
        jdbc.update("delete from sales_settlement where org_id = ? and sale_id = ? and status = 0",
                orgId, saleId);
        jdbc.update("update sales_order set balance_status = 0 where id = ? and balance_status = 1",
                saleId);
        orders.track(orgId, saleId, "rejectSettle",
                "驳回预结" + (SalesOrderService.notBlank(reason) ? ":" + reason.trim() : ""), operatorId);
    }

    /** 主单结算轴 = 行最小态(有行:0→1/1→2/2→3);逐行操作后重算。 */
    private void recomputeBalanceStatus(long orgId, long saleId) {
        Integer minStatus = jdbc.queryForObject(
                "select min(status) from sales_settlement where org_id = ? and sale_id = ?",
                Integer.class, orgId, saleId);
        if (minStatus == null) {
            return;
        }
        int target = minStatus == 0 ? 1 : minStatus == 1 ? 2 : 3;
        jdbc.update("""
                update sales_order set balance_status = ?
                where id = ? and org_id = ? and balance_status < ? and status <> 4
                """, target, saleId, orgId, target);
    }

    private Map<String, Object> load(long orgId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from sales_settlement where id = ? and org_id = ?", id, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_NOT_FOUND, orders.msg("sales.settle.notFound"));
        }
        return rows.get(0);
    }

    private void requireStatus(Map<String, Object> row, int expected) {
        if (((Number) row.get("status")).intValue() != expected) {
            throw new BizException(CODE_BAD_STATE, orders.msg("sales.state.illegal"));
        }
    }

    /** 非零且与 total 同向(退货行负值合法,零值恒拒)。 */
    private void requireSameSign(Map<String, Object> row, long value) {
        long total = ((Number) row.get("total")).longValue();
        if (value == 0 || total == 0 || (value > 0) != (total > 0)) {
            throw new BizException(CODE_VBALANCE, orders.msg("sales.settle.vbalance"));
        }
    }

    private void trackSale(Map<String, Object> row, String action, String detail, long operatorId) {
        orders.track(((Number) row.get("org_id")).longValue(),
                ((Number) row.get("sale_id")).longValue(), action, detail, operatorId);
    }
}
