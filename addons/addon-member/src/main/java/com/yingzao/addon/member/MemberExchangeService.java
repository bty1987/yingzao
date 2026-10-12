package com.yingzao.addon.member;

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
import org.springframework.transaction.support.TransactionTemplate;

import com.yingzao.addons.api.CustomerProfileApi;
import com.yingzao.addons.api.event.ExchangeCancelledEvent;
import com.yingzao.addons.api.event.ExchangeCreatedEvent;
import com.yingzao.core.eventbus.YzEventBus;
import com.yingzao.core.web.BizException;

/**
 * 兑换单服务(外部积分商城落单 + 到店核销 + 撤销回补)。
 * 修老 makeMemberOrder 族:①无单号防重 → exchange_no 全局唯一幂等 upsert(重复推送回显不重扣);
 * ②off-by-one 限件(count>1 才拒)→ 每单每线上礼品限一件:服务层去重 + uk(exchange_id,gift_id)
 * DB 兜底(绕过 API 直插也被拦);③落单不动账户不扣库存(真源在外)→ 落单与扣积分/扣库存同事务;
 * ④cancelMemberOrder 不校验状态可作废已核销 → 撤销仅受理态明细,已核销 350012;
 * ⑤核销一次性(状态守卫并发双核销只成功一次)+ 过期拦截(available>=0 闸,350009);
 * ⑥'签约自动核销'content 硬编码字面量 → 组织级配置 member_config。
 * 兑换消耗分项 pointType∈{1 装修,2 主材}(3 运营仅手工调整,不可兑 350017)。
 */
@Service
public class MemberExchangeService {

    private static final Logger log = LoggerFactory.getLogger(MemberExchangeService.class);

    public static final int CODE_GIFT_NOT_FOUND = MemberGiftService.CODE_NOT_FOUND;
    public static final int CODE_GIFT_STOCK = MemberGiftService.CODE_STOCK;
    public static final int CODE_GIFT_DISABLED = MemberGiftService.CODE_DISABLED;
    public static final int CODE_INSUFFICIENT = MemberPointService.CODE_INSUFFICIENT;
    public static final int CODE_EXPIRED = MemberPointService.CODE_EXPIRED;
    public static final int CODE_NOT_FOUND = 350007;
    public static final int CODE_ITEM_LIMIT = 350008;
    public static final int CODE_ITEM_STATUS = 350010;
    public static final int CODE_CONSUMED = 350011;
    public static final int CODE_REVOKE = 350012;
    public static final int CODE_NO_CONFLICT = 350013;
    public static final int CODE_CUSTOMER = MemberPointService.CODE_CUSTOMER;
    public static final int CODE_NOT_ALLOWED = MemberPointService.CODE_NOT_ALLOWED;
    public static final int CODE_PARAM = 350020;

    public static final int STATUS_ACCEPTED = 0;
    public static final int STATUS_CONSUMED = 1;
    public static final int STATUS_CANCELLED = 2;

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final YzEventBus bus;
    private final MemberGiftService gifts;
    private final MemberPointService points;
    private final CustomerProfileApi profiles;
    private final TransactionTemplate txTemplate;

    public MemberExchangeService(JdbcTemplate jdbc, MessageSource messages, YzEventBus bus,
            MemberGiftService gifts, MemberPointService points, CustomerProfileApi profiles,
            TransactionTemplate txTemplate) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.bus = bus;
        this.gifts = gifts;
        this.points = points;
        this.profiles = profiles;
        this.txTemplate = txTemplate;
    }

    /** 落单命令(外部商城同步;num 恒 1——每单每线上礼品限一件)。 */
    public record PlaceItem(String giftNo) {
    }

    public record PlaceCmd(String exchangeNo, Long customerId, Integer pointType, Long orderId,
            List<PlaceItem> items, String remark) {
    }

    /**
     * 商城落单(幂等):同 exchangeNo 重推,内容一致回显原单不重扣;不一致 350013。
     * 同事务:校验(客户/礼品/可用积分)→ 落单落明细 → 扣积分(条件 UPDATE)→ 扣库存(isStock=0,
     * 条件 UPDATE)→ 流水 → 事件。并发同号/同积分/同库存竞态各自收敛到条件守卫。
     */
    @Transactional
    public Map<String, Object> place(long orgId, PlaceCmd cmd, Long operatorId) {
        require(cmd.exchangeNo() != null && !cmd.exchangeNo().isBlank(), CODE_PARAM, "param.invalid");
        require(cmd.customerId() != null, CODE_PARAM, "param.invalid");
        int pointType = cmd.pointType() == null ? 0 : cmd.pointType();
        require(pointType == MemberPointService.TYPE_DECORATION || pointType == MemberPointService.TYPE_MATERIAL,
                CODE_NOT_ALLOWED, "point.notallowed");
        List<PlaceItem> items = cmd.items();
        require(items != null && !items.isEmpty(), CODE_PARAM, "exchange.items");
        Set<String> seen = new HashSet<>();
        for (PlaceItem item : items) {
            require(item.giftNo() != null && !item.giftNo().isBlank(), CODE_PARAM, "exchange.items");
            require(seen.add(item.giftNo().trim()), CODE_ITEM_LIMIT, "exchange.limit");
        }

        // 幂等预检:同号已存在 → 内容比对回显
        Map<String, Object> existing = findByNo(orgId, cmd.exchangeNo().trim());
        if (existing != null) {
            return echoExisting(orgId, existing, cmd);
        }

        // 客户校验(主数据唯一写点纪律:经契约,不直查档案表)
        var customer = profiles.queryCustomer(orgId, cmd.customerId());
        require(customer.isPresent(), CODE_CUSTOMER, "customer.notfound");
        String customerName = customer.get().name();

        // 礼品校验与积分合计(🔴 按号精确取:like 模糊检索会让 MG001 误中 MG0011)
        long total = 0;
        List<Map<String, Object>> giftRows = new ArrayList<>();
        for (PlaceItem item : items) {
            Map<String, Object> giftRow = gifts.getByNo(orgId, item.giftNo().trim());
            require(((Number) giftRow.get("status")).intValue() == 0, CODE_GIFT_DISABLED, "gift.disabled");
            total += ((Number) giftRow.get("integral_price")).longValue();
            giftRows.add(giftRow);
        }

        // 可用积分闸(过期批次只拦新兑换:可用 < 总额 且 余额 < 总额 → 350006;仅可用不足 → 350009)
        long available = points.available(orgId, cmd.customerId(), pointType);
        if (available < total) {
            long balance = points.balance(orgId, cmd.customerId(), pointType);
            throw new BizException(balance >= total ? CODE_EXPIRED : CODE_INSUFFICIENT,
                    msg(balance >= total ? "point.expired" : "point.insufficient"));
        }
        LocalDateTime earliestExpire = points.earliestExpire(orgId, cmd.customerId(), pointType);

        // 落单(第一写;uk exchange_no 兜底并发同号)
        long exchangeId;
        try {
            jdbc.update("""
                    insert into member_exchange
                        (org_id, exchange_no, customer_id, customer_name, point_type, order_id,
                         point_total, status, remark, created_by)
                    values (?,?,?,?,?,?,?,0,?,?)
                    """, orgId, cmd.exchangeNo().trim(), cmd.customerId(), customerName, pointType,
                    cmd.orderId(), total, cmd.remark(), operatorId);
            exchangeId = jdbc.queryForObject("select last_insert_id()", Long.class);
        } catch (DuplicateKeyException e) {
            // 并发同号:DB 兜底重查比对回显(此刻本事务内无已执行写,回显安全)
            Map<String, Object> raced = findByNo(orgId, cmd.exchangeNo().trim());
            require(raced != null, CODE_NO_CONFLICT, "exchange.no.conflict");
            return echoExisting(orgId, raced, cmd);
        }

        // 明细(uk(exchange_id,gift_id) 兜底限件;服务层已去重,此处不会再撞)
        for (Map<String, Object> giftRow : giftRows) {
            jdbc.update("""
                    insert into member_exchange_item
                        (org_id, exchange_id, gift_id, gift_no, gift_name, integral_price, num,
                         integral, status, expire_time)
                    values (?,?,?,?,?,?,1,?,0,?)
                    """, orgId, exchangeId, ((Number) giftRow.get("id")).longValue(),
                    (String) giftRow.get("no"), (String) giftRow.get("name"),
                    ((Number) giftRow.get("integral_price")).longValue(),
                    ((Number) giftRow.get("integral_price")).longValue(), earliestExpire);
        }

        // 扣积分(条件 UPDATE;并发余额只够一单时恰一成功一 350006,整体回滚)
        long accountId = points.accountIdFor(orgId, cmd.customerId(), pointType);
        int deducted = jdbc.update(
                "update member_point_account set balance = balance - ? where id = ? and balance >= ?",
                total, accountId, total);
        if (deducted == 0) {
            throw new BizException(CODE_INSUFFICIENT, msg("point.insufficient"));
        }
        long balanceAfter = jdbc.queryForObject(
                "select balance from member_point_account where id = ?", Long.class, accountId);
        jdbc.update("""
                insert into member_point_record
                    (org_id, customer_id, point_type, type, delta, balance_after, src_addon, src_type, src_id)
                values (?,?,?,?,?,?,?,?,?)
                """, orgId, cmd.customerId(), pointType, MemberPointService.REC_EXCHANGE, -total, balanceAfter,
                "member", "exchange", String.valueOf(exchangeId));

        // 扣库存(isStock=0 才扣;原子条件扣,并发库存不足一单 350003 整体回滚)
        for (Map<String, Object> giftRow : giftRows) {
            if (((Number) giftRow.get("is_stock")).intValue() == 0) {
                gifts.deductForExchange(orgId, ((Number) giftRow.get("id")).longValue(),
                        (String) giftRow.get("no"), 1, "member", "exchange", String.valueOf(exchangeId));
            }
        }

        bus.publish(new ExchangeCreatedEvent(exchangeId, orgId, cmd.exchangeNo().trim(), cmd.customerId(),
                pointType, total, cmd.orderId(), operatorId), "member-exchange:" + exchangeId + ":created");
        return echo(exchangeId, false);
    }

    /** 整单撤销(仅受理单;已核销明细存在即拒;积分与库存对称回补+反向流水)。 */
    @Transactional
    public void cancel(long orgId, long exchangeId, Long operatorId) {
        Map<String, Object> exchange = find(orgId, exchangeId);
        require(exchange != null, CODE_NOT_FOUND, "exchange.notfound");
        require(((Number) exchange.get("status")).intValue() == STATUS_ACCEPTED, CODE_REVOKE, "exchange.revoke");
        List<Map<String, Object>> items = jdbc.queryForList(
                "select id, gift_id, gift_no, integral, status from member_exchange_item"
                + " where org_id = ? and exchange_id = ?", orgId, exchangeId);
        long revokeTotal = 0;
        for (Map<String, Object> item : items) {
            require(((Number) item.get("status")).intValue() != STATUS_CONSUMED, CODE_REVOKE, "exchange.revoke");
            if (((Number) item.get("status")).intValue() == STATUS_ACCEPTED) {
                revokeTotal += ((Number) item.get("integral")).longValue();
            }
        }
        int updated = jdbc.update(
                "update member_exchange set status = ? where id = ? and org_id = ? and status = ?",
                STATUS_CANCELLED, exchangeId, orgId, STATUS_ACCEPTED);
        if (updated == 0) {
            throw new BizException(CODE_REVOKE, msg("exchange.revoke"));
        }
        // 积分对称回补(幂等锚:exchange_cancel 唯一)
        if (revokeTotal > 0) {
            int pointType = ((Number) exchange.get("point_type")).intValue();
            long customerId = ((Number) exchange.get("customer_id")).longValue();
            long accountId = points.accountIdFor(orgId, customerId, pointType);
            jdbc.update("update member_point_account set balance = balance + ? where id = ?",
                    revokeTotal, accountId);
            long balanceAfter = jdbc.queryForObject(
                    "select balance from member_point_account where id = ?", Long.class, accountId);
            jdbc.update("""
                    insert into member_point_record
                        (org_id, customer_id, point_type, type, delta, balance_after, src_addon, src_type, src_id)
                    values (?,?,?,?,?,?,?,?,?)
                    """, orgId, customerId, pointType, MemberPointService.REC_REVOKE, revokeTotal, balanceAfter,
                    "member", "exchange_cancel", String.valueOf(exchangeId));
        }
        // 库存对称回补 + 明细置 2
        for (Map<String, Object> item : items) {
            if (((Number) item.get("status")).intValue() != STATUS_ACCEPTED) {
                continue;
            }
            jdbc.update("update member_exchange_item set status = ? where id = ?", STATUS_CANCELLED, item.get("id"));
            Map<String, Object> gift = jdbc.queryForList(
                    "select is_stock from member_gift where id = ?", ((Number) item.get("gift_id")).longValue())
                    .stream().findFirst().orElse(null);
            if (gift != null && ((Number) gift.get("is_stock")).intValue() == 0) {
                gifts.restoreForRevoke(orgId, ((Number) item.get("gift_id")).longValue(),
                        (String) item.get("gift_no"), 1, "member", "exchange_revoke", String.valueOf(exchangeId));
            }
        }
        bus.publish(new ExchangeCancelledEvent(exchangeId, orgId, (String) exchange.get("exchange_no"),
                ((Number) exchange.get("customer_id")).longValue(),
                ((Number) exchange.get("point_total")).longValue(), operatorId),
                "member-exchange:" + exchangeId + ":cancelled");
    }

    /** 单件撤销(受理明细;积分库存按行回补;整单明细全部离开受理态则单自动置已撤销)。 */
    @Transactional
    public void cancelItem(long orgId, long itemId, Long operatorId) {
        Map<String, Object> item = findItem(orgId, itemId);
        require(item != null, CODE_NOT_FOUND, "exchange.notfound");
        require(((Number) item.get("status")).intValue() == STATUS_ACCEPTED, CODE_REVOKE, "exchange.revoke");
        Map<String, Object> exchange = find(orgId, ((Number) item.get("exchange_id")).longValue());
        require(exchange != null, CODE_NOT_FOUND, "exchange.notfound");
        require(((Number) exchange.get("status")).intValue() == STATUS_ACCEPTED, CODE_REVOKE, "exchange.revoke");

        int pointType = ((Number) exchange.get("point_type")).intValue();
        long customerId = ((Number) exchange.get("customer_id")).longValue();
        long integral = ((Number) item.get("integral")).longValue();
        long exchangeId = ((Number) item.get("exchange_id")).longValue();

        int updated = jdbc.update(
                "update member_exchange_item set status = ? where id = ? and status = ?",
                STATUS_CANCELLED, itemId, STATUS_ACCEPTED);
        if (updated == 0) {
            throw new BizException(CODE_REVOKE, msg("exchange.revoke"));
        }
        // 积分回补(幂等锚:item 唯一)
        long accountId = points.accountIdFor(orgId, customerId, pointType);
        jdbc.update("update member_point_account set balance = balance + ? where id = ?", integral, accountId);
        long balanceAfter = jdbc.queryForObject(
                "select balance from member_point_account where id = ?", Long.class, accountId);
        jdbc.update("""
                insert into member_point_record
                    (org_id, customer_id, point_type, type, delta, balance_after, src_addon, src_type, src_id)
                values (?,?,?,?,?,?,?,?,?)
                """, orgId, customerId, pointType, MemberPointService.REC_REVOKE, integral, balanceAfter,
                "member", "exchange_item_cancel", String.valueOf(itemId));
        // 库存回补
        Map<String, Object> gift = jdbc.queryForList(
                "select is_stock from member_gift where id = ?", ((Number) item.get("gift_id")).longValue())
                .stream().findFirst().orElse(null);
        if (gift != null && ((Number) gift.get("is_stock")).intValue() == 0) {
            gifts.restoreForRevoke(orgId, ((Number) item.get("gift_id")).longValue(),
                    (String) item.get("gift_no"), 1, "member", "exchange_revoke", String.valueOf(itemId));
        }
        refreshExchangeStatus(orgId, exchangeId);
    }

    /** 单件核销(REST 路径;事务由代理开启)。 */
    @Transactional
    public void consumeItem(long orgId, long itemId, String content, Long operatorId) {
        doConsumeItem(orgId, itemId, content, operatorId);
    }

    /** 核销核心(无注解;事务边界由调用方决定——REST 走 @Transactional 代理,签约自动核销走 TransactionTemplate)。 */
    private void doConsumeItem(long orgId, long itemId, String content, Long operatorId) {
        Map<String, Object> item = findItem(orgId, itemId);
        require(item != null, CODE_NOT_FOUND, "exchange.notfound");
        int status = ((Number) item.get("status")).intValue();
        require(status != STATUS_CONSUMED, CODE_CONSUMED, "exchange.item.status");
        require(status != STATUS_CANCELLED, CODE_REVOKE, "exchange.revoke");
        Map<String, Object> exchange = find(orgId, ((Number) item.get("exchange_id")).longValue());
        require(exchange != null, CODE_NOT_FOUND, "exchange.notfound");
        require(((Number) exchange.get("status")).intValue() == STATUS_ACCEPTED, CODE_REVOKE, "exchange.revoke");

        // 过期闸:可用积分(含过期过滤)< 0 = 本单扣减的批次已过期 → 拦核销(350009)
        long available = points.available(orgId, ((Number) exchange.get("customer_id")).longValue(),
                ((Number) exchange.get("point_type")).intValue());
        if (available < 0) {
            throw new BizException(CODE_EXPIRED, msg("point.expired"));
        }
        String finalContent = content != null && !content.isBlank() ? content.trim() : msg("exchange.consume.manual");
        int updated = jdbc.update("""
                update member_exchange_item set status = ?, consumed_at = now(), consumed_by = ?,
                    consume_content = ? where id = ? and status = ?
                """, STATUS_CONSUMED, operatorId, finalContent, itemId, STATUS_ACCEPTED);
        if (updated == 0) {
            // 并发双核销:重查定位语义(已核销 350011 / 已撤销 350012)
            Map<String, Object> latest = findItem(orgId, itemId);
            require(latest != null && ((Number) latest.get("status")).intValue() == STATUS_ACCEPTED,
                    CODE_CONSUMED, "exchange.item.status");
            throw new BizException(CODE_CONSUMED, msg("exchange.item.status"));
        }
        refreshExchangeStatus(orgId, ((Number) item.get("exchange_id")).longValue());
    }

    /**
     * 签约自动核销(消费 DecorationSignedEvent):组织开关开启时,把该装修单名下受理明细逐件核销,
     * content 取组织配置(修老硬编码'签约自动核销')。逐件独立:单件守卫失配跳过不阻塞其余。
     * 🔴 事务:每件经 TransactionTemplate 独立事务(直调 this.consumeItem 是自调用不走代理,@Transactional
     * 失效;也不能整批一个事务——内部消化 BizException 会标记 rollback-only 炸 UnexpectedRollbackException)。
     */
    public void autoSignConsume(long orgId, long orderId) {
        Map<String, Object> config = configOf(orgId);
        if (config == null || ((Number) config.get("auto_sign_consume")).intValue() != 1) {
            return;
        }
        String content = String.valueOf(config.get("sign_consume_content"));
        List<Map<String, Object>> items = jdbc.queryForList("""
                select i.id from member_exchange_item i
                join member_exchange e on i.exchange_id = e.id
                where e.org_id = ? and e.order_id = ? and i.status = ?
                """, orgId, orderId, STATUS_ACCEPTED);
        for (Map<String, Object> item : items) {
            long itemId = ((Number) item.get("id")).longValue();
            try {
                txTemplate.executeWithoutResult(tx -> doConsumeItem(orgId, itemId, content, null));
            } catch (BizException e) {
                log.warn("member auto sign consume skipped item {}: {}", itemId, e.getMessage());
            }
        }
    }

    // ---- 组织配置 ----

    public Map<String, Object> configOf(long orgId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select org_id, auto_sign_consume, sign_consume_content from member_config where org_id = ?", orgId);
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        try {
            jdbc.update("insert ignore into member_config (org_id, auto_sign_consume, sign_consume_content)"
                    + " values (?, 0, '签约自动核销')", orgId);
        } catch (DuplicateKeyException ignored) {
            // 并发懒初始化
        }
        rows = jdbc.queryForList(
                "select org_id, auto_sign_consume, sign_consume_content from member_config where org_id = ?", orgId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional
    public void saveConfig(long orgId, Integer autoSignConsume, String signConsumeContent) {
        require(autoSignConsume == null || autoSignConsume == 0 || autoSignConsume == 1,
                CODE_PARAM, "param.invalid");
        configOf(orgId);
        jdbc.update("update member_config set auto_sign_consume = ?, sign_consume_content = ? where org_id = ?",
                autoSignConsume == null ? 0 : autoSignConsume,
                signConsumeContent == null || signConsumeContent.isBlank() ? "签约自动核销" : signConsumeContent.trim(),
                orgId);
    }

    // ---- 查询 ----

    public Map<String, Object> get(long orgId, long exchangeId) {
        Map<String, Object> exchange = find(orgId, exchangeId);
        require(exchange != null, CODE_NOT_FOUND, "exchange.notfound");
        List<Map<String, Object>> items = jdbc.queryForList("""
                select id, exchange_id, gift_id, gift_no, gift_name, integral_price, num, integral,
                       status, expire_time, consumed_at, consumed_by, consume_content, created_at
                from member_exchange_item where org_id = ? and exchange_id = ? order by id
                """, orgId, exchangeId);
        exchange.put("items", items);
        return exchange;
    }

    public Map<String, Object> page(long orgId, Integer status, String exchangeNo, Long customerId,
            int pageNum, int size) {
        int safePage = Math.max(pageNum, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(" where org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        if (status != null) {
            where.append(" and status = ?");
            args.add(status);
        }
        if (exchangeNo != null && !exchangeNo.isBlank()) {
            where.append(" and exchange_no = ?");
            args.add(exchangeNo.trim());
        }
        if (customerId != null) {
            where.append(" and customer_id = ?");
            args.add(customerId);
        }
        Long total = jdbc.queryForObject("select count(*) from member_exchange" + where, Long.class, args.toArray());
        List<Object> allArgs = new ArrayList<>(args);
        allArgs.add(safeSize);
        allArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, org_id, exchange_no, customer_id, customer_name, point_type, order_id,"
                + " point_total, status, remark, created_by, created_at, updated_at from member_exchange" + where
                + " order by id desc limit ? offset ?", allArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    // ---- 内部 ----

    /** 单状态重算:全部明细已核销 → 单置已核销;无受理明细(且存在撤销)→ 单置已撤销。 */
    private void refreshExchangeStatus(long orgId, long exchangeId) {
        List<Map<String, Object>> agg = jdbc.queryForList("""
                select sum(status = ?) as consumed, sum(status = ?) as cancelled, count(*) as total
                from member_exchange_item where org_id = ? and exchange_id = ?
                """, STATUS_CONSUMED, STATUS_CANCELLED, orgId, exchangeId);
        if (agg.isEmpty()) {
            return;
        }
        long consumed = ((Number) agg.get(0).get("consumed")).longValue();
        long cancelled = ((Number) agg.get(0).get("cancelled")).longValue();
        long total = ((Number) agg.get(0).get("total")).longValue();
        if (consumed == total) {
            jdbc.update("update member_exchange set status = ? where id = ? and status = ?",
                    STATUS_CONSUMED, exchangeId, STATUS_ACCEPTED);
        } else if (cancelled == total) {
            jdbc.update("update member_exchange set status = ? where id = ? and status = ?",
                    STATUS_CANCELLED, exchangeId, STATUS_ACCEPTED);
        }
    }

    /** 幂等回显前的内容比对:同号同客户同分项同礼品集合=同一单,回显;否则 350013。 */
    private Map<String, Object> echoExisting(long orgId, Map<String, Object> exchange, PlaceCmd cmd) {
        require(cmd.customerId() != null
                && ((Number) exchange.get("customer_id")).longValue() == cmd.customerId()
                && ((Number) exchange.get("point_type")).intValue() == (cmd.pointType() == null ? -1 : cmd.pointType()),
                CODE_NO_CONFLICT, "exchange.no.conflict");
        List<String> existingGifts = jdbc.queryForList(
                "select gift_no from member_exchange_item where org_id = ? and exchange_id = ? order by gift_no",
                String.class, orgId, ((Number) exchange.get("id")).longValue());
        List<String> wants = cmd.items().stream().map(i -> i.giftNo().trim()).sorted().toList();
        require(existingGifts.equals(wants), CODE_NO_CONFLICT, "exchange.no.conflict");
        return echo(((Number) exchange.get("id")).longValue(), false);
    }

    private Map<String, Object> echo(long exchangeId, boolean created) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", exchangeId);
        result.put("created", created);
        return result;
    }

    private Map<String, Object> find(long orgId, long exchangeId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, org_id, exchange_no, customer_id, customer_name, point_type, order_id,"
                + " point_total, status, remark, created_by, created_at, updated_at from member_exchange"
                + " where id = ? and org_id = ?", exchangeId, orgId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> findByNo(long orgId, String exchangeNo) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, org_id, exchange_no, customer_id, customer_name, point_type, order_id,"
                + " point_total, status, remark from member_exchange where exchange_no = ? and org_id = ?",
                exchangeNo, orgId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> findItem(long orgId, long itemId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, org_id, exchange_id, gift_id, gift_no, gift_name, integral_price, num, integral,"
                + " status, expire_time, consumed_at, consumed_by, consume_content from member_exchange_item"
                + " where id = ? and org_id = ?", itemId, orgId);
        return rows.isEmpty() ? null : rows.get(0);
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
