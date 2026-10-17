package com.yingzao.addon.basedata;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.yingzao.core.web.ApiResponse;
import com.yingzao.core.web.BizException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 商品 Excel 导入端点:模板下载(GET,浏览器直接下 xlsx)+ 导入(POST multipart)。
 * 校验失败不是系统异常:260023 + 逐行明细走返回体 data.errors(全局异常处理器之外显式回)。
 * RBAC 归 /api/basedata prefix 菜单闸(既有声明,零新增)。
 */
@RestController
@RequestMapping("/api/basedata/goods")
public class GoodsImportController {

    private final GoodsImportService imports;
    private final MessageSource messages;

    public GoodsImportController(GoodsImportService imports, MessageSource messages) {
        this.imports = imports;
        this.messages = messages;
    }

    /** 导入历史台账(V1.2;仅成功导入落账)。 */
    @GetMapping("/import-history")
    public ApiResponse<Map<String, Object>> history(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        return ApiResponse.ok(imports.history(orgId, page, size));
    }

    @GetMapping("/import-template")
    public void template(jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        response.setContentType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=goods-import-template.xlsx");
        response.getOutputStream().write(imports.template());
    }

    @PostMapping("/import")
    public ApiResponse<Map<String, Object>> importGoods(@RequestParam("file") MultipartFile file,
            HttpServletRequest request) throws java.io.IOException {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = (Long) request.getAttribute("yz.userId");
        if (file == null || file.isEmpty()) {
            throw new BizException(GoodsImportService.CODE_IMPORT_INVALID, msg("goods.import.empty"));
        }
        try {
            GoodsImportService.ImportReport report =
                    imports.importGoods(orgId, file.getBytes(), userId == null ? 0L : userId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("total", report.total());
            out.put("imported", report.imported());
            out.put("errors", report.errors());
            return ApiResponse.ok(out);
        } catch (GoodsImportService.ImportInvalidException e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("total", e.report.total());
            out.put("imported", 0);
            out.put("errors", e.report.errors());
            return new ApiResponse<>(GoodsImportService.CODE_IMPORT_INVALID,
                    msg("goods.import.invalid"), out);
        }
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
