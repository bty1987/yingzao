package com.yingzao.addon.decoration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.addons.api.QuotationApi;
import com.yingzao.core.web.BizException;

/**
 * 三口径表(A6;老 DecorationMidpack/Holonomy/Inclusive 三 Service 的行业中性回归)。
 * 本质 = 「合同价与主材/服务费指标如何从默认报价推导」的三种商务口径,每单每型一行(uk)。
 * 统一门禁(rules/decoration.md §5):主单合同线 ∈ {0,1,2,4}(签约/决算/完工锁死)+
 * 默认报价存在且定稿锁定(经 QuotationApi 契约);老「订单活动锁定」门禁不迁(活动域未建);
 * 老裸状态位 update 不迁——重算仅初始态,confirm 显式冻结;
 * 口径与款线解耦:老 make 后重建款计划,营造款线=签约 scaleTemplate,口径表只产推导指标。
 * 金额一律厘;配比/费率统一 ppm(百万分比,200000=20%)——老四制式并存不迁;
 * MET 三分支(UPMET 仅正计/DOWNMET 仅负计/COMMET 无条件)=老公司配置 QUOTATION_EXTENSION_MET_FLAG
 * 收敛为口径行参数,逐单可选;增减数据源 V1=手工入参(拓展项域未建,随报价 V3 接入)——诚实边界。
 */
@Service
public class CaliberService {

    public static final int CODE_PARAM = 250006;
    /** 装修单不可操作(合同线锁死;复用 250002 状态机码)。 */
    public static final int CODE_ORDER_LOCKED = 250002;
    /** 默认报价未锁定或不存在(经 QuotationApi)。 */
    public static final int CODE_QUOTATION_NOT_LOCKED = 250030;
    /** 口径已确认,不可重算。 */
    public static final int CODE_CONFIRMED = 250031;
    /** 口径不存在。 */
    public static final int CODE_NOT_FOUND = 250032;

    /** 老统一门禁:主单合同线白名单(初始/定金/预签/重签)。 */
    private static final Set<Integer> CALIBER_ALLOWED_STATUS = Set.of(
            DecorationService.STATUS_INIT, DecorationService.STATUS_DEPOSIT,
            DecorationService.STATUS_PRESIGNED, DecorationService.STATUS_RESIGNED);

    public static final String KIND_MIDPACK = "midpack";
    public static final String KIND_HOLONOMY = "holonomy";
    public static final String KIND_INCLUSIVE = "inclusive";
    private static final Set<String> KINDS = Set.of(KIND_MIDPACK, KIND_HOLONOMY, KIND_INCLUSIVE);

    /** MET 计入口径(老 QUOTATION_EXTENSION_MET_FLAG 三分支)。 */
    public static final int MET_UP = 1;
    public static final int MET_DOWN = 2;
    public static final int MET_COM = 3;

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final QuotationApi quotations;

    public CaliberService(JdbcTemplate jdbc, MessageSource messages, QuotationApi quotations) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.quotations = quotations;
    }

    // ---- 半包配比:主材配额 = 工程基础额 × 主材配比;服务费 = 主材配额 × 服务费率 ----

    public record MidpackCmd(Integer materialRate, Integer serviceRate, Long materialPrice,
            String remark) {
    }

    @Transactional
    public Map<String, Object> makeMidpack(long orgId, long orderId, MidpackCmd cmd, long operatorId) {
        require(cmd != null && cmd.materialRate() != null && cmd.materialRate() >= 0, CODE_PARAM,
                "decoration.caliber.param");
        int materialRate = cmd.materialRate();
        QuotationApi.QuotationBreakdown q = gate(orgId, orderId, KIND_MIDPACK);
        long projectBase = q.undisPrice() + q.discountPrice() + q.managePrice() + q.carryPrice()
                + q.taxPrice();
        long materialPrice;
        if (materialRate == 0) {
            // 老语义:配比 0 = 手填主材额(配额手工核定)
            require(cmd.materialPrice() != null && cmd.materialPrice() >= 0, CODE_PARAM,
                    "decoration.caliber.param");
            materialPrice = cmd.materialPrice();
        } else {
            materialPrice = ppm(projectBase, materialRate);
        }
        int serviceRate = cmd.serviceRate() == null ? 0 : cmd.serviceRate();
        long servicePrice = ppm(materialPrice, serviceRate);
        long total = projectBase + materialPrice + servicePrice;
        jdbc.update("""
                insert into decoration_caliber_midpack(org_id, order_id, quotation_id, material_rate,
                    service_rate, project_base, material_price, material_origin, service_price,
                    total_price, status, remark, created_by)
                values (?,?,?,?,?,?,?,?,?,?,0,?,?)
                on duplicate key update quotation_id = values(quotation_id),
                    material_rate = values(material_rate), service_rate = values(service_rate),
                    project_base = values(project_base), material_price = values(material_price),
                    material_origin = values(material_origin), service_price = values(service_price),
                    total_price = values(total_price), status = 0, remark = values(remark)
                """, orgId, orderId, q.quotationId(), materialRate, serviceRate, projectBase,
                materialPrice, materialPrice, servicePrice, total, blankToNull(cmd.remark()), operatorId);
        return view(orgId, KIND_MIDPACK, orderId);
    }

    // ---- 整装满减:折上折(基数取整余数不打折)+ 主材配额 + 管理费可选跟随折扣 ----

    public record HolonomyCmd(Long discountBase, Integer discount, Integer manageFollow,
            Integer maching, Integer serviceRate, String remark) {
    }

    @Transactional
    public Map<String, Object> makeHolonomy(long orgId, long orderId, HolonomyCmd cmd, long operatorId) {
        require(cmd != null && cmd.discountBase() != null && cmd.discountBase() > 0
                && cmd.discount() != null && cmd.discount() > 0 && cmd.discount() <= 10000
                && cmd.maching() != null && cmd.maching() >= 0, CODE_PARAM, "decoration.caliber.param");
        QuotationApi.QuotationBreakdown q = gate(orgId, orderId, KIND_HOLONOMY);
        long projectBase = q.undisPrice() + q.discountPrice() + q.managePrice() + q.carryPrice()
                + q.taxPrice();
        // 折上折:工程额(未折+折后+搬运)按基数取整,余数不打折(老算法,余数保护零头)
        long projectAmount = q.undisPrice() + q.discountPrice() + q.carryPrice();
        long remainder = projectAmount % cmd.discountBase();
        long projectPrice = (projectAmount - remainder) * cmd.discount() / 10000 + remainder;
        long managePrice = q.managePrice();
        if (cmd.manageFollow() != null && cmd.manageFollow() == 1) {
            managePrice = q.managePrice() * cmd.discount() / 10000;
        }
        long materialPrice = ppm(projectBase, cmd.maching());
        int serviceRate = cmd.serviceRate() == null ? 0 : cmd.serviceRate();
        long servicePrice = ppm(materialPrice, serviceRate);
        long total = projectPrice + managePrice + q.taxPrice() + materialPrice + servicePrice;
        jdbc.update("""
                insert into decoration_caliber_holonomy(org_id, order_id, quotation_id, discount_base,
                    discount, manage_follow, maching, service_rate, project_base, project_price,
                    manage_price, material_price, service_price, total_price, status, remark, created_by)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?)
                on duplicate key update quotation_id = values(quotation_id),
                    discount_base = values(discount_base), discount = values(discount),
                    manage_follow = values(manage_follow), maching = values(maching),
                    service_rate = values(service_rate), project_base = values(project_base),
                    project_price = values(project_price), manage_price = values(manage_price),
                    material_price = values(material_price), service_price = values(service_price),
                    total_price = values(total_price), status = 0, remark = values(remark)
                """, orgId, orderId, q.quotationId(), cmd.discountBase(), cmd.discount(),
                cmd.manageFollow() == null ? 0 : cmd.manageFollow(), cmd.maching(), serviceRate,
                projectBase, projectPrice, managePrice, materialPrice, servicePrice, total,
                blankToNull(cmd.remark()), operatorId);
        return view(orgId, KIND_HOLONOMY, orderId);
    }

    // ---- 包干增项:合同总额 = 报价总价 + 常规增减 + MET 增减按口径分支 ----

    public record InclusiveCmd(Long commonUndis, Long commonDis, Long commonManage, Long commonTax,
            Long metUndis, Long metDis, Long metManage, Long metTax, Integer metFlag,
            String remark) {
    }

    @Transactional
    public Map<String, Object> makeInclusive(long orgId, long orderId, InclusiveCmd cmd, long operatorId) {
        require(cmd != null, CODE_PARAM, "decoration.caliber.param");
        int metFlag = cmd.metFlag() == null ? MET_UP : cmd.metFlag();
        require(metFlag >= MET_UP && metFlag <= MET_COM, CODE_PARAM, "decoration.caliber.param");
        QuotationApi.QuotationBreakdown q = gate(orgId, orderId, KIND_INCLUSIVE);
        long commonUndis = nvl0(cmd.commonUndis());
        long commonDis = nvl0(cmd.commonDis());
        long commonManage = nvl0(cmd.commonManage());
        long commonTax = nvl0(cmd.commonTax());
        long metUndis = nvl0(cmd.metUndis());
        long metDis = nvl0(cmd.metDis());
        long metManage = nvl0(cmd.metManage());
        long metTax = nvl0(cmd.metTax());
        // 常规增减全额并入(未折原值 + 折后额;折后项不再乘整单折扣——老算法直加)
        long total = q.totalPrice() + commonUndis + commonDis + commonManage + commonTax;
        // MET 三分支(老 UPMET 仅正计/DOWNMET 仅负计/COMMET 无条件):metTotal=Σ(MET 未折+折后+管理+税)
        long metTotal = metUndis + metDis + metManage + metTax;
        if ((metFlag == MET_UP && metTotal >= 0) || (metFlag == MET_DOWN && metTotal <= 0)
                || metFlag == MET_COM) {
            total += metTotal;
        }
        jdbc.update("""
                insert into decoration_caliber_inclusive(org_id, order_id, quotation_id, base_total,
                    common_undis, common_dis, common_manage, common_tax, met_undis, met_dis,
                    met_manage, met_tax, met_flag, total_price, status, remark, created_by)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?)
                on duplicate key update quotation_id = values(quotation_id),
                    base_total = values(base_total), common_undis = values(common_undis),
                    common_dis = values(common_dis), common_manage = values(common_manage),
                    common_tax = values(common_tax), met_undis = values(met_undis),
                    met_dis = values(met_dis), met_manage = values(met_manage),
                    met_tax = values(met_tax), met_flag = values(met_flag),
                    total_price = values(total_price), status = 0, remark = values(remark)
                """, orgId, orderId, q.quotationId(), q.totalPrice(), commonUndis, commonDis,
                commonManage, commonTax, metUndis, metDis, metManage, metTax, metFlag, total,
                blankToNull(cmd.remark()), operatorId);
        return view(orgId, KIND_INCLUSIVE, orderId);
    }

    /** 口径确认(冻结;重算/重做需业务重开——V1 不做反确认,显式排除)。 */
    @Transactional
    public void confirm(long orgId, String kind, long orderId, long operatorId) {
        require(KINDS.contains(kind), CODE_PARAM, "decoration.caliber.param");
        String table = tableOf(kind);
        int updated = jdbc.update(
                "update " + table + " set status = 1 where org_id = ? and order_id = ? and status = 0",
                orgId, orderId);
        if (updated == 0) {
            throw new BizException(CODE_CONFIRMED, msg("decoration.caliber.confirmed"));
        }
        track(orgId, kind, orderId, "confirm", operatorId);
    }

    // ---- 查询 ----

    /** 三口径联合查(每型一行或 null)。 */
    public Map<String, Object> all(long orgId, long orderId) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String kind : List.of(KIND_MIDPACK, KIND_HOLONOMY, KIND_INCLUSIVE)) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "select * from " + tableOf(kind) + " where org_id = ? and order_id = ?",
                    orgId, orderId);
            result.put(kind, rows.isEmpty() ? null : rows.get(0));
        }
        return result;
    }

    public Map<String, Object> view(long orgId, String kind, long orderId) {
        require(KINDS.contains(kind), CODE_PARAM, "decoration.caliber.param");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from " + tableOf(kind) + " where org_id = ? and order_id = ?",
                orgId, orderId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_NOT_FOUND, msg("decoration.caliber.notFound"));
        }
        return rows.get(0);
    }

    // ---- 门禁与内部 ----

    /** 统一门禁:合同线白名单 + 默认报价定稿锁定 + 口径行未冻结(重算仅初始态)。 */
    private QuotationApi.QuotationBreakdown gate(long orgId, long orderId, String kind) {
        Map<String, Object> order = jdbc.queryForMap(
                "select status from decoration_order where id = ? and org_id = ?", orderId, orgId);
        int status = ((Number) order.get("status")).intValue();
        if (!CALIBER_ALLOWED_STATUS.contains(status)) {
            throw new BizException(CODE_ORDER_LOCKED, msg("decoration.caliber.orderLocked"));
        }
        List<Map<String, Object>> existing = jdbc.queryForList(
                "select status from " + tableOf(kind) + " where org_id = ? and order_id = ?",
                orgId, orderId);
        if (!existing.isEmpty() && ((Number) existing.get(0).get("status")).intValue() == 1) {
            throw new BizException(CODE_CONFIRMED, msg("decoration.caliber.confirmed"));
        }
        QuotationApi.QuotationBreakdown q = quotations.defaultQuotationBreakdown(orgId, orderId)
                .orElseThrow(() -> new BizException(CODE_QUOTATION_NOT_LOCKED,
                        msg("decoration.caliber.quotationLocked")));
        if (!q.locked()) {
            throw new BizException(CODE_QUOTATION_NOT_LOCKED, msg("decoration.caliber.quotationLocked"));
        }
        return q;
    }

    private void track(long orgId, String kind, long orderId, String action, long operatorId) {
        Map<String, Object> operator = jdbc.queryForMap(
                "select display_name from yz_user where id = ?", operatorId);
        jdbc.update("""
                insert into decoration_order_track(org_id, order_id, action, operator_id,
                    operator_name, comment)
                values (?,?,?,?,?,?)
                """, orgId, orderId, "caliber-" + kind + "-" + action, operatorId,
                operator.get("display_name"), "口径" + kind + " " + action);
    }

    /** ppm 精确乘算:base × rate / 1000000(HALF_UP)。 */
    private static long ppm(long base, int rate) {
        return BigDecimal.valueOf(base).multiply(BigDecimal.valueOf(rate))
                .divide(BigDecimal.valueOf(1000000L), 0, RoundingMode.HALF_UP).longValue();
    }

    private static String tableOf(String kind) {
        return "decoration_caliber_" + kind;
    }

    private static long nvl0(Long v) {
        return v == null ? 0L : v;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
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
