package com.yingzao.addon.member;

import java.util.List;
import java.util.Map;

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
 * 会员营销域端点(/api/member;鉴权走内核 RbacInterceptor + addon.yml api 段,无注解)。
 * 商城同步通道(place/upsert)同闸:服务账号经角色授权对应菜单接入。
 */
@RestController
@RequestMapping("/api/member")
public class MemberController {

    private final MemberActivityService activities;
    private final MemberGiftService gifts;
    private final MemberPointService points;
    private final MemberExchangeService exchanges;

    public MemberController(MemberActivityService activities, MemberGiftService gifts,
            MemberPointService points, MemberExchangeService exchanges) {
        this.activities = activities;
        this.gifts = gifts;
        this.points = points;
        this.exchanges = exchanges;
    }

    // ---- 活动 ----

    public record ActivityBody(String name, Integer type, Integer multiple, Integer validays,
            String startTime, String endTime, Boolean isDefault, String remark) {
    }

    @GetMapping("/activities")
    public ApiResponse<Map<String, Object>> pageActivities(HttpServletRequest request,
            @RequestParam(required = false) Integer type,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(activities.page(orgId(request), type, status, keyword, page, size));
    }

    @PostMapping("/activities")
    public ApiResponse<Long> createActivity(HttpServletRequest request, @RequestBody ActivityBody body) {
        long id = activities.create(orgId(request), new MemberActivityService.CreateCmd(
                body.name(), body.type(), body.multiple(), body.validays(),
                body.startTime(), body.endTime(), body.isDefault(), body.remark()), userId(request));
        return ApiResponse.ok(id);
    }

    @PutMapping("/activities/{id}")
    public ApiResponse<Void> updateActivity(HttpServletRequest request, @PathVariable long id,
            @RequestBody ActivityBody body) {
        activities.update(orgId(request), id, new MemberActivityService.UpdateCmd(
                body.name(), body.multiple(), body.validays(),
                body.startTime(), body.endTime(), body.remark()));
        return ApiResponse.ok();
    }

    public record StatusBody(Integer status) {
    }

    @PostMapping("/activities/{id}/status")
    public ApiResponse<Void> changeActivityStatus(HttpServletRequest request, @PathVariable long id,
            @RequestBody StatusBody body) {
        activities.changeStatus(orgId(request), id, body.status() == null ? 0 : body.status(), userId(request));
        return ApiResponse.ok();
    }

    @PostMapping("/activities/{id}/default")
    public ApiResponse<Void> setDefaultActivity(HttpServletRequest request, @PathVariable long id) {
        activities.setDefault(orgId(request), id, userId(request));
        return ApiResponse.ok();
    }

    // ---- 组织配置 ----

    public record ConfigBody(Integer autoSignConsume, String signConsumeContent) {
    }

    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> getConfig(HttpServletRequest request) {
        return ApiResponse.ok(exchanges.configOf(orgId(request)));
    }

    @PostMapping("/config")
    public ApiResponse<Void> saveConfig(HttpServletRequest request, @RequestBody ConfigBody body) {
        exchanges.saveConfig(orgId(request), body.autoSignConsume(), body.signConsumeContent());
        return ApiResponse.ok();
    }

    /** 过期清算手动触发(组织级开关开启与否均可手动;幂等,已清算批次跳过)。 */
    @PostMapping("/points/expire-clear")
    public ApiResponse<Map<String, Object>> expireClear(HttpServletRequest request) {
        return ApiResponse.ok(points.expireClear(orgId(request)));
    }

    // ---- 礼品 ----

    public record GiftBody(String no, String name, Long integralPrice, Long unitPrice,
            Integer isPrefor, Integer isStock, Integer isPayment, String remark) {
    }

    @GetMapping("/gifts")
    public ApiResponse<Map<String, Object>> pageGifts(HttpServletRequest request,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(gifts.page(orgId(request), status, keyword, page, size));
    }

    @GetMapping("/gifts/{id}")
    public ApiResponse<Map<String, Object>> giftDetail(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.ok(gifts.get(orgId(request), id));
    }

    @PostMapping("/gifts")
    public ApiResponse<Long> createGift(HttpServletRequest request, @RequestBody GiftBody body) {
        return ApiResponse.ok(gifts.create(orgId(request), toGiftCmd(body)));
    }

    @PutMapping("/gifts/{id}")
    public ApiResponse<Void> updateGift(HttpServletRequest request, @PathVariable long id,
            @RequestBody GiftBody body) {
        gifts.update(orgId(request), id, toGiftCmd(body));
        return ApiResponse.ok();
    }

    @PostMapping("/gifts/upsert")
    public ApiResponse<Map<String, Object>> upsertGift(HttpServletRequest request, @RequestBody GiftBody body) {
        return ApiResponse.ok(gifts.upsert(orgId(request), toGiftCmd(body)));
    }

    @PostMapping("/gifts/{id}/status")
    public ApiResponse<Void> changeGiftStatus(HttpServletRequest request, @PathVariable long id,
            @RequestBody StatusBody body) {
        gifts.changeStatus(orgId(request), id, body.status() == null ? 0 : body.status());
        return ApiResponse.ok();
    }

    public record StockBody(Integer type, Long num, String remark) {
    }

    @PostMapping("/gifts/{id}/stock")
    public ApiResponse<Map<String, Object>> stockOp(HttpServletRequest request, @PathVariable long id,
            @RequestBody StockBody body) {
        return ApiResponse.ok(gifts.stockOp(orgId(request), id,
                new MemberGiftService.StockCmd(body.type(), body.num(), body.remark()), userId(request)));
    }

    @GetMapping("/gifts/{id}/records")
    public ApiResponse<Map<String, Object>> giftRecords(HttpServletRequest request, @PathVariable long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(gifts.records(orgId(request), id, page, size));
    }

    // ---- 兑换 ----

    public record PlaceBody(String exchangeNo, Long customerId, Integer pointType, Long orderId,
            List<PlaceItemBody> items, String remark) {
    }

    public record PlaceItemBody(String giftNo) {
    }

    @PostMapping("/exchanges")
    public ApiResponse<Map<String, Object>> place(HttpServletRequest request, @RequestBody PlaceBody body) {
        List<MemberExchangeService.PlaceItem> items = body.items() == null ? List.of()
                : body.items().stream().map(i -> new MemberExchangeService.PlaceItem(i.giftNo())).toList();
        return ApiResponse.ok(exchanges.place(orgId(request), new MemberExchangeService.PlaceCmd(
                body.exchangeNo(), body.customerId(), body.pointType(), body.orderId(), items, body.remark()),
                userId(request)));
    }

    @GetMapping("/exchanges")
    public ApiResponse<Map<String, Object>> pageExchanges(HttpServletRequest request,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String exchangeNo,
            @RequestParam(required = false) Long customerId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(exchanges.page(orgId(request), status, exchangeNo, customerId, page, size));
    }

    @GetMapping("/exchanges/{id}")
    public ApiResponse<Map<String, Object>> exchangeDetail(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.ok(exchanges.get(orgId(request), id));
    }

    public record ShipBody(String shipCompany, String shipNo) {
    }

    /** 发货登记(V1.1):受理/已核销单均可,登记物流公司+单号,状态 → 3 已发货。 */
    @PostMapping("/exchanges/{id}/ship")
    public ApiResponse<Void> ship(@PathVariable long id, @RequestBody ShipBody body,
            HttpServletRequest request) {
        exchanges.ship(orgId(request), id, body == null ? null : body.shipCompany(),
                body == null ? null : body.shipNo(), userId(request));
        return ApiResponse.ok();
    }

    @PostMapping("/exchanges/{id}/cancel")
    public ApiResponse<Void> cancelExchange(HttpServletRequest request, @PathVariable long id) {
        exchanges.cancel(orgId(request), id, userId(request));
        return ApiResponse.ok();
    }

    public record ConsumeBody(String content) {
    }

    @PostMapping("/exchanges/items/{itemId}/consume")
    public ApiResponse<Void> consumeItem(HttpServletRequest request, @PathVariable long itemId,
            @RequestBody(required = false) ConsumeBody body) {
        exchanges.consumeItem(orgId(request), itemId, body == null ? null : body.content(), userId(request));
        return ApiResponse.ok();
    }

    @PostMapping("/exchanges/items/{itemId}/cancel")
    public ApiResponse<Void> cancelItem(HttpServletRequest request, @PathVariable long itemId) {
        exchanges.cancelItem(orgId(request), itemId, userId(request));
        return ApiResponse.ok();
    }

    // ---- 积分 ----

    @GetMapping("/points/accounts")
    public ApiResponse<Map<String, Object>> pointAccounts(HttpServletRequest request,
            @RequestParam(required = false) Long customerId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(points.accounts(orgId(request), customerId, page, size));
    }

    @GetMapping("/points/accounts/{customerId}/records")
    public ApiResponse<Map<String, Object>> pointRecords(HttpServletRequest request, @PathVariable long customerId,
            @RequestParam(required = false) Integer pointType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(points.records(orgId(request), customerId, pointType, page, size));
    }

    public record AdjustBody(Long customerId, Integer pointType, Long delta, String reason) {
    }

    @PostMapping("/points/adjust")
    public ApiResponse<Long> adjust(HttpServletRequest request, @RequestBody AdjustBody body) {
        return ApiResponse.ok(points.adjust(orgId(request), body.customerId(), body.pointType(),
                body.delta(), body.reason(), userId(request)));
    }

    // ---- 内部 ----

    private MemberGiftService.SaveCmd toGiftCmd(GiftBody body) {
        return new MemberGiftService.SaveCmd(body.no(), body.name(), body.integralPrice(), body.unitPrice(),
                body.isPrefor(), body.isStock(), body.isPayment(), body.remark());
    }

    private long orgId(HttpServletRequest request) {
        return (Long) request.getAttribute("yz.orgId");
    }

    private Long userId(HttpServletRequest request) {
        return (Long) request.getAttribute("yz.userId");
    }
}
