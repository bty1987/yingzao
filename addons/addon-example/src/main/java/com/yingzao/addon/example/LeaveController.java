package com.yingzao.addon.example;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yingzao.core.web.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 请假示例接口:/api/example/leaves。发起即提交内核审批;撤回委托内核审批撤回。
 */
@RestController
@RequestMapping("/api/example")
public class LeaveController {

    public record CreateRequest(String reason, Integer days, Long assigneeId, Long managerId) {
    }

    private final LeaveService leaveService;

    public LeaveController(LeaveService leaveService) {
        this.leaveService = leaveService;
    }

    @PostMapping("/leaves")
    public ApiResponse<Long> create(@RequestBody CreateRequest body, HttpServletRequest request) {
        long userId = (Long) request.getAttribute("yz.userId");
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(leaveService.create(
                new LeaveService.CreateCmd(orgId, userId, body.reason(), body.days(), body.assigneeId(),
                        body.managerId())));
    }

    @GetMapping("/leaves")
    public ApiResponse<List<Map<String, Object>>> mine(HttpServletRequest request) {
        long userId = (Long) request.getAttribute("yz.userId");
        return ApiResponse.ok(leaveService.listMine(userId));
    }

    @PostMapping("/leaves/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable long id, HttpServletRequest request) {
        long userId = (Long) request.getAttribute("yz.userId");
        long orgId = (Long) request.getAttribute("yz.orgId");
        leaveService.cancel(orgId, userId, id);
        return ApiResponse.ok();
    }
}
