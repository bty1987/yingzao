package com.yingzao.addon.service;

import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.addons.api.event.ConstructionAcceptedEvent;
import com.yingzao.core.eventbus.YzEventConsumer;
import com.yingzao.core.eventbus.YzEventRecord;

/**
 * 竣工验收联动回访任务(AcceptanceVisitConsumer):消费 ConstructionAcceptedEvent,
 * 组织开关 visit_on_acceptance=1(默认关=行为不变)时生成验收回访任务
 * (biz=acceptance,plan_time=now+acceptance_visit_days);exists 查重(一单一任务,
 * 事件重放/整改复验多次验收均幂等)。消费时实时读开关——事件先于开关注册到达不丢联动
 * (重放场景由事件总线 requeue 补偿)。失败由总线重试。
 */
@Component
public class AcceptanceVisitConsumer implements YzEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AcceptanceVisitConsumer.class);

    private final JdbcTemplate jdbc;
    private final ServiceSupport support;
    private final VisitService visits;

    public AcceptanceVisitConsumer(JdbcTemplate jdbc, ServiceSupport support,
            @Lazy VisitService visits) {
        this.jdbc = jdbc;
        this.support = support;
        this.visits = visits;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("ConstructionAcceptedEvent");
    }

    @Override
    @Transactional
    public void consume(YzEventRecord event) {
        ConstructionAcceptedEvent payload = event.payloadAs(ConstructionAcceptedEvent.class);
        long orgId = payload.orgId();
        if (!support.visitOnAcceptance(orgId)) {
            return;
        }
        Integer exists = jdbc.queryForObject(
                "select count(*) from service_visit_task where org_id = ? and biz_type = 'acceptance'"
                        + " and order_id = ?",
                Integer.class, orgId, payload.orderId());
        if (exists != null && exists > 0) {
            return;
        }
        // 快照经契约(零直查他域表);计划日=now+配置天数
        Map<String, Object> order = support.orderSnapshot(orgId, payload.orderId());
        jdbc.update("""
                insert into service_visit_task(org_id, order_id, order_no, customer_name, biz_type,
                    biz_id, status, plan_time, created_by)
                values (?,?,?,?, 'acceptance', ?, 0, date_add(now(), interval ? day), ?)
                """, orgId, payload.orderId(), order.get("orderNo"), order.get("customerName"),
                payload.acceptanceId(), support.acceptanceVisitDays(orgId), payload.operatorId());
        log.info("acceptance visit task created: org={}, order={}", orgId, payload.orderId());
    }
}
