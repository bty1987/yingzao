package com.yingzao.addon.market;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.core.web.BizException;

/**
 * 投放源服务(活动×渠道的落地物):渠道码 code 服务端生成(随机 hex,组织内唯一 DB 约束兜底,
 * 修老先查后插无约束并发可重)+落地页 URL+UTM 三件套。归属校验:活动与渠道必须存在且
 * 启用(草稿活动可备源,停用渠道/归档活动拒绝);停用⇄恢复双接口(修老停用即终局)。
 */
@Service
public class SourceService {

    public static final int CODE_SOURCE_NOT_FOUND = 280007;
    public static final int CODE_SOURCE_DISABLED = 280008;
    public static final int CODE_SOURCE_OWNER_MISMATCH = 280009;
    public static final int CODE_CAMPAIGN_NOT_PUBLISHED = 280010;

    private static final int CODE_RETRY_EXHAUSTED = 280015;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final CampaignService campaigns;

    public SourceService(JdbcTemplate jdbc, MessageSource messages, CampaignService campaigns) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.campaigns = campaigns;
    }

    @Transactional
    public Map<String, Object> create(long orgId, long campaignId, long channelId, String name,
            String url, String utmSource, String utmMedium, String utmCampaign) {
        // 归属校验:活动存在(草稿/发布可备源,停用/归档拒绝)+ 渠道启用
        Map<String, Object> campaign = campaigns.campaignRef(orgId, campaignId);
        int campaignStatus = ((Number) campaign.get("status")).intValue();
        if (campaignStatus != CampaignService.STATUS_DRAFT && campaignStatus != CampaignService.STATUS_PUBLISHED) {
            throw new BizException(CampaignService.CODE_CAMPAIGN_ILLEGAL_STATUS,
                    messages.getMessage("market.campaign.illegalStatus", null, LocaleContextHolder.getLocale()));
        }
        campaigns.requireChannelEnabled(orgId, channelId);
        String code = generateCode(orgId);
        jdbc.update("""
                insert into market_source(org_id, campaign_id, channel_id, name, code, url,
                    utm_source, utm_medium, utm_campaign, status)
                values (?,?,?,?,?,?,?,?,?,0)
                """, orgId, campaignId, channelId, name, code, url, utmSource, utmMedium, utmCampaign);
        long id = jdbc.queryForObject("select last_insert_id()", Long.class);
        return detail(orgId, id);
    }

    public Map<String, Object> detail(long orgId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select s.id, s.org_id, s.campaign_id, c.name as campaign_name, s.channel_id,
                       h.name as channel_name, s.name, s.code, s.url, s.utm_source, s.utm_medium,
                       s.utm_campaign, s.status, s.created_at
                from market_source s
                join market_campaign c on c.id = s.campaign_id
                join market_channel h on h.id = s.channel_id
                where s.id = ? and s.org_id = ? limit 1
                """, id, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_SOURCE_NOT_FOUND,
                    messages.getMessage("market.source.notfound", null, LocaleContextHolder.getLocale()));
        }
        Map<String, Object> row = rows.get(0);
        row.put("utm_url", buildUtmUrl(row));
        return row;
    }

    public List<Map<String, Object>> list(long orgId, Long campaignId, Long channelId, Integer status) {
        StringBuilder where = new StringBuilder(" where s.org_id = ?");
        List<Object> args = new ArrayList<>(List.of(orgId));
        if (campaignId != null) {
            where.append(" and s.campaign_id = ?");
            args.add(campaignId);
        }
        if (channelId != null) {
            where.append(" and s.channel_id = ?");
            args.add(channelId);
        }
        if (status != null) {
            where.append(" and s.status = ?");
            args.add(status);
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select s.id, s.campaign_id, c.name as campaign_name, s.channel_id,
                       h.name as channel_name, s.name, s.code, s.url, s.utm_source, s.utm_medium,
                       s.utm_campaign, s.status, s.created_at
                from market_source s
                join market_campaign c on c.id = s.campaign_id
                join market_channel h on h.id = s.channel_id
                """ + where + " order by s.id desc", args.toArray());
        for (Map<String, Object> row : rows) {
            row.put("utm_url", buildUtmUrl(row));
        }
        return rows;
    }

    @Transactional
    public void disable(long orgId, long id) {
        int updated = jdbc.update(
                "update market_source set status = 1 where id = ? and org_id = ? and status = 0", id, orgId);
        if (updated == 0) {
            throwIllegalSource(orgId, id);
        }
    }

    /** 恢复启用(修老系统无恢复接口)。 */
    @Transactional
    public void enable(long orgId, long id) {
        int updated = jdbc.update(
                "update market_source set status = 0 where id = ? and org_id = ? and status = 1", id, orgId);
        if (updated == 0) {
            throwIllegalSource(orgId, id);
        }
    }

    /** 投放源启用态+归属校验(进线登记三重校验的第三重;归属不一致 280009,修老 390004)。 */
    public Map<String, Object> requireUsableSource(long orgId, long sourceId, Long campaignId, Long channelId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, campaign_id, channel_id, status from market_source where id = ? and org_id = ? limit 1",
                sourceId, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_SOURCE_NOT_FOUND,
                    messages.getMessage("market.source.notfound", null, LocaleContextHolder.getLocale()));
        }
        Map<String, Object> source = rows.get(0);
        if (((Number) source.get("status")).intValue() != 0) {
            throw new BizException(CODE_SOURCE_DISABLED,
                    messages.getMessage("market.source.disabled", null, LocaleContextHolder.getLocale()));
        }
        if (campaignId != null && ((Number) source.get("campaign_id")).longValue() != campaignId
                || channelId != null && ((Number) source.get("channel_id")).longValue() != channelId) {
            throw new BizException(CODE_SOURCE_OWNER_MISMATCH,
                    messages.getMessage("market.source.ownerMismatch", null, LocaleContextHolder.getLocale()));
        }
        return source;
    }

    // ---- 内部 ----

    /** 渠道码:16 位随机 hex,组织内查重+唯一索引兜底;连续撞码重试上限兜底。 */
    private String generateCode(long orgId) {
        for (int attempt = 0; attempt < 3; attempt++) {
            String code = randomHex();
            Integer count = jdbc.queryForObject(
                    "select count(*) from market_source where org_id = ? and code = ?", Integer.class, orgId, code);
            if (count != null && count == 0) {
                return code;
            }
        }
        throw new BizException(CODE_RETRY_EXHAUSTED,
                messages.getMessage("market.code.exhausted", null, LocaleContextHolder.getLocale()));
    }

    private String randomHex() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(16);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void throwIllegalSource(long orgId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, status from market_source where id = ? and org_id = ? limit 1", id, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_SOURCE_NOT_FOUND,
                    messages.getMessage("market.source.notfound", null, LocaleContextHolder.getLocale()));
        }
        throw new BizException(CODE_SOURCE_DISABLED,
                messages.getMessage("market.source.illegalStatus", null, LocaleContextHolder.getLocale()));
    }

    private String buildUtmUrl(Map<String, Object> row) {
        Object url = row.get("url");
        if (url == null || url.toString().isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(url.toString());
        String sep = url.toString().contains("?") ? "&" : "?";
        appendUtm(sb, sep, "utm_source", row.get("utm_source"));
        appendUtm(sb, "&", "utm_medium", row.get("utm_medium"));
        appendUtm(sb, "&", "utm_campaign", row.get("utm_campaign"));
        return sb.toString();
    }

    private void appendUtm(StringBuilder sb, String sep, String key, Object value) {
        if (value == null || value.toString().isBlank()) {
            return;
        }
        sb.append(sep).append(key).append('=').append(value);
    }
}
