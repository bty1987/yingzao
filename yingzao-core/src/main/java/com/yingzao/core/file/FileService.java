package com.yingzao.core.file;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.core.web.BizException;

/**
 * 文件元数据与存储编排(F1 内核 file 模块,错误码 125000 段)。
 * 校验链=空文件/大小限额/类型白名单(内容类型+扩展名双验)→ 落盘 → 元数据落库
 * (落盘失败删行兜底,零半成品);下载按 org 隔离,删除仅上传人(软删+物理清理)。
 * 业务挂接 V1 约定:插件业务表存 yz_file.id,列表端点按 bizType/bizId 反查——
 * 不走 addons-api 契约(插件后端无需文件字节,前端持 id 拼 URL 即可)。
 */
@Service
public class FileService {

    public static final int CODE_EMPTY = 125001;
    public static final int CODE_TOO_LARGE = 125002;
    public static final int CODE_TYPE = 125003;
    public static final int CODE_NOT_FOUND = 125004;
    public static final int CODE_SCOPE = 125005;
    public static final int CODE_DELETE_OWNER = 125006;
    public static final int CODE_STORAGE = 125007;
    public static final int CODE_EMPLOYEE_ONLY = 125008;

    /** 内容类型白名单:image/* 前缀 + 常用文档;可经 yingzao.file.allowed-content-types 覆盖。 */
    static final Set<String> INLINE_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp", "application/pdf");
    /** 扩展名白名单(与内容类型双验,存储键只允许安全字符)。 */
    private static final Set<String> EXT_WHITELIST = Set.of(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "pdf", "txt", "csv",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip");

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final JdbcTemplate jdbc;
    private final FileStorageProvider storage;
    private final MessageSource messages;
    private final Set<String> allowedTypes;
    private final long maxBytes;

    public FileService(JdbcTemplate jdbc, FileStorageProvider storage, MessageSource messages,
            @Value("${yingzao.file.allowed-content-types:}") String allowedContentTypes,
            @Value("${yingzao.file.max-size-mb:20}") long maxSizeMb) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.messages = messages;
        this.allowedTypes = allowedContentTypes == null || allowedContentTypes.isBlank()
                ? Set.of()
                : Set.of(allowedContentTypes.split("\\s*,\\s*"));
        this.maxBytes = maxSizeMb <= 0 ? 20 : maxSizeMb * 1024 * 1024;
    }

    /** 对外元数据投影(不持 storage_key,存储细节不出内核)。 */
    public record FileMeta(long id, String fileName, String contentType, long sizeBytes,
            String bizType, Long bizId, String createdAt) {
    }

    /** 内部行(含 storage_key/uploaded_by,仅下载与删除链可见)。 */
    public record FileRow(long id, long orgId, String fileName, String contentType, long sizeBytes,
            String storageKey, String bizType, Long bizId, Long uploadedBy, int status) {
    }

    @Transactional
    public FileMeta upload(long orgId, Long userId, String fileName, String contentType,
            byte[] bytes, String bizType, Long bizId) {
        if (bytes == null || bytes.length == 0) {
            throw new BizException(CODE_EMPTY, msg("file.empty"));
        }
        if (bytes.length > maxBytes) {
            throw new BizException(CODE_TOO_LARGE, msg("file.toolarge"));
        }
        String ext = extOf(fileName);
        String ct = contentType == null || contentType.isBlank()
                ? "application/octet-stream" : contentType.trim().toLowerCase(Locale.ROOT);
        if (!allowedTypes.isEmpty()) {
            if (!allowedTypes.contains(ct)) {
                throw new BizException(CODE_TYPE, msg("file.type"));
            }
        } else if (!ct.startsWith("image/") && !"application/octet-stream".equals(ct)
                && !EXT_DOC_TYPES.contains(ct)) {
            throw new BizException(CODE_TYPE, msg("file.type"));
        }
        String name = sanitizeName(fileName);
        LocalDateTime now = LocalDateTime.now();
        String storageKey = now.format(MONTH) + "/" + now.format(STAMP) + "-"
                + UUID.randomUUID().toString().substring(0, 8) + "." + ext;
        long id = insertRow(orgId, name, ct, bytes, storageKey, bizType, bizId, userId);
        try {
            storage.store(storageKey, bytes);
        } catch (RuntimeException e) {
            // 落盘失败:事务回滚兜底零半成品(物理残留无元数据引用,无害)
            throw new BizException(CODE_STORAGE, msg("file.storage"));
        }
        return new FileMeta(id, name, ct, bytes.length, bizType, bizId,
                DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(now));
    }

    /** 读取校验(org 隔离:跨组织一律 125005,存在性 125004 先于越权判——不向非属主泄漏存在性)。 */
    public FileRow get(long callerOrgId, long fileId) {
        List<FileRow> rows = jdbc.query("""
                select id, org_id, file_name, content_type, size_bytes, storage_key,
                       biz_type, biz_id, uploaded_by, status
                from yz_file where id = ?
                """, (rs, i) -> new FileRow(rs.getLong(1), rs.getLong(2), rs.getString(3),
                rs.getString(4), rs.getLong(5), rs.getString(6), rs.getString(7),
                rs.getObject(8) == null ? null : rs.getLong(8),
                rs.getObject(9) == null ? null : rs.getLong(9), rs.getInt(10)), fileId);
        if (rows.isEmpty() || rows.get(0).status() != 0) {
            throw new BizException(CODE_NOT_FOUND, msg("file.notfound"));
        }
        FileRow row = rows.get(0);
        if (row.orgId() != callerOrgId) {
            throw new BizException(CODE_SCOPE, msg("file.scope"));
        }
        return row;
    }

    public byte[] read(FileRow row) {
        try {
            return storage.read(row.storageKey());
        } catch (RuntimeException e) {
            throw new BizException(CODE_STORAGE, msg("file.storage"));
        }
    }

    /** 删除:仅上传人本人(125006);软删留痕 + 物理文件清理。 */
    @Transactional
    public void delete(long callerOrgId, long fileId, Long userId) {
        FileRow row = get(callerOrgId, fileId);
        if (userId == null || !Objects.equals(row.uploadedBy(), userId)) {
            throw new BizException(CODE_DELETE_OWNER, msg("file.owner"));
        }
        storage.delete(row.storageKey());
        jdbc.update("update yz_file set status = 1 where id = ?", fileId);
    }

    public List<FileMeta> list(long callerOrgId, String bizType, Long bizId) {
        StringBuilder sql = new StringBuilder("""
                select id, file_name, content_type, size_bytes, biz_type, biz_id, created_at
                from yz_file where org_id = ? and status = 0 and biz_type = ?
                """);
        java.util.List<Object> args = new java.util.ArrayList<>(List.of(callerOrgId,
                bizType == null ? "" : bizType.trim()));
        if (bizId != null) {
            sql.append(" and biz_id = ?");
            args.add(bizId);
        }
        sql.append(" order by id desc limit 200");
        return jdbc.query(sql.toString(), (rs, i) -> new FileMeta(rs.getLong(1), rs.getString(2),
                rs.getString(3), rs.getLong(4), rs.getString(5),
                rs.getObject(6) == null ? null : rs.getLong(6),
                rs.getString(7) == null ? null : rs.getTimestamp(7).toLocalDateTime().toString()), args.toArray());
    }

    private long insertRow(long orgId, String name, String ct, byte[] bytes, String storageKey,
            String bizType, Long bizId, Long userId) {
        jdbc.update("""
                insert into yz_file (org_id, file_name, content_type, size_bytes, sha256,
                    storage_key, biz_type, biz_id, status, uploaded_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                """, orgId, name, ct, (long) bytes.length, sha256(bytes), storageKey,
                bizType == null || bizType.isBlank() ? null : bizType.trim(), bizId, userId);
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    private String extOf(String fileName) {
        if (fileName == null) {
            throw new BizException(CODE_TYPE, msg("file.type"));
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            throw new BizException(CODE_TYPE, msg("file.type"));
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
        if (!EXT_WHITELIST.contains(ext)) {
            throw new BizException(CODE_TYPE, msg("file.type"));
        }
        return ext;
    }

    private String sanitizeName(String fileName) {
        String name = fileName == null ? "file" : fileName;
        int s1 = name.lastIndexOf('/');
        int s2 = name.lastIndexOf('\\');
        int cut = Math.max(s1, s2);
        if (cut >= 0) {
            name = name.substring(cut + 1);
        }
        name = name.replaceAll("[\\x00-\\x1f]", "").trim();
        if (name.isEmpty()) {
            name = "file." + "bin";
        }
        return name.length() > 255 ? name.substring(0, 255) : name;
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    /** 文档与通用二进制内容类型(image/* 前缀之外放行的部分)。 */
    private static final Set<String> EXT_DOC_TYPES = Set.of(
            "application/pdf", "text/plain", "text/csv",
            "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/zip");
}
