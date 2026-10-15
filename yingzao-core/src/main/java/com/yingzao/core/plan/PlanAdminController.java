package com.yingzao.core.plan;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yingzao.core.rbac.RoleService;
import com.yingzao.core.web.ApiResponse;
import com.yingzao.core.web.BizException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 计划任务管理端点(仅管理员,服务端守卫):提交/分页检索(按状态/处理器过滤)、
 * 详情、取消、死信/失败重跑。提交对外只作管理/演示入口——业务插件的常规姿势是
 * 注入 PlanService 直调(与 PaymentApi 注入同款),不走 REST。
 */
@RestController
@RequestMapping("/api/plans")
public class PlanAdminController {

    private static final DateTimeFormatter RUN_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PlanService plans;
    private final RoleService roles;
    private final MessageSource messages;

    public PlanAdminController(PlanService plans, RoleService roles, MessageSource messages) {
        this.plans = plans;
        this.roles = roles;
        this.messages = messages;
    }

    public record SubmitBody(String handler, String bizKey, Object params, Integer maxRetries,
            String runAt) {
    }

    @PostMapping
    public ApiResponse<Long> submit(@RequestBody SubmitBody body, HttpServletRequest request) {
        requireAdmin(request);
        require(body != null && body.handler() != null && !body.handler().isBlank(),
                PlanService.CODE_PARAM, "plan.param");
        LocalDateTime runAt = null;
        if (body.runAt() != null && !body.runAt().isBlank()) {
            try {
                runAt = LocalDateTime.parse(body.runAt().trim(), RUN_AT);
            } catch (DateTimeParseException e) {
                throw new BizException(PlanService.CODE_PARAM, msg("plan.param"));
            }
        }
        Long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(plans.submit(orgId(request), body.handler().trim(), body.bizKey(),
                body.params(), body.maxRetries(), runAt, userId));
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> page(@RequestParam(required = false) Integer status,
            @RequestParam(required = false) String handler,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        requireAdmin(request);
        return ApiResponse.ok(plans.page(status, handler, null, page, size));
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats(HttpServletRequest request) {
        requireAdmin(request);
        return ApiResponse.ok(plans.stats());
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable long id, HttpServletRequest request) {
        requireAdmin(request);
        return ApiResponse.ok(plans.get(id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable long id, HttpServletRequest request) {
        requireAdmin(request);
        plans.cancel(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/retry")
    public ApiResponse<Void> retry(@PathVariable long id, HttpServletRequest request) {
        requireAdmin(request);
        plans.retry(id);
        return ApiResponse.ok();
    }

    private void requireAdmin(HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        if (!roles.isAdmin(orgId, userId)) {
            throw new BizException(PlanService.CODE_FORBIDDEN, msg("plan.forbidden"));
        }
    }

    private long orgId(HttpServletRequest request) {
        return (Long) request.getAttribute("yz.orgId");
    }

    private void require(boolean cond, int code, String key) {
        if (!cond) {
            throw new BizException(code, msg(key));
        }
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
