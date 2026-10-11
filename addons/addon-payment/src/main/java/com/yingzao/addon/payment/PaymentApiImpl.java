package com.yingzao.addon.payment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.yingzao.addons.api.PaymentApi;

/**
 * PaymentApi 契约实现:业务插件(addon-decoration/addon-sales 等)注入本接口产生应收与划扣预付款,
 * 禁止依赖 addon-payment 实现模块(军规 2)。错误码全落 270000 段(BizException 运行时抛出)。
 */
@Component
public class PaymentApiImpl implements PaymentApi {

    private final BillService bills;
    private final BillTypeService billTypes;
    private final AdvanceService advance;

    public PaymentApiImpl(BillService bills, BillTypeService billTypes, AdvanceService advance) {
        this.bills = bills;
        this.billTypes = billTypes;
        this.advance = advance;
    }

    @Override
    public long createBill(CreateBillCmd cmd) {
        return bills.createBill(cmd.orgId(), cmd.bizAddon(), cmd.bizType(), cmd.bizId(),
                cmd.typeCode(), cmd.amount(), cmd.remark(), null);
    }

    @Override
    public long advanceCharge(AdvanceCmd cmd) {
        return advance.move(cmd.orgId(), cmd.subjectCode(), cmd.refType(), cmd.refId(), cmd.amount(),
                AdvanceService.FLOW_CHARGE, cmd.bizAddon(), cmd.bizType(), cmd.bizId(),
                cmd.operatorId(), cmd.remark());
    }

    @Override
    public long advanceDeduct(AdvanceCmd cmd) {
        return advance.move(cmd.orgId(), cmd.subjectCode(), cmd.refType(), cmd.refId(), cmd.amount(),
                AdvanceService.FLOW_DEDUCT, cmd.bizAddon(), cmd.bizType(), cmd.bizId(),
                cmd.operatorId(), cmd.remark());
    }

    @Override
    public long advanceBalance(long orgId, String subjectCode, String refType, String refId) {
        return advance.balance(orgId, subjectCode, refType, refId);
    }

    @Override
    public List<Map<String, Object>> listBillTypes(long orgId) {
        return billTypes.list(orgId, false).stream().map(row -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("code", row.get("code"));
            view.put("name", row.get("name"));
            view.put("sort", row.get("sort"));
            view.put("scaleTemplate", row.get("scale_template"));
            return view;
        }).toList();
    }

    @Override
    public List<BillSummary> listBills(long orgId, String bizAddon, String bizType, String bizId) {
        return bills.listByBiz(orgId, bizAddon, bizType, bizId).stream()
                .map(row -> new BillSummary(((Number) row.get("id")).longValue(),
                        (String) row.get("type_code"), (String) row.get("type_name"),
                        ((Number) row.get("direction")).intValue(), ((Number) row.get("amount")).longValue(),
                        ((Number) row.get("sort")).intValue(), ((Number) row.get("status")).intValue()))
                .toList();
    }

    @Override
    public void cancelBill(long orgId, long billId) {
        bills.cancel(orgId, billId);
    }

    @Override
    public long submitRefund(RefundCmd cmd) {
        return bills.submitRefund(cmd.orgId(), cmd.billId(), cmd.applicantId(), cmd.assigneeId(),
                cmd.reason());
    }
}
