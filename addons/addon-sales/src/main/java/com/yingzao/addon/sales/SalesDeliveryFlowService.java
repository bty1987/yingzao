package com.yingzao.addon.sales;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.addons.api.BrandApi;
import com.yingzao.core.web.BizException;

/**
 * B2 配送域 V2:品牌配送流程模板 + 里程碑分批结算(行业中性化,docs/prd/addon-sales-delivery.md)。
 * 拆老 checked+crate 两义同表:免人工位(auto_tran/check/confirm,finish 恒人工防自答)与
 * 里程碑分成(rate 万分比,Σ=10000 强守卫)正交配置,一行一品牌;
 * 分批行 = 认领计划视图(预结行三段式全额不动,零资金轴改动):milestone 到达即按计划生成分批行
 * (幂等 uk),非末批=五列×rate 逐列 HALF_UP,末批=总额−已批合计补尾差(修老 Σcrate 不校验漂移);
 * 预结未生成时跳过(计划缓存),预结生成/计划变更时未认领部分重算(已认领不回溯——诚实边界)。
 * 自包含零环:不依赖 SalesOrderService(track/文案本地实现,sales_track 为本域自有表)。
 */
@Service
public class SalesDeliveryFlowService {

    public static final int CODE_BRAND = 300200;
    public static final int CODE_RATE_SUM = 300201;
    public static final int CODE_AUTO_FLAG = 300202;
    public static final int CODE_MILESTONE = 300203;
    public static final int CODE_BATCH_LOCKED = 300204;
    public static final int CODE_RATE = 300205;

    /** 里程碑(1-4)与配送四级对齐(操作级映射,业务里程碑随配送域 V3)。 */
    public static final int MS_TRAN = 1;
    public static final int MS_CHECK = 2;
    public static final int MS_CONFIRM = 3;
    public static final int MS_FINISH = 4;

    private static final String[] RATE_COLUMNS = {"rate_tran", "rate_check", "rate_confirm", "rate_finish"};
    private static final String[] MONEY_COLUMNS = {"total", "balance", "vbalance", "pbalance", "difference"};

    public record RateItem(Integer milestone, Integer rate) {
    }

    /** flow(免人工位)+ rates(四级分成)一体提交;rates 空/全零 = 分成未启用。 */
    public record FlowBody(Integer autoTran, Integer autoCheck, Integer autoConfirm, List<RateItem> rates) {
    }

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final BrandApi brands;

    public SalesDeliveryFlowService(JdbcTemplate jdbc, MessageSource messages, BrandApi brands) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.brands = brands;
    }

    // ---- 配置面 ----

    /** 品牌流程模板列表(含品牌名经契约回填;零直查他域表)。 */
    public List<Map<String, Object>> list(long orgId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, brand_id, auto_tran, auto_check, auto_confirm,
                       rate_tran, rate_check, rate_confirm, rate_finish, updated_at
                from sales_delivery_flow where org_id = ? order by brand_id
                """, orgId);
        for (Map<String, Object> row : rows) {
            long brandId = ((Number) row.get("brand_id")).longValue();
            row.put("brand_name", brands.queryBrand(brandId).map(BrandApi.BrandSummary::name).orElse(""));
            row.put("enabled", rateSum(row) > 0);
        }
        return rows;
    }

    /** 保存模板(flow+四 rate 一体;守卫族服务端算),保存即重算该品牌未认领分批行(计划变更语义)。 */
    @Transactional
    public void save(long orgId, long brandId, FlowBody body, long operatorId) {
        BrandApi.BrandSummary brand = brands.queryBrand(brandId).orElse(null);
        if (brand == null || brand.status() != 0) {
            throw new BizException(CODE_BRAND, msg("sales.flow.brand"));
        }
        int autoTran = flag(orgId, body == null ? null : body.autoTran());
        int autoCheck = flag(orgId, body == null ? null : body.autoCheck());
        int autoConfirm = flag(orgId, body == null ? null : body.autoConfirm());
        int[] rates = rates(orgId, body == null ? null : body.rates());
        jdbc.update("""
                insert into sales_delivery_flow(org_id, brand_id, auto_tran, auto_check, auto_confirm,
                    rate_tran, rate_check, rate_confirm, rate_finish, updated_by)
                values (?,?,?,?,?,?,?,?,?,?)
                on duplicate key update auto_tran = values(auto_tran), auto_check = values(auto_check),
                    auto_confirm = values(auto_confirm), rate_tran = values(rate_tran),
                    rate_check = values(rate_check), rate_confirm = values(rate_confirm),
                    rate_finish = values(rate_finish), updated_by = values(updated_by)
                """, orgId, brandId, autoTran, autoCheck, autoConfirm,
                rates[0], rates[1], rates[2], rates[3], operatorId);
        // 计划变更:该品牌全部结算行的未认领分批行重算(已认领不回溯,已厂结/结算行整体不动)
        List<Long> saleIds = jdbc.queryForList("""
                select distinct sale_id from sales_settlement
                where org_id = ? and brand_id = ? and type = 0 and rid = 0
                """, Long.class, orgId, brandId);
        for (Long saleId : saleIds) {
            regenerate(orgId, saleId, brandId, true);
        }
    }

    private int flag(long orgId, Integer v) {
        if (v == null) {
            return 0;
        }
        if (v != 0 && v != 1) {
            throw new BizException(CODE_AUTO_FLAG, msg("sales.flow.autoFlag"));
        }
        return v;
    }

    private int[] rates(long orgId, List<RateItem> items) {
        int[] rates = {0, 0, 0, 0};
        if (items == null || items.isEmpty()) {
            return rates;
        }
        Set<Integer> seen = new HashSet<>();
        for (RateItem item : items) {
            if (item == null || item.milestone() == null
                    || item.milestone() < MS_TRAN || item.milestone() > MS_FINISH
                    || !seen.add(item.milestone())) {
                throw new BizException(CODE_MILESTONE, msg("sales.flow.milestone"));
            }
            Integer r = item.rate();
            if (r == null || r <= 0 || r > 10000) {
                throw new BizException(CODE_RATE, msg("sales.flow.rate"));
            }
            rates[item.milestone() - 1] = r;
        }
        int sum = rates[0] + rates[1] + rates[2] + rates[3];
        if (sum > 0 && sum != 10000) {
            throw new BizException(CODE_RATE_SUM, msg("sales.flow.rateSum"));
        }
        return rates;
    }

    // ---- 分批引擎(触发点:明细推进/预结生成/计划变更) ----

    /** 明细推进到某级(含免人工自动通过)后调用:免人工链式推进 + 按计划生成分批行。 */
    public void afterAdvance(long orgId, long saleId, long deliveryItemId, String goodsLabel,
            long brandId, int reachedLevel, long operatorId) {
        int[] rates = ratesFor(orgId, brandId);
        onMilestone(orgId, saleId, brandId, reachedLevel, rates);
        // 免人工链式推进(深度≤4 循环无递归;finish 恒人工不在链内——防自答)
        int current = reachedLevel;
        while (current < SalesDeliveryService.DV_FINISH) {
            int next = current + 1;
            if (!autoOn(orgId, brandId, next)) {
                break;
            }
            String column = switch (next) {
                case SalesDeliveryService.DV_TRAN -> "tran";
                case SalesDeliveryService.DV_CHECK -> "check";
                case SalesDeliveryService.DV_CONFIRM -> "confirm";
                default -> null;
            };
            if (column == null) {
                break;
            }
            int updated = jdbc.update(
                    ("update sales_delivery_item set status = ?, %s_by = ?, %s_at = now() "
                            + "where id = ? and org_id = ? and status = ?").formatted(column, column),
                    next, operatorId, deliveryItemId, orgId, current);
            if (updated == 0) {
                break; // 并发被人工抢先,停链(人工优先)
            }
            track(orgId, saleId, "auto" + column, goodsLabel + " ×系统按品牌流程自动"
                    + switch (next) {
                case SalesDeliveryService.DV_TRAN -> "配送";
                case SalesDeliveryService.DV_CHECK -> "审核";
                default -> "确认";
            }, operatorId);
            current = next;
            onMilestone(orgId, saleId, brandId, current, rates);
        }
    }

    /** 预结行生成后补扫(配送先于收款确认场景):按当前达成里程碑+计划生成未生成分批行。 */
    public void backfillForSaleBrand(long orgId, long saleId, long brandId) {
        regenerate(orgId, saleId, brandId, false);
    }

    /** 厂结/结算完成:该结算行分批行批量标记已认领(只做标记不参与守卫;此后的新生分批行随结算行状态出生即认领)。 */
    public void claimBatches(long orgId, long settleId) {
        jdbc.update("""
                update sales_settlement_batch set status = 1, claimed_at = now()
                where org_id = ? and settle_id = ? and status = 0
                """, orgId, settleId);
    }

    /** 结算驳回(整单原子):预继行删除时级联清其分批行(防孤儿;新预继行新 id 自然零残留)。 */
    public void dropBatches(long orgId, List<Long> settleIds) {
        for (Long settleId : settleIds) {
            jdbc.update("delete from sales_settlement_batch where org_id = ? and settle_id = ?",
                    orgId, settleId);
        }
    }

    /** 结算行分批计划视图(结算详情抽屉)。 */
    public List<Map<String, Object>> batches(long orgId, long settleId) {
        return jdbc.queryForList("""
                select id, milestone, rate, total, balance, vbalance, pbalance, difference,
                       status, claimed_at, created_at
                from sales_settlement_batch where org_id = ? and settle_id = ? order by milestone
                """, orgId, settleId);
    }

    /**
     * 重算该单该品牌未认领分批行(幂等:先删未认领,再按当前达成进度+当前计划生成)。
     * planChange=计划变更路径:已厂结/结算行整体不动(已认领不回溯,连补登也不做);
     * 推进/补扫路径:厂结后到达的里程碑生成出生即认领的分批行(保 Σ 随进度收敛)。
     * 更晚里程碑已有认领行时,更早里程碑不再补生(认领冻结时序,防双计)。
     */
    private void regenerate(long orgId, long saleId, long brandId, boolean planChange) {
        List<Map<String, Object>> settles = jdbc.queryForList("""
                select * from sales_settlement
                where org_id = ? and sale_id = ? and brand_id = ? and type = 0 and rid = 0
                """, orgId, saleId, brandId);
        if (settles.isEmpty()) {
            return; // 预结未生成:计划缓存,等预结生成时补扫
        }
        Map<String, Object> settle = settles.get(0);
        long settleId = ((Number) settle.get("id")).longValue();
        int settleStatus = ((Number) settle.get("status")).intValue();
        jdbc.update("delete from sales_settlement_batch where org_id = ? and settle_id = ? and status = 0",
                orgId, settleId);
        int[] rates = ratesFor(orgId, brandId);
        if (rateSum(rates) <= 0) {
            return; // 分成未启用:清空即计划(只配流程不配分成合法)
        }
        if (planChange && settleStatus > 0) {
            return; // 已认领不回溯(诚实边界)
        }
        List<Long> claimedLater = jdbc.queryForList("""
                select milestone from sales_settlement_batch
                where org_id = ? and settle_id = ? and status = 1
                """, Long.class, orgId, settleId);
        int bornStatus = settleStatus > 0 ? 1 : 0;
        for (int m = MS_TRAN; m <= MS_FINISH; m++) {
            if (rates[m - 1] <= 0 || !milestoneReached(orgId, saleId, brandId, m)) {
                continue;
            }
            int mm = m;
            if (claimedLater.stream().anyMatch(c -> ((Number) c).intValue() > mm)) {
                continue; // 更晚里程碑已认领冻结:更早的不回溯补生
            }
            insertBatch(orgId, settle, settleId, m, rates[m - 1], rates, bornStatus);
        }
    }

    /** 里程碑到达:计划含该级分成且预结行在,即重算(幂等,重复推进零新增)。 */
    private void onMilestone(long orgId, long saleId, long brandId, int milestone, int[] rates) {
        if (rateSum(rates) <= 0 || milestone < MS_TRAN || milestone > MS_FINISH
                || rates[milestone - 1] <= 0) {
            return;
        }
        regenerate(orgId, saleId, brandId, false);
    }

    /** 生成分批行:非末批=五列×rate 逐列 HALF_UP;末批(计划内最后配置级)=总额−已批合计补尾差
     *  (Σ 分批 = 预结总额;历史极端序列下余差可为负=超额认领的算术补偿,不裁剪不隐藏)。 */
    private void insertBatch(long orgId, Map<String, Object> settle, long settleId, int milestone,
            int rate, int[] rates, int bornStatus) {
        int last = MS_TRAN;
        for (int m = MS_TRAN; m <= MS_FINISH; m++) {
            if (rates[m - 1] > 0) {
                last = m;
            }
        }
        List<Map<String, Object>> others = jdbc.queryForList(
                "select total, balance, vbalance, pbalance, difference from sales_settlement_batch "
                        + "where org_id = ? and settle_id = ? and milestone <> ?",
                orgId, settleId, milestone);
        StringBuilder sql = new StringBuilder(
                "insert ignore into sales_settlement_batch(org_id, settle_id, milestone, rate, status, ");
        StringBuilder marks = new StringBuilder("values (?,?,?,?,?,");
        List<Object> args = new ArrayList<>(List.of(orgId, settleId, milestone, rate, bornStatus));
        for (String col : MONEY_COLUMNS) {
            long base = ((Number) settle.get(col)).longValue();
            long value;
            if (milestone == last) {
                long sumOther = 0;
                for (Map<String, Object> o : others) {
                    sumOther += ((Number) o.get(col)).longValue();
                }
                value = base - sumOther; // 末批补尾差(Σ 分批 = 预结总额)
            } else {
                value = BigDecimal.valueOf(base).multiply(BigDecimal.valueOf(rate))
                        .divide(BigDecimal.valueOf(10000), 0, RoundingMode.HALF_UP).longValue();
            }
            sql.append(col).append(", ");
            marks.append("?,");
            args.add(value);
        }
        sql.setLength(sql.length() - 2);
        marks.setLength(marks.length() - 1);
        jdbc.update(sql + ") " + marks + ")", args.toArray());
    }

    /** 里程碑达成 = 存在该单该品牌配送明细 status ≥ 级(顺序推进,≥ 即到达)。 */
    private boolean milestoneReached(long orgId, long saleId, long brandId, int milestone) {
        Integer count = jdbc.queryForObject("""
                select count(*) from sales_delivery_item i
                join sales_order_item s on s.id = i.sale_item_id
                where i.org_id = ? and s.sale_id = ? and s.brand_id = ? and i.status >= ?
                """, Integer.class, orgId, saleId, brandId, milestone);
        return count != null && count > 0;
    }

    /** 免人工位(下一级是否自动通过;finish 恒人工恒 false)。 */
    private boolean autoOn(long orgId, long brandId, int level) {
        if (level < SalesDeliveryService.DV_TRAN || level > SalesDeliveryService.DV_CONFIRM) {
            return false;
        }
        String column = switch (level) {
            case SalesDeliveryService.DV_TRAN -> "auto_tran";
            case SalesDeliveryService.DV_CHECK -> "auto_check";
            default -> "auto_confirm";
        };
        List<Integer> flags = jdbc.queryForList(
                "select " + column + " from sales_delivery_flow where org_id = ? and brand_id = ?",
                Integer.class, orgId, brandId);
        return !flags.isEmpty() && flags.get(0) == 1;
    }

    private int[] ratesFor(long orgId, long brandId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select rate_tran, rate_check, rate_confirm, rate_finish from sales_delivery_flow "
                        + "where org_id = ? and brand_id = ?", orgId, brandId);
        if (rows.isEmpty()) {
            return new int[]{0, 0, 0, 0};
        }
        Map<String, Object> row = rows.get(0);
        return new int[]{((Number) row.get("rate_tran")).intValue(),
                ((Number) row.get("rate_check")).intValue(),
                ((Number) row.get("rate_confirm")).intValue(),
                ((Number) row.get("rate_finish")).intValue()};
    }

    private int rateSum(int[] rates) {
        return rates[0] + rates[1] + rates[2] + rates[3];
    }

    private int rateSum(Map<String, Object> row) {
        int sum = 0;
        for (String col : RATE_COLUMNS) {
            sum += ((Number) row.get(col)).intValue();
        }
        return sum;
    }

    private void track(long orgId, long saleId, String action, String detail, long operatorId) {
        jdbc.update("""
                insert into sales_track(org_id, sale_id, action, detail, operator_id, operator_name)
                values (?,?,?,?,?,?)
                """, orgId, saleId, action, detail, operatorId, displayName(operatorId));
    }

    private String displayName(long userId) {
        List<String> names = jdbc.queryForList(
                "select display_name from yz_user where id = ?", String.class, userId);
        return names.isEmpty() ? String.valueOf(userId) : names.get(0);
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
