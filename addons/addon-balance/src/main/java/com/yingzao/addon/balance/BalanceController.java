package com.yingzao.addon.balance;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yingzao.core.web.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 结算域端点:结算单流转(锁/审/结算/让减/解绑/删除)/资金双腿(台账入账/出纳付款/付款标/完结)/
 * 账户(台账+流水)/扣款池闭环(池/扣款单/bind/unbind/deduct)。吸附与退回只走事件,不设 REST 入口
 * (上游唯一通道=总线,零旁路)。金额入参全部厘。
 */
@RestController
@RequestMapping("/api/balance")
public class BalanceController {

    public record DiscountRequest(Long value) {
    }

    public record UnbindRequest(String sourceAddon, String sourceType, String sourceItemId,
                                String reason) {
    }

    public record EnterRequest(Long accountId, Long money) {
    }

    public record PayRequest(Long accountId, Long money, String memo) {
    }

    public record PayMarkRequest(List<Long> ids) {
    }

    public record AccountRequest(String belonger, String belongerName, Integer type) {
    }

    public record AccountStatusRequest(Integer status) {
    }

    public record CashInRequest(Long money, String memo) {
    }

    public record PoolRequest(String name, Integer type, String supplier, Long total) {
    }

    public record PoolStatusRequest(Integer status) {
    }

    public record CutpayRequest(Long poolId, Long money, Integer type, String memo) {
    }

    public record BindRequest(Long settlementId) {
    }

    public record UnbindCutpayRequest(String reason) {
    }

    public record DeductRequest(Long money, String memo) {
    }

    private final BalanceSettlementService settlements;
    private final BalanceAccountService accounts;
    private final BalanceCutpayService cutpays;

    public BalanceController(BalanceSettlementService settlements, BalanceAccountService accounts,
            BalanceCutpayService cutpays) {
        this.settlements = settlements;
        this.accounts = accounts;
        this.cutpays = cutpays;
    }

    // ---- 结算单 ----

    @GetMapping("/settlements")
    public ApiResponse<Map<String, Object>> page(
            @RequestParam(required = false) String settlementType,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long brandId,
            @RequestParam(required = false) String no,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        long orgId = orgId(request);
        return ApiResponse.ok(settlements.page(orgId, settlementType, status, brandId, no, page, size));
    }

    @GetMapping("/settlements/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(settlements.detail(orgId(request), id));
    }

    @PostMapping("/settlements/{id}/lock")
    public ApiResponse<Void> lock(@PathVariable long id, HttpServletRequest request) {
        settlements.lock(orgId(request), id, userId(request));
        return ApiResponse.ok();
    }

    @PostMapping("/settlements/{id}/unlock")
    public ApiResponse<Void> unlock(@PathVariable long id, HttpServletRequest request) {
        settlements.unlock(orgId(request), id, userId(request));
        return ApiResponse.ok();
    }

    @PostMapping("/settlements/{id}/audit")
    public ApiResponse<Void> audit(@PathVariable long id, HttpServletRequest request) {
        settlements.audit(orgId(request), id, userId(request));
        return ApiResponse.ok();
    }

    @PostMapping("/settlements/{id}/unaudit")
    public ApiResponse<Void> unaudit(@PathVariable long id, HttpServletRequest request) {
        settlements.unaudit(orgId(request), id, userId(request));
        return ApiResponse.ok();
    }

    @PostMapping("/settlements/{id}/settle")
    public ApiResponse<Void> settle(@PathVariable long id, HttpServletRequest request) {
        settlements.settle(orgId(request), id, userId(request));
        return ApiResponse.ok();
    }

    @PutMapping("/settlements/{id}/discount")
    public ApiResponse<Void> updateDiscount(@PathVariable long id, @RequestBody DiscountRequest body,
            HttpServletRequest request) {
        settlements.updateDiscount(orgId(request), id,
                body == null || body.value() == null ? 0 : body.value(), userId(request));
        return ApiResponse.ok();
    }

    @PostMapping("/settlements/{id}/unbind-item")
    public ApiResponse<Void> unbindItem(@PathVariable long id, @RequestBody UnbindRequest body,
            HttpServletRequest request) {
        settlements.unbindItem(orgId(request), id,
                body == null ? null : body.sourceAddon(), body == null ? null : body.sourceType(),
                body == null ? null : body.sourceItemId(), body == null ? null : body.reason(),
                userId(request));
        return ApiResponse.ok();
    }

    @DeleteMapping("/settlements/{id}")
    public ApiResponse<Void> delete(@PathVariable long id, HttpServletRequest request) {
        settlements.delete(orgId(request), id, userId(request));
        return ApiResponse.ok();
    }

    // ---- 资金双腿 ----

    @PostMapping("/settlements/{id}/enter")
    public ApiResponse<Void> enter(@PathVariable long id, @RequestBody EnterRequest body,
            HttpServletRequest request) {
        accounts.enterSettlement(orgId(request), id,
                body == null || body.accountId() == null ? 0 : body.accountId(),
                body == null || body.money() == null ? 0 : body.money(), userId(request));
        return ApiResponse.ok();
    }

    @PostMapping("/settlements/{id}/pay")
    public ApiResponse<Void> pay(@PathVariable long id, @RequestBody PayRequest body,
            HttpServletRequest request) {
        accounts.payCash(orgId(request), id,
                body == null || body.accountId() == null ? 0 : body.accountId(),
                body == null || body.money() == null ? 0 : body.money(),
                body == null ? null : body.memo(), userId(request));
        return ApiResponse.ok();
    }

    @PostMapping("/settlements/pay-mark")
    public ApiResponse<Void> payMark(@RequestBody PayMarkRequest body, HttpServletRequest request) {
        accounts.markPaid(orgId(request), body == null ? null : body.ids(), userId(request));
        return ApiResponse.ok();
    }

    @PostMapping("/settlements/{id}/finish")
    public ApiResponse<Void> finish(@PathVariable long id, HttpServletRequest request) {
        accounts.finish(orgId(request), id, userId(request));
        return ApiResponse.ok();
    }

    // ---- 账户 ----

    @GetMapping("/accounts")
    public ApiResponse<Map<String, Object>> pageAccounts(
            @RequestParam(required = false) String belonger,
            @RequestParam(required = false) Integer type,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return ApiResponse.ok(accounts.page(orgId(request), belonger, type, page, size));
    }

    @PostMapping("/accounts")
    public ApiResponse<Long> createAccount(@RequestBody AccountRequest body,
            HttpServletRequest request) {
        return ApiResponse.ok(accounts.create(orgId(request),
                body == null ? null : body.belonger(), body == null ? null : body.belongerName(),
                body == null || body.type() == null ? 0 : body.type(), userId(request)));
    }

    @PostMapping("/accounts/{id}/status")
    public ApiResponse<Void> updateAccountStatus(@PathVariable long id,
            @RequestBody AccountStatusRequest body, HttpServletRequest request) {
        accounts.updateStatus(orgId(request), id,
                body == null || body.status() == null ? 0 : body.status(), userId(request));
        return ApiResponse.ok();
    }

    @PostMapping("/accounts/{id}/cash-in")
    public ApiResponse<Void> cashIn(@PathVariable long id, @RequestBody CashInRequest body,
            HttpServletRequest request) {
        accounts.cashIn(orgId(request), id, body == null || body.money() == null ? 0 : body.money(),
                body == null ? null : body.memo(), userId(request));
        return ApiResponse.ok();
    }

    @GetMapping("/accounts/{id}/details")
    public ApiResponse<Map<String, Object>> accountDetails(@PathVariable long id,
            HttpServletRequest request) {
        return ApiResponse.ok(accounts.details(orgId(request), id));
    }

    // ---- 扣款池 ----

    @GetMapping("/cutpay-pools")
    public ApiResponse<Map<String, Object>> pagePools(
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return ApiResponse.ok(cutpays.pagePools(orgId(request), status, page, size));
    }

    @PostMapping("/cutpay-pools")
    public ApiResponse<Long> createPool(@RequestBody PoolRequest body, HttpServletRequest request) {
        return ApiResponse.ok(cutpays.createPool(orgId(request),
                body == null ? null : body.name(),
                body == null || body.type() == null ? 0 : body.type(),
                body == null ? null : body.supplier(),
                body == null || body.total() == null ? 0 : body.total(), userId(request)));
    }

    @PostMapping("/cutpay-pools/{id}/status")
    public ApiResponse<Void> updatePoolStatus(@PathVariable long id,
            @RequestBody PoolStatusRequest body, HttpServletRequest request) {
        cutpays.updatePoolStatus(orgId(request), id,
                body == null || body.status() == null ? 0 : body.status(), userId(request));
        return ApiResponse.ok();
    }

    @GetMapping("/cutpays")
    public ApiResponse<Map<String, Object>> pageCutpays(
            @RequestParam(required = false) Long poolId,
            @RequestParam(required = false) Long settlementId,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return ApiResponse.ok(cutpays.pageCutpays(orgId(request), poolId, settlementId, status,
                page, size));
    }

    @GetMapping("/cutpays/{id}")
    public ApiResponse<Map<String, Object>> cutpayDetail(@PathVariable long id,
            HttpServletRequest request) {
        return ApiResponse.ok(cutpays.cutpayDetail(orgId(request), id));
    }

    @PostMapping("/cutpays")
    public ApiResponse<Long> createCutpay(@RequestBody CutpayRequest body,
            HttpServletRequest request) {
        return ApiResponse.ok(cutpays.createCutpay(orgId(request),
                body == null || body.poolId() == null ? 0 : body.poolId(),
                body == null || body.money() == null ? 0 : body.money(),
                body == null || body.type() == null ? 0 : body.type(),
                body == null ? null : body.memo(), userId(request)));
    }

    @PostMapping("/cutpays/{id}/bind")
    public ApiResponse<Void> bind(@PathVariable long id, @RequestBody BindRequest body,
            HttpServletRequest request) {
        cutpays.bind(orgId(request), id,
                body == null || body.settlementId() == null ? 0 : body.settlementId(),
                userId(request));
        return ApiResponse.ok();
    }

    @PostMapping("/cutpays/{id}/unbind")
    public ApiResponse<Void> unbind(@PathVariable long id, @RequestBody UnbindCutpayRequest body,
            HttpServletRequest request) {
        cutpays.unbind(orgId(request), id, body == null ? null : body.reason(), userId(request));
        return ApiResponse.ok();
    }

    @PostMapping("/cutpays/{id}/deduct")
    public ApiResponse<Void> deduct(@PathVariable long id, @RequestBody DeductRequest body,
            HttpServletRequest request) {
        cutpays.deduct(orgId(request), id,
                body == null || body.money() == null ? 0 : body.money(),
                body == null ? null : body.memo(), userId(request));
        return ApiResponse.ok();
    }

    private long orgId(HttpServletRequest request) {
        return (Long) request.getAttribute("yz.orgId");
    }

    private long userId(HttpServletRequest request) {
        return (Long) request.getAttribute("yz.userId");
    }
}
