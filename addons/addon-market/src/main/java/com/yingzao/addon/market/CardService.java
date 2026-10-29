package com.yingzao.addon.market;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.addons.api.event.CardNoticeCreatedEvent;
import com.yingzao.core.eventbus.YzEventBus;
import com.yingzao.core.security.CryptoBox;
import com.yingzao.core.web.BizException;

/**
 * 电子名片子域(D6b 精简版,docs/prd/addon-market-card.md 按推荐拍板;280100 段)。
 *
 * 老缺陷收敛(重做即修复):一人一卡 uk(org,user)(280102);手机 AES+hash 双列全域红线;
 * 卡片与访客解耦(零解引用);留资同人同卡幂等回显(HMAC 锚)+频次防刷(同 hash 60s 内 1 条/
 * 日 10 条,查询期计算零新表);已读守卫=名片主人(修老任意写);删名片级联相册与留痕;
 * 留资通知=同事务发 CardNoticeCreatedEvent(零网络出事务,通知中心消费)——修老事务内同步
 * httpGet 明文 PII 外发三重违例;isv 配置键两义拆 enabled/notifyUrl 两键 V1 不启用(扩展点)。
 */
@Service
public class CardService {

    public static final int ST_ENABLED = 0;
    public static final int ST_DISABLED = 1;
    public static final int NOTICE_PENDING = 0;
    public static final int NOTICE_READ = 1;

    public static final int CODE_NOT_FOUND = 280101;
    public static final int CODE_DUP = 280102;
    public static final int CODE_DISABLED = 280103;
    public static final int CODE_CONTENT = 280104;
    public static final int CODE_PHOTO_NOT_FOUND = 280105;
    public static final int CODE_FORBIDDEN = 280106;
    public static final int CODE_NOTICE_NOT_FOUND = 280107;
    public static final int CODE_NOTICE_STATE = 280108;
    public static final int CODE_TOKEN = 280109;
    public static final int CODE_RATE = 280110;

    private static final Pattern PHONE = Pattern.compile("^1\\d{10}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    public record CardCmd(String userName, String phone, String wechat, String industry,
            String profession, String address, String info, String userPhoto) {
    }

    public record NoticeCmd(String name, String phone, String comment) {
    }

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final CryptoBox crypto;
    private final YzEventBus eventBus;

    public CardService(JdbcTemplate jdbc, MessageSource messages, CryptoBox crypto, YzEventBus eventBus) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.crypto = crypto;
        this.eventBus = eventBus;
    }

    // ---- 管理端:名片 CRUD(一人一卡) ----

    @Transactional
    public long create(long orgId, long userId, CardCmd cmd) {
        require(cmd != null && notBlank(cmd.userName()) && cmd.userName().trim().length() <= 64,
                CODE_CONTENT, "market.card.content");
        require(cmd.phone() != null && PHONE.matcher(cmd.phone().trim()).matches(),
                CODE_CONTENT, "market.card.content");
        Integer dup = jdbc.queryForObject(
                "select count(*) from market_card where org_id = ? and user_id = ?",
                Integer.class, orgId, userId);
        require(dup == null || dup == 0, CODE_DUP, "market.card.dup");
        jdbc.update("""
                insert into market_card(org_id, user_id, user_photo, user_name, phone_cipher, phone_hash,
                    wechat, industry, profession, address, info, status, share_token)
                values (?,?,?,?,?,?,?,?,?,?,?,0,?)""",
                orgId, userId, blankToNull(cmd.userPhoto()), cmd.userName().trim(),
                crypto.encrypt(cmd.phone().trim()), crypto.hmac(cmd.phone().trim()),
                blankToNull(cmd.wechat()), blankToNull(cmd.industry()), blankToNull(cmd.profession()),
                blankToNull(cmd.address()), blankToNull(cmd.info()), newToken());
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    @Transactional
    public void update(long orgId, long userId, long cardId, CardCmd cmd) {
        Map<String, Object> card = loadOwned(orgId, userId, cardId);
        require(cmd != null && notBlank(cmd.userName()) && cmd.userName().trim().length() <= 64,
                CODE_CONTENT, "market.card.content");
        String phone = cmd.phone() == null ? null : cmd.phone().trim();
        require(phone == null || PHONE.matcher(phone).matches(), CODE_CONTENT, "market.card.content");
        if (phone == null) { // 手机不变更(改手机=重置留资幂等锚,V1 不开放)
            jdbc.update("""
                    update market_card set user_photo = ?, user_name = ?, wechat = ?, industry = ?,
                        profession = ?, address = ?, info = ? where id = ?""",
                    blankToNull(cmd.userPhoto()), cmd.userName().trim(), blankToNull(cmd.wechat()),
                    blankToNull(cmd.industry()), blankToNull(cmd.profession()),
                    blankToNull(cmd.address()), blankToNull(cmd.info()), card.get("id"));
        } else {
            jdbc.update("""
                    update market_card set user_photo = ?, user_name = ?, phone_cipher = ?, phone_hash = ?,
                        wechat = ?, industry = ?, profession = ?, address = ?, info = ? where id = ?""",
                    blankToNull(cmd.userPhoto()), cmd.userName().trim(), crypto.encrypt(phone),
                    crypto.hmac(phone), blankToNull(cmd.wechat()), blankToNull(cmd.industry()),
                    blankToNull(cmd.profession()), blankToNull(cmd.address()), blankToNull(cmd.info()),
                    card.get("id"));
        }
    }

    /** 重置分享令牌(旧链接失效;不换卡 id——核对清单 4)。 */
    @Transactional
    public String resetToken(long orgId, long userId, long cardId) {
        loadOwned(orgId, userId, cardId);
        String token = newToken();
        jdbc.update("update market_card set share_token = ? where id = ?", token, cardId);
        return token;
    }

    @Transactional
    public void status(long orgId, long userId, long cardId, Integer status) {
        loadOwned(orgId, userId, cardId);
        require(status != null && (status == ST_ENABLED || status == ST_DISABLED), CODE_CONTENT,
                "market.card.content");
        jdbc.update("update market_card set status = ? where id = ?", status, cardId);
    }

    /** 删除名片(级联相册+留资;老孤儿图片修法;文件实体清理归 OSS 策略)。 */
    @Transactional
    public void delete(long orgId, long userId, long cardId) {
        loadOwned(orgId, userId, cardId);
        jdbc.update("delete from market_card_photo where card_id = ?", cardId);
        jdbc.update("delete from market_card_notice where card_id = ?", cardId);
        jdbc.update("delete from market_card where id = ?", cardId);
    }

    public Map<String, Object> mine(long orgId, long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, user_photo, user_name, wechat, industry, profession, address, info,
                       status, share_token, created_at
                from market_card where org_id = ? and user_id = ?""", orgId, userId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> card = rows.get(0);
        card.put("photos", photosOf(cardId(card)));
        card.put("pending", jdbc.queryForObject("""
                select count(*) from market_card_notice where card_id = ? and status = ?""",
                Integer.class, card.get("id"), NOTICE_PENDING));
        return card;
    }

    /** 留资列表(名片主人;admin 全员视角经 listAll)。 */
    public Map<String, Object> notices(long orgId, long userId, long cardId, Integer status,
            int page, int size) {
        loadOwned(orgId, userId, cardId);
        return noticePage(orgId, cardId, status, page, size);
    }

    /** 全员留资列表(主管视角;/market/cards 菜单闸内)。 */
    public Map<String, Object> noticesAll(long orgId, Integer status, int page, int size) {
        return noticePage(orgId, null, status, page, size);
    }

    /** 已读(守卫=名片主人,修老 status 任意写)。 */
    @Transactional
    public void readNotice(long orgId, long userId, long noticeId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select n.id, n.card_id, n.status from market_card_notice n
                join market_card c on c.id = n.card_id
                where n.id = ? and n.org_id = ? and c.user_id = ?""", noticeId, orgId, userId);
        require(!rows.isEmpty(), CODE_NOTICE_NOT_FOUND, "market.card.noticeNotFound");
        require(((Number) rows.get(0).get("status")).intValue() == NOTICE_PENDING,
                CODE_NOTICE_STATE, "market.card.noticeState");
        jdbc.update("""
                update market_card_notice set status = ?, reader_id = ?, read_time = now()
                where id = ? and status = ?""", NOTICE_READ, userId, noticeId, NOTICE_PENDING);
    }

    // ---- 相册 ----

    @Transactional
    public long addPhoto(long orgId, long userId, long cardId, String url, int sortNo) {
        loadOwned(orgId, userId, cardId);
        require(notBlank(url) && url.length() <= 500, CODE_CONTENT, "market.card.content");
        jdbc.update("insert into market_card_photo(org_id, card_id, url, sort_no) values (?,?,?,?)",
                orgId, cardId, url.trim(), sortNo);
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    @Transactional
    public void deletePhoto(long orgId, long userId, long cardId, long photoId) {
        loadOwned(orgId, userId, cardId);
        int updated = jdbc.update("delete from market_card_photo where id = ? and card_id = ?",
                photoId, cardId);
        require(updated > 0, CODE_PHOTO_NOT_FOUND, "market.card.photoNotFound");
    }

    // ---- 匿名前台(访客免登录;org 上下文=配置键,V1 单组织) ----

    /** 名片展示投影(相册全量+脱敏无——名片用途即公开联系方式,展示给主动访问的访客)。 */
    public Map<String, Object> byToken(long orgId, String token) {
        Map<String, Object> card = loadByToken(orgId, token);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("userName", card.get("user_name"));
        view.put("userPhoto", card.get("user_photo"));
        view.put("phone", crypto.decrypt((String) card.get("phone_cipher")));
        view.put("wechat", card.get("wechat"));
        view.put("industry", card.get("industry"));
        view.put("profession", card.get("profession"));
        view.put("address", card.get("address"));
        view.put("info", card.get("info"));
        view.put("photos", photosOf(cardId(card)));
        return view;
    }

    /**
     * 留资:校验→同人同卡幂等回显(hash 锚,对齐获客链惯例)→频次防刷(同 hash 60s 内 1 条
     * /日 10 条)→落库→同事务发 CardNoticeCreatedEvent(零网络出事务)。
     */
    @Transactional
    public Map<String, Object> submitNotice(long orgId, String token, NoticeCmd cmd) {
        Map<String, Object> card = loadByToken(orgId, token);
        require(cmd != null && notBlank(cmd.name()) && cmd.name().trim().length() <= 64,
                CODE_CONTENT, "market.card.content");
        require(cmd.phone() != null && PHONE.matcher(cmd.phone().trim()).matches(),
                CODE_CONTENT, "market.card.content");
        require(cmd.comment() == null || cmd.comment().length() <= 500, CODE_CONTENT,
                "market.card.content");
        long cardId = cardId(card);
        String phone = cmd.phone().trim();
        String phoneHash = crypto.hmac(phone);
        List<Map<String, Object>> dup = jdbc.queryForList("""
                select id from market_card_notice
                where card_id = ? and phone_hash = ? and status in (0, 1) limit 1""", cardId, phoneHash);
        if (!dup.isEmpty()) { // 同人同卡重复留资=幂等回显非报错(对齐获客链幂等惯例)
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("noticeId", ((Number) dup.get(0).get("id")).longValue());
            result.put("duplicate", true);
            return result;
        }
        // 频次防刷(修老无任何闸):60s 内同 hash 恒 1 条 + 当日同 hash ≤10 条
        Integer recent = jdbc.queryForObject("""
                select count(*) from market_card_notice
                where card_id = ? and phone_hash = ? and created_at > date_sub(now(), interval 60 second)""",
                Integer.class, cardId, phoneHash);
        require(recent == null || recent == 0, CODE_RATE, "market.card.rate");
        Integer today = jdbc.queryForObject("""
                select count(*) from market_card_notice
                where card_id = ? and phone_hash = ? and created_at >= curdate()""",
                Integer.class, cardId, phoneHash);
        require(today == null || today < 10, CODE_RATE, "market.card.rate");
        jdbc.update("""
                insert into market_card_notice(org_id, card_id, visitor_id, name, phone_cipher, phone_hash,
                    comment, status) values (?,?,null,?,?,?,?,0)""",
                orgId, cardId, cmd.name().trim(), crypto.encrypt(phone), phoneHash,
                blankToNull(cmd.comment()));
        long noticeId = jdbc.queryForObject("select last_insert_id()", Long.class);
        eventBus.publish(new CardNoticeCreatedEvent(orgId, cardId, noticeId),
                "card-notice:" + noticeId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("noticeId", noticeId);
        result.put("duplicate", false);
        return result;
    }

    // ---- 内部 ----

    private Map<String, Object> loadOwned(long orgId, long userId, long cardId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, status, share_token from market_card where id = ? and org_id = ? and user_id = ?",
                cardId, orgId, userId);
        if (rows.isEmpty()) { // 存在但非本人=280106(防误操作);不存在=280101
            Integer exists = jdbc.queryForObject(
                    "select count(*) from market_card where id = ? and org_id = ?",
                    Integer.class, cardId, orgId);
            require(exists != null && exists == 0, CODE_FORBIDDEN, "market.card.forbidden");
            throw new BizException(CODE_NOT_FOUND, msg("market.card.notFound"));
        }
        return rows.get(0);
    }

    private Map<String, Object> loadByToken(long orgId, String token) {
        require(notBlank(token) && token.length() <= 64, CODE_TOKEN, "market.card.token");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, user_name, user_photo, phone_cipher, wechat, industry, profession, address,
                       info, status from market_card where org_id = ? and share_token = ?""",
                orgId, token.trim());
        require(!rows.isEmpty(), CODE_TOKEN, "market.card.token");
        Map<String, Object> card = rows.get(0);
        require(((Number) card.get("status")).intValue() == ST_ENABLED, CODE_DISABLED,
                "market.card.disabled");
        return card;
    }

    private Map<String, Object> noticePage(long orgId, Long cardId, Integer status, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(" where n.org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        if (cardId != null) {
            where.append(" and n.card_id = ?");
            args.add(cardId);
        }
        if (status != null) {
            where.append(" and n.status = ?");
            args.add(status);
        }
        Long total = jdbc.queryForObject(
                "select count(*) from market_card_notice n" + where, Long.class, args.toArray());
        List<Object> allArgs = new ArrayList<>(args);
        allArgs.add(safeSize);
        allArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select n.id, n.card_id, c.user_name as card_user_name, n.name, n.phone_cipher,
                       n.comment, n.status, n.read_time, n.created_at
                from market_card_notice n join market_card c on c.id = n.card_id""" + where
                + " order by n.id desc limit ? offset ?", allArgs.toArray());
        for (Map<String, Object> row : rows) { // 密文列解密展示(管理端本人/主管视角)
            row.put("phone", crypto.decrypt((String) row.remove("phone_cipher")));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    private List<Map<String, Object>> photosOf(long cardId) {
        return jdbc.queryForList(
                "select id, url, sort_no from market_card_photo where card_id = ? order by sort_no, id",
                cardId);
    }

    private long cardId(Map<String, Object> card) {
        return ((Number) card.get("id")).longValue();
    }

    private String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    private String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private void require(boolean cond, int code, String key) {
        if (!cond) {
            throw new BizException(code, msg(key));
        }
    }
}
