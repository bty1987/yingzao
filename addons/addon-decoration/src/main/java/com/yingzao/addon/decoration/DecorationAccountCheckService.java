package com.yingzao.addon.decoration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.addons.api.ConstructionApi;
import com.yingzao.addons.api.PaymentApi;
import com.yingzao.addons.api.SalesApi;
import com.yingzao.core.web.BizException;

/**
 * 装修决算六项前置检查(B 类#6 回补;老 DecorationAccountStatusServiceImpl 口径收编,
 * docs/rules/decoration.md §8)。
 *
 * 与老系统的三点结构差异(显式决策,详见第 47 次报告):
 * 1. 老系统落 decorationaccountstatus 台账表+各域事件驱动刷新;营造无状态实时计算——
 *    决算门槛只需要决算时刻的快照,实时算零时序问题,消掉整层事件刷新复杂度;
 * 2. 老配置按 company 取值回退全局(方林嫌疑);营造组织级单表(军规 1/7),target 统一 ‰
 *    (老 99 百分比 → 990);
 * 3. 老系统「未处理款项校验」被注释放宽(允许带未收款决算,靠 payScale target 闸兜底);
 *    营造 V1 是款项全收齐硬闸(250016)——总闸关保持硬闸(保守),总闸开后该硬闸由
 *    「装修收款比例」子项接管(target 可配,默认 990=99%,设 1000 等价全收齐),老系统
 *    的行业口径(尾款决算后收)由此可配。
 *
 * 子项:0签约/1主材销售/2施工/3选材/4装修收款/5销售收款;状态 0 UNCHECK/1 PASS/2 FAIL;
 * 合成:任一 FAIL → FAIL(拒 250023);否则任一 PASS → PASS;否则 UNCHECK。
 * 「选材」子项预留:选材域插件未交付,开关可配但恒 UNCHECK(数据源就位后接入)。
 */
@Service
public class DecorationAccountCheckService {

    /** 子项/总状态(老 DecorationAccountStatusPO 语义)。 */
    public static final int ST_UNCHECK = 0;
    public static final int ST_PASS = 1;
    public static final int ST_FAIL = 2;

    /** 决算检查不满足(250000 段;老 140085 同义)。 */
    public static final int CODE_CHECK_FAILED = 250023;

    /** 子项键(配置列后缀/预检返回键两用)。 */
    private static final String[] ITEMS = {
            "sign", "sales", "construction", "selection", "decoPayment", "salePayment"};

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final SalesApi sales;
    private final ConstructionApi constructions;
    private final PaymentApi payments;

    public DecorationAccountCheckService(JdbcTemplate jdbc, MessageSource messages,
            @Lazy SalesApi sales, ConstructionApi constructions, PaymentApi payments) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.sales = sales;
        this.constructions = constructions;
        this.payments = payments;
    }

    // ---- 组织配置(懒初始化,照 construction_config 模式) ----

    private Map<String, Object> ensureConfig(long orgId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from decoration_config where org_id = ?", orgId);
        if (rows.isEmpty()) {
            jdbc.update("insert into decoration_config(org_id) values (?)", orgId);
            return jdbc.queryForMap("select * from decoration_config where org_id = ?", orgId);
        }
        return rows.get(0);
    }

    public Map<String, Object> getConfig(long orgId) {
        return ensureConfig(orgId);
    }

    /** 更新白名单:九配置列;org 不可改。开关 0/1,target 0-1000(‰)。 */
    @Transactional
    public void updateConfig(long orgId, Boolean checkEnable, Boolean signEnable, Boolean salesEnable,
            Boolean constructionEnable, Boolean selectionEnable, Boolean decoPaymentEnable,
            Boolean salePaymentEnable, Integer decoPaymentTarget, Integer salePaymentTarget) {
        ensureConfig(orgId);
        StringBuilder set = new StringBuilder("update decoration_config set updated_at = updated_at");
        List<Object> args = new ArrayList<>();
        Boolean[] switches = { checkEnable, signEnable, salesEnable, constructionEnable,
                selectionEnable, decoPaymentEnable, salePaymentEnable };
        String[] columns = { "check_enable", "check_sign_enable", "check_sales_enable",
                "check_construction_enable", "check_selection_enable", "check_deco_payment_enable",
                "check_sale_payment_enable" };
        for (int i = 0; i < switches.length; i++) {
            if (switches[i] != null) {
                set.append(", ").append(columns[i]).append(" = ?");
                args.add(switches[i] ? 1 : 0);
            }
        }
        if (decoPaymentTarget != null) {
            requireTarget(decoPaymentTarget);
            set.append(", deco_payment_target = ?");
            args.add(decoPaymentTarget);
        }
        if (salePaymentTarget != null) {
            requireTarget(salePaymentTarget);
            set.append(", sale_payment_target = ?");
            args.add(salePaymentTarget);
        }
        set.append(" where org_id = ?");
        args.add(orgId);
        jdbc.update(set.toString(), args.toArray());
    }

    // ---- 预检(只读实时计算;决算按钮先调本端点展示六子项,再提交决算) ----

    public Map<String, Object> preview(long orgId, long orderId) {
        Map<String, Object> config = ensureConfig(orgId);
        boolean master = intVal(config, "check_enable") == 1;
        Map<String, Object> computed = compute(orgId, orderId, config, master);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkEnable", master);
        result.put("overall", computed.get("overall"));
        result.put("items", computed.get("items"));
        result.put("decoPaymentTarget", intVal(config, "deco_payment_target"));
        result.put("salePaymentTarget", intVal(config, "sale_payment_target"));
        return result;
    }

    /** 总闸是否开启(DecorationService.account 据此分流:关=款项全收齐硬闸,开=六项检查)。 */
    public boolean enabled(long orgId) {
        return intVal(ensureConfig(orgId), "check_enable") == 1;
    }

    /** 决算门槛(DecorationService.account 调用):总闸关直接过(保持款项全收齐硬闸);开则任一 FAIL 拒。 */
    public void check(long orgId, long orderId) {
        Map<String, Object> config = ensureConfig(orgId);
        if (intVal(config, "check_enable") != 1) {
            return;
        }
        Map<String, Object> computed = compute(orgId, orderId, config, true);
        if ((int) computed.get("overall") != ST_FAIL) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) computed.get("items");
        List<String> failedNames = new ArrayList<>();
        for (String item : ITEMS) {
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) items.get(item);
            if ((int) row.get("status") == ST_FAIL) {
                failedNames.add(msg("decoration.account.check." + item));
            }
        }
        throw new BizException(CODE_CHECK_FAILED,
                msgArgs("decoration.order.account.checkFailed", String.join("、", failedNames)));
    }

    // ---- 内部:六子项计算 ----

    private Map<String, Object> compute(long orgId, long orderId, Map<String, Object> config, boolean master) {
        Integer orderStatus = jdbc.queryForObject(
                "select status from decoration_order where id = ? and org_id = ?", Integer.class, orderId, orgId);
        if (orderStatus == null) {
            throw new BizException(DecorationService.CODE_NOT_FOUND, msg("decoration.order.notfound"));
        }
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("sign", item(master, intVal(config, "check_sign_enable") == 1,
                orderStatus == DecorationService.STATUS_SIGNED || orderStatus == DecorationService.STATUS_ACCOUNT,
                null));
        // 迁移白名单已硬闸(仅 3 可决算),sign 子项在决算路径恒 PASS——保留开关为结构对齐六项。
        boolean salesOn = master && intVal(config, "check_sales_enable") == 1;
        int salesUnfinish = salesOn ? sales.countUnfinishedOrders(orgId, orderId) : 0;
        items.put("sales", item(salesOn, true, salesUnfinish == 0, Map.of("unfinish", salesUnfinish)));

        boolean constructionOn = master && intVal(config, "check_construction_enable") == 1;
        ConstructionApi.AccountNodesCheck nodes = constructionOn
                ? constructions.accountNodes(orgId, orderId) : null;
        items.put("construction", item(constructionOn, true,
                nodes != null && nodes.allFinished(),
                nodes == null ? null : Map.of("total", nodes.total(), "finished", nodes.finished(),
                        "firstPendingName", nodes.firstPendingName() == null ? "" : nodes.firstPendingName())));

        // 选材子项预留:开关可配,选材域插件交付前恒 UNCHECK(不拦;开关只影响 enabled 回显)。
        items.put("selection", Map.of("enabled", master && intVal(config, "check_selection_enable") == 1,
                "status", ST_UNCHECK, "detail", Map.of()));

        boolean decoPayOn = master && intVal(config, "check_deco_payment_enable") == 1;
        long decoTarget = intVal(config, "deco_payment_target");
        long decoRatio = decoPayOn ? collectedRatio(orgId, DecorationService.BIZ_ADDON,
                DecorationService.BIZ_TYPE, orderId) : 0;
        items.put("decoPayment", item(decoPayOn, true, decoRatio >= decoTarget,
                Map.of("ratio", decoRatio, "target", decoTarget)));

        boolean salePayOn = master && intVal(config, "check_sale_payment_enable") == 1;
        long saleTarget = intVal(config, "sale_payment_target");
        long saleRatio = salePayOn ? sales.collectedRatio(orgId, orderId) : 0;
        items.put("salePayment", item(salePayOn, true, saleRatio >= saleTarget,
                Map.of("ratio", saleRatio, "target", saleTarget)));

        int overall = ST_UNCHECK;
        for (String key : ITEMS) {
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) items.get(key);
            int status = (int) row.get("status");
            if (status == ST_FAIL) {
                overall = ST_FAIL;
                break;
            }
            if (status == ST_PASS && overall == ST_UNCHECK) {
                overall = ST_PASS;
            }
        }
        // 未启用子项恒 UNCHECK,不参与合成(任一 FAIL→FAIL;否则任一 PASS→PASS;否则 UNCHECK)。
        return Map.of("overall", overall, "items", items);
    }

    /** enabled=总闸×子项开关双开才检查;disabled → UNCHECK。 */
    private Map<String, Object> item(boolean master, boolean switchOn, boolean pass, Map<String, Object> detail) {
        boolean enabled = master && switchOn;
        int status = !enabled ? ST_UNCHECK : (pass ? ST_PASS : ST_FAIL);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("enabled", enabled);
        row.put("status", status);
        row.put("detail", detail == null ? Map.of() : detail);
        return row;
    }

    /** 收款比例(‰)= Σ(已收+已确认) / Σ未作废应收;口径与 construction 闸一致(经 PaymentApi 契约)。 */
    private long collectedRatio(long orgId, String bizAddon, String bizType, long orderId) {
        long denom = 0;
        long numer = 0;
        for (PaymentApi.BillSummary bill : payments.listBills(orgId, bizAddon, bizType, String.valueOf(orderId))) {
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

    private int intVal(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v instanceof Number n ? n.intValue() : 0;
    }

    private void requireTarget(int target) {
        if (target < 0 || target > 1000) {
            throw new BizException(DecorationService.CODE_INVALID_PARAM, msg("decoration.param.invalid"));
        }
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private String msgArgs(String key, Object... args) {
        return messages.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}
