package com.yingzao.addon.customer;

import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.core.eventbus.YzEventBus;
import com.yingzao.core.web.BizException;

/**
 * 客源服务:登记 / 列表 / 转化。
 * 转化在事务内更新状态并经内核事件总线发布 LeadConvertedEvent(outbox 落库与业务同事务,
 * 提交后立即投递;失败可重试直至死信可见),客户档案 addon 以总线 SPI 消费器建档。
 */
@Service
public class LeadService {

    public static final int CODE_LEAD_NOT_FOUND = 210001;
    public static final int CODE_LEAD_ALREADY_CONVERTED = 210002;
    /** 撞单策略 2:同手机号未转化客源已存在(B 类#1,组织级可配,缺省=允许重复)。 */
    public static final int CODE_LEAD_DUP_PHONE = 210008;

    /** 撞单策略档位(0 允许重复/1 同手机号合并回显/2 同手机号拒绝)。 */
    public static final int DUP_ALLOW = 0;
    public static final int DUP_MERGE = 1;
    public static final int DUP_REJECT = 2;

    public static final int STATUS_INITIAL = 0;
    public static final int STATUS_CONVERTED = 1;

    private final JdbcTemplate jdbc;
    private final YzEventBus bus;
    private final MessageSource messages;

    public LeadService(JdbcTemplate jdbc, YzEventBus bus, MessageSource messages) {
        this.jdbc = jdbc;
        this.bus = bus;
        this.messages = messages;
    }

    /**
     * 登记客源(撞单策略闸,B 类#1):按组织配置对「同手机号未转化客源」执行
     * 合并回显(策略 1,返回既有 leadId,接口兼容)/拒绝(策略 2,210008);
     * 缺省 0 允许多渠道重复登记(现行为)。承接链(market 进线)经本方法同闸。
     */
    public Long create(long orgId, String name, String phone, String source, String note) {
        int strategy = dupStrategy(orgId);
        if (strategy == DUP_MERGE || strategy == DUP_REJECT) {
            List<Map<String, Object>> exist = jdbc.queryForList(
                    "select id from customer_lead where org_id = ? and phone = ? and status = ?"
                            + " order by id desc limit 1",
                    orgId, phone == null ? "" : phone.trim(), STATUS_INITIAL);
            if (!exist.isEmpty()) {
                if (strategy == DUP_MERGE) {
                    return ((Number) exist.get(0).get("id")).longValue();
                }
                throw new BizException(CODE_LEAD_DUP_PHONE,
                        messages.getMessage("lead.dupPhone", null, LocaleContextHolder.getLocale()));
            }
        }
        jdbc.update("insert into customer_lead(org_id, name, phone, source, note, status) values (?,?,?,?,?,0)",
                orgId, name, phone, source, note);
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    private void require(boolean cond, int code, String i18nKey) {
        if (!cond) {
            throw new BizException(code,
                    messages.getMessage(i18nKey, null, LocaleContextHolder.getLocale()));
        }
    }

    /** 组织撞单策略(懒初始化,缺省 0 允许重复)。 */
    public int dupStrategy(long orgId) {
        try {
            jdbc.update("insert ignore into customer_config (org_id, dup_strategy) values (?, 0)", orgId);
        } catch (DuplicateKeyException ignored) {
            // 并发懒初始化
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select dup_strategy from customer_config where org_id = ?", orgId);
        return rows.isEmpty() ? DUP_ALLOW : ((Number) rows.get(0).get("dup_strategy")).intValue();
    }

    /** 更新组织撞单策略(0/1/2)。 */
    public void saveDupStrategy(long orgId, Integer strategy) {
        require(strategy != null && strategy >= DUP_ALLOW && strategy <= DUP_REJECT,
                CODE_LEAD_DUP_PHONE, "lead.dupPhone");
        jdbc.update("insert ignore into customer_config (org_id, dup_strategy) values (?, 0)", orgId);
        jdbc.update("update customer_config set dup_strategy = ? where org_id = ?", strategy, orgId);
    }

    public List<Map<String, Object>> list(long orgId) {
        return jdbc.queryForList(
                "select id, name, phone, source, note, status, created_at from customer_lead where org_id = ? order by id desc",
                orgId);
    }

    @Transactional
    public void convert(long orgId, long leadId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, name, phone, status from customer_lead where id = ? and org_id = ? limit 1", leadId, orgId);
        if (rows.isEmpty()) {
            throw new BizException(CODE_LEAD_NOT_FOUND,
                    messages.getMessage("lead.notfound", null, LocaleContextHolder.getLocale()));
        }
        Map<String, Object> lead = rows.get(0);
        if (((Number) lead.get("status")).intValue() != STATUS_INITIAL) {
            throw new BizException(CODE_LEAD_ALREADY_CONVERTED,
                    messages.getMessage("lead.alreadyConverted", null, LocaleContextHolder.getLocale()));
        }
        jdbc.update("update customer_lead set status = 1 where id = ?", leadId);
        // eventKey 以 leadId 为幂等锚点:一单只转化一次(上方状态守卫保证),重试/requeue 重放安全
        bus.publish(new com.yingzao.addons.api.event.LeadConvertedEvent(leadId, orgId,
                (String) lead.get("name"), (String) lead.get("phone")), "lead-converted-" + leadId);
    }
}
