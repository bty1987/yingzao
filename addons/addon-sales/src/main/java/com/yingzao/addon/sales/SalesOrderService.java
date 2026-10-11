package com.yingzao.addon.sales;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.addons.api.DecorationApi;
import com.yingzao.addons.api.GoodsApi;
import com.yingzao.addons.api.PaymentApi;
import com.yingzao.addons.api.event.SalesOrderCreatedEvent;
import com.yingzao.addons.api.event.SalesOrderStatusChangedEvent;
import com.yingzao.core.eventbus.YzEventBus;
import com.yingzao.core.notification.NotificationService;
import com.yingzao.core.web.BizException;

/**
 * 销售单主链:开单(挂装修单,DecorationApi 校验+展示快照)→明细维护(服务端算价,拒前端金额)→
 * 客户确认(验证码+手机三重校验服务端强制,修老单传其一即绕过)→收款(addon-payment 事件驱动款项轴)→
 * 完结/废单。老定金线随 payment 多款项能力 V2,款项轴 V1 单全款线(typeCode=salesFull)。
 * 五计数事件回写:出货扣 xgnum(原子,不足=超卖死信)/删行回加/采购入库加 punum/采购释放扣 punum——
 * 本域零直写他域表,inventory 亦零直写本域(修老互相直改)。
 * 算价:disPrice = selling×discount/1000,total = disPrice×num/1000(千分位件数×厘单价),HALF_UP。
 */
@Service
public class SalesOrderService {

    public static final int CODE_NOT_FOUND = 300001;
    public static final int CODE_BAD_STATE = 300002;
    public static final int CODE_PARAM = 300003;
    public static final int CODE_DECORATION = 300004;
    public static final int CODE_EMPTY_ITEMS = 300005;
    public static final int CODE_ITEMS_FROZEN = 300006;
    public static final int CODE_NUM_POSITIVE = 300007;
    public static final int CODE_XGNUM_SHORT = 300008;
    public static final int CODE_GOODS = 300009;
    public static final int CODE_CONFIRM_PARAMS = 300010;
    public static final int CODE_VERIFY_CODE = 300011;
    public static final int CODE_ALREADY_CONFIRMED = 300012;
    public static final int CODE_VERIFY_FREQUENT = 300028;
    public static final int CODE_BILL_TYPE_MISSING = 300029;
    public static final int CODE_ITEM_MISMATCH = 300027;

    // 主单轴(沿老编号,缺 1 系历史痕迹;行业共性四轴保留)
    public static final int ST_INIT = 0;
    public static final int ST_FULL_REFUND = 2;
    public static final int ST_COUNTERMAND = 3;
    public static final int ST_TERMINATED = 4;
    public static final int ST_FINISH = 5;
    // 结算轴
    public static final int BS_NONE = 0;
    public static final int BS_PRE = 1;
    public static final int BS_VPOST = 2;
    public static final int BS_POST = 3;
    // 款项轴(V1 单全款线)
    public static final int FS_UNRECORDED = -1;
    public static final int FS_RECORDED = 0;
    public static final int FS_PAID = 1;
    public static final int FS_CONFIRMED = 2;

    public static final String BIZ_ADDON = "sales";
    public static final String BIZ_TYPE_ORDER = "order";
    /** 全款款项类型协议键(组织款项类型字典须预置启用行)。 */
    public static final String TYPE_CODE_FULL = "sales_full";

    private static final String PREFIX_XS = "XS";

    public record ItemCmd(Long goodsId, Long colorId, Long formatId, Long num, Long selling,
                          Long discount) {
    }

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final YzEventBus eventBus;
    private final DecorationApi decorations;
    private final GoodsApi goods;
    private final PaymentApi payments;
    private final NotificationService notifications;

    public SalesOrderService(JdbcTemplate jdbc, MessageSource messages, YzEventBus eventBus,
            DecorationApi decorations, GoodsApi goods, PaymentApi payments,
            NotificationService notifications) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.eventBus = eventBus;
        this.decorations = decorations;
        this.goods = goods;
        this.payments = payments;
        this.notifications = notifications;
    }

    // ---- 开单 ----

    @Transactional
    public long create(long orgId, long orderId, List<ItemCmd> items, String remark, long operatorId) {
        DecorationApi.OrderSummary order = decorations.queryOrder(orgId, orderId)
                .orElseThrow(() -> new BizException(CODE_DECORATION, msg("sales.decoration.notFound")));
        // 合同线废单(5)/终止(6)不可挂单
        if (order.status() == 5 || order.status() == 6) {
            throw new BizException(CODE_DECORATION, msg("sales.decoration.notFound"));
        }
        require(items != null && !items.isEmpty(), CODE_EMPTY_ITEMS, "sales.items.empty");
        // 单号撞号重试(uk_sales_code 兜底:计数器与历史单错位时重取流水,不裸炸)
        long saleId = -1;
        String code = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                code = PREFIX_XS + codeDate() + String.format("%04d", (int) seq(orgId, PREFIX_XS));
                jdbc.update("""
                        insert into sales_order(org_id, code, order_id, order_no, customer_name, house_label,
                            salesman_id, status, balance_status, fullpay_status, customer_confirmed, total,
                            remark, created_by, created_by_name)
                        values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """, orgId, code, orderId, order.orderNo(), order.customerName(), order.houseLabel(),
                        order.salesmanId(), ST_INIT, BS_NONE, FS_UNRECORDED, 0, 0L, blankToNull(remark),
                        operatorId, displayName(operatorId));
                saleId = jdbc.queryForObject("select last_insert_id()", Long.class);
                break;
            } catch (DuplicateKeyException e) {
                if (attempt == 2) {
                    throw new BizException(CODE_PARAM, msg("sales.param.invalid"));
                }
            }
        }
        long total = replaceItems(orgId, saleId, items);
        jdbc.update("update sales_order set total = ? where id = ?", total, saleId);
        track(orgId, saleId, "create", "开单挂装修单 " + order.orderNo(), operatorId);
        eventBus.publish(new SalesOrderCreatedEvent(saleId, orgId, orderId, order.orderNo(),
                code, total, operatorId), "sales:" + saleId + ":created");
        return saleId;
    }

    /** 明细全量替换(修老信任前端 total):仅初始态且款项未付;套餐展开+空配置降级;xgnum 基线=num。 */
    @Transactional
    public long updateItems(long orgId, long saleId, List<ItemCmd> items, long operatorId) {
        Map<String, Object> sale = loadSale(orgId, saleId);
        int fs = ((Number) sale.get("fullpay_status")).intValue();
        require(((Number) sale.get("status")).intValue() == ST_INIT, CODE_BAD_STATE, "sales.state.illegal");
        require(fs == FS_UNRECORDED || fs == FS_RECORDED, CODE_ITEMS_FROZEN, "sales.items.frozen");
        require(items != null && !items.isEmpty(), CODE_EMPTY_ITEMS, "sales.items.empty");
        jdbc.update("delete from sales_order_item where org_id = ? and sale_id = ?", orgId, saleId);
        long total = replaceItems(orgId, saleId, items);
        jdbc.update("update sales_order set total = ? where id = ?", total, saleId);
        if (fs == FS_RECORDED) {
            resyncBill(orgId, saleId, total);
        }
        track(orgId, saleId, "updateItem", "明细调整,总额 " + yuan(total), operatorId);
        return total;
    }

    /** 生成/重生成全款款项单(款项轴 -1/0→0 已录;已付禁改)。 */
    @Transactional
    public void generateBill(long orgId, long saleId, long operatorId) {
        Map<String, Object> sale = loadSale(orgId, saleId);
        int fs = ((Number) sale.get("fullpay_status")).intValue();
        require(fs == FS_UNRECORDED || fs == FS_RECORDED, CODE_ITEMS_FROZEN, "sales.items.frozen");
        long total = ((Number) sale.get("total")).longValue();
        resyncBill(orgId, saleId, total);
        if (fs == FS_UNRECORDED) {
            jdbc.update("update sales_order set fullpay_status = ? where id = ? and fullpay_status = ?",
                    FS_RECORDED, saleId, FS_UNRECORDED);
        }
        track(orgId, saleId, "generateBill", "生成全款款项单 " + yuan(total), operatorId);
    }

    /** 款项单确保存在(生成/重建):作废未收旧单后按当前总额重建;录款动作显式走本方法。 */
    /** 款项单确保存在(生成/重建):作废未收旧单后按当前总额重建;录款动作显式走本方法。 */
    private void resyncBill(long orgId, long saleId, long total) {
        for (PaymentApi.BillSummary bill : payments.listBills(orgId, BIZ_ADDON, BIZ_TYPE_ORDER,
                String.valueOf(saleId))) {
            if (bill.direction() == 0 && bill.status() == 0) {
                payments.cancelBill(orgId, bill.billId());
            }
        }
        if (total > 0) {
            payments.createBill(new PaymentApi.CreateBillCmd(orgId, BIZ_ADDON, BIZ_TYPE_ORDER,
                    String.valueOf(saleId), TYPE_CODE_FULL, total, null));
        }
    }

    /** 明细展开落库,返回主项总额(套餐子项不计入,老口径保留);空配置套餐降级普通商品。 */
    private long replaceItems(long orgId, long saleId, List<ItemCmd> items) {
        jdbc.update("delete from sales_order_item where sale_id = ? and package_id > 0", saleId);
        jdbc.update("delete from sales_order_item where sale_id = ? and package_id = 0", saleId);
        long total = 0;
        for (ItemCmd cmd : items) {
            require(cmd != null && cmd.goodsId() != null, CODE_PARAM, "sales.param.invalid");
            require(cmd.num() != null && cmd.num() > 0, CODE_NUM_POSITIVE, "sales.num.positive");
            long discount = cmd.discount() == null ? 1000 : cmd.discount();
            require(discount > 0 && discount <= 100000, CODE_PARAM, "sales.param.invalid");
            GoodsApi.GoodsSummary g = goods.queryGoods(orgId, cmd.goodsId())
                    .orElseThrow(() -> new BizException(CODE_GOODS, msg("sales.goods.notFound")));
            if (g.status() != 0) {
                throw new BizException(CODE_GOODS, msg("sales.goods.notFound"));
            }
            long selling = cmd.selling() == null ? g.selling() : cmd.selling();
            require(selling > 0, CODE_PARAM, "sales.param.invalid");
            long mainId;
            if (g.type() == 1) {
                List<GoodsApi.PackageItem> members = goods.listPackageItems(orgId, g.goodsId());
                if (members.isEmpty()) {
                    // 空配置套装降级:按普通商品落单(老 eeac3c0c1 语义,防孤儿套餐缺行)
                    mainId = insertItem(orgId, saleId, 0L, g, cmd.colorId(), cmd.formatId(),
                            cmd.num(), cmd.num(), selling, discount);
                    total += itemTotal(mainId);
                    continue;
                }
                mainId = insertItem(orgId, saleId, 0L, g, cmd.colorId(), cmd.formatId(),
                        cmd.num(), cmd.num(), selling, discount);
                total += itemTotal(mainId);
                for (GoodsApi.PackageItem m : members) {
                    GoodsApi.GoodsSummary mg = goods.queryGoods(orgId, m.goodsId())
                            .orElseThrow(() -> new BizException(CODE_GOODS, msg("sales.goods.notFound")));
                    long mnum = cmd.num() * m.num() / 1000;
                    if (mnum <= 0) {
                        continue;
                    }
                    insertItem(orgId, saleId, mainId, mg, 0L, 0L, mnum, m.num(),
                            mg.selling(), 1000L);
                }
            } else {
                mainId = insertItem(orgId, saleId, 0L, g, cmd.colorId(), cmd.formatId(),
                        cmd.num(), cmd.num(), selling, discount);
                total += itemTotal(mainId);
            }
        }
        return total;
    }

    private long insertItem(long orgId, long saleId, long packageId, GoodsApi.GoodsSummary g,
            Long colorId, Long formatId, long num, long initnum, long selling, long discount) {
        long cid = nvl(colorId);
        long fid = nvl(formatId);
        String colorName = attrName(orgId, g.goodsId(), cid);
        String formatName = attrName(orgId, g.goodsId(), fid);
        long disPrice = (selling * discount + 500) / 1000;
        long total = (disPrice * num + 500) / 1000;
        jdbc.update("""
                insert into sales_order_item(org_id, sale_id, package_id, goods_id, color_id, format_id,
                    goods_name, color_name, format_name, brand_id, brand_name,
                    num, initnum, punum, xgnum, trnum, selling, discount, dis_price, total)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, orgId, saleId, packageId, g.goodsId(), cid, fid, g.name(), colorName, formatName,
                g.brandId(), g.brandName() == null ? "" : g.brandName(), num, initnum, 0, num, 0,
                selling, discount, disPrice, total);
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    private String attrName(long orgId, long goodsId, long attrId) {
        if (attrId <= 0) {
            return "";
        }
        return goods.querySkuAttr(orgId, goodsId, attrId).map(a -> a.value()).orElse("");
    }

    private long itemTotal(long itemId) {
        Long total = jdbc.queryForObject(
                "select total from sales_order_item where id = ? and package_id = 0", Long.class, itemId);
        return total == null ? 0 : total;
    }

    // ---- 客户确认(三重校验服务端强制) ----

    @Transactional
    public void sendVerifyCode(long orgId, long saleId, String phone, long operatorId) {
        loadSale(orgId, saleId);
        require(notBlank(phone) && phone.trim().matches("\\d{6,20}"), CODE_CONFIRM_PARAMS,
                "sales.confirm.params");
        Integer recent = jdbc.queryForObject("""
                select count(*) from sales_verify_code
                where org_id = ? and biz_type = 'order' and biz_id = ? and phone = ?
                  and created_at > date_sub(now(), interval 1 minute)
                """, Integer.class, orgId, saleId, phone.trim());
        if (recent != null && recent > 0) {
            throw new BizException(CODE_VERIFY_FREQUENT, msg("sales.verify.frequent"));
        }
        String code = String.valueOf((int) ((Math.random() * 9 + 1) * 100000));
        jdbc.update("""
                insert into sales_verify_code(org_id, biz_type, biz_id, phone, code, expire_at)
                values (?,?,?,?,?, date_add(now(), interval 10 minute))
                """, orgId, "order", saleId, phone.trim(), code);
        notifications.send(new NotificationService.SendCmd(orgId, operatorId,
                msg("sales.verify.notifyTitle"), msg("sales.verify.notifyBody") + code,
                BIZ_ADDON, "verifyCode", String.valueOf(saleId)));
    }

    @Transactional
    public void confirm(long orgId, long saleId, String phone, String code) {
        require(notBlank(phone) && notBlank(code), CODE_CONFIRM_PARAMS, "sales.confirm.params");
        Map<String, Object> sale = loadSale(orgId, saleId);
        require(((Number) sale.get("customer_confirmed")).intValue() == 0, CODE_ALREADY_CONFIRMED,
                "sales.confirm.duplicated");
        int updated = jdbc.update("""
                update sales_verify_code set used_at = now()
                where org_id = ? and biz_type = 'order' and biz_id = ? and phone = ? and code = ?
                  and used_at is null and expire_at > now()
                """, orgId, saleId, phone.trim(), code.trim());
        require(updated > 0, CODE_VERIFY_CODE, "sales.verify.invalid");
        jdbc.update("update sales_order set customer_confirmed = 1, confirmed_at = now() where id = ?",
                saleId);
    }

    // ---- 完结/废单 ----

    @Transactional
    public void finish(long orgId, long saleId, long operatorId) {
        Map<String, Object> sale = loadSale(orgId, saleId);
        require(((Number) sale.get("status")).intValue() == ST_INIT, CODE_BAD_STATE, "sales.state.illegal");
        require(((Number) sale.get("fullpay_status")).intValue() == FS_CONFIRMED, CODE_BAD_STATE,
                "sales.finish.notConfirmed");
        Integer delivering = jdbc.queryForObject("""
                select count(*) from sales_delivery_item
                where org_id = ? and sale_item_id in (select id from sales_order_item where sale_id = ?)
                  and status < 4
                """, Integer.class, orgId, saleId);
        require(delivering == null || delivering == 0, CODE_BAD_STATE, "sales.finish.delivering");
        int updated = jdbc.update(
                "update sales_order set status = ?, finish_time = now() where id = ? and status = ?",
                ST_FINISH, saleId, ST_INIT);
        require(updated > 0, CODE_BAD_STATE, "sales.state.illegal");
        track(orgId, saleId, "finish", "完结", operatorId);
        publishStatus(orgId, sale, saleId, ST_INIT, ST_FINISH, SalesOrderStatusChangedEvent.ACTION_FINISH,
                operatorId);
    }

    @Transactional
    public void terminate(long orgId, long saleId, long operatorId) {
        Map<String, Object> sale = loadSale(orgId, saleId);
        require(((Number) sale.get("status")).intValue() == ST_INIT, CODE_BAD_STATE, "sales.state.illegal");
        require(((Number) sale.get("balance_status")).intValue() == BS_NONE, CODE_BAD_STATE,
                "sales.terminate.guard");
        int fs = ((Number) sale.get("fullpay_status")).intValue();
        require(fs == FS_UNRECORDED || fs == FS_RECORDED, CODE_BAD_STATE, "sales.terminate.guard");
        Integer deliveries = jdbc.queryForObject(
                "select count(*) from sales_delivery where org_id = ? and sale_id = ?",
                Integer.class, orgId, saleId);
        require(deliveries == null || deliveries == 0, CODE_BAD_STATE, "sales.terminate.guard");
        Integer refunds = jdbc.queryForObject(
                "select count(*) from sales_refunds where org_id = ? and sale_id = ? and status in (3, 0, 1)",
                Integer.class, orgId, saleId);
        require(refunds == null || refunds == 0, CODE_BAD_STATE, "sales.terminate.guard");
        for (PaymentApi.BillSummary bill : payments.listBills(orgId, BIZ_ADDON, BIZ_TYPE_ORDER,
                String.valueOf(saleId))) {
            if (bill.direction() == 0 && bill.status() == 0) {
                payments.cancelBill(orgId, bill.billId());
            }
        }
        int updated = jdbc.update(
                "update sales_order set status = ?, fullpay_status = ? where id = ? and status = ?",
                ST_TERMINATED, FS_UNRECORDED, saleId, ST_INIT);
        require(updated > 0, CODE_BAD_STATE, "sales.state.illegal");
        track(orgId, saleId, "terminate", "废单", operatorId);
        publishStatus(orgId, sale, saleId, ST_INIT, ST_TERMINATED,
                SalesOrderStatusChangedEvent.ACTION_TERMINATE, operatorId);
    }

    // ---- 查询 ----

    public Map<String, Object> page(long orgId, String code, Long orderId, Integer status,
            Integer balanceStatus, int pageNo, int size) {
        int safePage = Math.max(pageNo, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(" where org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        if (notBlank(code)) {
            where.append(" and code like ?");
            args.add("%" + code.trim() + "%");
        }
        if (orderId != null) {
            where.append(" and order_id = ?");
            args.add(orderId);
        }
        if (status != null) {
            where.append(" and status = ?");
            args.add(status);
        }
        if (balanceStatus != null) {
            where.append(" and balance_status = ?");
            args.add(balanceStatus);
        }
        String w = where.toString();
        Long count = jdbc.queryForObject("select count(*) from sales_order" + w, Long.class, args.toArray());
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, code, order_id, order_no, customer_name, house_label, status, balance_status,
                       fullpay_status, customer_confirmed, total, finish_time, created_by_name, created_at
                from sales_order""" + w + " order by id desc limit ? offset ?",
                appendArgs(args, safeSize, (safePage - 1) * safeSize));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", count == null ? 0 : count);
        result.put("rows", rows);
        return result;
    }

    public Map<String, Object> detail(long orgId, long saleId) {
        Map<String, Object> sale = loadSale(orgId, saleId);
        sale.put("items", jdbc.queryForList("""
                select id, package_id, goods_id, goods_name, color_name, format_name, brand_id, brand_name,
                       num, initnum, punum, xgnum, trnum, selling, discount, dis_price, total
                from sales_order_item where org_id = ? and sale_id = ? order by package_id, id
                """, orgId, saleId));
        sale.put("settlements", jdbc.queryForList("""
                select id, brand_id, brand_name, type, onetype, rid, total, balance, vbalance, pbalance,
                       difference, status, vposted_at, posted_at, created_at
                from sales_settlement where org_id = ? and sale_id = ? order by type, id
                """, orgId, saleId));
        sale.put("refunds", jdbc.queryForList("""
                select id, type, status, approval_id, is_affirm, amount, remark, created_by_name, created_at
                from sales_refunds where org_id = ? and sale_id = ? order by id
                """, orgId, saleId));
        sale.put("deliveries", jdbc.queryForList("""
                select id, remark, created_by_name, created_at from sales_delivery
                where org_id = ? and sale_id = ? order by id
                """, orgId, saleId));
        sale.put("bills", payments.listBills(orgId, BIZ_ADDON, BIZ_TYPE_ORDER, String.valueOf(saleId))
                .stream().map(b -> {
                    Map<String, Object> v = new LinkedHashMap<String, Object>();
                    v.put("billId", b.billId());
                    v.put("typeCode", b.typeCode());
                    v.put("typeName", b.typeName());
                    v.put("direction", b.direction());
                    v.put("amount", b.amount());
                    v.put("status", b.status());
                    return v;
                }).toList());
        sale.put("tracks", jdbc.queryForList("""
                select action, detail, operator_name, created_at from sales_track
                where org_id = ? and sale_id = ? order by id
                """, orgId, saleId));
        return sale;
    }

    public List<Map<String, Object>> deliveryItems(long orgId, long saleId) {
        return jdbc.queryForList("""
                select di.id, di.delivery_id, di.sale_item_id, di.goods_name, di.color_name, di.format_name,
                       di.num, di.status, di.tran_at, di.check_at, di.confirm_at, di.finish_at
                from sales_delivery_item di
                join sales_delivery d on d.id = di.delivery_id
                where di.org_id = ? and d.sale_id = ? order by di.id
                """, orgId, saleId);
    }

    // ---- 事件回写:款项三事件(总线 SPI 由 SalesPaymentConsumer 调入) ----

    /** 收款:-1/0 → 1 已付(条件 UPDATE,事件重放幂等)。 */
    @Transactional
    public void onPaymentCollected(long orgId, long saleId) {
        jdbc.update("""
                update sales_order set fullpay_status = 1
                where id = ? and org_id = ? and fullpay_status in (-1, 0)
                """, saleId, orgId);
    }

    /** 确认:1 → 2 已确认;成功后生成按品牌预结算行(uk 兜底幂等)+结算轴 0→1。 */
    @Transactional
    public void onPaymentConfirmed(long orgId, long saleId, long operatorId) {
        int updated = jdbc.update("""
                update sales_order set fullpay_status = 2
                where id = ? and org_id = ? and fullpay_status = 1
                """, saleId, orgId);
        if (updated == 0) {
            return; // 事件重放/非已付态
        }
        generateSettlements(orgId, saleId, operatorId);
        jdbc.update("update sales_order set balance_status = ? where id = ? and balance_status = ?",
                BS_PRE, saleId, BS_NONE);
        track(orgId, saleId, "confirmPay", "全款确认,生成预结算行", operatorId);
    }

    /** 按明细主项品牌分组生成购货预结行(type=0);uk(org,sale,brand,type,rid) 兜底幂等。 */
    private void generateSettlements(long orgId, long saleId, long operatorId) {
        List<Map<String, Object>> groups = jdbc.queryForList("""
                select brand_id, brand_name, sum(total) as amount
                from sales_order_item where org_id = ? and sale_id = ? and package_id = 0
                group by brand_id, brand_name
                """, orgId, saleId);
        for (Map<String, Object> g : groups) {
            long brandId = ((Number) g.get("brand_id")).longValue();
            String brandName = (String) g.get("brand_name");
            long amount = ((Number) g.get("amount")).longValue();
            jdbc.update("""
                    insert ignore into sales_settlement(org_id, sale_id, brand_id, brand_name, type,
                        onetype, rid, total, balance, vbalance, pbalance, difference, status, created_by)
                    values (?,?,?,?,?,0,0,?,?,?,?,0,0,?)
                    """, orgId, saleId, brandId, brandName == null ? "" : brandName, 0,
                    amount, amount, amount, amount, operatorId);
        }
    }

    /** 退款完成(PaymentRefundedEvent):该单初始态退货单 → 1 已退款。 */
    @Transactional
    public void onPaymentRefunded(long orgId, long saleId) {
        jdbc.update("""
                update sales_refunds set status = 1
                where org_id = ? and sale_id = ? and status = 0
                """, orgId, saleId);
    }

    // ---- 事件回写:库存三事件(总线 SPI 由 SalesStockConsumer 调入;计数回写依赖总线消费确认语义) ----

    /** 出货(XG):扣 xgnum,原子条件,不足 = 超卖(死信可见,修老 inventory 直改本域表)。 */
    @Transactional
    public void onStockOut(long orgId, long itemId, long num) {
        int updated = jdbc.update("""
                update sales_order_item set xgnum = xgnum - ?
                where id = ? and org_id = ? and xgnum >= ?
                """, num, itemId, orgId, num);
        if (updated == 0) {
            throw new BizException(CODE_XGNUM_SHORT, msg("sales.xgnum.short"));
        }
    }

    /** 出库单初始态删行:xgnum 回加(出货未遂回冲)。 */
    @Transactional
    public void onStockOutRemoved(long orgId, long itemId, long num) {
        jdbc.update("update sales_order_item set xgnum = xgnum + ? where id = ? and org_id = ?",
                num, itemId, orgId);
    }

    /** 采购入库(PU 货单):punum 增加(已采购核销数)。 */
    @Transactional
    public void onPurchaseInbound(long orgId, long itemId, long num) {
        jdbc.update("update sales_order_item set punum = punum + ? where id = ? and org_id = ?",
                num, itemId, orgId);
    }

    /** 采购取消/删项释放:punum 扣减(原子,修老 punum 永不释放)。 */
    @Transactional
    public void onPurchaseReleased(long orgId, long itemId, long num) {
        jdbc.update("""
                update sales_order_item set punum = punum - ?
                where id = ? and org_id = ? and punum >= ?
                """, num, itemId, orgId, num);
    }

    // ---- 销售款比例(SalesApi 契约实现源;施工开工/完工闸消费) ----

    public long collectedRatio(long orgId, long orderId) {
        List<Map<String, Object>> sales = jdbc.queryForList(
                "select id from sales_order where org_id = ? and order_id = ? and status <> ?",
                orgId, orderId, ST_TERMINATED);
        long denom = 0;
        long numer = 0;
        for (Map<String, Object> s : sales) {
            long saleId = ((Number) s.get("id")).longValue();
            for (PaymentApi.BillSummary bill : payments.listBills(orgId, BIZ_ADDON, BIZ_TYPE_ORDER,
                    String.valueOf(saleId))) {
                if (bill.direction() != 0 || bill.status() == 3 || bill.status() == 4) {
                    continue;
                }
                denom += bill.amount();
                if (bill.status() == 1 || bill.status() == 2) {
                    numer += bill.amount();
                }
            }
        }
        return denom == 0 ? 0 : numer * 1000 / denom;
    }

    // ---- 内部工具 ----

    public Map<String, Object> loadSale(long orgId, long saleId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from sales_order where id = ? and org_id = ?", saleId, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_NOT_FOUND, msg("sales.notFound"));
        }
        return rows.get(0);
    }

    public Map<String, Object> loadItem(long orgId, long itemId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from sales_order_item where id = ? and org_id = ?", itemId, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_ITEM_MISMATCH, msg("sales.item.mismatch"));
        }
        return rows.get(0);
    }

    void publishStatus(long orgId, Map<String, Object> sale, long saleId, int from, int to,
            String action, long operatorId) {
        long orderId = ((Number) sale.get("order_id")).longValue();
        eventBus.publish(new SalesOrderStatusChangedEvent(saleId, orgId, orderId, action, from, to,
                operatorId), "sales:" + saleId + ":" + action);
    }

    void track(long orgId, long saleId, String action, String detail, long operatorId) {
        jdbc.update("""
                insert into sales_track(org_id, sale_id, action, detail, operator_id, operator_name)
                values (?,?,?,?,?,?)
                """, orgId, saleId, action, blankToNull(detail), operatorId, displayName(operatorId));
    }

    private long seq(long orgId, String prefix) {
        jdbc.update("""
                insert into sales_counter(org_id, prefix, date_key, last_seq)
                values (?,?,?,last_insert_id(1))
                on duplicate key update last_seq = last_insert_id(last_seq + 1)
                """, orgId, prefix, seqDate());
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    private String codeDate() {
        return java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"));
    }

    private int seqDate() {
        return Integer.parseInt(codeDate());
    }

    String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    void require(boolean condition, int code, String key) {
        if (!condition) {
            throw new BizException(code, msg(key));
        }
    }

    String msgArgs(String key, Object... args) {
        return messages.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    String displayName(long userId) {
        List<String> names = jdbc.queryForList(
                "select display_name from yz_user where id = ?", String.class, userId);
        return names.isEmpty() ? (userId == 0 ? "客户" : String.valueOf(userId)) : names.get(0);
    }

    private String yuan(long li) {
        return li % 1000 == 0 ? String.valueOf(li / 1000) + "元"
                : String.format("%.2f元", li / 1000.0);
    }

    static long nvl(Long v) {
        return v == null ? 0 : v;
    }

    static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    static List<Object> appendArgs(List<Object> args, Object... extra) {
        List<Object> all = new ArrayList<>(args);
        for (Object o : extra) {
            all.add(o);
        }
        return all;
    }
}
