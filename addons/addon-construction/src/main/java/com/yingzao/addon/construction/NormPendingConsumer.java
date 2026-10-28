package com.yingzao.addon.construction;

import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.event.NormAdditionPendingChangedEvent;
import com.yingzao.core.eventbus.YzEventConsumer;
import com.yingzao.core.eventbus.YzEventRecord;

/**
 * 定额附加待审徽章消费器(总线 SPI):addon-quotation 附加确认线登记/确认/驳回/删除后
 * 发布 NormAdditionPendingChangedEvent(pendingCount=该单该节点当前待审行数),本域把
 * 对应节点排期行 additem_status 置 1/0——替代老系统 quotation 域跨域直写
 * construction schedule 表的违例(事件回写惯例,PRD addon-quotation-norm 拍板 7)。
 * 绝对值写入幂等(事件重放安全);未点火单/模板外节点零行命中=空转。
 * 老口径注记:待审集合只认附加行(source_type=3 且 status=0)——普通报价行天然 status=0
 * 不得计入,否则确认/驳回后无法归零(老 countNormriceItem 注释实证)。
 * 仅注入 JdbcTemplate,无服务依赖无构造环,无需 @Lazy。
 */
@Component
public class NormPendingConsumer implements YzEventConsumer {

    private final JdbcTemplate jdbc;

    public NormPendingConsumer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("NormAdditionPendingChangedEvent");
    }

    @Override
    public void consume(YzEventRecord event) {
        NormAdditionPendingChangedEvent payload = event.payloadAs(NormAdditionPendingChangedEvent.class);
        jdbc.update("""
                update construction_schedule set additem_status = ?
                where org_id = ? and order_id = ? and node_id = ?""",
                payload.pendingCount() != null && payload.pendingCount() > 0 ? 1 : 0,
                payload.orgId(), payload.orderId(), payload.nodeId());
    }
}
