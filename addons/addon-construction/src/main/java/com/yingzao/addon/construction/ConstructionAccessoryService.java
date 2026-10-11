package com.yingzao.addon.construction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.addons.api.ConstructionAccessoryApi;
import com.yingzao.addons.api.GoodsApi;
import com.yingzao.core.web.BizException;

/**
 * 工程辅料额度台账(V602):总额闸(施工单 accessory_limit)+ per-gid 登记闸(checkup=1)
 * + append-only 流水。实现 ConstructionAccessoryApi 供 addon-inventory 同步调用
 * (同事务,领料创建/撤销/退回与记账原子);闸门全部原子条件 UPDATE,并发不超领
 * (修老 check-then-act 竞态 + 信任前端 total 的口径漂移:consume/refund 双向服务端重算)。
 * 未登记商品不受 per-gid 限但受总额限(老宽松登记制语义保留);售后领料由调用方跳过。
 */
@Service
public class ConstructionAccessoryService implements ConstructionAccessoryApi {

    public static final int CODE_ACCESSORY_LOCKED = 240019;
    public static final int CODE_TOTAL_OVER = 240020;
    public static final int CODE_NUM_OVER = 240021;
    public static final int CODE_CONSTRUCTION_MISSING = 240022;
    public static final int CODE_QUOTA_CONSUMED = 240023;
    public static final int CODE_GOODS_NOT_FOUND = 240024;

    public static final int SWITCH_OPEN = 0;
    public static final int SWITCH_LOCKED = 1;
    /** 无上限哨兵(与节点 salary/accessory limit 同族)。 */
    public static final long LIMIT_SENTINEL = 999999000L;

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final GoodsApi goods;

    public ConstructionAccessoryService(JdbcTemplate jdbc, MessageSource messages, GoodsApi goods) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.goods = goods;
    }

    // ---- 契约实现(调用方 addon-inventory) ----

    @Override
    @Transactional
    public void consume(ConsumeCmd cmd) {
        Map<String, Object> order = loadConstruction(cmd.orgId(), cmd.orderId());
        require(((Number) order.get("accessory")).intValue() != SWITCH_LOCKED,
                CODE_ACCESSORY_LOCKED, "construction.accessory.locked");
        long total = cmd.total();
        require(total > 0, ConstructionService.CODE_PARAM, "construction.param.invalid");
        // 总额闸 = 原子条件自增(并发只成功一个,修老 check-then-act 竞态)
        int updated = jdbc.update("""
                update construction_order set accessory_consumed_total = accessory_consumed_total + ?
                where org_id = ? and order_id = ? and accessory_consumed_total + ? <= accessory_limit
                """, total, cmd.orgId(), cmd.orderId(), total);
        require(updated > 0, CODE_TOTAL_OVER, "construction.accessory.totalOver");
        for (Item item : cmd.items()) {
            upsertQuota(cmd.orgId(), cmd.orderId(), item);
            insertLedger(cmd.orgId(), cmd.orderId(), cmd.requisitionId(), cmd.requisitionNo(),
                    "consume", item, cmd.operatorId());
        }
    }

    @Override
    @Transactional
    public void refund(RefundCmd cmd) {
        loadConstruction(cmd.orgId(), cmd.orderId());
        require(cmd.total() > 0, ConstructionService.CODE_PARAM, "construction.param.invalid");
        int updated = jdbc.update("""
                update construction_order set accessory_consumed_total = accessory_consumed_total - ?
                where org_id = ? and order_id = ? and accessory_consumed_total >= ?
                """, cmd.total(), cmd.orgId(), cmd.orderId(), cmd.total());
        require(updated > 0, CODE_TOTAL_OVER, "construction.accessory.totalOver");
        for (Item item : cmd.items()) {
            jdbc.update("""
                    update construction_accessory_quota
                    set consumed_num = consumed_num - ?, consumed_total = consumed_total - ?
                    where org_id = ? and order_id = ? and goods_id = ? and consumed_num >= ?
                    """, item.num(), item.total(), cmd.orgId(), cmd.orderId(), item.goodsId(), item.num());
            insertLedger(cmd.orgId(), cmd.orderId(), cmd.requisitionId(), cmd.requisitionNo(),
                    "refund", new Item(item.goodsId(), item.goodsName(), -item.num(), -item.total()),
                    cmd.operatorId());
        }
    }

    private void upsertQuota(long orgId, long orderId, Item item) {
        int updated = jdbc.update("""
                update construction_accessory_quota
                set consumed_num = consumed_num + ?, consumed_total = consumed_total + ?
                where org_id = ? and order_id = ? and goods_id = ?
                  and (checkup = 0 or consumed_num + ? <= num_limit)
                """, item.num(), item.total(), orgId, orderId, item.goodsId(), item.num());
        if (updated > 0) {
            return;
        }
        Integer registered = jdbc.queryForObject("""
                select count(*) from construction_accessory_quota
                where org_id = ? and order_id = ? and goods_id = ?
                """, Integer.class, orgId, orderId, item.goodsId());
        if (registered != null && registered > 0) {
            throw new BizException(CODE_NUM_OVER, msgArgs("construction.accessory.numOver", item.goodsName()));
        }
        try {
            jdbc.update("""
                    insert into construction_accessory_quota(org_id, order_id, goods_id, goods_name,
                        consumed_num, consumed_total)
                    values (?,?,?,?,?,?)
                    """, orgId, orderId, item.goodsId(), item.goodsName(), item.num(), item.total());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发首插撞唯一键:回退到带闸自增,失败即超限
            int retried = jdbc.update("""
                    update construction_accessory_quota
                    set consumed_num = consumed_num + ?, consumed_total = consumed_total + ?
                    where org_id = ? and order_id = ? and goods_id = ?
                      and (checkup = 0 or consumed_num + ? <= num_limit)
                    """, item.num(), item.total(), orgId, orderId, item.goodsId(), item.num());
            require(retried > 0, CODE_NUM_OVER, msgArgs("construction.accessory.numOver", item.goodsName()));
        }
    }

    private void insertLedger(long orgId, long orderId, long requisitionId, String requisitionNo,
            String action, Item item, long operatorId) {
        jdbc.update("""
                insert into construction_accessory_ledger(org_id, order_id, requisition_id, requisition_no,
                    action, goods_id, goods_name, num, total, operator_id, operator_name)
                values (?,?,?,?,?,?,?,?,?,?,?)
                """, orgId, orderId, requisitionId, requisitionNo, action, item.goodsId(),
                item.goodsName(), item.num(), item.total(), operatorId, displayName(operatorId));
    }

    // ---- 配置端点(本域 Web:额度/登记行/流水) ----

    public Map<String, Object> getAccessory(long orgId, long orderId) {
        Map<String, Object> order = loadConstruction(orgId, orderId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accessory", ((Number) order.get("accessory")).intValue());
        result.put("accessoryLimit", ((Number) order.get("accessory_limit")).longValue());
        result.put("accessoryConsumedTotal", ((Number) order.get("accessory_consumed_total")).longValue());
        result.put("quotas", jdbc.queryForList("""
                select goods_id, goods_name, checkup, num_limit, consumed_num, consumed_total, updated_at
                from construction_accessory_quota where org_id = ? and order_id = ? order by goods_id
                """, orgId, orderId));
        return result;
    }

    @Transactional
    public void updateLimit(long orgId, long orderId, Long limit) {
        loadConstruction(orgId, orderId);
        require(limit != null && limit > 0 && limit <= LIMIT_SENTINEL,
                ConstructionService.CODE_PARAM, "construction.param.invalid");
        jdbc.update("""
                update construction_order set accessory_limit = ? where org_id = ? and order_id = ?
                """, limit, orgId, orderId);
    }

    /** 登记行 upsert:商品经 GoodsApi 校验(禁直查 basedata),登记行只管闸门定义,不预记账。 */
    @Transactional
    public void saveQuota(long orgId, long orderId, Long goodsId, Integer checkup, Long numLimit) {
        loadConstruction(orgId, orderId);
        require(goodsId != null && checkup != null && (checkup == 0 || checkup == 1)
                && numLimit != null && numLimit >= 0, ConstructionService.CODE_PARAM,
                "construction.param.invalid");
        GoodsApi.GoodsSummary summary = goods.queryGoods(orgId, goodsId)
                .orElseThrow(() -> new BizException(CODE_GOODS_NOT_FOUND,
                        msg("construction.accessory.goodsNotFound")));
        jdbc.update("""
                insert into construction_accessory_quota(org_id, order_id, goods_id, goods_name,
                    checkup, num_limit)
                values (?,?,?,?,?,?)
                on duplicate key update checkup = values(checkup), num_limit = values(num_limit),
                    goods_name = values(goods_name)
                """, orgId, orderId, goodsId, summary.name(), checkup, numLimit);
    }

    @Transactional
    public void deleteQuota(long orgId, long orderId, Long goodsId) {
        loadConstruction(orgId, orderId);
        require(goodsId != null, ConstructionService.CODE_PARAM, "construction.param.invalid");
        int updated = jdbc.update("""
                delete from construction_accessory_quota
                where org_id = ? and order_id = ? and goods_id = ? and consumed_num = 0
                """, orgId, orderId, goodsId);
        require(updated > 0, CODE_QUOTA_CONSUMED, "construction.accessory.quotaConsumed");
    }

    public List<Map<String, Object>> ledger(long orgId, long orderId) {
        loadConstruction(orgId, orderId);
        return jdbc.queryForList("""
                select requisition_no, action, goods_name, num, total, operator_name, created_at
                from construction_accessory_ledger where org_id = ? and order_id = ?
                order by id desc limit 200
                """, orgId, orderId);
    }

    // ---- 内部 ----

    private void require(boolean cond, int code, String msgKey) {
        if (!cond) {
            throw new BizException(code, msg(msgKey));
        }
    }

    private Map<String, Object> loadConstruction(long orgId, long orderId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select order_id, accessory, accessory_limit, accessory_consumed_total
                from construction_order where org_id = ? and order_id = ?
                """, orgId, orderId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_CONSTRUCTION_MISSING, msg("construction.accessory.notIgnited"));
        }
        return rows.get(0);
    }

    private String displayName(long userId) {
        return jdbc.queryForObject("select display_name from yz_user where id = ?", String.class, userId);
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private String msgArgs(String key, Object... args) {
        return messages.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}
