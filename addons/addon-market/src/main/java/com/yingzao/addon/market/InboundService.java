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

import com.yingzao.addons.api.MarketApi;
import com.yingzao.addons.api.event.InboundAcceptedEvent;
import com.yingzao.core.eventbus.YzEventBus;
import com.yingzao.core.security.CryptoBox;
import com.yingzao.core.web.BizException;

/**
 * 进线服务(获客链核心):登记(三重校验+幂等键 DB 兜底+手机密文+归因 submit)→
 * InboundAcceptedEvent 发布(addon-customer 承接,废老 REQUIRES_NEW 直调孤儿事务)→
 * MarketApi 回写(承接结果+归因逐级落行)→ 人工重试/忽略(操作留痕,修老 retry 无权限无日志)。
 * 幂等口径:服务端生成 HMAC(org-投放三元组/case-手机hash),命中返回全量+duplicate 标记
 * (修老 phone 缺失掺 UUID / 先查后插无锁 / 命中回半截数据三退化);匿名进线必须显式允许。
 */
@Service
public class InboundService implements MarketApi {

    public static final int CODE_INBOUND_NOT_FOUND = 280011;
    public static final int CODE_INBOUND_ILLEGAL_STATUS = 280012;
    public static final int CODE_ACCEPT_MODE_UNSUPPORTED = 280013;
    public static final int CODE_PHONE_REQUIRED = 280014;
    public static final int CODE_ACCEPT_NOT_IN_PROGRESS = 280024;

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_ACCEPTED = 1;
    public static final int STATUS_FAILED = 2;
    public static final int STATUS_ACCEPTING = 3;
    public static final int STATUS_IGNORED = 4;

    public static final String ATTR_SUBMIT = "submit";
    public static final String ATTR_RETRY = "retry";
    public static final String ATTR_IGNORE = "ignore";
    public static final String ATTR_ACCEPT_FAIL = "acceptFail";

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final CryptoBox crypto;
    private final YzEventBus eventBus;
    private final CampaignService campaigns;
    private final SourceService sources;

    public InboundService(JdbcTemplate jdbc, MessageSource messages, CryptoBox crypto,
            YzEventBus eventBus, CampaignService campaigns, SourceService sources) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.crypto = crypto;
        this.eventBus = eventBus;
        this.campaigns = campaigns;
        this.sources = sources;
    }

    /** 登记请求(投放主链:sourceId 必填,campaignId/channelId 可选显式归属校验;案例报名:caseId 必填)。 */
    public record CreateCmd(Long sourceId, Long campaignId, Long channelId, Long caseId, String caseName,
            String name, String phone, String acceptMode, String note, Boolean allowAnonymous,
            List<FieldItem> items) {
    }

    public record FieldItem(String key, String value) {
    }

    @Transactional
    public Map<String, Object> create(long orgId, long operatorId, CreateCmd cmd) {
        Long campaignId = null;
        Long channelId = null;
        Long caseId = null;
        String caseName = null;
        if (cmd.sourceId() != null) {
            // 三重校验:投放源存在+启用+归属一致 → 活动已发布 → 渠道启用(修老 390001/390003/390004)
            Map<String, Object> source = sources.requireUsableSource(orgId, cmd.sourceId(),
                    cmd.campaignId(), cmd.channelId());
            campaignId = ((Number) source.get("campaign_id")).longValue();
            channelId = ((Number) source.get("channel_id")).longValue();
            Map<String, Object> campaign = campaigns.campaignRef(orgId, campaignId);
            if (((Number) campaign.get("status")).intValue() != CampaignService.STATUS_PUBLISHED) {
                throw new BizException(SourceService.CODE_CAMPAIGN_NOT_PUBLISHED,
                        messages.getMessage("market.campaign.notPublished", null, LocaleContextHolder.getLocale()));
            }
            campaigns.requireChannelEnabled(orgId, channelId);
        } else if (cmd.caseId() != null) {
            // 案例报名:一种进线入口,复用幂等/密文/归因链(废老独立明文 ecustomer 表)
            caseId = cmd.caseId();
            caseName = cmd.caseName();
        } else {
            throw new BizException(SourceService.CODE_SOURCE_OWNER_MISMATCH,
                    messages.getMessage("market.inbound.noContext", null, LocaleContextHolder.getLocale()));
        }

        String mode = cmd.acceptMode() == null || cmd.acceptMode().isBlank()
                ? InboundAcceptedEvent.MODE_CHANCE : cmd.acceptMode();
        if (!InboundAcceptedEvent.MODE_SOURCE.equals(mode) && !InboundAcceptedEvent.MODE_CHANCE.equals(mode)) {
            throw new BizException(CODE_ACCEPT_MODE_UNSUPPORTED,
                    messages.getMessage("market.inbound.modeUnsupported", null, LocaleContextHolder.getLocale()));
        }

        // 手机口径:密文落库+hmac 查重;缺失必须显式允许匿名(禁掺 UUID 退化)
        String phone = cmd.phone() == null ? "" : cmd.phone().trim();
        String phoneHash = phone.isEmpty() ? "" : crypto.hmac(phone);
        if (phone.isEmpty() && !Boolean.TRUE.equals(cmd.allowAnonymous())) {
            throw new BizException(CODE_PHONE_REQUIRED,
                    messages.getMessage("market.inbound.phoneRequired", null, LocaleContextHolder.getLocale()));
        }
        String idempotentKey = phoneHash.isEmpty() || phoneHash.isBlank()
                ? crypto.hmac("anon|" + orgId + "|" + (cmd.sourceId() == null ? "case-" + cmd.caseId() : cmd.sourceId()))
                : crypto.hmac(orgId + "|" + cmd.sourceId() + "|" + phoneHash);

        // 幂等命中:回全量+重复标记(业务码提示,不报错——修老命中回半截)
        Map<String, Object> hit = findDetailByKey(orgId, idempotentKey);
        if (hit != null) {
            hit.put("duplicate", true);
            return hit;
        }

        String inboundNo = nextInboundNo(orgId);
        jdbc.update("""
                insert into market_inbound(org_id, inbound_no, campaign_id, channel_id, source_id,
                    case_id, case_name, name, phone_cipher, phone_hash, idempotent_key, accept_mode,
                    note, status)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,0)
                """, orgId, inboundNo, campaignId, channelId, cmd.sourceId(), caseId, caseName,
                cmd.name(), phone.isEmpty() ? null : crypto.encrypt(phone),
                phone.isEmpty() ? null : phoneHash, idempotentKey, mode, cmd.note());
        long inboundId = jdbc.queryForObject("select last_insert_id()", Long.class);
        saveItems(inboundId, cmd.items());
        // 归因流水 insert-only:0 提交
        insertAttribution(orgId, inboundId, campaignId, channelId, cmd.sourceId(), 0, null);
        insertTrack(inboundId, ATTR_SUBMIT, null, operatorId);
        // 承接事件:addon-customer 监听走自己的建档链,完成后经 MarketApi 回写
        eventBus.publish(new InboundAcceptedEvent(inboundId, orgId, cmd.name(),
                phoneHash.isEmpty() ? null : phoneHash, mode, cmd.note()),
                "market:inbound:" + inboundId + ":accepted");
        return detail(orgId, inboundId);
    }

    /** 详情:主行+动态字段+操作留痕+归因流水+承接结果(回全量口径)。 */
    public Map<String, Object> detail(long orgId, long inboundId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select i.id, i.org_id, i.inbound_no, i.campaign_id, c.name as campaign_name,
                       i.channel_id, h.name as channel_name, i.source_id, s.code as source_code,
                       i.case_id, i.case_name, i.name, i.accept_mode, i.note, i.status, i.error_msg,
                       i.customer_lead_id, i.chance_id, i.accept_time, i.ignored_by, i.created_at
                from market_inbound i
                left join market_campaign c on c.id = i.campaign_id
                left join market_channel h on h.id = i.channel_id
                left join market_source s on s.id = i.source_id
                where i.id = ? and i.org_id = ? limit 1
                """, inboundId, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_INBOUND_NOT_FOUND,
                    messages.getMessage("market.inbound.notfound", null, LocaleContextHolder.getLocale()));
        }
        Map<String, Object> row = rows.get(0);
        row.put("items", jdbc.queryForList(
                "select field_key, field_value from market_inbound_item where inbound_id = ? order by id", inboundId));
        row.put("tracks", jdbc.queryForList(
                "select action, reason, operator_id, created_at from market_track where inbound_id = ? order by id",
                inboundId));
        row.put("attributions", jdbc.queryForList(
                "select event_type, ref_id, created_at from market_attribution where inbound_id = ? order by id",
                inboundId));
        return row;
    }

    public Map<String, Object> page(long orgId, Integer status, Long campaignId, Long channelId,
            Long caseId, String name, int pageNo, int size) {
        StringBuilder where = new StringBuilder(" where i.org_id = ?");
        List<Object> args = new ArrayList<>(List.of(orgId));
        if (status != null) {
            where.append(" and i.status = ?");
            args.add(status);
        }
        if (campaignId != null) {
            where.append(" and i.campaign_id = ?");
            args.add(campaignId);
        }
        if (channelId != null) {
            where.append(" and i.channel_id = ?");
            args.add(channelId);
        }
        if (caseId != null) {
            where.append(" and i.case_id = ?");
            args.add(caseId);
        }
        if (name != null && !name.isBlank()) {
            where.append(" and i.name like concat('%', ?, '%')");
            args.add(name.trim());
        }
        Long total = jdbc.queryForObject("select count(*) from market_inbound i" + where, Long.class, args.toArray());
        List<Object> allArgs = new ArrayList<>(args);
        allArgs.add(size);
        allArgs.add((pageNo - 1) * size);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select i.id, i.inbound_no, i.campaign_id, c.name as campaign_name, i.channel_id,
                       h.name as channel_name, i.source_id, i.case_id, i.case_name, i.name,
                       i.accept_mode, i.status, i.error_msg, i.customer_lead_id, i.chance_id,
                       i.accept_time, i.created_at
                from market_inbound i
                left join market_campaign c on c.id = i.campaign_id
                left join market_channel h on h.id = i.channel_id
                """ + where + " order by i.id desc limit ? offset ?", allArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    /** 重试:0/2 态可重试(2 先置回待承接),重发承接事件;操作留痕(修老无权限无日志)。 */
    @Transactional
    public void retry(long orgId, long inboundId, long operatorId) {
        Map<String, Object> row = load(orgId, inboundId);
        int status = ((Number) row.get("status")).intValue();
        if (status != STATUS_PENDING && status != STATUS_FAILED) {
            throw new BizException(CODE_INBOUND_ILLEGAL_STATUS,
                    messages.getMessage("market.inbound.illegalStatus", null, LocaleContextHolder.getLocale()));
        }
        jdbc.update("""
                update market_inbound set status = 0, error_msg = null
                where id = ? and org_id = ? and status in (0, 2)
                """, inboundId, orgId);
        insertTrack(inboundId, ATTR_RETRY, null, operatorId);
        eventBus.publish(new InboundAcceptedEvent(inboundId, orgId, (String) row.get("name"),
                (String) row.get("phone_hash"), (String) row.get("accept_mode"), (String) row.get("note")),
                "market:inbound:" + inboundId + ":accepted");
    }

    /** 忽略:0/2 → 4 显式关闭,带操作人与原因留痕(修老 4 ignore 死状态无写点)。 */
    @Transactional
    public void ignore(long orgId, long inboundId, long operatorId, String reason) {
        int updated = jdbc.update("""
                update market_inbound set status = 4, ignored_by = ?
                where id = ? and org_id = ? and status in (0, 2)
                """, operatorId, inboundId, orgId);
        if (updated == 0) {
            load(orgId, inboundId);
            throw new BizException(CODE_INBOUND_ILLEGAL_STATUS,
                    messages.getMessage("market.inbound.illegalStatus", null, LocaleContextHolder.getLocale()));
        }
        insertTrack(inboundId, ATTR_IGNORE, reason, operatorId);
    }

    // ---- 获客漏斗(按活动/渠道分组;有效进线 = 承接成功口径,老 countMarketInbound 语义) ----

    public List<Map<String, Object>> funnelByCampaign(long orgId) {
        List<Map<String, Object>> campaignsList = jdbc.queryForList(
                "select id, name, budget, status from market_campaign where org_id = ? order by id desc", orgId);
        Map<Long, Map<String, Object>> inboundAgg = groupCount("""
                select campaign_id as k, count(*) as total, sum(status = 1) as valid
                from market_inbound where org_id = ? and campaign_id is not null group by campaign_id
                """, orgId);
        Map<Long, Map<String, Object>> attrAgg = groupCount("""
                select campaign_id as k, sum(event_type in (1, 2)) as leads, sum(event_type = 2) as chances
                from market_attribution where org_id = ? and campaign_id is not null group by campaign_id
                """, orgId);
        return joinFunnel(campaignsList, inboundAgg, attrAgg);
    }

    public List<Map<String, Object>> funnelByChannel(long orgId, Long campaignId) {
        StringBuilder where = new StringBuilder(" where org_id = ?");
        List<Object> args = new ArrayList<>(List.of(orgId));
        if (campaignId != null) {
            where.append(" and id = ?");
            args.add(campaignId);
        }
        List<Map<String, Object>> channelList = jdbc.queryForList(
                "select id, name, status from market_channel" + where + " order by id desc", args.toArray());
        StringBuilder inboundWhere = new StringBuilder(" where org_id = ? and channel_id is not null");
        List<Object> inboundArgs = new ArrayList<>(List.of(orgId));
        if (campaignId != null) {
            inboundWhere.append(" and campaign_id = ?");
            inboundArgs.add(campaignId);
        }
        StringBuilder attrWhere = new StringBuilder(" where org_id = ? and channel_id is not null");
        List<Object> attrArgs = new ArrayList<>(List.of(orgId));
        if (campaignId != null) {
            attrWhere.append(" and campaign_id = ?");
            attrArgs.add(campaignId);
        }
        Map<Long, Map<String, Object>> inboundAgg = groupCount(
                "select channel_id as k, count(*) as total, sum(status = 1) as valid from market_inbound"
                        + inboundWhere + " group by channel_id",
                inboundArgs.toArray());
        Map<Long, Map<String, Object>> attrAgg = groupCount(
                "select channel_id as k, sum(event_type in (1, 2)) as leads, sum(event_type = 2) as chances"
                        + " from market_attribution" + attrWhere + " group by channel_id",
                attrArgs.toArray());
        return joinFunnel(channelList, inboundAgg, attrAgg);
    }

    // ---- MarketApi 承接回写实现(addon-customer 经契约调用) ----

    @Override
    public boolean beginAccept(long inboundId, long orgId) {
        int updated = jdbc.update(
                "update market_inbound set status = 3 where id = ? and org_id = ? and status = 0",
                inboundId, orgId);
        return updated == 1;
    }

    @Override
    public void acceptResult(long inboundId, long orgId, boolean success, Long leadId, Long chanceId,
            String errorMsg) {
        int updated;
        if (success) {
            updated = jdbc.update("""
                    update market_inbound set status = 1, customer_lead_id = ?, chance_id = ?,
                        accept_time = now(), error_msg = null
                    where id = ? and org_id = ? and status = 3
                    """, leadId, chanceId, inboundId, orgId);
            if (updated == 0) {
                throw new BizException(CODE_ACCEPT_NOT_IN_PROGRESS,
                        messages.getMessage("market.inbound.notAccepting", null, LocaleContextHolder.getLocale()));
            }
            List<Map<String, Object>> ctx = jdbc.queryForList(
                    "select campaign_id, channel_id, source_id from market_inbound where id = ?", inboundId);
            Map<String, Object> row = ctx.get(0);
            Long campaignId = (Long) row.get("campaign_id");
            Long channelId = (Long) row.get("channel_id");
            Long sourceId = (Long) row.get("source_id");
            // 归因逐级落行(insert-only):1 建客源 / 2 建机会(修老 ccid 优先单条推导,逐级利于漏斗分层)
            if (leadId != null) {
                insertAttribution(orgId, inboundId, campaignId, channelId, sourceId, 1, leadId);
            }
            if (chanceId != null) {
                insertAttribution(orgId, inboundId, campaignId, channelId, sourceId, 2, chanceId);
            }
        } else {
            updated = jdbc.update("""
                    update market_inbound set status = 2, error_msg = ?
                    where id = ? and org_id = ? and status in (0, 3)
                    """, trim(errorMsg), inboundId, orgId);
            if (updated == 0) {
                throw new BizException(CODE_ACCEPT_NOT_IN_PROGRESS,
                        messages.getMessage("market.inbound.notAccepting", null, LocaleContextHolder.getLocale()));
            }
            insertTrack(inboundId, ATTR_ACCEPT_FAIL, trim(errorMsg), 0);
        }
    }

    /** C 端案例库只读投影(仅上架效果图;city 空=全部城市;主案人物=设计师,无则空串)。 */
    @Override
    public com.yingzao.addons.api.MarketApi.CasePage cCases(long orgId, String city, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 50);
        String cityFilter = city == null || city.isBlank() ? "" : "and c2.name = ? ";
        List<Object> baseArgs = new ArrayList<>();
        baseArgs.add(orgId);
        if (!cityFilter.isEmpty()) {
            baseArgs.add(city.trim());
        }
        Long total = jdbc.queryForObject(new StringBuilder()
                .append("select count(*) from market_ecase e ")
                .append("join market_ecity c2 on c2.id = e.city_id ")
                .append("where e.org_id = ? and e.status = 1 and e.type = 0 ")
                .append(cityFilter)
                .toString(), Long.class, baseArgs.toArray());
        List<Object> rowsArgs = new ArrayList<>(baseArgs);
        rowsArgs.add((safePage - 1) * safeSize);
        rowsArgs.add(safeSize);
        List<com.yingzao.addons.api.MarketApi.CaseCard> rows = jdbc.queryForList(new StringBuilder()
                .append("select e.id, e.name, c2.name as city_name, e.style, e.cover_url, ")
                .append("e.designer_id, p.name as designer_name ")
                .append("from market_ecase e ")
                .append("join market_ecity c2 on c2.id = e.city_id ")
                .append("left join market_eperson p on p.id = e.designer_id ")
                .append("where e.org_id = ? and e.status = 1 and e.type = 0 ")
                .append(cityFilter)
                .append("order by e.id desc limit ?, ?")
                .toString(), rowsArgs.toArray()).stream()
                .map(r -> new com.yingzao.addons.api.MarketApi.CaseCard(
                        ((Number) r.get("id")).longValue(), (String) r.get("name"),
                        (String) r.get("city_name"), (String) r.get("style"),
                        (String) r.get("cover_url"),
                        r.get("designer_id") == null ? 0 : ((Number) r.get("designer_id")).longValue(),
                        r.get("designer_name") == null ? "" : (String) r.get("designer_name")))
                .toList();
        return new com.yingzao.addons.api.MarketApi.CasePage(total == null ? 0 : total, rows);
    }

    public String revealPhone(long inboundId, long orgId, long operatorId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select phone_cipher from market_inbound where id = ? and org_id = ? limit 1", inboundId, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_INBOUND_NOT_FOUND,
                    messages.getMessage("market.inbound.notfound", null, LocaleContextHolder.getLocale()));
        }
        jdbc.update("insert into market_phone_access_log(inbound_id, operator_id, action) values (?,?, 'reveal')",
                inboundId, operatorId);
        Object cipher = rows.get(0).get("phone_cipher");
        return cipher == null ? null : crypto.decrypt(cipher.toString());
    }

    // ---- 内部 ----

    private Map<String, Object> load(long orgId, long inboundId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, name, phone_hash, accept_mode, note, status from market_inbound"
                        + " where id = ? and org_id = ? limit 1",
                inboundId, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_INBOUND_NOT_FOUND,
                    messages.getMessage("market.inbound.notfound", null, LocaleContextHolder.getLocale()));
        }
        return rows.get(0);
    }

    private Map<String, Object> findDetailByKey(long orgId, String idempotentKey) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id from market_inbound where org_id = ? and idempotent_key = ? limit 1", orgId, idempotentKey);
        if (rows.isEmpty()) {
            return null;
        }
        return detail(orgId, ((Number) rows.get(0).get("id")).longValue());
    }

    private void saveItems(long inboundId, List<FieldItem> items) {
        if (items == null) {
            return;
        }
        for (FieldItem item : items) {
            if (item.key() == null || item.key().isBlank()) {
                continue;
            }
            jdbc.update("insert into market_inbound_item(inbound_id, field_key, field_value) values (?,?,?)",
                    inboundId, item.key().trim(), item.value());
        }
    }

    private void insertAttribution(long orgId, long inboundId, Long campaignId, Long channelId,
            Long sourceId, int eventType, Long refId) {
        jdbc.update("""
                insert into market_attribution(org_id, inbound_id, campaign_id, channel_id, source_id,
                    event_type, ref_id) values (?,?,?,?,?,?,?)
                """, orgId, inboundId, campaignId, channelId, sourceId, eventType, refId);
    }

    private void insertTrack(long inboundId, String action, String reason, long operatorId) {
        jdbc.update(
                "insert into market_track(inbound_id, action, reason, operator_id) values (?,?,?,?)",
                inboundId, action, reason, operatorId);
    }

    /** MI+yyMMdd+4 位流水:计数器 last_insert_id 原子自增(修老 "MI"+UUID 无流水语义)。
     * 🔴 首插分支显式 last_insert_id(1)——若写死 1,LAST_INSERT_ID() 返回连接上一次自增
     * 主键 id(market_counter.id 自增),当天首单号错位为该 id,seq 正常爬升到该值即撞
     * uk_market_inbound_no 裸炸 110000(第 45 次同日多轮冒烟实锤;decoration nextOrderNo
     * 为同构正确范式)。存量错位号占用区间由 exists 检查跳过(decoration 同构),
     * 计数器原子自增保证并发不取同号,查后插竞态仅剩理论窗口。 */
    private String nextInboundNo(long orgId) {
        String ymd = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        for (int attempt = 0; attempt < 3; attempt++) {
            jdbc.update("""
                    insert into market_counter(org_id, prefix, ymd, seq) values (?, 'MI', ?, last_insert_id(1))
                    on duplicate key update seq = last_insert_id(seq + 1)
                    """, orgId, ymd);
            long seq = jdbc.queryForObject("select last_insert_id()", Long.class);
            String inboundNo = "MI" + ymd + String.format("%04d", (int) seq);
            Integer exists = jdbc.queryForObject(
                    "select count(*) from market_inbound where org_id = ? and inbound_no = ?",
                    Integer.class, orgId, inboundNo);
            if (exists == null || exists == 0) {
                return inboundNo;
            }
            // 存量错位号占用(bug 遗留区间):计数器继续自增跳过
        }
        throw new IllegalStateException("market inbound no counter exhausted after skip");
    }

    private Map<Long, Map<String, Object>> groupCount(String sql, Object... args) {
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(sql, args)) {
            if (row.get("k") == null) {
                continue;
            }
            result.put(((Number) row.get("k")).longValue(), row);
        }
        return result;
    }

    private List<Map<String, Object>> joinFunnel(List<Map<String, Object>> masters,
            Map<Long, Map<String, Object>> inboundAgg, Map<Long, Map<String, Object>> attrAgg) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> master : masters) {
            long id = ((Number) master.get("id")).longValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("name", master.get("name"));
            row.put("status", master.get("status"));
            if (master.containsKey("budget")) {
                row.put("budget", master.get("budget"));
            }
            Map<String, Object> inbound = inboundAgg.get(id);
            Map<String, Object> attr = attrAgg.get(id);
            row.put("inboundTotal", inbound == null ? 0 : ((Number) inbound.get("total")).longValue());
            row.put("inboundValid", inbound == null || inbound.get("valid") == null
                    ? 0 : ((Number) inbound.get("valid")).longValue());
            row.put("leadCount", attr == null || attr.get("leads") == null
                    ? 0 : ((Number) attr.get("leads")).longValue());
            row.put("chanceCount", attr == null || attr.get("chances") == null
                    ? 0 : ((Number) attr.get("chances")).longValue());
            rows.add(row);
        }
        return rows;
    }

    private String trim(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    /** 进线月合计(只读聚合;target 渠道业绩线消费;漏斗顶段=全部进线)。 */
    @Override
    public long inboundTotalByMonth(long orgId, String yearMonth) {
        Long n = jdbc.queryForObject(
                "select count(*) from market_inbound where org_id = ?"
                        + " and date_format(created_at, '%Y-%m') = ?",
                Long.class, orgId, yearMonth);
        return n == null ? 0 : n;
    }

    /** 进线日区间合计(只读聚合;target 阶段目标消费;登记日落于区间)。 */
    @Override
    public long inboundTotalBetween(long orgId, String beginDay, String endDay) {
        Long n = jdbc.queryForObject(
                "select count(*) from market_inbound where org_id = ?"
                        + " and date(created_at) >= ? and date(created_at) <= ?",
                Long.class, orgId, beginDay, endDay);
        return n == null ? 0 : n;
    }
}
