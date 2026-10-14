package com.yingzao.addon.portal;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.addons.api.ConstructionApi;
import com.yingzao.addons.api.CustomerProfileApi;
import com.yingzao.addons.api.DecorationApi;
import com.yingzao.addons.api.MarketApi;
import com.yingzao.addons.api.MemberApi;
import com.yingzao.core.auth.TokenStore;
import com.yingzao.core.web.BizException;

/**
 * C 端门户聚合服务:手机验证码登录(get-or-create 建档经 CustomerProfileApi 契约,
 * 唯一写点纪律保持)+ 案例/进度/积分只读聚合(各域零改动,全部经 addons-api 只读契约)。
 *
 * 认证边界(与内核协作):
 * - 发码/登录 = anonymous(内核白名单 /api/c/auth/);
 * - 进度/积分 = c-customer(RbacInterceptor 按 principal=customer 单独归类,越权面收口一处);
 * - 进度归属校验 = DecorationApi.queryOrderCustomer 返回 customerId 与令牌 customerId 比对,
 *   不匹配 360004(修老「知道单号就能看别人进度」类越权)。
 *
 * 验证码:6 位/5 分钟/单次消费;60s 限发+日限 10 条(portal_sms_code 表);
 * 投递通道 V1 仅落表(冒烟经库直查断言),短信 Provider/通知转告 V2(设计稿 §2.3)。
 */
@Service
public class PortalService {

    public static final int CODE_PARAM = 360000;
    public static final int CODE_CODE_INVALID = 360001;
    public static final int CODE_RATE = 360002;
    public static final int CODE_DAILY = 360003;
    public static final int CODE_NOT_YOURS = 360004;

    private static final int CODE_TTL_MINUTES = 5;
    private static final int RESEND_INTERVAL_SECONDS = 60;
    private static final int DAILY_LIMIT = 10;

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final TokenStore tokens;
    private final CustomerProfileApi profiles;
    private final MarketApi markets;
    private final ConstructionApi constructions;
    private final DecorationApi decorations;
    private final MemberApi members;
    private final SecureRandom random = new SecureRandom();

    /** 匿名面组织上下文(C 端匿名请求无令牌无 org;小程序/域名与组织绑定 V2 走多租户解析)。 */
    @org.springframework.beans.factory.annotation.Value("${yingzao.portal.org-id:1}")
    private long anonymousOrgId;

    public PortalService(JdbcTemplate jdbc, MessageSource messages, TokenStore tokens,
            CustomerProfileApi profiles, MarketApi markets, ConstructionApi constructions,
            DecorationApi decorations, MemberApi members) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.tokens = tokens;
        this.profiles = profiles;
        this.markets = markets;
        this.constructions = constructions;
        this.decorations = decorations;
        this.members = members;
    }

    /** 发验证码(匿名):60s 限发+日限;6 位随机;5 分钟过期。 */
    public void sendCode(long orgId, String mobile) {
        requireMobile(mobile);
        Integer recent = jdbc.queryForObject("""
                select count(*) from portal_sms_code
                where org_id = ? and mobile = ? and created_at > date_sub(now(), interval ? second)
                """, Integer.class, orgId, mobile, RESEND_INTERVAL_SECONDS);
        if (recent != null && recent > 0) {
            throw new BizException(CODE_RATE, msg("portal.code.rate"));
        }
        Integer today = jdbc.queryForObject("""
                select count(*) from portal_sms_code
                where org_id = ? and mobile = ? and created_at > curdate()
                """, Integer.class, orgId, mobile);
        if (today != null && today >= DAILY_LIMIT) {
            throw new BizException(CODE_DAILY, msg("portal.code.daily"));
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        jdbc.update("""
                insert into portal_sms_code(org_id, mobile, code, expire_at) values (?,?,?,?)
                """, orgId, mobile, code,
                LocalDateTime.now().plusMinutes(CODE_TTL_MINUTES));
        // 投递通道 V1 仅落表(冒烟库直查);短信 Provider/通知转告 V2(设计稿 §2.3)
    }

    /** 验证码登录:单次消费校验 → get-or-create 建档(契约)→ 发 C 端令牌。 */
    @Transactional
    public Map<String, Object> login(long orgId, String mobile, String code) {
        requireMobile(mobile);
        require(code != null && code.matches("\\d{6}"), CODE_PARAM, "portal.param.invalid");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id from portal_sms_code
                where org_id = ? and mobile = ? and code = ? and used_at is null
                    and expire_at > now()
                order by id desc limit 1
                """, orgId, mobile, code);
        if (rows.isEmpty()) {
            throw new BizException(CODE_CODE_INVALID, msg("portal.code.invalid"));
        }
        jdbc.update("update portal_sms_code set used_at = now() where id = ?",
                ((Number) rows.get(0).get("id")).longValue());
        long customerId = profiles.ensureCustomerByPhone(orgId, mobile);
        String token = tokens.issueCustomer(orgId, customerId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("customerId", customerId);
        return out;
    }

    /** 案例库(匿名只读投影,经 MarketApi 契约)。 */
    public Map<String, Object> cases(long orgId, String city, int page, int size) {
        MarketApi.CasePage p = markets.cCases(orgId, blankToNull(city), Math.max(page, 1),
                Math.min(Math.max(size, 1), 50));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", p.total());
        out.put("rows", p.rows());
        return out;
    }

    /** 施工进度(登录;归属校验 customerId,非本人装修单 360004)。 */
    public Map<String, Object> progress(long orgId, long customerId, long orderId) {
        long owner = decorations.queryOrderCustomer(orgId, orderId)
                .orElseThrow(() -> new BizException(CODE_NOT_YOURS, msg("portal.progress.notYours")))
                .customerId();
        if (owner != customerId) {
            throw new BizException(CODE_NOT_YOURS, msg("portal.progress.notYours"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("nodes", constructions.progress(orgId, orderId));
        return out;
    }

    /** 本人积分视图(登录;只读投影经 MemberApi 契约)。 */
    public MemberApi.PointView points(long orgId, long customerId) {
        return members.points(orgId, customerId);
    }

    /** 匿名面组织上下文访问器(controller 用;登录后请求改走令牌内 orgId)。 */
    public long anonymousOrgId() {
        return anonymousOrgId;
    }

    private void require(boolean cond, int code, String msgKey) {
        if (!cond) {
            throw new BizException(code, msg(msgKey));
        }
    }

    private void requireMobile(String mobile) {
        if (mobile == null || !mobile.matches("1\\d{10}")) {
            throw new BizException(CODE_PARAM, msg("portal.param.invalid"));
        }
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
