package com.yingzao.addon.market;

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
 * 电子名片管理端点(D6b;归 /api/market/cards prefix 菜单闸,addon.yml 声明)。
 * C 端匿名面在 CardApiController(/api/c/cards,anonymous 白名单)。
 */
@RestController
@RequestMapping("/api/market/cards")
public class CardController {

    public record StatusRequest(Integer status) {
    }

    public record PhotoRequest(String url, Integer sortNo) {
    }

    private final CardService cards;

    public CardController(CardService cards) {
        this.cards = cards;
    }

    private long orgId(HttpServletRequest request) {
        return (Long) request.getAttribute("yz.orgId");
    }

    private long userId(HttpServletRequest request) {
        return (Long) request.getAttribute("yz.userId");
    }

    // ---- 我的名片 ----

    @GetMapping("/mine")
    public ApiResponse<Map<String, Object>> mine(HttpServletRequest request) {
        return ApiResponse.ok(cards.mine(orgId(request), userId(request)));
    }

    @PostMapping
    public ApiResponse<Long> create(@RequestBody CardService.CardCmd cmd, HttpServletRequest request) {
        return ApiResponse.ok(cards.create(orgId(request), userId(request), cmd));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable long id, @RequestBody CardService.CardCmd cmd,
            HttpServletRequest request) {
        cards.update(orgId(request), userId(request), id, cmd);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/token")
    public ApiResponse<String> resetToken(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(cards.resetToken(orgId(request), userId(request), id));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<Void> status(@PathVariable long id, @RequestBody StatusRequest req,
            HttpServletRequest request) {
        cards.status(orgId(request), userId(request), id, req.status());
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id, HttpServletRequest request) {
        cards.delete(orgId(request), userId(request), id);
        return ApiResponse.ok(null);
    }

    // ---- 相册 ----

    @PostMapping("/{id}/photos")
    public ApiResponse<Long> addPhoto(@PathVariable long id, @RequestBody PhotoRequest req,
            HttpServletRequest request) {
        return ApiResponse.ok(cards.addPhoto(orgId(request), userId(request), id, req.url(),
                req.sortNo() == null ? 0 : req.sortNo()));
    }

    @DeleteMapping("/{id}/photos/{photoId}")
    public ApiResponse<Void> deletePhoto(@PathVariable long id, @PathVariable long photoId,
            HttpServletRequest request) {
        cards.deletePhoto(orgId(request), userId(request), id, photoId);
        return ApiResponse.ok(null);
    }

    // ---- 留资 ----

    @GetMapping("/notices")
    public ApiResponse<Map<String, Object>> notices(@RequestParam(required = false) Long cardId,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        if (cardId == null) {
            return ApiResponse.ok(cards.noticesAll(orgId(request), status, page, size));
        }
        return ApiResponse.ok(cards.notices(orgId(request), userId(request), cardId, status, page, size));
    }

    @PostMapping("/notices/{noticeId}/read")
    public ApiResponse<Void> readNotice(@PathVariable long noticeId, HttpServletRequest request) {
        cards.readNotice(orgId(request), userId(request), noticeId);
        return ApiResponse.ok(null);
    }
}
