package com.yingzao.addon.customerprofile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.addons.api.event.CustomerCreatedEvent;
import com.yingzao.core.security.CryptoBox;
import com.yingzao.core.web.BizException;

/**
 * 客户主档案服务:一人一档,手机号 = 身份锚点。
 * 老系统四条核心规约落地:纯手机查重(existCustomer get-or-create 幂等回显)/
 * 唯一键下沉 DB(uk(org,active_hmac) 只锁启用中号码,修「禁用号可再建档」漏洞)/
 * 解密必留痕(reveal 接口强制写审计)/ 默认号码显式切换(修「加号即抢默认」副作用),
 * 并守住老系统两颗雷:updateCustomer 拼音条件反转 NPE(本实现无拼音列,动态 set 仅非 null 字段)、
 * 改名断链(更新直接生效,客源插件经 CustomerCreatedEvent/HouseCreatedEvent 之外的查询契约读取主档)。
 */
@Service
public class CustomerService {

    public static final int CODE_CUSTOMER_NOT_FOUND = 330005;
    public static final int CODE_PHONE_EXISTS = 330006;
    public static final int CODE_PHONE_NOT_FOUND = 330007;
    public static final int CODE_DEFAULT_PHONE_UNDISABLEABLE = 330008;
    public static final int CODE_DISABLED_PHONE_UNDEFAULTABLE = 330009;
    public static final int CODE_INVALID_PARAM = 330017;

    public static final int STATUS_INITIAL = 0;
    public static final int STATUS_VERIFIED = 1;

    public static final int PHONE_ENABLED = 0;
    public static final int PHONE_DISABLED = 1;

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final CryptoBox crypto;
    private final ApplicationEventPublisher publisher;

    public CustomerService(JdbcTemplate jdbc, MessageSource messages, CryptoBox crypto,
            ApplicationEventPublisher publisher) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.crypto = crypto;
        this.publisher = publisher;
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private void require(boolean cond, int code, String msgKey) {
        if (!cond) {
            throw new BizException(code, msg(msgKey));
        }
    }

    // ---- 建档 ----

    public record CreateCustomerCmd(String name, String phone, String gender, String address, String identity) {
    }

    @Transactional
    public long create(long orgId, CreateCustomerCmd cmd, Long createdBy) {
        require(cmd.name() != null && !cmd.name().isBlank(), CODE_INVALID_PARAM, "param.invalid");
        require(cmd.phone() != null && !cmd.phone().isBlank(), CODE_INVALID_PARAM, "param.invalid");
        requireActivePhoneFree(orgId, cmd.phone());
        long id = insertCustomer(orgId, cmd, createdBy);
        insertPhone(orgId, id, cmd.phone(), true);
        publisher.publishEvent(new CustomerCreatedEvent(id, orgId, cmd.name().trim(), crypto.hmac(cmd.phone())));
        return id;
    }

    /** get-or-create:启用中的同号存在即返回已有档(幂等),否则建档;跨插件与客源转化的唯一建档入口。 */
    @Transactional
    public Map<String, Object> exist(long orgId, String phone, String name, Long createdBy) {
        require(phone != null && !phone.isBlank(), CODE_INVALID_PARAM, "param.invalid");
        String hmac = crypto.hmac(phone.trim());
        List<Map<String, Object>> found = jdbc.queryForList("""
                select p.customer_id from customerprofile_customer_phone p
                where p.org_id = ? and p.active_hmac = ? limit 1
                """, orgId, hmac);
        if (!found.isEmpty()) {
            return existResult(((Number) found.get(0).get("customer_id")).longValue(), false);
        }
        require(name != null && !name.isBlank(), CODE_INVALID_PARAM, "param.invalid");
        long id = create(orgId, new CreateCustomerCmd(name, phone, null, null, null), createdBy);
        return existResult(id, true);
    }

    private Map<String, Object> existResult(long customerId, boolean created) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("customerId", customerId);
        m.put("created", created);
        return m;
    }

    private long insertCustomer(long orgId, CreateCustomerCmd cmd, Long createdBy) {
        try {
            jdbc.update("""
                    insert into customerprofile_customer
                        (org_id, no, name, gender, phone_cipher, phone_hmac, identity_cipher, address,
                         status, created_by)
                    values (?, concat('P', left(md5(uuid()), 12)), ?, ?, ?, ?, ?, ?, ?, ?)
                    """, orgId, cmd.name().trim(), cmd.gender(),
                    crypto.encrypt(cmd.phone()), crypto.hmac(cmd.phone()),
                    cmd.identity() == null || cmd.identity().isBlank() ? null : crypto.encrypt(cmd.identity()),
                    cmd.address(), STATUS_INITIAL, createdBy);
            long id = jdbc.queryForObject("select last_insert_id()", Long.class);
            // 编号按 id 生成,无计数器读改写竞态(老系统 counting 取号教训)
            jdbc.update("update customerprofile_customer set no = concat('C', lpad(id, 8, '0')) where id = ?", id);
            return id;
        } catch (DuplicateKeyException e) {
            throw new BizException(CODE_PHONE_EXISTS, msg("phone.exists"));
        }
    }

    private void insertPhone(long orgId, long customerId, String phone, boolean isDefault) {
        String hmac = crypto.hmac(phone.trim());
        try {
            jdbc.update("""
                    insert into customerprofile_customer_phone
                        (org_id, customer_id, phone_cipher, phone_hmac, active_hmac, status, is_default)
                    values (?,?,?,?,?,?,?)
                    """, orgId, customerId, crypto.encrypt(phone.trim()), hmac,
                    hmac, PHONE_ENABLED, isDefault ? 1 : 0);
        } catch (DuplicateKeyException e) {
            throw new BizException(CODE_PHONE_EXISTS, msg("phone.exists"));
        }
        if (isDefault) {
            mirrorDefaultPhone(customerId, phone.trim(), hmac);
        }
    }

    private void mirrorDefaultPhone(long customerId, String phone, String hmac) {
        jdbc.update("update customerprofile_customer set phone_cipher = ?, phone_hmac = ? where id = ?",
                crypto.encrypt(phone), hmac, customerId);
    }

    private void requireActivePhoneFree(long orgId, String phone) {
        Integer count = jdbc.queryForObject("""
                select count(*) from customerprofile_customer_phone
                where org_id = ? and active_hmac = ?
                """, Integer.class, orgId, crypto.hmac(phone.trim()));
        if (count != null && count > 0) {
            throw new BizException(CODE_PHONE_EXISTS, msg("phone.exists"));
        }
    }

    // ---- 查询 ----

    public Map<String, Object> page(long orgId, String keyword, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(" where c.org_id = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(orgId);
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (c.name like concat('%', ?, '%') or c.phone_hmac = ?)");
            args.add(keyword.trim());
            args.add(crypto.hmac(keyword.trim()));
        }
        Long total = jdbc.queryForObject(
                "select count(*) from customerprofile_customer c" + where, Long.class, args.toArray());
        List<Object> allArgs = new java.util.ArrayList<>(args);
        allArgs.add(safeSize);
        allArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select c.id, c.no, c.name, c.gender, c.phone_hmac, c.address, c.status, c.created_at
                from customerprofile_customer c
                """ + where + " order by c.id desc limit ? offset ?",
                allArgs.toArray());
        rows.forEach(r -> r.remove("phone_hmac"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    public Map<String, Object> get(long orgId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select c.id, c.no, c.name, c.gender, c.phone_cipher, c.address, c.status,
                       c.created_at, c.updated_at
                from customerprofile_customer c where c.id = ? and c.org_id = ?
                """, id, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_CUSTOMER_NOT_FOUND, msg("customer.notfound"));
        }
        Map<String, Object> customer = rows.get(0);
        String phone = crypto.decrypt((String) customer.remove("phone_cipher"));
        customer.put("phoneMasked", CryptoBox.maskPhone(phone));
        return customer;
    }

    /** 更新:非空白才覆盖(身份证传空串 = 清空);修复老系统「不带 name 的更新必触雷」。 */
    public void update(long orgId, long id, String name, String gender, String address, String identity) {
        require(customerExists(orgId, id), CODE_CUSTOMER_NOT_FOUND, "customer.notfound");
        Map<String, Object> current = jdbc.queryForMap("""
                select name, gender, address, identity_cipher
                from customerprofile_customer where id = ? and org_id = ?
                """, id, orgId);
        boolean clearIdentity = identity != null && identity.isBlank();
        jdbc.update("""
                update customerprofile_customer
                set name = ?, gender = ?, address = ?, identity_cipher = ?
                where id = ? and org_id = ?
                """,
                firstNonBlank(name, current.get("name")),
                firstNonNull(gender, current.get("gender")),
                firstNonNull(address, current.get("address")),
                clearIdentity ? null
                        : firstNonNull(identity == null ? null : crypto.encrypt(identity),
                                current.get("identity_cipher")),
                id, orgId);
    }

    private Object firstNonBlank(String value, Object fallback) {
        return value != null && !value.isBlank() ? value.trim() : fallback;
    }

    private Object firstNonNull(Object value, Object fallback) {
        return value != null ? value : fallback;
    }

    private boolean customerExists(long orgId, long id) {
        Integer count = jdbc.queryForObject(
                "select count(*) from customerprofile_customer where id = ? and org_id = ?", Integer.class, id, orgId);
        return count != null && count > 0;
    }

    // ---- 电话管理 ----

    @Transactional
    public long addPhone(long orgId, long customerId, String phone) {
        require(phone != null && !phone.isBlank(), CODE_INVALID_PARAM, "param.invalid");
        require(customerExists(orgId, customerId), CODE_CUSTOMER_NOT_FOUND, "customer.notfound");
        requireActivePhoneFree(orgId, phone);
        Integer count = jdbc.queryForObject(
                "select count(*) from customerprofile_customer_phone where customer_id = ?",
                Integer.class, customerId);
        boolean first = count == null || count == 0;
        insertPhone(orgId, customerId, phone, first);
        long id = jdbc.queryForObject("""
                select id from customerprofile_customer_phone
                where customer_id = ? and phone_hmac = ? limit 1
                """, Long.class, customerId, crypto.hmac(phone.trim()));
        return id;
    }

    /** 号码列表(脱敏,明文必须走 reveal 留痕接口)。 */
    public List<Map<String, Object>> phones(long orgId, long customerId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, phone_cipher, status, is_default, created_at
                from customerprofile_customer_phone
                where org_id = ? and customer_id = ? order by is_default desc, id
                """, orgId, customerId);
        rows.forEach(r -> {
            String plaintext = crypto.decrypt((String) r.remove("phone_cipher"));
            r.put("phoneMasked", CryptoBox.maskPhone(plaintext));
        });
        return rows;
    }

    @Transactional
    public void setDefaultPhone(long orgId, long customerId, long phoneId) {
        Map<String, Object> phone = loadPhone(orgId, customerId, phoneId);
        if (((Number) phone.get("status")).intValue() == PHONE_DISABLED) {
            throw new BizException(CODE_DISABLED_PHONE_UNDEFAULTABLE, msg("phone.disabledUndefaultable"));
        }
        jdbc.update("update customerprofile_customer_phone set is_default = 0 where customer_id = ?", customerId);
        jdbc.update("update customerprofile_customer_phone set is_default = 1 where id = ?", phoneId);
        mirrorDefaultPhone(customerId,
                crypto.decrypt((String) phone.get("phone_cipher")), (String) phone.get("phone_hmac"));
    }

    @Transactional
    public void setPhoneStatus(long orgId, long customerId, long phoneId, int status) {
        require(status == PHONE_ENABLED || status == PHONE_DISABLED, CODE_INVALID_PARAM, "param.invalid");
        Map<String, Object> phone = loadPhone(orgId, customerId, phoneId);
        boolean isDefault = ((Number) phone.get("is_default")).intValue() == 1;
        if (status == PHONE_DISABLED && isDefault) {
            throw new BizException(CODE_DEFAULT_PHONE_UNDISABLEABLE, msg("phone.defaultUndisableable"));
        }
        String newActive = status == PHONE_ENABLED ? (String) phone.get("phone_hmac") : null;
        try {
            jdbc.update("""
                    update customerprofile_customer_phone set status = ?, active_hmac = ? where id = ?
                    """, status, newActive, phoneId);
        } catch (DuplicateKeyException e) {
            // 启用时撞「启用中号码唯一」:该号已是他人启用号码
            throw new BizException(CODE_PHONE_EXISTS, msg("phone.exists"));
        }
    }

    /** 解密查看(唯一明文出口):强制写审计——无留痕不出数。 */
    public Map<String, Object> revealPhone(long orgId, long customerId, long phoneId, long accessedBy, String purpose) {
        Map<String, Object> phone = loadPhone(orgId, customerId, phoneId);
        jdbc.update("""
                insert into customerprofile_phone_access_log(org_id, customer_id, phone_id, accessed_by, purpose)
                values (?,?,?,?,?)
                """, orgId, customerId, phoneId, accessedBy, purpose);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("phone", crypto.decrypt((String) phone.get("phone_cipher")));
        return result;
    }

    public Map<String, Object> accessLogs(long orgId, long customerId, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Long total = jdbc.queryForObject(
                "select count(*) from customerprofile_phone_access_log where org_id = ? and customer_id = ?",
                Long.class, orgId, customerId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select l.id, l.phone_id, l.accessed_by, u.display_name as accessed_by_name,
                       l.purpose, l.created_at
                from customerprofile_phone_access_log l
                left join yz_user u on u.id = l.accessed_by
                where l.org_id = ? and l.customer_id = ?
                order by l.id desc limit ? offset ?
                """, orgId, customerId, safeSize, (safePage - 1) * safeSize);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    private Map<String, Object> loadPhone(long orgId, long customerId, long phoneId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, phone_cipher, phone_hmac, status, is_default
                from customerprofile_customer_phone
                where id = ? and customer_id = ? and org_id = ?
                """, phoneId, customerId, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_PHONE_NOT_FOUND, msg("phone.notfound"));
        }
        return rows.get(0);
    }
}
