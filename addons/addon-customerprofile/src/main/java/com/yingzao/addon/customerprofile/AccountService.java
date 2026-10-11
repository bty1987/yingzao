package com.yingzao.addon.customerprofile;

import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.core.security.CryptoBox;
import com.yingzao.core.web.BizException;

/**
 * 客户收款银行账户:档案非余额;账号密文 + HMAC 去重;(客户,账号) 唯一;
 * 首账户自动默认,默认显式切换;停用 = 软删,默认账户不可停用。
 * 单一事实源:不与客户主档做对公六要素双向同步(老系统互触发风险不迁)。
 */
@Service
public class AccountService {

    public static final int CODE_ACCOUNT_NOT_FOUND = 330010;
    public static final int CODE_DEFAULT_ACCOUNT_UNDISABLEABLE = 330011;
    public static final int CODE_ACCOUNT_EXISTS = 330012;
    public static final int CODE_INVALID_PARAM = 330017;

    public static final int TYPE_PRIVATE = 0;
    public static final int TYPE_PUBLIC = 1;

    public static final int STATUS_ENABLED = 0;
    public static final int STATUS_DISABLED = 1;

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final CryptoBox crypto;

    public AccountService(JdbcTemplate jdbc, MessageSource messages, CryptoBox crypto) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.crypto = crypto;
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private void require(boolean cond, int code, String msgKey) {
        if (!cond) {
            throw new BizException(code, msg(msgKey));
        }
    }

    public record AddAccountCmd(Integer type, String holderName, String bankName, String accountNo) {
    }

    @Transactional
    public long add(long orgId, long customerId, AddAccountCmd cmd) {
        require(cmd.holderName() != null && !cmd.holderName().isBlank(), CODE_INVALID_PARAM, "param.invalid");
        require(cmd.accountNo() != null && !cmd.accountNo().isBlank(), CODE_INVALID_PARAM, "param.invalid");
        int type = cmd.type() == null ? TYPE_PRIVATE : cmd.type();
        require(type == TYPE_PRIVATE || type == TYPE_PUBLIC, CODE_INVALID_PARAM, "param.invalid");
        Integer customerCount = jdbc.queryForObject(
                "select count(*) from customerprofile_customer where id = ? and org_id = ?",
                Integer.class, customerId, orgId);
        if (customerCount == null || customerCount == 0) {
            throw new BizException(CustomerService.CODE_CUSTOMER_NOT_FOUND, msg("customer.notfound"));
        }
        String hmac = crypto.hmac(cmd.accountNo().trim());
        Integer dup = jdbc.queryForObject(
                "select count(*) from customerprofile_customer_account where customer_id = ? and account_hmac = ?",
                Integer.class, customerId, hmac);
        if (dup != null && dup > 0) {
            throw new BizException(CODE_ACCOUNT_EXISTS, msg("account.exists"));
        }
        Integer count = jdbc.queryForObject(
                "select count(*) from customerprofile_customer_account where customer_id = ?",
                Integer.class, customerId);
        boolean first = count == null || count == 0;
        try {
            jdbc.update("""
                    insert into customerprofile_customer_account
                        (org_id, customer_id, type, holder_name, bank_name, account_cipher, account_hmac,
                         status, is_default)
                    values (?,?,?,?,?,?,?,?,?)
                    """, orgId, customerId, type, cmd.holderName().trim(), cmd.bankName(),
                    crypto.encrypt(cmd.accountNo().trim()), hmac, STATUS_ENABLED, first ? 1 : 0);
        } catch (DuplicateKeyException e) {
            throw new BizException(CODE_ACCOUNT_EXISTS, msg("account.exists"));
        }
        return jdbc.queryForObject("""
                select id from customerprofile_customer_account
                where customer_id = ? and account_hmac = ? limit 1
                """, Long.class, customerId, hmac);
    }

    /** 账号脱敏展示(末 4 位);明文不出档案域。 */
    public List<Map<String, Object>> list(long orgId, long customerId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, type, holder_name, bank_name, account_cipher, status, is_default,
                       created_at, updated_at
                from customerprofile_customer_account
                where org_id = ? and customer_id = ?
                order by is_default desc, id
                """, orgId, customerId);
        rows.forEach(r -> r.put("accountMasked",
                CryptoBox.maskAccount(crypto.decrypt((String) r.remove("account_cipher")))));
        return rows;
    }

    @Transactional
    public void setDefault(long orgId, long customerId, long accountId) {
        Map<String, Object> account = load(orgId, customerId, accountId);
        if (((Number) account.get("status")).intValue() == STATUS_DISABLED) {
            throw new BizException(CODE_INVALID_PARAM, "param.invalid");
        }
        jdbc.update("update customerprofile_customer_account set is_default = 0 where customer_id = ?", customerId);
        jdbc.update("update customerprofile_customer_account set is_default = 1 where id = ?", accountId);
    }

    @Transactional
    public void setStatus(long orgId, long customerId, long accountId, int status) {
        require(status == STATUS_ENABLED || status == STATUS_DISABLED, CODE_INVALID_PARAM, "param.invalid");
        Map<String, Object> account = load(orgId, customerId, accountId);
        if (status == STATUS_DISABLED && ((Number) account.get("is_default")).intValue() == 1) {
            throw new BizException(CODE_DEFAULT_ACCOUNT_UNDISABLEABLE, msg("account.defaultUndisableable"));
        }
        jdbc.update("update customerprofile_customer_account set status = ? where id = ?", status, accountId);
    }

    private Map<String, Object> load(long orgId, long customerId, long accountId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, status, is_default from customerprofile_customer_account
                where id = ? and customer_id = ? and org_id = ?
                """, accountId, customerId, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_ACCOUNT_NOT_FOUND, msg("account.notfound"));
        }
        return rows.get(0);
    }
}
