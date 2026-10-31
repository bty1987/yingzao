package com.yingzao.core.file;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.yingzao.core.auth.TokenStore;
import com.yingzao.core.web.ApiResponse;
import com.yingzao.core.web.BizException;

/**
 * 文件端点(F1 内核 file 模块;内核端点自带守卫——/api/files 非插件 URI,
 * enforce 豁免同 auth/users 等内核端点):上传=登录员工;下载/预览=登录+org 隔离;
 * 删除=上传人本人。C 端主体(principal=customer)不开放文件端点,消费场景
 * (工地相册/资金流水)由各 addon 在 R20 按业务授权自建 C 端投影端点。
 */
@RestController
public class FileController {

    private final FileService files;
    private final MessageSource messages;

    public FileController(FileService files, MessageSource messages) {
        this.files = files;
        this.messages = messages;
    }

    /** 上传:multipart 直传;bizType/bizId 可选业务挂接(业务表存返回 id 即完成挂接)。 */
    @PostMapping("/api/files")
    public ApiResponse<FileService.FileMeta> upload(@RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) Long bizId,
            HttpServletRequest request) throws IOException {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = requireEmployee(request);
        return ApiResponse.ok(files.upload(orgId, userId, file.getOriginalFilename(),
                file.getContentType(), file.getBytes(), bizType, bizId));
    }

    /** 下载(attachment;文件名 RFC 5987 编码支持中文)。 */
    @GetMapping("/api/files/{id}")
    public ResponseEntity<byte[]> download(@PathVariable long id, HttpServletRequest request) {
        return content(load(id, request), false);
    }

    /** 预览:仅图片/PDF 内联(inline),其余强制 attachment——html/svg 永不内联防 stored XSS。 */
    @GetMapping("/api/files/{id}/preview")
    public ResponseEntity<byte[]> preview(@PathVariable long id, HttpServletRequest request) {
        return content(load(id, request), true);
    }

    /** 按业务挂接列表(bizType 必填;bizId 可选收窄)。 */
    @GetMapping("/api/files")
    public ApiResponse<List<FileService.FileMeta>> list(@RequestParam String bizType,
            @RequestParam(required = false) Long bizId, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        requireEmployee(request);
        return ApiResponse.ok(files.list(orgId, bizType, bizId));
    }

    /** 删除(仅上传人;软删留痕+物理文件清理)。 */
    @PostMapping("/api/files/{id}/delete")
    public ApiResponse<Void> delete(@PathVariable long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        Long userId = requireEmployee(request);
        files.delete(orgId, id, userId);
        return ApiResponse.ok(null);
    }

    private FileService.FileRow load(long id, HttpServletRequest request) {
        long orgId = (Long) request.getAttribute("yz.orgId");
        requireEmployee(request);
        return files.get(orgId, id);
    }

    private ResponseEntity<byte[]> content(FileService.FileRow row, boolean inline) {
        boolean allowInline = inline && FileService.INLINE_TYPES.contains(row.contentType());
        String encoded = URLEncoder.encode(row.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, allowInline ? row.contentType() : "application/octet-stream")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        (allowInline ? "inline" : "attachment") + "; filename*=UTF-8''" + encoded)
                .header("X-Content-Type-Options", "nosniff")
                .body(files.read(row));
    }

    /** C 端主体与匿名一律拒(125008):文件端点 V1 仅员工可用。 */
    private Long requireEmployee(HttpServletRequest request) {
        if (TokenStore.PRINCIPAL_CUSTOMER.equals(request.getAttribute("yz.principal"))) {
            throw new BizException(FileService.CODE_EMPLOYEE_ONLY, msg("file.employee"));
        }
        return (Long) request.getAttribute("yz.userId");
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
