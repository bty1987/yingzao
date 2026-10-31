package com.yingzao.core.audit;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingzao.core.web.BizException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 审计日志落库(F3):操作行(切面/显式调用)+ 登录行(登录/踢出钩子)统一写 yz_audit_log。
 *
 * <p>写库纪律(老 safeAddActionLog 语义继承,docs/rules/audit.md §1/§2):
 * REQUIRES_NEW 独立事务(业务回滚审计仍在——失败恰恰是最需要留痕的时刻)+
 * {@link #safe} 吞掉一切异常绝不影响主业务。
 */
@Service
public class AuditRecorder {

    public static final int CATEGORY_OPERATION = 0;
    public static final int CATEGORY_LOGIN = 1;

    public static final int RESULT_SUCCESS = 0;
    public static final int RESULT_FAIL = 1;
    public static final int RESULT_ERROR = 2;

    /** 敏感键:精确命中整体替换 ***;模糊命中(phone/mobile/idcard/bankcard/cardno/certno)同样替换。 */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwordsha256", "pwd", "oldpwd", "newpwd", "token", "secret", "authorization");
    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
            ".*(phone|mobile|idcard|identity|bankcard|bankno|cardno|certno).*", Pattern.CASE_INSENSITIVE);
    private static final String MASK = "***";
    private static final int MAX_DETAIL_LEN = 2000;
    private static final int MAX_MSG_LEN = 255;

    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuditRecorder(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 操作行(切面入口;各字段语义见 docs/rules/audit.md §1)。 */
    public void operation(String module, String action, String objectType, String objectId,
            String summary, String detail, int result, Integer errorCode, String errorMsg, long runTime) {
        safe(() -> insert(CATEGORY_OPERATION, currentOrgId(), currentUserId(), currentDisplayName(currentUserId()),
                action, module, objectType, objectId, summary, detail, currentIp(),
                result, errorCode, errorMsg, runTime));
    }

    /**
     * 登录行:成功/失败统一;登录请求未认证(无 org 属性),org/userId 由调用方带回
     * (用户存在即有真实 org;用户不存在=0);errorMsg 传文案(业务码同列 error_code)。
     */
    public void login(Long orgId, String username, Long userId, boolean success,
            Integer errorCode, String errorMsg) {
        safe(() -> insert(CATEGORY_LOGIN, orgId == null ? 0L : orgId, userId == null ? 0L : userId,
                username == null ? "" : username,
                "login", "auth", "", "", "", null, currentIp(),
                success ? RESULT_SUCCESS : RESULT_FAIL, success ? null : errorCode,
                success ? "" : truncate(errorMsg, MAX_MSG_LEN), 0));
    }

    /** 踢出行(停用/重置密码等 evictUser 触达点;老系统踢出不留痕,营造补上)。 */
    public void evict(long operatorOrgId, long targetUserId, String targetName, String reason) {
        safe(() -> insert(CATEGORY_LOGIN, operatorOrgId, targetUserId, targetName == null ? "" : targetName,
                "evict", "auth", "user", String.valueOf(targetUserId), reason, null, currentIp(),
                RESULT_SUCCESS, null, "", 0));
    }

    // ---- 内部 ----

    private void safe(Runnable write) {
        try {
            write.run();
        } catch (Throwable t) {
            log.error("[audit] write failed (swallowed)", t);
        }
    }

    private void insert(int category, long orgId, long userId, String userName, String action, String module,
            String objectType, String objectId, String summary, String detail, String ip,
            int result, Integer errorCode, String errorMsg, long runTime) {
        tx.executeWithoutResult(status -> jdbc.update("""
                insert into yz_audit_log(category, org_id, user_id, user_name, action, module,
                        object_type, object_id, summary, detail, ip, result, error_code, error_msg, run_time, created_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, category, orgId, userId, truncate(userName, 64), truncate(action, 128),
                truncate(module, 64), truncate(objectType, 64), truncate(objectId, 64),
                truncate(summary, 512), detail, truncate(ip, 64), result, errorCode,
                truncate(errorMsg, MAX_MSG_LEN), (int) Math.min(runTime, Integer.MAX_VALUE),
                LocalDateTime.now()));
    }

    /** SpEL 评估(参数名靠根 pom -parameters 军规可读);异常返回 null(调用方回落缺省)。 */
    String evalSpel(Method method, Object[] args, String expression) {
        try {
            org.springframework.expression.spel.standard.SpelExpressionParser parser =
                    new org.springframework.expression.spel.standard.SpelExpressionParser();
            org.springframework.expression.Expression expr = parser.parseExpression(expression);
            org.springframework.expression.spel.support.StandardEvaluationContext ctx =
                    new org.springframework.expression.spel.support.StandardEvaluationContext();
            var params = method.getParameters();
            for (int i = 0; i < params.length && i < args.length; i++) {
                ctx.setVariable(params[i].getName(), args[i]);
            }
            Object value = expr.getValue(ctx);
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            log.warn("[audit] spel eval failed: {}", expression);
            return null;
        }
    }

    /** 参数脱敏 JSON:跳过 servlet/输入流类参数;敏感键替换 ***;超长截断;失败兜底。 */
    String sanitizeArgs(Method method, Object[] args) {
        Map<String, Object> out = new LinkedHashMap<>();
        var params = method.getParameters();
        for (int i = 0; i < params.length && i < args.length; i++) {
            Object arg = args[i];
            if (arg == null) {
                continue;
            }
            String type = params[i].getType().getName();
            if (type.startsWith("jakarta.servlet") || type.startsWith("org.springframework")
                    || type.startsWith("java.io")) {
                continue;
            }
            out.put(params[i].getName(), arg);
        }
        if (out.isEmpty()) {
            return null;
        }
        try {
            // 先转 Jackson 树再递归脱敏:record/POJO 参数(body Cmd 类)必须展开,否则密码明文入库
            String json = objectMapper.writeValueAsString(maskNode(objectMapper.valueToTree(out)));
            return json.length() > MAX_DETAIL_LEN
                    ? json.substring(0, MAX_DETAIL_LEN) + "...[truncated]" : json;
        } catch (Exception e) {
            return "[sanitize-error]";
        }
    }

    /** 树级递归脱敏:ObjectNode 键命中替换值为 ***;ArrayNode 逐项;叶子原样。 */
    private com.fasterxml.jackson.databind.JsonNode maskNode(com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isObject()) {
            var out = objectMapper.createObjectNode();
            var fields = node.fields();
            while (fields.hasNext()) {
                var e = fields.next();
                if (isSensitive(e.getKey())) {
                    out.put(e.getKey(), MASK);
                } else {
                    out.set(e.getKey(), maskNode(e.getValue()));
                }
            }
            return out;
        }
        if (node.isArray()) {
            var out = objectMapper.createArrayNode();
            for (var item : node) {
                out.add(maskNode(item));
            }
            return out;
        }
        return node;
    }

    private boolean isSensitive(String key) {
        String k = key.toLowerCase();
        return SENSITIVE_KEYS.contains(k) || SENSITIVE_PATTERN.matcher(k).matches();
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() > max ? text.substring(0, max) : text;
    }

    // ---- 请求上下文(调度线程无请求=安全缺省) ----

    private HttpServletRequest request() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    long currentUserId() {
        HttpServletRequest req = request();
        Object v = req == null ? null : req.getAttribute("yz.userId");
        return v instanceof Number n ? n.longValue() : 0L;
    }

    long currentOrgId() {
        HttpServletRequest req = request();
        Object v = req == null ? null : req.getAttribute("yz.orgId");
        return v instanceof Number n ? n.longValue() : 0L;
    }

    String currentDisplayName(long userId) {
        if (userId <= 0) {
            return "";
        }
        try {
            return jdbc.queryForObject("select display_name from yz_user where id = ?", String.class, userId);
        } catch (Exception e) {
            return "";
        }
    }

    String currentIp() {
        HttpServletRequest req = request();
        if (req == null) {
            return "";
        }
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String remote = req.getRemoteAddr();
        return remote == null ? "" : remote;
    }

    /** BizException 归类:业务失败(result=1);其他 Throwable=系统异常(result=2)。 */
    static int classify(Throwable t) {
        return t instanceof BizException ? RESULT_FAIL : RESULT_ERROR;
    }
}
