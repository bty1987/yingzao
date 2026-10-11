package com.yingzao.addon.market;

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
 * 投放物字典服务:营销活动 + 投放渠道。
 * 活动状态机 0 草稿 →(publish)→ 2 发布 →(disable)→ 3 停用 →(publish 恢复)→ 2;
 * 0/2/3 →(archive)→ 5 归档终态——老系统 1 warm / 4 finish 无任何写点的半成品死态不迁;
 * 编辑仅草稿态(发布/停用后不可改,PRD 口径)。渠道 0 启用 ⇄ 1 停用,
 * 带恢复接口(修老系统停用即终局无恢复)。
 */
@Service
public class CampaignService {

    public static final int CODE_CAMPAIGN_NOT_FOUND = 280001;
    public static final int CODE_CAMPAIGN_ILLEGAL_STATUS = 280002;
    public static final int CODE_CAMPAIGN_NAME_DUP = 280003;
    public static final int CODE_CHANNEL_NOT_FOUND = 280004;
    public static final int CODE_CHANNEL_DISABLED = 280005;
    public static final int CODE_CHANNEL_NAME_DUP = 280006;

    public static final int STATUS_DRAFT = 0;
    public static final int STATUS_PUBLISHED = 2;
    public static final int STATUS_DISABLED = 3;
    public static final int STATUS_ARCHIVED = 5;

    private final JdbcTemplate jdbc;
    private final MessageSource messages;

    public CampaignService(JdbcTemplate jdbc, MessageSource messages) {
        this.jdbc = jdbc;
        this.messages = messages;
    }

    // ---- 活动 ----

    @Transactional
    public long createCampaign(long orgId, String name, String description, long budget) {
        requireCampaignNameFree(orgId, name, 0);
        jdbc.update("insert into market_campaign(org_id, name, description, budget, status) values (?,?,?,?,0)",
                orgId, name, description, budget);
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    @Transactional
    public void updateCampaign(long orgId, long id, String name, String description, Long budget) {
        Map<String, Object> row = loadCampaign(orgId, id);
        if (((Number) row.get("status")).intValue() != STATUS_DRAFT) {
            throw new BizException(CODE_CAMPAIGN_ILLEGAL_STATUS,
                    messages.getMessage("market.campaign.notDraft", null, LocaleContextHolder.getLocale()));
        }
        requireCampaignNameFree(orgId, name, id);
        jdbc.update("update market_campaign set name = ?, description = ?, budget = ? where id = ? and org_id = ?",
                name, description, budget == null ? 0L : budget, id, orgId);
    }

    /** 发布:0 草稿 / 3 停用 → 2 发布(停用可恢复,状态机闭环)。 */
    @Transactional
    public void publishCampaign(long orgId, long id) {
        int updated = jdbc.update(
                "update market_campaign set status = 2, publish_time = now() where id = ? and org_id = ? and status in (0, 3)",
                id, orgId);
        if (updated == 0) {
            throwIllegalCampaign(orgId, id);
        }
    }

    @Transactional
    public void disableCampaign(long orgId, long id) {
        int updated = jdbc.update(
                "update market_campaign set status = 3 where id = ? and org_id = ? and status = 2", id, orgId);
        if (updated == 0) {
            throwIllegalCampaign(orgId, id);
        }
    }

    /** 归档终态:0/2/3 → 5,单向无逆向(终态写拒)。 */
    @Transactional
    public void archiveCampaign(long orgId, long id) {
        int updated = jdbc.update(
                "update market_campaign set status = 5 where id = ? and org_id = ? and status in (0, 2, 3)", id, orgId);
        if (updated == 0) {
            throwIllegalCampaign(orgId, id);
        }
    }

    public List<Map<String, Object>> listCampaigns(long orgId, Integer status, String name) {
        StringBuilder where = new StringBuilder(" where org_id = ?");
        List<Object> args = new ArrayList<>(List.of(orgId));
        if (status != null) {
            where.append(" and status = ?");
            args.add(status);
        }
        if (name != null && !name.isBlank()) {
            where.append(" and name like concat('%', ?, '%')");
            args.add(name.trim());
        }
        return jdbc.queryForList(
                "select id, name, description, budget, status, publish_time, created_at, updated_at"
                        + " from market_campaign" + where + " order by id desc",
                args.toArray());
    }

    // ---- 渠道 ----

    @Transactional
    public long createChannel(long orgId, String name) {
        requireChannelNameFree(orgId, name, 0);
        jdbc.update("insert into market_channel(org_id, name, status) values (?,?,0)", orgId, name);
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    @Transactional
    public void updateChannel(long orgId, long id, String name) {
        loadChannel(orgId, id);
        requireChannelNameFree(orgId, name, id);
        jdbc.update("update market_channel set name = ? where id = ? and org_id = ?", name, id, orgId);
    }

    @Transactional
    public void disableChannel(long orgId, long id) {
        int updated = jdbc.update(
                "update market_channel set status = 1 where id = ? and org_id = ? and status = 0", id, orgId);
        if (updated == 0) {
            throwIllegalChannel(orgId, id);
        }
    }

    /** 恢复启用(修老系统无恢复接口,停用即终局)。 */
    @Transactional
    public void enableChannel(long orgId, long id) {
        int updated = jdbc.update(
                "update market_channel set status = 0 where id = ? and org_id = ? and status = 1", id, orgId);
        if (updated == 0) {
            throwIllegalChannel(orgId, id);
        }
    }

    public List<Map<String, Object>> listChannels(long orgId, Integer status) {
        StringBuilder where = new StringBuilder(" where org_id = ?");
        List<Object> args = new ArrayList<>(List.of(orgId));
        if (status != null) {
            where.append(" and status = ?");
            args.add(status);
        }
        return jdbc.queryForList(
                "select id, name, status, created_at from market_channel" + where + " order by id desc",
                args.toArray());
    }

    // ---- 内部 ----

    private Map<String, Object> loadCampaign(long orgId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, name, status from market_campaign where id = ? and org_id = ? limit 1", id, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_CAMPAIGN_NOT_FOUND,
                    messages.getMessage("market.campaign.notfound", null, LocaleContextHolder.getLocale()));
        }
        return rows.get(0);
    }

    private void throwIllegalCampaign(long orgId, long id) {
        Map<String, Object> row = loadCampaign(orgId, id);
        throw new BizException(CODE_CAMPAIGN_ILLEGAL_STATUS,
                messages.getMessage("market.campaign.illegalStatus", null, LocaleContextHolder.getLocale())
                        + " (status=" + row.get("status") + ")");
    }

    private void requireCampaignNameFree(long orgId, String name, long selfId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from market_campaign where org_id = ? and name = ? and id != ?",
                Integer.class, orgId, name, selfId);
        if (count != null && count > 0) {
            throw new BizException(CODE_CAMPAIGN_NAME_DUP,
                    messages.getMessage("market.campaign.nameDup", null, LocaleContextHolder.getLocale()));
        }
    }

    private Map<String, Object> loadChannel(long orgId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, name, status from market_channel where id = ? and org_id = ? limit 1", id, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_CHANNEL_NOT_FOUND,
                    messages.getMessage("market.channel.notfound", null, LocaleContextHolder.getLocale()));
        }
        return rows.get(0);
    }

    private void throwIllegalChannel(long orgId, long id) {
        Map<String, Object> row = loadChannel(orgId, id);
        throw new BizException(CODE_CHANNEL_DISABLED,
                messages.getMessage("market.channel.illegalStatus", null, LocaleContextHolder.getLocale())
                        + " (status=" + row.get("status") + ")");
    }

    private void requireChannelNameFree(long orgId, String name, long selfId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from market_channel where org_id = ? and name = ? and id != ?",
                Integer.class, orgId, name, selfId);
        if (count != null && count > 0) {
            throw new BizException(CODE_CHANNEL_NAME_DUP,
                    messages.getMessage("market.channel.nameDup", null, LocaleContextHolder.getLocale()));
        }
    }

    /** 渠道启用态校验(投放源创建用,InboundService 登记也复用)。 */
    public Map<String, Object> requireChannelEnabled(long orgId, long channelId) {
        Map<String, Object> channel = loadChannel(orgId, channelId);
        if (((Number) channel.get("status")).intValue() != 0) {
            throw new BizException(CODE_CHANNEL_DISABLED,
                    messages.getMessage("market.channel.disabled", null, LocaleContextHolder.getLocale()));
        }
        return channel;
    }

    public Map<String, Object> campaignRef(long orgId, long campaignId) {
        Map<String, Object> campaign = loadCampaign(orgId, campaignId);
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("id", campaign.get("id"));
        ref.put("name", campaign.get("name"));
        ref.put("status", campaign.get("status"));
        return ref;
    }
}
