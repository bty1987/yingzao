package com.yingzao.core.eventbus;

import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yingzao.core.rbac.RoleService;
import com.yingzao.core.web.ApiResponse;
import com.yingzao.core.web.BizException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 事件总线管理端点(仅管理员,服务端守卫):出站箱分页检索(按状态/类型过滤)、
 * 统计、死信/存量 requeue。死信是「禁静默跳过」红线的可见化出口。
 */
@RestController
@RequestMapping("/api/events")
public class EventAdminController {

    public static final int CODE_EVENT_NOT_FOUND = 123000;
    public static final int CODE_EVENT_FORBIDDEN = 123001;

    private final YzEventBus bus;
    private final RoleService roles;
    private final MessageSource messages;

    public EventAdminController(YzEventBus bus, RoleService roles, MessageSource messages) {
        this.bus = bus;
        this.roles = roles;
        this.messages = messages;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> page(@RequestParam(required = false) Integer status,
            @RequestParam(required = false) String eventType,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        requireAdmin(request);
        return ApiResponse.ok(bus.page(status, eventType, page, size));
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats(HttpServletRequest request) {
        requireAdmin(request);
        return ApiResponse.ok(bus.stats());
    }

    @PostMapping("/{id}/requeue")
    public ApiResponse<Void> requeue(@PathVariable long id, HttpServletRequest request) {
        requireAdmin(request);
        try {
            bus.requeue(id);
        } catch (YzEventBus.BizEventNotFound e) {
            throw notFound();
        }
        return ApiResponse.ok();
    }

    private void requireAdmin(HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        if (!roles.isAdmin(orgId, userId)) {
            throw new BizException(CODE_EVENT_FORBIDDEN,
                    messages.getMessage("event.forbidden", null, LocaleContextHolder.getLocale()));
        }
    }

    private BizException notFound() {
        return new BizException(CODE_EVENT_NOT_FOUND,
                messages.getMessage("event.notfound", null, LocaleContextHolder.getLocale()));
    }
}
