package com.yingzao.addon.market;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yingzao.core.web.ApiResponse;

/**
 * 名片 C 端匿名端点(访客免登录;白名单 /api/c/cards 在内核 AuthWebConfig,对齐 /api/c/cases;
 * addon.yml anonymous 型 exact 声明对齐 portal 先例)。组织上下文=配置键(V1 单组织,
 * 多租户解析 V2——portal anonymousOrgId 同款临时口径)。
 */
@RestController
@RequestMapping("/api/c/cards")
public class CardApiController {

    private final CardService cards;

    @Value("${yingzao.market.card.org-id:1}")
    private long anonymousOrgId;

    public CardApiController(CardService cards) {
        this.cards = cards;
    }

    @GetMapping("/{token}")
    public ApiResponse<Map<String, Object>> view(@PathVariable String token) {
        return ApiResponse.ok(cards.byToken(anonymousOrgId, token));
    }

    @PostMapping("/{token}/notices")
    public ApiResponse<Map<String, Object>> notice(@PathVariable String token,
            @RequestBody CardService.NoticeCmd cmd) {
        return ApiResponse.ok(cards.submitNotice(anonymousOrgId, token, cmd));
    }
}
