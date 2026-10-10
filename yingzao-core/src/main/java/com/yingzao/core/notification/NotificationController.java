package com.yingzao.core.notification;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yingzao.core.web.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 站内通知接口(内核):收件箱即当前登录人,无跨人查看。
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(HttpServletRequest request,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(notificationService.list(userId, unreadOnly, page, size));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Long> unreadCount(HttpServletRequest request) {
        long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(notificationService.unreadCount(userId));
    }

    @PostMapping("/{id}/read")
    public ApiResponse<Void> markRead(@PathVariable long id, HttpServletRequest request) {
        long userId = (Long) request.getAttribute("yz.userId");
        notificationService.markRead(userId, id);
        return ApiResponse.ok();
    }

    @PostMapping("/read-all")
    public ApiResponse<Void> markAllRead(HttpServletRequest request) {
        long userId = (Long) request.getAttribute("yz.userId");
        notificationService.markAllRead(userId);
        return ApiResponse.ok();
    }
}
