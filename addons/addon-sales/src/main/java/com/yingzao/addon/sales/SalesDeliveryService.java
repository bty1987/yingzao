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
 * 配送履约:批次制(明细级四级状态机 0初始→1配送→2审核→3确认→4完结,每级操作人+时间留痕),
 * 越级/状态不符显式报错(修老静默 continue);生成守卫 = 主单初始/完结 且 款项已付
 * (付了钱哪怕未确认即可配送,老守卫组合保留);品牌跳级权限与按批次分成克隆(方林嫌疑)不迁;
 * 完结回写明细 trnum(主单完结以"无未完配送项"为守卫,方向不反)。
 */
@Service
public class SalesDeliveryService {

    public static final int CODE_BAD_STATE = 300022;
    public static final int CODE_NOT_PAID = 300023;
    public static final int CODE_NUM_OVER = 300024;

    public static final int DV_INIT = 0;
    public static final int DV_TRAN = 1;
    public static final int DV_CHECK = 2;
    public static final int DV_CONFIRM = 3;
    public static final int DV_FINISH = 4;

    public record DeliveryItemCmd(Long saleItemId, Long num) {
    }

    private final JdbcTemplate jdbc;
    private final SalesOrderService orders;
    private final SalesDeliveryFlowService flows;

    public SalesDeliveryService(JdbcTemplate jdbc, SalesOrderService orders,
            SalesDeliveryFlowService flows) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.flows = flows;
    }

    @Transactional
    public long create(long orgId, long saleId, List<DeliveryItemCmd> items, String remark,
            long operatorId) {
        Map<String, Object> sale = orders.loadSale(orgId, saleId);
        int status = ((Number) sale.get("status")).intValue();
        int fs = ((Number) sale.get("fullpay_status")).intValue();
        if (status != SalesOrderService.ST_INIT && status != SalesOrderService.ST_FINISH) {
            throw new BizException(CODE_BAD_STATE, orders.msg("sales.state.illegal"));
        }
        if (fs != SalesOrderService.FS_PAID && fs != SalesOrderService.FS_CONFIRMED) {
            throw new BizException(CODE_NOT_PAID, orders.msg("sales.delivery.notPaid"));
        }
        require(items != null && !items.isEmpty(), SalesOrderService.CODE_EMPTY_ITEMS, "sales.items.empty");
        jdbc.update("""
                insert into sales_delivery(org_id, sale_id, order_id, remark, created_by, created_by_name)
                values (?,?,?,?,?,?)
                """, orgId, saleId, ((Number) sale.get("order_id")).longValue(),
                SalesOrderService.blankToNull(remark), operatorId, orders.displayName(operatorId));
        long deliveryId = jdbc.queryForObject("select last_insert_id()", Long.class);
        List<long[]> inserted = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (DeliveryItemCmd cmd : items) {
            require(cmd != null && cmd.saleItemId() != null && cmd.num() != null && cmd.num() > 0,
                    SalesOrderService.CODE_PARAM, "sales.param.invalid");
            Map<String, Object> item = orders.loadItem(orgId, cmd.saleItemId());
            if (((Number) item.get("sale_id")).longValue() != saleId) {
                throw new BizException(SalesOrderService.CODE_ITEM_MISMATCH, orders.msg("sales.item.mismatch"));
            }
            long deliverable = ((Number) item.get("num")).longValue()
                    - ((Number) item.get("trnum")).longValue();
            if (cmd.num() > deliverable) {
                throw new BizException(CODE_NUM_OVER, orders.msg("sales.delivery.numOver"));
            }
            jdbc.update("""
                    insert into sales_delivery_item(org_id, delivery_id, sale_item_id, goods_name,
                        color_name, format_name, num, status)
                    values (?,?,?,?,?,?,?,?)
                    """, orgId, deliveryId, cmd.saleItemId(), item.get("goods_name"),
                    item.get("color_name"), item.get("format_name"), cmd.num(), DV_INIT);
            long itemId = jdbc.queryForObject("select last_insert_id()", Long.class);
            List<Long> brandIds = jdbc.queryForList(
                    "select brand_id from sales_order_item where id = ?", Long.class, cmd.saleItemId());
            inserted.add(new long[]{itemId, brandIds.isEmpty() ? 0L : brandIds.get(0)});
            labels.add(item.get("goods_name") + " ×" + cmd.num());
        }
        orders.track(orgId, saleId, "deliver", "生成配送批次(" + items.size() + " 行)", operatorId);
        // B2 品牌流程模板:配送级免人工(auto_tran)在建单点火,后续级仍由推进链代打;
        // 未配模板=全人工,行为与 V1 完全一致
        for (int i = 0; i < inserted.size(); i++) {
            flows.afterAdvance(orgId, saleId, inserted.get(i)[0], labels.get(i), inserted.get(i)[1],
                    DV_INIT, operatorId);
        }
        return deliveryId;
    }

    /** 四级推进(action 协议键 tran/check/confirm/finish),每级条件 UPDATE+留痕;完结回写 trnum。 */
    @Transactional
    public void advance(long orgId, long deliveryItemId, String action, long operatorId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from sales_delivery_item where id = ? and org_id = ?", deliveryItemId, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_BAD_STATE, orders.msg("sales.delivery.notFound"));
        }
        Map<String, Object> item = rows.get(0);
        int from;
        int to;
        String column;
        switch (action == null ? "" : action) {
            case "tran" -> { from = DV_INIT; to = DV_TRAN; column = "tran"; }
            case "check" -> { from = DV_TRAN; to = DV_CHECK; column = "check"; }
            case "confirm" -> { from = DV_CHECK; to = DV_CONFIRM; column = "confirm"; }
            case "finish" -> { from = DV_CONFIRM; to = DV_FINISH; column = "finish"; }
            default -> throw new BizException(SalesOrderService.CODE_PARAM, orders.msg("sales.param.invalid"));
        }
        int updated = jdbc.update(
                "update sales_delivery_item set status = ?, %s_by = ?, %s_at = now() where id = ? and org_id = ? and status = ?"
                        .formatted(column, column),
                to, operatorId, deliveryItemId, orgId, from);
        if (updated == 0) {
            throw new BizException(CODE_BAD_STATE, orders.msg("sales.delivery.badStep"));
        }
        if (to == DV_FINISH) {
            jdbc.update("""
                    update sales_order_item set trnum = trnum + ? where id = ?
                    """, item.get("num"), item.get("sale_item_id"));
        }
        Map<String, Object> delivery = jdbc.queryForList(
                "select * from sales_delivery where id = ?", ((Number) item.get("delivery_id")).longValue())
                .get(0);
        long saleId = ((Number) delivery.get("sale_id")).longValue();
        orders.track(orgId, saleId, "delivery" + action,
                item.get("goods_name") + " ×" + item.get("num"), operatorId);
        // B2 品牌流程模板:免人工级自动通过(track 记 auto_ 前缀)+ 里程碑分批行生成(幂等);
        // 未配模板=全人工,行为与 V1 完全一致。越级显式报错纪律对人保持,系统按配置代打。
        List<Long> brandIds = jdbc.queryForList(
                "select brand_id from sales_order_item where id = ?", Long.class, item.get("sale_item_id"));
        flows.afterAdvance(orgId, saleId, deliveryItemId,
                item.get("goods_name") + " ×" + item.get("num"),
                brandIds.isEmpty() ? 0L : brandIds.get(0), to, operatorId);
    }

    public Map<String, Object> page(long orgId, Long saleId, int pageNo, int size) {
        int safePage = Math.max(pageNo, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(" where org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        if (saleId != null) {
            where.append(" and sale_id = ?");
            args.add(saleId);
        }
        String w = where.toString();
        Long count = jdbc.queryForObject("select count(*) from sales_delivery" + w, Long.class,
                args.toArray());
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select d.id, d.sale_id, o.code as sale_code, d.order_id, d.remark,
                       d.created_by_name, d.created_at,
                       (select count(*) from sales_delivery_item i where i.delivery_id = d.id) as item_count,
                       (select count(*) from sales_delivery_item i where i.delivery_id = d.id and i.status < 4) as open_count
                from sales_delivery d join sales_order o on o.id = d.sale_id""" + w
                + " order by d.id desc limit ? offset ?",
                SalesOrderService.appendArgs(args, safeSize, (safePage - 1) * safeSize).toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", count == null ? 0 : count);
        result.put("rows", rows);
        return result;
    }

    public Map<String, Object> detail(long orgId, long deliveryId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from sales_delivery where id = ? and org_id = ?", deliveryId, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_BAD_STATE, orders.msg("sales.delivery.notFound"));
        }
        Map<String, Object> delivery = rows.get(0);
        delivery.put("items", jdbc.queryForList("""
                select id, sale_item_id, goods_name, color_name, format_name, num, status,
                       tran_by, tran_at, check_by, check_at, confirm_by, confirm_at, finish_by, finish_at
                from sales_delivery_item where org_id = ? and delivery_id = ? order by id
                """, orgId, deliveryId));
        return delivery;
    }

    private void require(boolean condition, int code, String key) {
        if (!condition) {
            throw new BizException(code, orders.msg(key));
        }
    }
}
