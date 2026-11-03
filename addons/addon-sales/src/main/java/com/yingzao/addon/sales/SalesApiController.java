package com.yingzao.addon.sales;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yingzao.core.web.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * C 端确认中心(R21,docs/15;addon.yml prefix /api/c/sales-orders,c-customer 主体):
 * 验证码登录=实名锚,登录态自助确认替代「发码→员工代录报码」旧链(员工代录端点保留,对账场景可用)。
 * 归属校验在 service(销售单→装修单→客户,不匹配 300001 不泄露存在性)。
 */
@RestController
@RequestMapping("/api/c/sales-orders")
public class SalesApiController {

    private final SalesOrderService orders;

    public SalesApiController(SalesOrderService orders) {
        this.orders = orders;
    }

    @GetMapping("/confirmations")
    public ApiResponse<Object> confirmations(HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long customerId = (Long) request.getAttribute("yz.customerId");
        return ApiResponse.ok(orders.listForCustomer(orgId, customerId));
    }

    @PostMapping("/{saleId}/confirm")
    public ApiResponse<Void> confirm(@PathVariable long saleId, HttpServletRequest request) {
        // 无 body 动作端点(与内核 begin/finish 同款):不声明 @RequestBody,
        // 空 POST(任意/缺失 Content-Type)均可达,业务守卫在 service
        long orgId = (Long) request.getAttribute("yz.orgId");
        long customerId = (Long) request.getAttribute("yz.customerId");
        orders.confirmByCustomer(orgId, saleId, customerId);
        return ApiResponse.ok();
    }
}
