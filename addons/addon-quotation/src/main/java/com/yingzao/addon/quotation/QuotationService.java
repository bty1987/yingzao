package com.yingzao.addon.quotation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.addons.api.DecorationApi;
import com.yingzao.addons.api.event.QuotationCreatedEvent;
import com.yingzao.addons.api.event.QuotationStatusChangedEvent;
import com.yingzao.core.eventbus.YzEventBus;
import com.yingzao.core.web.BizException;

/**
 * 报价主链:建报价(版本费率快照+自动费率项)→ 明细编辑(仅编辑态)→ 定稿锁定 ⇄ 解锁
 * → 审核线 提交→审核→完成 → 切默认(同单唯一)。
 * 对老缺陷的显式修正(rules/quotation.md):
 *   总价服务端聚合(老系统信任前端总价,QuotationAction 直收 totalPrice 落库);
 *   金额厘 + 费率‰ + 折扣万分比显式文档化(老 1e6 缩放黑盒不迁);
 *   状态流转显式守卫(老 updateQuotationForStatus 裸状态位写入口不迁);
 *   老五张分表化石(QuotationFunctionAreaItemPO0-4)收敛单表;
 *   挂单经 DecorationApi 只读契约(禁跨插件直查他域表);
 *   通知走领域事件(老站内信+钉钉直连不迁,订阅归内核通知中心)。
 * 双态位正交(定稿线 status × 审核线 attstatus)是老系统好设计,保留;
 * 编辑门槛收紧为 attstatus&lt;2(审核通过后冻结编辑,老系统提交后仍可改明细导致审改不一致)。
 */
@Service
public class QuotationService {

    // ---- 错误码(230000 段,同码同消息见 i18n/quotation.properties) ----
    public static final int CODE_INVALID_PARAM = 230001;
    public static final int CODE_NOT_FOUND = 230002;
    public static final int CODE_LOCKED = 230003;
    public static final int CODE_LIMIT = 230004;
    public static final int CODE_ORDER_NOT_FOUND = 230005;
    public static final int CODE_NOT_LOCKED = 230006;
    public static final int CODE_ALREADY_LOCKED = 230007;
    public static final int CODE_NOT_LOCKED_STATE = 230008;
    public static final int CODE_ATT_SUBMITTED = 230009;
    public static final int CODE_ATT_NOT_SUBMITTED = 230010;
    public static final int CODE_ATT_NOT_AUDITED = 230011;
    public static final int CODE_ATT_FINISHED = 230012;
    public static final int CODE_NOT_LOCKABLE_DEFAULT = 230013;
    public static final int CODE_AREA_NOT_FOUND = 230014;
    public static final int CODE_ITEM_NOT_FOUND = 230015;
    public static final int CODE_AUTO_PRICED = 230016;
    public static final int CODE_VERSION_NOT_FOUND = 230017;
    public static final int CODE_VERSION_DUP = 230018;
    public static final int CODE_VERSION_DISABLED = 230019;
    public static final int CODE_RATE_INVALID = 230020;
    public static final int CODE_DISCOUNT_INVALID = 230021;

    // ---- 定稿线(老 QuotationPO status;裸写入口不迁) ----
    public static final int STATUS_EDITING = 0;
    public static final int STATUS_LOCKED = 1;
    // ---- 审核线(老 attstatus;与定稿线正交) ----
    public static final int ATT_INIT = 0;
    public static final int ATT_SUBMITTED = 1;
    public static final int ATT_AUDITED = 2;
    public static final int ATT_FINISHED = 3;

    // ---- 功能区/功能项类型(老枚举;5 成本/6 未完成不迁——成本线归报表插件) ----
    public static final int AREA_TYPE_MANAGEMENT = 0;
    public static final int AREA_TYPE_WORKLOAD = 1;
    public static final int AREA_TYPE_AREA_TOTAL = 2;
    public static final int AREA_TYPE_NOCALC = 3;
    public static final int AREA_TYPE_COMPREHENSIVE = 7;
    public static final int ITEM_TYPE_NORMAL = 0;
    public static final int ITEM_TYPE_CUSTOM = 1;
    public static final int ITEM_TYPE_CARRY = 2;
    public static final int ITEM_TYPE_MANAGE = 3;
    public static final int ITEM_TYPE_IGNORE = 4;
    public static final int ITEM_TYPE_TAX = 8;
    /** 费率自动项(创建时自动生成,金额由聚合回填,禁手改禁删——修老自动项可手改漂移)。 */
    private static final Set<Integer> AUTO_ITEM_TYPES = Set.of(ITEM_TYPE_CARRY, ITEM_TYPE_MANAGE, ITEM_TYPE_TAX);
    /** 手工明细允许的类型(仅常规/自定义/忽略)。 */
    private static final Set<Integer> MANUAL_ITEM_TYPES = Set.of(ITEM_TYPE_NORMAL, ITEM_TYPE_CUSTOM, ITEM_TYPE_IGNORE);
    /** 参与合计的项目类型(老语义:常规+自定义;忽略/费率项不进 undis)。 */
    private static final Set<Integer> COUNTED_ITEM_TYPES = Set.of(ITEM_TYPE_NORMAL, ITEM_TYPE_CUSTOM);

    /** 功能项 subtype:0 工程/1 材料(老语义保留)。 */
    public static final int SUBTYPE_PROJECT = 0;
    public static final int SUBTYPE_MATERIAL = 1;

    /** 自动生成功能区名与排序(老"生成项目" sort=9999 语义保留)。 */
    private static final String AUTO_AREA_NAME = "生成项目";
    private static final int AUTO_AREA_SORT = 9999;
    private static final String COMPREHENSIVE_AREA_NAME = "综合项目";

    private record QuotationRow(long id, long orgId, long orderId, String orderNo, String name, int status,
            int attstatus, int isDefault, int discount, int manageRate, int manageDiscount,
            int carryRate, int carryDiscount, int taxRate, int taxDiscount) {
    }

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final YzEventBus eventBus;
    private final DecorationApi decorations;

    public QuotationService(JdbcTemplate jdbc, MessageSource messages, YzEventBus eventBus, DecorationApi decorations) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.eventBus = eventBus;
        this.decorations = decorations;
    }

    // ---- 报价版本(组织级配置域:费率默认值/启税) ----

    public List<Map<String, Object>> listVersions(long orgId, boolean includeDisabled) {
        String sql = "select v.id, v.name, v.busitype, v.manage_rate, v.manage_discount, v.carry_rate,"
                + " v.carry_discount, v.tax_rate, v.tax_discount, v.status, v.remark, v.created_at"
                + " from quotation_version v where v.org_id = ?"
                + (includeDisabled ? "" : " and v.status = 1") + " order by v.id desc";
        return jdbc.queryForList(sql, orgId);
    }

    public record VersionCmd(String name, Integer busitype, Integer manageRate, Integer manageDiscount,
            Integer carryRate, Integer carryDiscount, Integer taxRate, Integer taxDiscount,
            String remark) {
    }

    @Transactional
    public long createVersion(long orgId, VersionCmd cmd, long operatorId) {
        require(cmd != null && cmd.name() != null && !cmd.name().isBlank(), CODE_INVALID_PARAM,
                "quotation.version.nameRequired");
        int[] rates = normalizeRates(cmd);
        Integer dup = jdbc.queryForObject(
                "select count(*) from quotation_version where org_id = ? and name = ?",
                Integer.class, orgId, cmd.name().trim());
        if (dup != null && dup > 0) {
            throw new BizException(CODE_VERSION_DUP, msg("quotation.version.dup"));
        }
        jdbc.update("""
                insert into quotation_version(org_id, name, busitype, manage_rate, manage_discount,
                    carry_rate, carry_discount, tax_rate, tax_discount, status, remark)
                values (?,?,?,?,?,?,?,?,?,1,?)
                """, orgId, cmd.name().trim(), cmd.busitype() == null ? 0 : cmd.busitype(),
                rates[0], rates[1], rates[2], rates[3], rates[4], rates[5], blankToNull(cmd.remark()));
        return jdbc.queryForObject("select id from quotation_version where org_id = ? and name = ?",
                Long.class, orgId, cmd.name().trim());
    }

    @Transactional
    public void updateVersion(long orgId, long versionId, VersionCmd cmd, long operatorId) {
        require(cmd != null && cmd.name() != null && !cmd.name().isBlank(), CODE_INVALID_PARAM,
                "quotation.version.nameRequired");
        int[] rates = normalizeRates(cmd);
        loadVersion(orgId, versionId);
        Integer dup = jdbc.queryForObject("""
                select count(*) from quotation_version where org_id = ? and name = ? and id != ?
                """, Integer.class, orgId, cmd.name().trim(), versionId);
        if (dup != null && dup > 0) {
            throw new BizException(CODE_VERSION_DUP, msg("quotation.version.dup"));
        }
        jdbc.update("""
                update quotation_version set name = ?, busitype = ?, manage_rate = ?, manage_discount = ?,
                    carry_rate = ?, carry_discount = ?, tax_rate = ?, tax_discount = ?, remark = ?
                where id = ? and org_id = ?
                """, cmd.name().trim(), cmd.busitype() == null ? 0 : cmd.busitype(),
                rates[0], rates[1], rates[2], rates[3], rates[4], rates[5], blankToNull(cmd.remark()),
                versionId, orgId);
    }

    @Transactional
    public void updateVersionStatus(long orgId, long versionId, Integer status, long operatorId) {
        require(status != null && (status == 0 || status == 1), CODE_INVALID_PARAM, "quotation.param.invalid");
        loadVersion(orgId, versionId);
        jdbc.update("update quotation_version set status = ? where id = ? and org_id = ?", status, versionId, orgId);
    }

    /** 费率/折扣显式校验(万分比黑盒的语义在此文档化:rate 千分比 0~1000,discount 万分比 1~10000)。 */
    private int[] normalizeRates(VersionCmd cmd) {
        int manageRate = orDefault(cmd.manageRate(), 0);
        int manageDiscount = orDefault(cmd.manageDiscount(), 10000);
        int carryRate = orDefault(cmd.carryRate(), 0);
        int carryDiscount = orDefault(cmd.carryDiscount(), 10000);
        int taxRate = orDefault(cmd.taxRate(), 0);
        int taxDiscount = orDefault(cmd.taxDiscount(), 10000);
        require(inRange(manageRate, 0, 1000) && inRange(carryRate, 0, 1000) && inRange(taxRate, 0, 1000),
                CODE_RATE_INVALID, "quotation.version.rateInvalid");
        require(inRange(manageDiscount, 1, 10000) && inRange(carryDiscount, 1, 10000)
                && inRange(taxDiscount, 1, 10000), CODE_DISCOUNT_INVALID, "quotation.version.discountInvalid");
        return new int[] { manageRate, manageDiscount, carryRate, carryDiscount, taxRate, taxDiscount };
    }

    private Map<String, Object> loadVersion(long orgId, long versionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from quotation_version where id = ? and org_id = ?", versionId, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_VERSION_NOT_FOUND, msg("quotation.version.notfound"));
        }
        return rows.get(0);
    }

    // ---- 建报价(挂单校验+上限+版本费率快照+自动费率项) ----

    public record CreateCmd(long orderId, String name, Long versionId, String remark) {
    }

    @Transactional
    public long create(long orgId, CreateCmd cmd, long operatorId) {
        require(cmd != null && cmd.orderId() > 0 && cmd.name() != null && !cmd.name().isBlank(),
                CODE_INVALID_PARAM, "quotation.quotation.nameRequired");
        // 挂单经只读契约校验+取展示快照(禁跨插件直查 decoration 表)
        DecorationApi.OrderSummary order = decorations.queryOrder(orgId, cmd.orderId())
                .orElseThrow(() -> new BizException(CODE_ORDER_NOT_FOUND, msg("quotation.quotation.orderNotFound")));
        int limit = orgLimit(orgId);
        Integer count = jdbc.queryForObject(
                "select count(*) from quotation_quotation where org_id = ? and order_id = ?",
                Integer.class, orgId, cmd.orderId());
        if (count != null && count >= limit) {
            throw new BizException(CODE_LIMIT, msg("quotation.quotation.limit"));
        }
        int manageRate = 0, manageDiscount = 10000, carryRate = 0, carryDiscount = 10000;
        int taxRate = 0, taxDiscount = 10000;
        if (cmd.versionId() != null) {
            Map<String, Object> version = loadVersion(orgId, cmd.versionId());
            if (((Number) version.get("status")).intValue() != 1) {
                throw new BizException(CODE_VERSION_DISABLED, msg("quotation.version.disabled"));
            }
            manageRate = ((Number) version.get("manage_rate")).intValue();
            manageDiscount = ((Number) version.get("manage_discount")).intValue();
            carryRate = ((Number) version.get("carry_rate")).intValue();
            carryDiscount = ((Number) version.get("carry_discount")).intValue();
            taxRate = ((Number) version.get("tax_rate")).intValue();
            taxDiscount = ((Number) version.get("tax_discount")).intValue();
        }
        int isFirst = count == null || count == 0 ? 1 : 0;
        jdbc.update("""
                insert into quotation_quotation(org_id, order_id, order_no, customer_name, house_label,
                    name, version_id, status, attstatus, is_default, discount, manage_rate, manage_discount,
                    carry_rate, carry_discount, tax_rate, tax_discount, remark, created_by)
                values (?,?,?,?,?,?,?,0,0,?,10000,?,?,?,?,?,?,?,?)
                """, orgId, cmd.orderId(), order.orderNo(), order.customerName(), order.houseLabel(),
                cmd.name().trim(), cmd.versionId(), isFirst, manageRate, manageDiscount,
                carryRate, carryDiscount, taxRate, taxDiscount, blankToNull(cmd.remark()), operatorId);
        long quotationId = jdbc.queryForObject("""
                select id from quotation_quotation where org_id = ? and order_id = ? and name = ?
                order by id desc limit 1
                """, Long.class, orgId, cmd.orderId(), cmd.name().trim());
        // 自动生成"生成项目"区+搬运/管理费率项(+税项,仅版本启税)——老 QuotationServiceImpl 自动生成语义
        long autoAreaId = insertArea(orgId, quotationId, AUTO_AREA_NAME, AREA_TYPE_MANAGEMENT, AUTO_AREA_SORT);
        insertAutoItem(orgId, quotationId, autoAreaId, "搬运费", ITEM_TYPE_CARRY);
        insertAutoItem(orgId, quotationId, autoAreaId, "管理费", ITEM_TYPE_MANAGE);
        if (taxRate > 0) {
            insertAutoItem(orgId, quotationId, autoAreaId, "税金", ITEM_TYPE_TAX);
        }
        // 综合项目区开关(老 QUOTATION_CMPREH_CONFIG 公司开关→组织级配置)
        if (orgComprehensive(orgId)) {
            insertArea(orgId, quotationId, COMPREHENSIVE_AREA_NAME, AREA_TYPE_COMPREHENSIVE, AUTO_AREA_SORT + 1);
        }
        recalc(orgId, quotationId);
        track(orgId, quotationId, "create", null, null, null, null, operatorId, null);
        eventBus.publish(new QuotationCreatedEvent(quotationId, orgId, cmd.orderId(), cmd.name().trim(), operatorId),
                "quotation:" + quotationId + ":created");
        return quotationId;
    }

    // ---- 明细编辑(仅编辑态;费率自动项禁手改) ----

    public record AreaCmd(String name, Integer type, Integer sort) {
    }

    @Transactional
    public long addArea(long orgId, long quotationId, AreaCmd cmd, long operatorId) {
        require(cmd != null && cmd.name() != null && !cmd.name().isBlank(), CODE_INVALID_PARAM,
                "quotation.area.nameRequired");
        QuotationRow q = load(orgId, quotationId);
        requireEditable(q);
        int type = cmd.type() == null ? AREA_TYPE_WORKLOAD : cmd.type();
        require(type == AREA_TYPE_MANAGEMENT || type == AREA_TYPE_WORKLOAD || type == AREA_TYPE_AREA_TOTAL
                || type == AREA_TYPE_NOCALC || type == AREA_TYPE_COMPREHENSIVE,
                CODE_INVALID_PARAM, "quotation.param.invalid");
        long areaId = insertArea(orgId, quotationId, cmd.name().trim(), type,
                cmd.sort() == null ? 0 : cmd.sort());
        recalc(orgId, quotationId);
        return areaId;
    }

    @Transactional
    public void updateArea(long orgId, long quotationId, long areaId, AreaCmd cmd, long operatorId) {
        require(cmd != null && cmd.name() != null && !cmd.name().isBlank(), CODE_INVALID_PARAM,
                "quotation.area.nameRequired");
        QuotationRow q = load(orgId, quotationId);
        requireEditable(q);
        int updated = jdbc.update("""
                update quotation_function_area set name = ?, type = ?, sort = ?
                where id = ? and quotation_id = ? and org_id = ?
                """, cmd.name().trim(), cmd.type() == null ? AREA_TYPE_WORKLOAD : cmd.type(),
                cmd.sort() == null ? 0 : cmd.sort(), areaId, quotationId, orgId);
        if (updated == 0) {
            throw new BizException(CODE_AREA_NOT_FOUND, msg("quotation.area.notfound"));
        }
        recalc(orgId, quotationId);
    }

    @Transactional
    public void deleteArea(long orgId, long quotationId, long areaId, long operatorId) {
        QuotationRow q = load(orgId, quotationId);
        requireEditable(q);
        Integer autoItems = jdbc.queryForObject("""
                select count(*) from quotation_function_item
                where area_id = ? and type in (2, 3, 8)
                """, Integer.class, areaId);
        if (autoItems != null && autoItems > 0) {
            throw new BizException(CODE_AUTO_PRICED, msg("quotation.item.autoPriced"));
        }
        int deleted = jdbc.update("delete from quotation_function_area where id = ? and quotation_id = ? and org_id = ?",
                areaId, quotationId, orgId);
        if (deleted == 0) {
            throw new BizException(CODE_AREA_NOT_FOUND, msg("quotation.area.notfound"));
        }
        jdbc.update("delete from quotation_function_item where area_id = ?", areaId);
        recalc(orgId, quotationId);
    }

    public record ItemCmd(String name, Integer type, Integer subtype, String unit, Long num, Long price,
            Integer sort, String remark) {
    }

    @Transactional
    public long addItem(long orgId, long quotationId, long areaId, ItemCmd cmd, long operatorId) {
        require(cmd != null && cmd.name() != null && !cmd.name().isBlank(), CODE_INVALID_PARAM,
                "quotation.item.nameRequired");
        QuotationRow q = load(orgId, quotationId);
        requireEditable(q);
        int type = cmd.type() == null ? ITEM_TYPE_NORMAL : cmd.type();
        require(MANUAL_ITEM_TYPES.contains(type), CODE_INVALID_PARAM, "quotation.param.invalid");
        requireItemAmounts(cmd);
        int subtype = cmd.subtype() == null ? SUBTYPE_PROJECT : cmd.subtype();
        require(subtype == SUBTYPE_PROJECT || subtype == SUBTYPE_MATERIAL,
                CODE_INVALID_PARAM, "quotation.param.invalid");
        jdbc.update("""
                insert into quotation_function_item(org_id, quotation_id, area_id, name, type, subtype,
                    unit, num, price, sort, remark)
                values (?,?,?,?,?,?,?,?,?,?,?)
                """, orgId, quotationId, areaId, cmd.name().trim(), type, subtype, blankToNull(cmd.unit()),
                cmd.num(), cmd.price(), cmd.sort() == null ? 0 : cmd.sort(), blankToNull(cmd.remark()));
        recalc(orgId, quotationId);
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    @Transactional
    public void updateItem(long orgId, long quotationId, long itemId, ItemCmd cmd, long operatorId) {
        require(cmd != null && cmd.name() != null && !cmd.name().isBlank(), CODE_INVALID_PARAM,
                "quotation.item.nameRequired");
        QuotationRow q = load(orgId, quotationId);
        requireEditable(q);
        Map<String, Object> item = loadItem(orgId, quotationId, itemId);
        if (AUTO_ITEM_TYPES.contains(((Number) item.get("type")).intValue())) {
            throw new BizException(CODE_AUTO_PRICED, msg("quotation.item.autoPriced"));
        }
        requireItemAmounts(cmd);
        int subtype = cmd.subtype() == null ? SUBTYPE_PROJECT : cmd.subtype();
        require(subtype == SUBTYPE_PROJECT || subtype == SUBTYPE_MATERIAL,
                CODE_INVALID_PARAM, "quotation.param.invalid");
        jdbc.update("""
                update quotation_function_item set name = ?, subtype = ?, unit = ?, num = ?, price = ?,
                    sort = ?, remark = ?
                where id = ? and quotation_id = ? and org_id = ?
                """, cmd.name().trim(), subtype, blankToNull(cmd.unit()), cmd.num(), cmd.price(),
                cmd.sort() == null ? 0 : cmd.sort(), blankToNull(cmd.remark()), itemId, quotationId, orgId);
        recalc(orgId, quotationId);
    }

    @Transactional
    public void deleteItem(long orgId, long quotationId, long itemId, long operatorId) {
        QuotationRow q = load(orgId, quotationId);
        requireEditable(q);
        Map<String, Object> item = loadItem(orgId, quotationId, itemId);
        if (AUTO_ITEM_TYPES.contains(((Number) item.get("type")).intValue())) {
            throw new BizException(CODE_AUTO_PRICED, msg("quotation.item.autoPriced"));
        }
        int deleted = jdbc.update("delete from quotation_function_item where id = ? and quotation_id = ? and org_id = ?",
                itemId, quotationId, orgId);
        if (deleted == 0) {
            throw new BizException(CODE_ITEM_NOT_FOUND, msg("quotation.item.notfound"));
        }
        recalc(orgId, quotationId);
    }

    /** 明细数量/单家乡校验:num 千分位恒正,price 厘非负。 */
    private void requireItemAmounts(ItemCmd cmd) {
        require(cmd.num() != null && cmd.num() > 0, CODE_INVALID_PARAM, "quotation.item.numInvalid");
        require(cmd.price() != null && cmd.price() >= 0, CODE_INVALID_PARAM, "quotation.item.priceInvalid");
    }

    // ---- 报价级费率/折扣覆盖(仅编辑态;改后聚合) ----

    public record PricingCmd(Integer discount, Integer manageRate, Integer manageDiscount, Integer carryRate,
            Integer carryDiscount, Integer taxRate, Integer taxDiscount) {
    }

    @Transactional
    public void updatePricing(long orgId, long quotationId, PricingCmd cmd, long operatorId) {
        require(cmd != null, CODE_INVALID_PARAM, "quotation.param.invalid");
        QuotationRow q = load(orgId, quotationId);
        requireEditable(q);
        int discount = orDefault(cmd.discount(), q.discount());
        require(inRange(discount, 1, 10000), CODE_DISCOUNT_INVALID, "quotation.version.discountInvalid");
        int manageRate = orDefault(cmd.manageRate(), q.manageRate());
        int carryRate = orDefault(cmd.carryRate(), q.carryRate());
        int taxRate = orDefault(cmd.taxRate(), q.taxRate());
        require(inRange(manageRate, 0, 1000) && inRange(carryRate, 0, 1000) && inRange(taxRate, 0, 1000),
                CODE_RATE_INVALID, "quotation.version.rateInvalid");
        int manageDiscount = orDefault(cmd.manageDiscount(), q.manageDiscount());
        int carryDiscount = orDefault(cmd.carryDiscount(), q.carryDiscount());
        int taxDiscount = orDefault(cmd.taxDiscount(), q.taxDiscount());
        require(inRange(manageDiscount, 1, 10000) && inRange(carryDiscount, 1, 10000)
                && inRange(taxDiscount, 1, 10000), CODE_DISCOUNT_INVALID, "quotation.version.discountInvalid");
        jdbc.update("""
                update quotation_quotation set discount = ?, manage_rate = ?, manage_discount = ?,
                    carry_rate = ?, carry_discount = ?, tax_rate = ?, tax_discount = ?
                where id = ? and org_id = ?
                """, discount, manageRate, manageDiscount, carryRate, carryDiscount, taxRate, taxDiscount,
                quotationId, orgId);
        // 费率项行的有无可随启税变化:税率从 0 变正补税金行
        if (q.taxRate() == 0 && taxRate > 0) {
            Map<String, Object> autoArea = jdbc.queryForMap("""
                    select id from quotation_function_area
                    where quotation_id = ? and name = ? and type = ? order by id limit 1
                    """, quotationId, AUTO_AREA_NAME, AREA_TYPE_MANAGEMENT);
            insertAutoItem(orgId, quotationId, ((Number) autoArea.get("id")).longValue(), "税金", ITEM_TYPE_TAX);
        }
        recalc(orgId, quotationId);
    }

    // ---- 定稿线(锁定⇄解锁) ----

    /** 定稿锁定:0→1。锁定前置校验「无未关联选材项」V1 结构位天然可过(选材线 V3);lockTime 首次幂等。 */
    @Transactional
    public void lock(long orgId, long quotationId, long operatorId) {
        QuotationRow q = load(orgId, quotationId);
        if (q.status() == STATUS_LOCKED) {
            throw new BizException(CODE_ALREADY_LOCKED, msg("quotation.quotation.alreadyLocked"));
        }
        int updated = jdbc.update(
                "update quotation_quotation set status = 1, lock_time = coalesce(lock_time, now())"
                        + " where id = ? and status = 0",
                quotationId);
        if (updated == 0) {
            throw new BizException(CODE_NOT_FOUND, msg("quotation.quotation.notfound"));
        }
        track(orgId, quotationId, "lock", STATUS_EDITING, STATUS_LOCKED, null, null, operatorId, null);
        publishStatus(orgId, q, STATUS_LOCKED, q.attstatus(), "lock", operatorId);
    }

    @Transactional
    public void unlock(long orgId, long quotationId, long operatorId) {
        QuotationRow q = load(orgId, quotationId);
        if (q.status() != STATUS_LOCKED) {
            throw new BizException(CODE_NOT_LOCKED_STATE, msg("quotation.quotation.notLockedState"));
        }
        int updated = jdbc.update("update quotation_quotation set status = 0 where id = ? and status = 1", quotationId);
        if (updated == 0) {
            throw new BizException(CODE_NOT_FOUND, msg("quotation.quotation.notfound"));
        }
        track(orgId, quotationId, "unlock", STATUS_LOCKED, STATUS_EDITING, null, null, operatorId, null);
        publishStatus(orgId, q, STATUS_EDITING, q.attstatus(), "unlock", operatorId);
    }

    // ---- 审核线(提交→审核→完成;与定稿线正交) ----

    /** 提交审核:前置=已锁定(定稿送审);attstatus 0→1。 */
    @Transactional
    public void submit(long orgId, long quotationId, long operatorId) {
        QuotationRow q = load(orgId, quotationId);
        if (q.status() != STATUS_LOCKED) {
            throw new BizException(CODE_NOT_LOCKED, msg("quotation.quotation.notLocked"));
        }
        if (q.attstatus() != ATT_INIT) {
            throw new BizException(CODE_ATT_SUBMITTED, msg("quotation.quotation.attSubmitted"));
        }
        int updated = jdbc.update(
                "update quotation_quotation set attstatus = 1, submit_time = coalesce(submit_time, now())"
                        + " where id = ? and attstatus = 0",
                quotationId);
        if (updated == 0) {
            throw new BizException(CODE_ATT_SUBMITTED, msg("quotation.quotation.attSubmitted"));
        }
        track(orgId, quotationId, "submit", null, null, ATT_INIT, ATT_SUBMITTED, operatorId, null);
        publishStatus(orgId, q, q.status(), ATT_SUBMITTED, "submit", operatorId);
    }

    @Transactional
    public void audit(long orgId, long quotationId, long operatorId) {
        QuotationRow q = load(orgId, quotationId);
        if (q.attstatus() != ATT_SUBMITTED) {
            throw new BizException(CODE_ATT_NOT_SUBMITTED, msg("quotation.quotation.attNotSubmitted"));
        }
        int updated = jdbc.update(
                "update quotation_quotation set attstatus = 2, audit_time = coalesce(audit_time, now())"
                        + " where id = ? and attstatus = 1",
                quotationId);
        if (updated == 0) {
            throw new BizException(CODE_ATT_NOT_SUBMITTED, msg("quotation.quotation.attNotSubmitted"));
        }
        track(orgId, quotationId, "audit", null, null, ATT_SUBMITTED, ATT_AUDITED, operatorId, null);
        publishStatus(orgId, q, q.status(), ATT_AUDITED, "audit", operatorId);
    }

    @Transactional
    public void finish(long orgId, long quotationId, long operatorId) {
        QuotationRow q = load(orgId, quotationId);
        if (q.attstatus() == ATT_FINISHED) {
            throw new BizException(CODE_ATT_FINISHED, msg("quotation.quotation.attFinished"));
        }
        if (q.attstatus() != ATT_AUDITED) {
            throw new BizException(CODE_ATT_NOT_AUDITED, msg("quotation.quotation.attNotAudited"));
        }
        int updated = jdbc.update(
                "update quotation_quotation set attstatus = 3, finish_time = coalesce(finish_time, now())"
                        + " where id = ? and attstatus = 2",
                quotationId);
        if (updated == 0) {
            throw new BizException(CODE_ATT_NOT_AUDITED, msg("quotation.quotation.attNotAudited"));
        }
        track(orgId, quotationId, "finish", null, null, ATT_AUDITED, ATT_FINISHED, operatorId, null);
        publishStatus(orgId, q, q.status(), ATT_FINISHED, "finish", operatorId);
    }

    // ---- 切默认(同装修单唯一;仅锁定报价可设默认——默认报价=对客户生效的合同价格基准) ----

    @Transactional
    public void setDefault(long orgId, long quotationId, long operatorId) {
        QuotationRow q = load(orgId, quotationId);
        if (q.status() != STATUS_LOCKED) {
            throw new BizException(CODE_NOT_LOCKABLE_DEFAULT, msg("quotation.quotation.notLockableDefault"));
        }
        jdbc.update("update quotation_quotation set is_default = 0 where org_id = ? and order_id = ?",
                orgId, q.orderId());
        int updated = jdbc.update(
                "update quotation_quotation set is_default = 1 where id = ? and status = 1", quotationId);
        if (updated == 0) {
            throw new BizException(CODE_NOT_FOUND, msg("quotation.quotation.notfound"));
        }
        track(orgId, quotationId, "setDefault", null, null, null, null, operatorId, null);
        publishStatus(orgId, q, q.status(), q.attstatus(), "setDefault", operatorId);
    }

    // ---- 查询 ----

    public Map<String, Object> page(long orgId, Long orderId, String orderNo, Integer status, Integer attstatus,
            int pageNo, int size) {
        int safePage = Math.max(pageNo, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(" where q.org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        if (orderId != null) {
            where.append(" and q.order_id = ?");
            args.add(orderId);
        }
        if (orderNo != null && !orderNo.isBlank()) {
            where.append(" and q.order_no like ?");
            args.add("%" + orderNo.trim() + "%");
        }
        if (status != null) {
            where.append(" and q.status = ?");
            args.add(status);
        }
        if (attstatus != null) {
            where.append(" and q.attstatus = ?");
            args.add(attstatus);
        }
        Long total = jdbc.queryForObject(
                "select count(*) from quotation_quotation q" + where, Long.class, args.toArray());
        List<Object> allArgs = new ArrayList<>(args);
        allArgs.add(safeSize);
        allArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select q.id, q.order_id, q.order_no, q.customer_name, q.house_label, q.name, q.version_id,
                       q.status, q.attstatus, q.is_default, q.discount, q.undis_price, q.discount_price,
                       q.total_price, q.lock_time, q.finish_time, q.remark, u.display_name as creator_name,
                       q.created_at
                from quotation_quotation q left join yz_user u on u.id = q.created_by
                """ + where + " order by q.id desc limit ? offset ?", allArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    public Map<String, Object> detail(long orgId, long quotationId) {
        load(orgId, quotationId);
        Map<String, Object> result = jdbc.queryForMap("""
                select q.*, u.display_name as creator_name from quotation_quotation q
                left join yz_user u on u.id = q.created_by where q.id = ? and q.org_id = ?
                """, quotationId, orgId);
        List<Map<String, Object>> areas = jdbc.queryForList("""
                select id, name, type, sort from quotation_function_area
                where quotation_id = ? order by sort, id
                """, quotationId);
        List<Map<String, Object>> items = jdbc.queryForList("""
                select id, area_id, name, type, subtype, unit, num, price, sort, remark
                from quotation_function_item where quotation_id = ? order by sort, id
                """, quotationId);
        Map<String, List<Map<String, Object>>> byArea = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            byArea.computeIfAbsent(String.valueOf(item.get("area_id")), k -> new ArrayList<>()).add(item);
        }
        List<Map<String, Object>> areaViews = new ArrayList<>();
        for (Map<String, Object> area : areas) {
            area.put("items", byArea.getOrDefault(String.valueOf(area.get("id")), new ArrayList<>()));
            areaViews.add(area);
        }
        result.put("areas", areaViews);
        result.put("tracks", jdbc.queryForList("""
                select action, from_value, to_value, from_att, to_att, operator_name, comment, created_at
                from quotation_track where quotation_id = ? order by id desc limit 100
                """, quotationId));
        return result;
    }

    // ---- 服务端金额聚合(修老信任前端总价;公式见 V401 头注释) ----

    private void recalc(long orgId, long quotationId) {
        QuotationRow q = load(orgId, quotationId);
        List<Map<String, Object>> items = jdbc.queryForList(
                "select type, num, price from quotation_function_item where quotation_id = ?", quotationId);
        BigDecimal undis = BigDecimal.ZERO;
        for (Map<String, Object> item : items) {
            int type = ((Number) item.get("type")).intValue();
            if (!COUNTED_ITEM_TYPES.contains(type)) {
                continue;
            }
            BigDecimal num = new BigDecimal(((Number) item.get("num")).longValue());
            BigDecimal price = new BigDecimal(((Number) item.get("price")).longValue());
            // num 千分位:金额(厘) = num × price / 1000
            undis = undis.add(num.multiply(price).divide(BigDecimal.valueOf(1000), 0, RoundingMode.HALF_UP));
        }
        BigDecimal discount = BigDecimal.valueOf(q.discount());
        BigDecimal dis = undis.multiply(discount)
                .divide(BigDecimal.valueOf(10000), 0, RoundingMode.HALF_UP);
        BigDecimal manage = dis.multiply(BigDecimal.valueOf(q.manageRate()))
                .multiply(BigDecimal.valueOf(q.manageDiscount()))
                .divide(BigDecimal.valueOf(100000000L), 0, RoundingMode.HALF_UP);
        BigDecimal carry = dis.multiply(BigDecimal.valueOf(q.carryRate()))
                .multiply(BigDecimal.valueOf(q.carryDiscount()))
                .divide(BigDecimal.valueOf(100000000L), 0, RoundingMode.HALF_UP);
        BigDecimal tax = BigDecimal.ZERO;
        if (q.taxRate() > 0) {
            tax = dis.add(manage).add(carry).multiply(BigDecimal.valueOf(q.taxRate()))
                    .multiply(BigDecimal.valueOf(q.taxDiscount()))
                    .divide(BigDecimal.valueOf(100000000L), 0, RoundingMode.HALF_UP);
        }
        BigDecimal total = dis.add(manage).add(carry).add(tax);
        // 费率自动项金额回填(行与总额一致,页面直接读行)
        backfillAutoItem(quotationId, ITEM_TYPE_CARRY, carry.longValue());
        backfillAutoItem(quotationId, ITEM_TYPE_MANAGE, manage.longValue());
        backfillAutoItem(quotationId, ITEM_TYPE_TAX, tax.longValue());
        jdbc.update("""
                update quotation_quotation set undis_price = ?, discount_price = ?, total_price = ?
                where id = ?
                """, undis.longValue(), dis.longValue(), total.longValue(), quotationId);
    }

    private void backfillAutoItem(long quotationId, int type, long price) {
        jdbc.update("update quotation_function_item set price = ? where quotation_id = ? and type = ?",
                price, quotationId, type);
    }

    // ---- 组织级配置(懒初始化) ----

    private int orgLimit(long orgId) {
        Map<String, Object> config = loadConfig(orgId);
        return ((Number) config.get("quotation_limit")).intValue();
    }

    private boolean orgComprehensive(long orgId) {
        Map<String, Object> config = loadConfig(orgId);
        return ((Number) config.get("comprehensive_enabled")).intValue() == 1;
    }

    private Map<String, Object> loadConfig(long orgId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from quotation_config where org_id = ?", orgId);
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        jdbc.update("insert ignore into quotation_config(org_id) values (?)", orgId);
        return jdbc.queryForList("select * from quotation_config where org_id = ?", orgId).get(0);
    }

    // ---- 内部 ----

    private long insertArea(long orgId, long quotationId, String name, int type, int sort) {
        jdbc.update("insert into quotation_function_area(org_id, quotation_id, name, type, sort) values (?,?,?,?,?)",
                orgId, quotationId, name, type, sort);
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    private void insertAutoItem(long orgId, long quotationId, long areaId, String name, int type) {
        jdbc.update("""
                insert into quotation_function_item(org_id, quotation_id, area_id, name, type, subtype,
                    unit, num, price, sort)
                values (?,?,?,?,?,0,'项',1000,0,?)
                """, orgId, quotationId, areaId, name, type, AUTO_AREA_SORT);
    }

    private void requireEditable(QuotationRow q) {
        if (q.status() != STATUS_EDITING) {
            throw new BizException(CODE_LOCKED, msg("quotation.quotation.locked"));
        }
        // 审核通过后冻结编辑(收紧:老系统提交/审核后仍可改明细,审改不一致)
        if (q.attstatus() >= ATT_AUDITED) {
            throw new BizException(CODE_LOCKED, msg("quotation.quotation.notEditable"));
        }
    }

    private Map<String, Object> loadItem(long orgId, long quotationId, long itemId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from quotation_function_item where id = ? and quotation_id = ? and org_id = ?",
                itemId, quotationId, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_ITEM_NOT_FOUND, msg("quotation.item.notfound"));
        }
        return rows.get(0);
    }

    private QuotationRow load(long orgId, long quotationId) {
        require(quotationId > 0, CODE_INVALID_PARAM, "quotation.param.invalid");
        List<QuotationRow> rows = jdbc.query("""
                select id, org_id, order_id, order_no, name, status, attstatus, is_default, discount,
                       manage_rate, manage_discount, carry_rate, carry_discount, tax_rate, tax_discount
                from quotation_quotation where id = ? and org_id = ?
                """, (rs, rowNum) -> new QuotationRow(rs.getLong("id"), rs.getLong("org_id"),
                rs.getLong("order_id"), rs.getString("order_no"), rs.getString("name"), rs.getInt("status"),
                rs.getInt("attstatus"), rs.getInt("is_default"), rs.getInt("discount"), rs.getInt("manage_rate"),
                rs.getInt("manage_discount"), rs.getInt("carry_rate"), rs.getInt("carry_discount"),
                rs.getInt("tax_rate"), rs.getInt("tax_discount")), quotationId, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_NOT_FOUND, msg("quotation.quotation.notfound"));
        }
        return rows.get(0);
    }

    private void track(long orgId, long quotationId, String action, Integer from, Integer to,
            Integer fromAtt, Integer toAtt, long operatorId, String comment) {
        jdbc.update("""
                insert into quotation_track(org_id, quotation_id, action, from_value, to_value, from_att,
                    to_att, operator_id, operator_name, comment)
                values (?,?,?,?,?,?,?,?,?,?)
                """, orgId, quotationId, action, from, to, fromAtt, toAtt, operatorId,
                displayName(operatorId), blankToNull(comment));
    }

    private void publishStatus(long orgId, QuotationRow q, int toStatus, int toAtt, String action, long operatorId) {
        int fromStatus = action.equals("lock") ? STATUS_EDITING
                : action.equals("unlock") ? STATUS_LOCKED : q.status();
        Integer fromAtt = switch (action) {
            case "submit" -> ATT_INIT;
            case "audit" -> ATT_SUBMITTED;
            case "finish" -> ATT_AUDITED;
            default -> null;
        };
        eventBus.publish(new QuotationStatusChangedEvent(q.id(), orgId, q.orderId(), fromStatus, toStatus,
                fromAtt, fromAtt != null ? toAtt : null, action, operatorId),
                "quotation:" + q.id() + ":" + action);
    }

    private String displayName(long userId) {
        return jdbc.queryForObject("select display_name from yz_user where id = ?", String.class, userId);
    }

    private static int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static boolean inRange(int value, int min, int max) {
        return value >= min && value <= max;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private void require(boolean cond, int code, String msgKey) {
        if (!cond) {
            throw new BizException(code, msg(msgKey));
        }
    }
}
