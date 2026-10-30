package com.yingzao.addon.sales;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yingzao.core.web.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * B2 品牌配送流程模板端点:列表(含品牌名/启用位)+ 保存(flow+四 rate 一体提交)。
 * 配置面归配送履约菜单(/api/sales/delivery-flows 前缀,addon.yml 声明)。
 */
@RestController
@RequestMapping("/api/sales")
public class SalesDeliveryFlowController {

    public record FlowRequest(Integer autoTran, Integer autoCheck, Integer autoConfirm,
                              List<SalesDeliveryFlowService.RateItem> rates) {
    }

    private final SalesDeliveryFlowService flows;

    public SalesDeliveryFlowController(SalesDeliveryFlowService flows) {
        this.flows = flows;
    }

    @GetMapping("/delivery-flows")
    public ApiResponse<List<Map<String, Object>>> list(HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(flows.list(orgId));
    }

    @PutMapping("/delivery-flows/{brandId}")
    public ApiResponse<Void> save(@PathVariable long brandId, @RequestBody FlowRequest body,
            HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        long userId = (Long) request.getAttribute("yz.userId");
        flows.save(orgId, brandId, body == null ? null
                : new SalesDeliveryFlowService.FlowBody(body.autoTran(), body.autoCheck(),
                        body.autoConfirm(), body.rates()), userId);
        return ApiResponse.ok();
    }
}
