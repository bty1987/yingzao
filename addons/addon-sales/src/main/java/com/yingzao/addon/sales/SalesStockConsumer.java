package com.yingzao.addon.sales;

import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.event.StockManifestCreatedEvent;
import com.yingzao.addons.api.event.StockManifestItemRemovedEvent;
import com.yingzao.addons.api.event.StockPurchaseChangedEvent;
import com.yingzao.core.eventbus.YzEventConsumer;
import com.yingzao.core.eventbus.YzEventRecord;

/**
 * 库存事件回程车(五计数回写,替代老 inventory 直改 sales 表的跨模块直写):
 * 出货(XG)/采购入库(PU)按明细锚点 salesItemId 分别扣 xgnum/加 punum(出货不足 = 超卖,死信可见);
 * 出库单删行回加 xgnum;采购取消/删项释放扣 punum(修老 punum 永不释放)。
 * 计数回写非幂等运算,依赖总线消费确认语义(at-least-once,确认后不重投),严格 exactly-once
 * 待总线幂等表设施 V2——较老系统跨模块裸写已前进一大步。
 */
@Component
public class SalesStockConsumer implements YzEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(SalesStockConsumer.class);

    private final SalesOrderService orders;

    public SalesStockConsumer(@Lazy SalesOrderService orders) {
        this.orders = orders;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("StockManifestCreatedEvent", "StockManifestItemRemovedEvent",
                "StockPurchaseChangedEvent");
    }

    @Override
    public void consume(YzEventRecord event) {
        switch (event.eventType()) {
            case "StockManifestCreatedEvent" -> {
                StockManifestCreatedEvent e = event.payloadAs(StockManifestCreatedEvent.class);
                List<StockManifestCreatedEvent.Item> items = e.items() == null ? List.of() : e.items();
                for (StockManifestCreatedEvent.Item item : items) {
                    if (item.salesItemId() == null) {
                        continue;
                    }
                    if (e.type() != null && e.type() == 3) {
                        orders.onStockOut(e.orgId(), item.salesItemId(), nvl(item.num()));
                    } else if (e.type() != null && e.type() == 6) {
                        orders.onPurchaseInbound(e.orgId(), item.salesItemId(), nvl(item.num()));
                    }
                }
                log.info("sales counters synced for manifest {} (type={})", e.manifestId(), e.type());
            }
            case "StockManifestItemRemovedEvent" -> {
                StockManifestItemRemovedEvent e = event.payloadAs(StockManifestItemRemovedEvent.class);
                if (e.salesItemId() != null) {
                    orders.onStockOutRemoved(e.orgId(), e.salesItemId(), nvl(e.num()));
                }
            }
            case "StockPurchaseChangedEvent" -> {
                StockPurchaseChangedEvent e = event.payloadAs(StockPurchaseChangedEvent.class);
                List<StockPurchaseChangedEvent.Item> items = e.items() == null ? List.of() : e.items();
                for (StockPurchaseChangedEvent.Item item : items) {
                    if (item.salesItemId() != null) {
                        orders.onPurchaseReleased(e.orgId(), item.salesItemId(), nvl(item.num()));
                    }
                }
                log.info("sales punum released for purchase {} (action={})", e.purchaseId(), e.action());
            }
            default -> {
            }
        }
    }

    private static long nvl(Long v) {
        return v == null ? 0 : v;
    }
}
