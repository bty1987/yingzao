package com.yingzao.addon.customerprofile;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 客户档案服务:订阅客源转化事件(事务提交后)自动建档,幂等(lead_id 唯一)。
 */
@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    private final JdbcTemplate jdbc;

    public ProfileService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onLeadConverted(com.yingzao.addons.api.event.LeadConvertedEvent event) {
        jdbc.update("""
                insert into customer_profile(org_id, lead_id, name, phone)
                values (?,?,?,?)
                on duplicate key update name = values(name), phone = values(phone)
                """, event.orgId(), event.leadId(), event.name(), event.phone());
        log.info("customer profile upserted from lead {} (org {})", event.leadId(), event.orgId());
    }

    public List<Map<String, Object>> list(long orgId) {
        return jdbc.queryForList(
                "select id, lead_id, name, phone, created_at from customer_profile where org_id = ? order by id desc",
                orgId);
    }
}
