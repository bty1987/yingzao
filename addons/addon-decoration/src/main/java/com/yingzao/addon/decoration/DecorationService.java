package com.yingzao.addon.decoration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingzao.addons.api.CustomerProfileApi;
import com.yingzao.addons.api.PaymentApi;
import com.yingzao.addons.api.QuotationApi;
import com.yingzao.addons.api.event.DecorationCreatedEvent;
import com.yingzao.addons.api.event.DecorationSignedEvent;
import com.yingzao.addons.api.event.DecorationStatusChangedEvent;
import com.yingzao.core.approval.ApprovalService;
import com.yingzao.core.eventbus.YzEventBus;
import com.yingzao.core.web.BizException;

/**
 * 装修单主链:登记(七联动)→ 定金(事件回写)→ 预签 → 签约(款线拆分)→(重签)→ 决算 → 完工;
 * 异常收口 = 废单(排除法)/ 终止(先审批,通过回写终态,驳回零改动)。
 * 对老缺陷的显式修正(rules/decoration.md §14):
 *   合同线显式迁移表白名单(老 changeDecorationForStatus 裸写任意直改);
 *   跳号过渡态 20/30/32/33 不迁——终止走内核轻量审批的显式审批单,驳回原状态零改动(老「驳回顶态」);
 *   款线拆分经 PaymentApi 契约,禁直查/直写 payment 表(修老跨模块写表面);
 *   展示名一律建档快照(经 CustomerProfileApi 只读契约取,禁跨插件直查他域表);
 *   确认线 0→1→2 不可跳步不可倒退(老 %10 变体语义保留:11 微改/21 大改)。
 * 四条状态线(合同/工程/设计/确认)独立推进互不覆盖;每步轨迹留痕+领域事件经内核事件总线。
 */
@Service
public class DecorationService {

    // ---- 错误码(250000 段,同码同消息见 i18n/decoration.properties) ----
    public static final int CODE_NOT_FOUND = 250001;
    public static final int CODE_ILLEGAL_TRANSITION = 250002;
    public static final int CODE_DUP = 250003;
    public static final int CODE_CUSTOMER_NOT_FOUND = 250004;
    public static final int CODE_HOUSE_NOT_FOUND = 250005;
    public static final int CODE_INVALID_PARAM = 250006;
    public static final int CODE_DEPOSIT_AMOUNT = 250007;
    public static final int CODE_PRESIGN_NO_PROFILE = 250008;
    public static final int CODE_SIGN_NO_PAYMENT = 250009;
    public static final int CODE_SIGN_AMOUNT = 250010;
    public static final int CODE_SIGN_TEMPLATE = 250011;
    public static final int CODE_APPROVAL_PENDING = 250012;
    public static final int CODE_TERMINATION_PENDING = 250013;
    public static final int CODE_SOURCE_BAD_STEP = 250014;
    public static final int CODE_SOURCE_NOT_SIGNED = 250015;
    public static final int CODE_ACCOUNT_NOT_COLLECTED = 250016;
    public static final int CODE_MEASURE_BAD_STATE = 250017;
    public static final int CODE_MEASURE_BAD_ORDER_STATE = 250018;
    public static final int CODE_BELONG_NOT_FOUND = 250019;
    public static final int CODE_BELONG_BAD_STATE = 250020;
    public static final int CODE_NO_CONFLICT = 250021;
    public static final int CODE_PRESIGN_NO_QUOTATION = 250022;
    /** 重签前置:存在未确认增减差量单(B 类联动回补,经 QuotationApi 契约)。 */
    public static final int CODE_AR_PENDING = 250024;

    // ---- 合同线状态(老 DecorationPO:51-113;跳号过渡态不迁,审批用内核显式审批单) ----
    public static final int STATUS_INIT = 0;
    public static final int STATUS_DEPOSIT = 1;
    public static final int STATUS_PRESIGNED = 2;
    public static final int STATUS_SIGNED = 3;
    public static final int STATUS_RESIGNED = 4;
    public static final int STATUS_OBSOLETE = 5;
    public static final int STATUS_TERMINATED = 6;
    public static final int STATUS_ACCOUNT = 7;
    public static final int STATUS_FINISHED = 8;

    /** 合同线显式迁移表(唯一合法路径;白名单外一律 250002)。
     *  签约白名单照老系统 {0,1,2,4}→3(允许跳过预签直签);重签 3→4→3 循环保留。 */
    private static final Map<Integer, Set<Integer>> TRANSITIONS = Map.of(
            STATUS_INIT, Set.of(STATUS_DEPOSIT, STATUS_PRESIGNED, STATUS_SIGNED, STATUS_OBSOLETE),
            STATUS_DEPOSIT, Set.of(STATUS_PRESIGNED, STATUS_SIGNED, STATUS_OBSOLETE),
            STATUS_PRESIGNED, Set.of(STATUS_SIGNED, STATUS_OBSOLETE),
            STATUS_SIGNED, Set.of(STATUS_RESIGNED, STATUS_TERMINATED, STATUS_ACCOUNT),
            STATUS_RESIGNED, Set.of(STATUS_SIGNED),
            STATUS_OBSOLETE, Set.of(),
            STATUS_TERMINATED, Set.of(),
            STATUS_ACCOUNT, Set.of(STATUS_FINISHED),
            STATUS_FINISHED, Set.of());

    /** 单类型(老 DecorationPO:101-105)。 */
    public static final int TYPE_MIDPACK = 0;
    public static final int TYPE_INCLUSIVE = 1;
    public static final int TYPE_HOLONOMY = 2;
    private static final Set<Integer> TYPES = Set.of(TYPE_MIDPACK, TYPE_INCLUSIVE, TYPE_HOLONOMY);

    /** 确认线变体(老 %10==1 = 合同确认位):0 正常/1 微改/2 大改。 */
    public static final int VARIANT_NONE = 0;
    public static final int VARIANT_MINOR = 1;
    public static final int VARIANT_MAJOR = 2;

    /** 归属线。 */
    public static final int LINE_DESIGNER = 0;
    public static final int LINE_SALESMAN = 1;
    /** 归属状态:0 未生效/1 有效/2 失效。 */
    public static final int BELONG_ACTIVE = 1;
    public static final int BELONG_INVALID = 2;
    /** 分佣千分比独占(合尺 500 V2)。 */
    public static final int RATE_EXCLUSIVE = 1000;

    /** 转归属允许的主单状态(老 {2,3,4,8} 拒;决算 7 收紧为不可——业绩已结算锚定,行业惯例)。 */
    private static final Set<Integer> TRANSFER_ALLOWED = Set.of(STATUS_INIT, STATUS_DEPOSIT,
            STATUS_OBSOLETE, STATUS_TERMINATED);
    /** 设计确认/量房发起允许的合同状态(预签起)。 */
    private static final Set<Integer> DELIVERY_ALLOWED = Set.of(STATUS_PRESIGNED, STATUS_SIGNED, STATUS_RESIGNED);

    public static final String BIZ_ADDON = "decoration";
    public static final String BIZ_TYPE = "order";
    public static final String BIZ_TYPE_TERMINATION = "termination";
    /** 定金款项类型协议键(组织款项类型字典 code;签约拆分组由比例模板声明)。 */
    public static final String TYPE_CODE_DEPOSIT = "deposit";

    private static final DateTimeFormatter SEQ_DATE = DateTimeFormatter.ofPattern("yyMMdd");

    private record OrderRow(long id, long orgId, String orderNo, long customerId, String customerName,
            long houseId, String houseLabel, int type, int status, int projectStatus, int designStatus,
            int sourceStatus, long designerId, long salesmanId, Long terminationApprovalId, Long createdBy) {
    }

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final YzEventBus eventBus;
    private final PaymentApi payments;
    private final CustomerProfileApi profiles;
    private final QuotationApi quotations;
    private final ApprovalService approvals;
    private final DecorationAccountCheckService accountChecks;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DecorationService(JdbcTemplate jdbc, MessageSource messages, YzEventBus eventBus,
            PaymentApi payments, CustomerProfileApi profiles, QuotationApi quotations, ApprovalService approvals,
            DecorationAccountCheckService accountChecks) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.eventBus = eventBus;
        this.payments = payments;
        this.profiles = profiles;
        this.quotations = quotations;
        this.approvals = approvals;
        this.accountChecks = accountChecks;
    }

    // ---- 登记(七联动:主单+补充信息+归属两条+服务团队+轨迹+定金款线+事件) ----

    public record CreateCmd(long customerId, long houseId, Integer type, long designerId, long salesmanId,
            Long depositAmount, Long innerArea, String houseType, String style, String note, String remark) {
    }

    @Transactional
    public long create(long orgId, CreateCmd cmd, long operatorId) {
        require(cmd != null && cmd.type() != null && TYPES.contains(cmd.type()), CODE_INVALID_PARAM, "decoration.param.invalid");
        require(cmd.designerId() > 0 && cmd.salesmanId() > 0, CODE_INVALID_PARAM, "decoration.param.invalid");
        if (cmd.depositAmount() != null) {
            require(cmd.depositAmount() > 0, CODE_DEPOSIT_AMOUNT, "decoration.order.depositAmount");
        }
        // 档案经只读契约校验+取展示快照(禁跨插件直查他域表)
        CustomerProfileApi.CustomerSummary customer = profiles.queryCustomer(orgId, cmd.customerId())
                .orElseThrow(() -> new BizException(CODE_CUSTOMER_NOT_FOUND, msg("decoration.order.customerNotFound")));
        CustomerProfileApi.HouseSummary house = profiles.queryHouse(orgId, cmd.houseId())
                .orElseThrow(() -> new BizException(CODE_HOUSE_NOT_FOUND, msg("decoration.order.houseNotFound")));
        // 查重:同客户×同楼盘存在在途单(老 decoration 客户×楼盘唯一;终态不挡重建)
        Integer dup = jdbc.queryForObject("""
                select count(*) from decoration_order
                where org_id = ? and customer_id = ? and house_id = ? and status not in (?, ?, ?)
                """, Integer.class, orgId, cmd.customerId(), cmd.houseId(),
                STATUS_OBSOLETE, STATUS_TERMINATED, STATUS_FINISHED);
        if (dup != null && dup > 0) {
            throw new BizException(CODE_DUP, msg("decoration.order.dup"));
        }
        String orderNo = nextOrderNo(orgId);
        String designerName = displayName(cmd.designerId());
        String salesmanName = displayName(cmd.salesmanId());
        jdbc.update("""
                insert into decoration_order(org_id, order_no, customer_id, customer_name, house_id, house_label,
                    type, status, designer_id, salesman_id, remark, created_by)
                values (?,?,?,?,?,?,?,0,?,?,?,?)
                """, orgId, orderNo, cmd.customerId(), customer.name(), cmd.houseId(), house.label(),
                cmd.type(), cmd.designerId(), cmd.salesmanId(), blankToNull(cmd.remark()), operatorId);
        long orderId = jdbc.queryForObject("""
                select id from decoration_order where org_id = ? and order_no = ?
                """, Long.class, orgId, orderNo);
        jdbc.update("""
                insert into decoration_order_profile(order_id, org_id, inner_area, house_type, style, note)
                values (?,?,?,?,?,?)
                """, orderId, orgId, cmd.innerArea(), blankToNull(cmd.houseType()), blankToNull(cmd.style()),
                blankToNull(cmd.note()));
        insertBelong(orgId, orderId, LINE_DESIGNER, cmd.designerId(), designerName);
        insertBelong(orgId, orderId, LINE_SALESMAN, cmd.salesmanId(), salesmanName);
        insertTeam(orgId, orderId, cmd.designerId(), designerName, "designer");
        insertTeam(orgId, orderId, cmd.salesmanId(), salesmanName, "salesman");
        track(orgId, orderId, "create", null, STATUS_INIT, operatorId, null);
        if (cmd.depositAmount() != null) {
            payments.createBill(new PaymentApi.CreateBillCmd(orgId, BIZ_ADDON, BIZ_TYPE, String.valueOf(orderId),
                    TYPE_CODE_DEPOSIT, cmd.depositAmount(), "定金"));
        }
        eventBus.publish(new DecorationCreatedEvent(orderId, orgId, orderNo, cmd.customerId(), cmd.houseId(),
                cmd.type(), cmd.designerId(), cmd.salesmanId()), "order:" + orderId + ":created");
        return orderId;
    }

    // ---- 合同线流转 ----

    /** 预签:0/1 → 2。门槛 = 补充信息已登记 + 存在已锁定报价(经 QuotationApi 只读契约回补,修老门槛缺位;
     *  活动锁定门槛随会员营销插件再议)。 */
    @Transactional
    public void presign(long orgId, long orderId, long operatorId) {
        OrderRow order = load(orgId, orderId);
        requireTransition(order.status(), STATUS_PRESIGNED);
        Integer profileCount = jdbc.queryForObject(
                "select count(*) from decoration_order_profile where order_id = ?", Integer.class, orderId);
        if (profileCount == null || profileCount == 0) {
            throw new BizException(CODE_PRESIGN_NO_PROFILE, msg("decoration.order.presign.noProfile"));
        }
        if (!quotations.hasLockedQuotation(orgId, orderId)) {
            throw new BizException(CODE_PRESIGN_NO_QUOTATION, msg("decoration.order.presign.noQuotation"));
        }
        transition(orgId, order, STATUS_PRESIGNED, "presign", operatorId, null);
    }

    /** 签约:{0,1,2,4} → 3。门槛 = 已有已收款项 + 无进行中审批 + 合同总额>0;按类型字典比例模板拆分签约款线。 */
    @Transactional
    public void sign(long orgId, long orderId, Long contractAmount, long operatorId) {
        OrderRow order = load(orgId, orderId);
        require(contractAmount != null && contractAmount > 0, CODE_SIGN_AMOUNT, "decoration.order.sign.amount");
        requireTransition(order.status(), STATUS_SIGNED);
        requireNoRunningApproval(orderId);
        List<PaymentApi.BillSummary> bills = payments.listBills(orgId, BIZ_ADDON, BIZ_TYPE, String.valueOf(orderId));
        boolean anyCollected = bills.stream()
                .anyMatch(b -> b.direction() == 0 && (b.status() == 1 || b.status() == 2));
        if (!anyCollected) {
            throw new BizException(CODE_SIGN_NO_PAYMENT, msg("decoration.order.sign.noPayment"));
        }
        splitContractBills(orgId, orderId, contractAmount, bills);
        int updated = jdbc.update(
                "update decoration_order set status = ?, contract_time = coalesce(contract_time, now()) "
                        + "where id = ? and status = ?",
                STATUS_SIGNED, orderId, order.status());
        if (updated == 0) {
            throw new BizException(CODE_ILLEGAL_TRANSITION, msg("decoration.order.illegalTransition"));
        }
        // 签约联动设计线 0→1(老 DecorationContractServiceImpl:612-615;条件更新,已完成不回退)
        jdbc.update("update decoration_order set design_status = 1 where id = ? and design_status = 0", orderId);
        track(orgId, orderId, "sign", order.status(), STATUS_SIGNED, operatorId, null);
        eventBus.publish(new DecorationSignedEvent(orderId, orgId, order.orderNo(), contractAmount, operatorId),
                "order:" + orderId + ":signed");
        publishStatus(orgId, order, STATUS_SIGNED, "sign", operatorId);
    }

    /** 按组织款项类型字典的比例模板逐项拆分签约款线(已存在同类型款线跳过——重签再签约不重建)。 */
    private void splitContractBills(long orgId, long orderId, long contractAmount,
            List<PaymentApi.BillSummary> existing) {
        Set<String> presentTypes = existing.stream()
                .filter(b -> b.direction() == 0)
                .map(PaymentApi.BillSummary::typeCode)
                .collect(java.util.stream.Collectors.toSet());
        for (Map<String, Object> type : payments.listBillTypes(orgId)) {
            String templateJson = (String) type.get("scaleTemplate");
            if (templateJson == null || templateJson.isBlank()) {
                continue;
            }
            String code = (String) type.get("code");
            if (presentTypes.contains(code)) {
                continue;
            }
            long amount = scaleAmount(contractAmount, templateJson, code);
            if (amount <= 0) {
                continue;
            }
            payments.createBill(new PaymentApi.CreateBillCmd(orgId, BIZ_ADDON, BIZ_TYPE, String.valueOf(orderId),
                    code, amount, "签约款线(按比例模板拆分)"));
        }
    }

    /** 模板 = [{"sort":1,"scale":500}](‰);取比例合计拆分,非法模板显式拒绝(灭绝魔法值)。 */
    private long scaleAmount(long contractAmount, String templateJson, String typeCode) {
        try {
            JsonNode template = objectMapper.readTree(templateJson);
            if (!template.isArray() || template.isEmpty()) {
                throw new BizException(CODE_SIGN_TEMPLATE, msg("decoration.order.sign.template"));
            }
            long scaleSum = 0;
            for (JsonNode node : template) {
                if (node.get("scale") == null || node.get("scale").asLong() <= 0) {
                    throw new BizException(CODE_SIGN_TEMPLATE, msg("decoration.order.sign.template"));
                }
                scaleSum += node.get("scale").asLong();
            }
            return BigDecimal.valueOf(contractAmount).multiply(BigDecimal.valueOf(scaleSum))
                    .divide(BigDecimal.valueOf(1000), 0, RoundingMode.HALF_UP).longValue();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(CODE_SIGN_TEMPLATE, msg("decoration.order.sign.template") + ": " + typeCode);
        }
    }

    /** 重签:仅 3 → 4(留 recontractTime,不覆盖首签;无增减项门槛随 addon-quotation 回补)。 */
    @Transactional
    public void resign(long orgId, long orderId, long operatorId) {
        OrderRow order = load(orgId, orderId);
        requireTransition(order.status(), STATUS_RESIGNED);
        requireNoRunningApproval(orderId);
        // 重签前置「无增减项」(老 ContractServiceImpl:689-740;B 类联动回补):
        // 存在未确认增减差量单(草稿/已提交/已付款)不可重签——差量款未落定前价格不得重置
        if (quotations.hasUnconfirmedAddReduce(orgId, orderId)) {
            throw new BizException(CODE_AR_PENDING, msg("decoration.order.arPending"));
        }
        int updated = jdbc.update(
                "update decoration_order set status = ?, recontract_time = now() where id = ? and status = ?",
                STATUS_RESIGNED, orderId, STATUS_SIGNED);
        if (updated == 0) {
            throw new BizException(CODE_ILLEGAL_TRANSITION, msg("decoration.order.illegalTransition"));
        }
        track(orgId, orderId, "resign", STATUS_SIGNED, STATUS_RESIGNED, operatorId, null);
        publishStatus(orgId, order, STATUS_RESIGNED, "resign", operatorId);
    }

    /** 废单:仅未签约前 {0,1,2} → 5(排除法白名单化);联动作废全部未收款项单。 */
    @Transactional
    public void obsolete(long orgId, long orderId, long operatorId) {
        OrderRow order = load(orgId, orderId);
        requireTransition(order.status(), STATUS_OBSOLETE);
        int updated = jdbc.update(
                "update decoration_order set status = ? where id = ? and status = ?",
                STATUS_OBSOLETE, orderId, order.status());
        if (updated == 0) {
            throw new BizException(CODE_ILLEGAL_TRANSITION, msg("decoration.order.illegalTransition"));
        }
        cancelUnpaidBills(orgId, orderId);
        track(orgId, orderId, "obsolete", order.status(), STATUS_OBSOLETE, operatorId, null);
        publishStatus(orgId, order, STATUS_OBSOLETE, "obsolete", operatorId);
    }

    /** 提交终止审批:仅 3;同单存在进行中审批即拒(显式审批单代替老跳号过渡态 32/33)。 */
    @Transactional
    public long submitTermination(long orgId, long orderId, Long assigneeId, String reason, long operatorId) {
        require(assigneeId != null, CODE_INVALID_PARAM, "decoration.param.invalid");
        OrderRow order = load(orgId, orderId);
        if (order.status() != STATUS_SIGNED) {
            throw new BizException(CODE_ILLEGAL_TRANSITION, msg("decoration.order.illegalTransition"));
        }
        Integer pendingTermination = jdbc.queryForObject("""
                select count(*) from yz_approval
                where biz_addon = ? and biz_type = ? and biz_id = ? and status = ?
                """, Integer.class, BIZ_ADDON, BIZ_TYPE_TERMINATION, String.valueOf(orderId),
                ApprovalService.STATUS_RUNNING);
        if (pendingTermination != null && pendingTermination > 0) {
            throw new BizException(CODE_TERMINATION_PENDING, msg("decoration.order.terminationPending"));
        }
        String title = messages.getMessage("decoration.terminate.approval.title",
                new Object[] { order.orderNo() }, LocaleContextHolder.getLocale());
        long approvalId = approvals.submit(new ApprovalService.SubmitCmd(orgId, operatorId, title,
                BIZ_ADDON, BIZ_TYPE_TERMINATION, String.valueOf(orderId), assigneeId));
        jdbc.update("update decoration_order set termination_approval_id = ? where id = ?", approvalId, orderId);
        track(orgId, orderId, "terminateSubmit", null, null, operatorId, blankToNull(reason));
        return approvalId;
    }

    /** 决算:仅 3 → 7。门槛两态(组织级配置,DecorationAccountCheckService,B 类#6 六项检查回补):
     *  总闸关(V1 行为保持)= 该单款项全部收齐(未作废款线 status∈{1,2});
     *  总闸开 = 六项检查合成(任一 FAIL 拒 250023),款项全收齐硬闸由「装修收款比例」子项接管
     *  (老 payScale target 口径,target 可配,尾款决算后收的行业惯例由此可配)。 */
    @Transactional
    public void account(long orgId, long orderId, long operatorId) {
        OrderRow order = load(orgId, orderId);
        requireTransition(order.status(), STATUS_ACCOUNT);
        if (accountChecks.enabled(orgId)) {
            accountChecks.check(orgId, orderId);
        } else {
            List<PaymentApi.BillSummary> bills = payments.listBills(orgId, BIZ_ADDON, BIZ_TYPE, String.valueOf(orderId));
            boolean allCollected = bills.stream()
                    .filter(b -> b.direction() == 0 && b.status() != 4)
                    .allMatch(b -> b.status() == 1 || b.status() == 2);
            if (!allCollected) {
                throw new BizException(CODE_ACCOUNT_NOT_COLLECTED, msg("decoration.order.account.notCollected"));
            }
        }
        int updated = jdbc.update(
                "update decoration_order set status = ?, account_time = now() where id = ? and status = ?",
                STATUS_ACCOUNT, orderId, STATUS_SIGNED);
        if (updated == 0) {
            throw new BizException(CODE_ILLEGAL_TRANSITION, msg("decoration.order.illegalTransition"));
        }
        track(orgId, orderId, "account", STATUS_SIGNED, STATUS_ACCOUNT, operatorId, null);
        publishStatus(orgId, order, STATUS_ACCOUNT, "account", operatorId);
    }

    /** 完工:仅 7 → 8(工程线 project_status=2 由 addon-construction 联动,保持四线独立)。 */
    @Transactional
    public void finish(long orgId, long orderId, long operatorId) {
        OrderRow order = load(orgId, orderId);
        requireTransition(order.status(), STATUS_FINISHED);
        int updated = jdbc.update(
                "update decoration_order set status = ?, finish_time = now() where id = ? and status = ?",
                STATUS_FINISHED, orderId, STATUS_ACCOUNT);
        if (updated == 0) {
            throw new BizException(CODE_ILLEGAL_TRANSITION, msg("decoration.order.illegalTransition"));
        }
        track(orgId, orderId, "finish", STATUS_ACCOUNT, STATUS_FINISHED, operatorId, null);
        publishStatus(orgId, order, STATUS_FINISHED, "finish", operatorId);
    }

    /** 终止审批通过回写(供 TerminationApprovalListener 调用;REQUIRES_NEW 事务内):
     *  3 → 6 带 approval_id 守卫(陈旧事件拒);联动未收款项单作废+归属置失效。 */
    @Transactional
    public void executeTermination(long orgId, long orderId, long approvalId, long operatorId) {
        OrderRow order = load(orgId, orderId);
        if (order.terminationApprovalId() == null || order.terminationApprovalId() != approvalId) {
            throw new BizException(CODE_ILLEGAL_TRANSITION, msg("decoration.order.illegalTransition"));
        }
        int updated = jdbc.update("""
                update decoration_order set status = ? where id = ? and status = ? and termination_approval_id = ?
                """, STATUS_TERMINATED, orderId, STATUS_SIGNED, approvalId);
        if (updated == 0) {
            throw new BizException(CODE_ILLEGAL_TRANSITION, msg("decoration.order.illegalTransition"));
        }
        cancelUnpaidBills(orgId, orderId);
        jdbc.update("update decoration_order_belong set status = ? where order_id = ? and status = ?",
                BELONG_INVALID, orderId, BELONG_ACTIVE);
        track(orgId, orderId, "terminate", STATUS_SIGNED, STATUS_TERMINATED, operatorId, null);
        publishStatus(orgId, order, STATUS_TERMINATED, "terminate", operatorId);
    }

    // ---- 确认线(0 →(1/11/21)→ 2;不可跳步不可倒退) ----

    /** 合同方案客户确认:0 → 1/11/21(变体=微改/大改);联动设计线 0→1。 */
    @Transactional
    public void designConfirm(long orgId, long orderId, Integer variant, long operatorId) {
        require(variant != null && variant >= VARIANT_NONE && variant <= VARIANT_MAJOR,
                CODE_INVALID_PARAM, "decoration.param.invalid");
        OrderRow order = load(orgId, orderId);
        if (!DELIVERY_ALLOWED.contains(order.status())) {
            throw new BizException(CODE_SOURCE_NOT_SIGNED, msg("decoration.order.source.notSigned"));
        }
        if (order.sourceStatus() != 0) {
            throw new BizException(CODE_SOURCE_BAD_STEP, msg("decoration.order.source.badStep"));
        }
        int target = variant == VARIANT_NONE ? 1 : variant * 10 + 1;
        int updated = jdbc.update(
                "update decoration_order set source_status = ?, design_status = 1 where id = ? and source_status = 0",
                target, orderId);
        if (updated == 0) {
            throw new BizException(CODE_SOURCE_BAD_STEP, msg("decoration.order.source.badStep"));
        }
        track(orgId, orderId, "designConfirm", 0, target, operatorId,
                variant == VARIANT_MINOR ? "微改" : variant == VARIANT_MAJOR ? "大改" : null);
    }

    /** 放线确认:1/11/21 → 2(%10==1 = 合同确认位);联动设计线 → 2 已完成。 */
    @Transactional
    public void drawlineConfirm(long orgId, long orderId, long operatorId) {
        OrderRow order = load(orgId, orderId);
        if (!DELIVERY_ALLOWED.contains(order.status())) {
            throw new BizException(CODE_SOURCE_NOT_SIGNED, msg("decoration.order.source.notSigned"));
        }
        if (order.sourceStatus() % 10 != 1) {
            // %10==1 = 合同确认位(1/11/21);0 待确认不可跳步,2 已放线确认不可倒退
            throw new BizException(CODE_SOURCE_BAD_STEP, msg("decoration.order.source.badStep"));
        }
        int updated = jdbc.update(
                "update decoration_order set source_status = 2, design_status = 2 where id = ? and source_status in (1, 11, 21)",
                orderId);
        if (updated == 0) {
            throw new BizException(CODE_SOURCE_BAD_STEP, msg("decoration.order.source.badStep"));
        }
        track(orgId, orderId, "drawlineConfirm", order.sourceStatus(), 2, operatorId, null);
    }

    // ---- 量房/放线计划(0 排队 → 1 已申请 → 2 成功 / 3 失败,失败可重申) ----

    @Transactional
    public long measureApply(long orgId, long orderId, String planTimeText, String remark, long operatorId) {
        OrderRow order = load(orgId, orderId);
        if (!DELIVERY_ALLOWED.contains(order.status())) {
            throw new BizException(CODE_MEASURE_BAD_ORDER_STATE, msg("decoration.order.measure.badOrderState"));
        }
        LocalDateTime planTime = null;
        if (planTimeText != null && !planTimeText.isBlank()) {
            try {
                planTime = LocalDateTime.parse(planTimeText.trim().replace(' ', 'T'));
            } catch (DateTimeParseException e) {
                throw new BizException(CODE_INVALID_PARAM, msg("decoration.param.invalid"));
            }
        }
        Integer running = jdbc.queryForObject(
                "select count(*) from decoration_order_measure where order_id = ? and status = 1",
                Integer.class, orderId);
        if (running != null && running > 0) {
            throw new BizException(CODE_MEASURE_BAD_STATE, msg("decoration.order.measure.badState"));
        }
        jdbc.update("""
                insert into decoration_order_measure(org_id, order_id, status, plan_time, apply_time,
                    operator_id, operator_name, remark)
                values (?,?,1,?,?,?, ?,?)
                """, orgId, orderId, planTime, LocalDateTime.now(), operatorId, displayName(operatorId),
                blankToNull(remark));
        long measureId = jdbc.queryForObject("select last_insert_id()", Long.class);
        track(orgId, orderId, "measureApply", null, 1, operatorId, null);
        return measureId;
    }

    /** 量房成功:联动主单工程线 0→1 开工(老 DecorationMeasureServiceImpl:244-248)。 */
    @Transactional
    public void measureSuccess(long orgId, long measureId, long operatorId) {
        int updated = jdbc.update("""
                update decoration_order_measure set status = 2, finish_time = now()
                where id = ? and org_id = ? and status = 1
                """, measureId, orgId);
        if (updated == 0) {
            throw new BizException(CODE_MEASURE_BAD_STATE, msg("decoration.order.measure.badState"));
        }
        long orderId = jdbc.queryForObject(
                "select order_id from decoration_order_measure where id = ?", Long.class, measureId);
        jdbc.update("update decoration_order set project_status = 1 where id = ? and project_status = 0",
                orderId);
        track(orgId, orderId, "measureSuccess", 1, 2, operatorId, null);
    }

    @Transactional
    public void measureFail(long orgId, long measureId, String remark, long operatorId) {
        int updated = jdbc.update("""
                update decoration_order_measure set status = 3, finish_time = now(), remark = ?
                where id = ? and org_id = ? and status = 1
                """, blankToNull(remark), measureId, orgId);
        if (updated == 0) {
            throw new BizException(CODE_MEASURE_BAD_STATE, msg("decoration.order.measure.badState"));
        }
        long orderId = jdbc.queryForObject(
                "select order_id from decoration_order_measure where id = ?", Long.class, measureId);
        track(orgId, orderId, "measureFail", 1, 3, operatorId, null);
    }

    // ---- 归属(转归属即时生效;合尺客户确认两阶段 V2) ----

    @Transactional
    public void transferBelong(long orgId, long orderId, Integer line, long newUserId, long operatorId) {
        require(line != null && (line == LINE_DESIGNER || line == LINE_SALESMAN),
                CODE_INVALID_PARAM, "decoration.param.invalid");
        OrderRow order = load(orgId, orderId);
        if (!TRANSFER_ALLOWED.contains(order.status())) {
            throw new BizException(CODE_BELONG_BAD_STATE, msg("decoration.order.belong.badState"));
        }
        List<Map<String, Object>> actives = jdbc.queryForList("""
                select id from decoration_order_belong
                where order_id = ? and line = ? and status = ?
                """, orderId, line, BELONG_ACTIVE);
        if (actives.isEmpty()) {
            throw new BizException(CODE_BELONG_NOT_FOUND, msg("decoration.order.belong.notFound"));
        }
        jdbc.update("update decoration_order_belong set status = ? where id = ?",
                BELONG_INVALID, ((Number) actives.get(0).get("id")).longValue());
        String newName = displayName(newUserId);
        insertBelong(orgId, orderId, line, newUserId, newName);
        if (line == LINE_DESIGNER) {
            jdbc.update("update decoration_order set designer_id = ? where id = ?", newUserId, orderId);
        } else {
            jdbc.update("update decoration_order set salesman_id = ? where id = ?", newUserId, orderId);
        }
        track(orgId, orderId, "belongTransfer", null, line, operatorId, "→ " + newName);
    }

    // ---- 补充信息 ----

    public record ProfileFields(Long innerArea, String houseType, String style, String note) {
    }

    @Transactional
    public void saveProfile(long orgId, long orderId, ProfileFields f, long operatorId) {
        require(f != null, CODE_INVALID_PARAM, "decoration.param.invalid");
        OrderRow order = load(orgId, orderId);
        if (TRANSITIONS.get(order.status()).isEmpty()) {
            // 终态(废单/终止/完工)不可再改补充信息
            throw new BizException(CODE_ILLEGAL_TRANSITION, msg("decoration.order.illegalTransition"));
        }
        jdbc.update("""
                update decoration_order_profile set inner_area = ?, house_type = ?, style = ?, note = ?
                where order_id = ?
                """, f.innerArea(), blankToNull(f.houseType()), blankToNull(f.style()), blankToNull(f.note()),
                orderId);
        track(orgId, orderId, "profileSave", null, null, operatorId, null);
    }

    // ---- 收款事件回写(首收升定金;由 DecorationPaymentConsumer 调用,必须幂等) ----

    @Transactional
    public void onPaymentCollected(long orgId, long orderId, String typeCode, long operatorId) {
        if (!TYPE_CODE_DEPOSIT.equals(typeCode)) {
            return;
        }
        OrderRow order = load(orgId, orderId);
        if (order.status() != STATUS_INIT) {
            // 已升定金(事件重放)或已推进,幂等返回
            return;
        }
        requireTransition(order.status(), STATUS_DEPOSIT);
        int updated = jdbc.update(
                "update decoration_order set status = ?, deposit_time = now() where id = ? and status = ?",
                STATUS_DEPOSIT, orderId, STATUS_INIT);
        if (updated == 0) {
            return; // 并发窗口:另一条事件已升
        }
        track(orgId, orderId, "deposit", STATUS_INIT, STATUS_DEPOSIT, operatorId, null);
        publishStatus(orgId, order, STATUS_DEPOSIT, "deposit", operatorId);
    }

    // ---- 施工事件回写(由 DecorationConstructionConsumer 调用,必须幂等) ----

    /** 开工点火(放线成功/补录)→ 工程线 0→1;条件更新,重放/已越过(量房先行)静默放行。 */
    @Transactional
    public void onConstructionStarted(long orgId, long orderId, long operatorId) {
        int updated = jdbc.update(
                "update decoration_order set project_status = 1 where id = ? and project_status = 0",
                orderId);
        if (updated > 0) {
            track(orgId, orderId, "constructionStart", null, 1, operatorId, null);
        }
    }

    /** 验收竣工 → 工程线 1→2;条件更新,重放/未开工(0)不越级。 */
    @Transactional
    public void onConstructionAccepted(long orgId, long orderId, long operatorId) {
        int updated = jdbc.update(
                "update decoration_order set project_status = 2 where id = ? and project_status = 1",
                orderId);
        if (updated > 0) {
            track(orgId, orderId, "constructionAccept", 1, 2, operatorId, null);
        }
    }

    // ---- 查询 ----

    /**
     * 轻量检索(docs/15 R15 发起审批选单等跨页场景):keyword 模糊匹配单号/客户名/楼盘快照,
     * 只回轻量展示列(零业务面);与 /orders 列表同口径 authenticated 跨域引用惯例。
     */
    public List<Map<String, Object>> lookup(long orgId, String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            return List.of();
        }
        String like = "%" + kw + "%";
        return jdbc.queryForList("""
                select id, order_no, customer_name, house_label
                from decoration_order
                where org_id = ? and (order_no like ? or customer_name like ? or house_label like ?)
                order by id desc limit 20
                """, orgId, like, like, like);
    }

    public Map<String, Object> page(long orgId, String orderNo, String customerName, Integer status,
            Integer type, Long designerId, int pageNo, int size) {
        int safePage = Math.max(pageNo, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(" where o.org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        if (orderNo != null && !orderNo.isBlank()) {
            where.append(" and o.order_no like ?");
            args.add("%" + orderNo.trim() + "%");
        }
        if (customerName != null && !customerName.isBlank()) {
            where.append(" and o.customer_name like ?");
            args.add("%" + customerName.trim() + "%");
        }
        if (status != null) {
            where.append(" and o.status = ?");
            args.add(status);
        }
        if (type != null) {
            where.append(" and o.type = ?");
            args.add(type);
        }
        if (designerId != null) {
            where.append(" and o.designer_id = ?");
            args.add(designerId);
        }
        Long total = jdbc.queryForObject(
                "select count(*) from decoration_order o" + where, Long.class, args.toArray());
        List<Object> allArgs = new ArrayList<>(args);
        allArgs.add(safeSize);
        allArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select o.id, o.order_no, o.customer_id, o.customer_name, o.house_id, o.house_label,
                       o.type, o.status, o.project_status, o.design_status, o.source_status,
                       o.designer_id, o.salesman_id, o.deposit_time, o.contract_time, o.recontract_time,
                       o.account_time, o.finish_time, o.remark, u.display_name as creator_name, o.created_at
                from decoration_order o left join yz_user u on u.id = o.created_by
                """ + where + " order by o.id desc limit ? offset ?", allArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    public Map<String, Object> detail(long orgId, long orderId) {
        OrderRow order = load(orgId, orderId);
        Map<String, Object> result = jdbc.queryForMap("""
                select o.*, u.display_name as creator_name from decoration_order o
                left join yz_user u on u.id = o.created_by where o.id = ?
                """, orderId);
        Map<String, Object> profile = jdbc.queryForMap(
                "select * from decoration_order_profile where order_id = ?", orderId);
        result.put("profile", profile);
        result.put("team", jdbc.queryForList("""
                select role, user_id, user_name, created_at from decoration_order_team
                where order_id = ? order by id
                """, orderId));
        result.put("belongs", jdbc.queryForList("""
                select id, line, user_id, user_name, rate, status, created_at
                from decoration_order_belong where order_id = ? order by line, id
                """, orderId));
        result.put("tracks", jdbc.queryForList("""
                select action, from_value, to_value, operator_name, comment, created_at
                from decoration_order_track where order_id = ? order by id desc limit 100
                """, orderId));
        result.put("measures", jdbc.queryForList("""
                select id, status, plan_time, apply_time, finish_time, operator_name, remark
                from decoration_order_measure where order_id = ? order by id desc
                """, orderId));
        List<Map<String, Object>> bills = new ArrayList<>();
        for (PaymentApi.BillSummary bill : payments.listBills(orgId, BIZ_ADDON, BIZ_TYPE, String.valueOf(orderId))) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("billId", bill.billId());
            view.put("typeCode", bill.typeCode());
            view.put("typeName", bill.typeName());
            view.put("direction", bill.direction());
            view.put("amount", bill.amount());
            view.put("sort", bill.sort());
            view.put("status", bill.status());
            bills.add(view);
        }
        result.put("bills", bills);
        result.put("terminationApprovalId", order.terminationApprovalId());
        return result;
    }

    // ---- 内部 ----

    private void requireTransition(int from, int to) {
        if (!TRANSITIONS.getOrDefault(from, Set.of()).contains(to)) {
            throw new BizException(CODE_ILLEGAL_TRANSITION, msg("decoration.order.illegalTransition"));
        }
    }

    /** 单上任意进行中审批(含终止审批)即挡流转——老签约/重签门槛「无未处理审批」全量口径。 */
    private void requireNoRunningApproval(long orderId) {
        Integer running = jdbc.queryForObject("""
                select count(*) from yz_approval
                where biz_addon = ? and biz_id = ? and status = ?
                """, Integer.class, BIZ_ADDON, String.valueOf(orderId), ApprovalService.STATUS_RUNNING);
        if (running != null && running > 0) {
            throw new BizException(CODE_APPROVAL_PENDING, msg("decoration.order.approvalPending"));
        }
    }

    /** 状态迁移统一走条件 UPDATE(并发只成功一次)+轨迹+状态事件。 */
    private void transition(long orgId, OrderRow order, int to, String action, long operatorId, String comment) {
        int updated = jdbc.update(
                "update decoration_order set status = ? where id = ? and status = ?",
                to, order.id(), order.status());
        if (updated == 0) {
            throw new BizException(CODE_ILLEGAL_TRANSITION, msg("decoration.order.illegalTransition"));
        }
        track(orgId, order.id(), action, order.status(), to, operatorId, comment);
        publishStatus(orgId, order, to, action, operatorId);
    }

    private void cancelUnpaidBills(long orgId, long orderId) {
        for (PaymentApi.BillSummary bill : payments.listBills(orgId, BIZ_ADDON, BIZ_TYPE, String.valueOf(orderId))) {
            if (bill.direction() == 0 && bill.status() == 0) {
                payments.cancelBill(orgId, bill.billId());
            }
        }
    }

    private void publishStatus(long orgId, OrderRow order, int to, String action, long operatorId) {
        eventBus.publish(new DecorationStatusChangedEvent(order.id(), orgId, order.orderNo(),
                order.status(), to, action, operatorId), "order:" + order.id() + ":" + action);
    }

    /** 单号 = 前缀 YZ + yyMMdd + 4 位日序号;原子计数器(last_insert_id 惯用法)+ 唯一索引兜底。 */
    private String nextOrderNo(long orgId) {
        String seqDate = LocalDateTime.now().format(SEQ_DATE);
        jdbc.update("""
                insert into decoration_order_counter(org_id, seq_date, seq) values (?, ?, last_insert_id(1))
                on duplicate key update seq = last_insert_id(seq + 1)
                """, orgId, seqDate);
        long seq = jdbc.queryForObject("select last_insert_id()", Long.class);
        String orderNo = "YZ" + seqDate + String.format("%04d", seq);
        Integer exists = jdbc.queryForObject(
                "select count(*) from decoration_order where org_id = ? and order_no = ?",
                Integer.class, orgId, orderNo);
        if (exists != null && exists > 0) {
            throw new BizException(CODE_NO_CONFLICT, msg("decoration.order.noConflict"));
        }
        return orderNo;
    }

    private void insertBelong(long orgId, long orderId, int line, long userId, String userName) {
        jdbc.update("""
                insert into decoration_order_belong(org_id, order_id, line, user_id, user_name, rate, status)
                values (?,?,?,?,?,?,?)
                """, orgId, orderId, line, userId, userName, RATE_EXCLUSIVE, BELONG_ACTIVE);
    }

    private void insertTeam(long orgId, long orderId, long userId, String userName, String role) {
        jdbc.update("""
                insert into decoration_order_team(org_id, order_id, user_id, user_name, role)
                values (?,?,?,?,?)
                """, orgId, orderId, userId, userName, role);
    }

    private void track(long orgId, long orderId, String action, Integer from, Integer to,
            long operatorId, String comment) {
        jdbc.update("""
                insert into decoration_order_track(org_id, order_id, action, from_value, to_value,
                    operator_id, operator_name, comment)
                values (?,?,?,?,?,?,?,?)
                """, orgId, orderId, action, from, to, operatorId, displayName(operatorId), blankToNull(comment));
    }

    private OrderRow load(long orgId, long orderId) {
        require(orderId > 0, CODE_INVALID_PARAM, "decoration.param.invalid");
        List<OrderRow> rows = jdbc.query("""
                select id, org_id, order_no, customer_id, customer_name, house_id, house_label, type,
                       status, project_status, design_status, source_status, designer_id, salesman_id,
                       termination_approval_id, created_by
                from decoration_order where id = ? and org_id = ?
                """, (rs, rowNum) -> new OrderRow(rs.getLong("id"), rs.getLong("org_id"),
                rs.getString("order_no"), rs.getLong("customer_id"), rs.getString("customer_name"),
                rs.getLong("house_id"), rs.getString("house_label"), rs.getInt("type"), rs.getInt("status"),
                rs.getInt("project_status"), rs.getInt("design_status"), rs.getInt("source_status"),
                rs.getLong("designer_id"), rs.getLong("salesman_id"),
                rs.getObject("termination_approval_id") == null ? null : rs.getLong("termination_approval_id"),
                rs.getLong("created_by")),
                orderId, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_NOT_FOUND, msg("decoration.order.notfound"));
        }
        return rows.get(0);
    }

    private String displayName(long userId) {
        return jdbc.queryForObject("select display_name from yz_user where id = ?", String.class, userId);
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
