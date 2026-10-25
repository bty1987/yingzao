package com.yingzao.addon.inventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.addons.api.GoodsApi;
import com.yingzao.addons.api.event.StockManifestCreatedEvent;
import com.yingzao.addons.api.event.StockManifestItemRemovedEvent;
import com.yingzao.core.approval.ApprovalService;
import com.yingzao.core.eventbus.YzEventBus;
import com.yingzao.core.web.BizException;

/**
 * 出入库单(单据面):老 manifest 12 类收敛为类型字典,「单据即库存」语义保留——
 * 建单事务内即时过账,confirm 只是账面标记(老"审核通过才动库存"模型不存在,不引入);
 * 手工建单仅进货(1)/出货(3),其余系统派生落单即确认;冲销 = 反向类型新单(CR/XB),
 * 源单明细 surplus 原子条件扣减(修老 check-then-act 五处竞态);调拨(5)走内核轻量审批,
 * 审批期不占库存(老语义),通过过账/驳回置 3;盘盈亏(11/12)事件闭环(盘点孤表不迁);
 * 套合/套拆(9/10)随套餐场景 V1.1。数量千分位恒正、明细恒非空(修老恒假守卫)。
 */
@Service
public class ManifestService {

    public static final int CODE_NOT_FOUND = 310001;
    public static final int CODE_BAD_STATE = 310002;
    public static final int CODE_PARAM = 310003;
    public static final int CODE_EMPTY_ITEMS = 310030;
    public static final int CODE_SOURCE_SURPLUS = 310033;
    public static final int CODE_TRANSFER_SAME_WH = 310034;
    // ---- 套合/套拆(A5;310035-310036) ----
    public static final int CODE_GOODS_NOT_PACKAGE = 310035;
    public static final int CODE_PACKAGE_NO_MEMBERS = 310036;

    // ---- 类型字典(1-12;9/10 套合套拆 V1.5) ----
    public static final int TYPE_CG = 1;
    public static final int TYPE_CR = 2;
    public static final int TYPE_XG = 3;
    public static final int TYPE_XB = 4;
    public static final int TYPE_DB = 5;
    public static final int TYPE_PU = 6;
    public static final int TYPE_AS = 7;
    public static final int TYPE_AR = 8;
    public static final int TYPE_PM = 9;
    public static final int TYPE_PS = 10;
    public static final int TYPE_CP = 11;
    public static final int TYPE_CL = 12;

    public static final int STATUS_INIT = 0;
    public static final int STATUS_CONFIRMED = 1;
    public static final int STATUS_APPROVING = 2;
    public static final int STATUS_REJECTED = 3;

    public static final String BIZ_ADDON = "inventory";
    public static final String BIZ_TYPE_TRANSFER = "transfer";
    public static final String SOURCE_STOCK_MANIFEST = "stockManifest";
    public static final String SOURCE_REQUISITION = "requisition";
    public static final String SOURCE_PURCHASE = "purchase";
    public static final String SOURCE_PACKAGE = "package";

    private static final Map<Integer, String> PREFIX = Map.ofEntries(
            Map.entry(TYPE_CG, "CG"), Map.entry(TYPE_CR, "CR"), Map.entry(TYPE_XG, "XG"),
            Map.entry(TYPE_XB, "XB"), Map.entry(TYPE_DB, "DB"), Map.entry(TYPE_PU, "PU"),
            Map.entry(TYPE_AS, "AS"), Map.entry(TYPE_AR, "AR"), Map.entry(TYPE_PM, "PM"),
            Map.entry(TYPE_PS, "PS"), Map.entry(TYPE_CP, "CP"), Map.entry(TYPE_CL, "CL"));

    public record ItemCmd(Long goodsId, Long colorId, Long formatId, Long num, Long price,
                          Long warehouseIn, Long warehouseOut, Long salesItemId) {
    }

    public record ReversalLineCmd(Long sourceItemId, Long num) {
    }

    public record TransferLineCmd(Long goodsId, Long colorId, Long formatId, Long num,
                                  Long warehouseOut, Long warehouseIn) {
    }

    public record AdjustItemCmd(Long goodsId, Long colorId, Long formatId, Long warehouseId, Long num) {
    }

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final YzEventBus eventBus;
    private final StockService stocks;
    private final ApprovalService approvals;
    private final GoodsApi goods;

    public ManifestService(JdbcTemplate jdbc, MessageSource messages, YzEventBus eventBus,
            StockService stocks, ApprovalService approvals, GoodsApi goods) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.eventBus = eventBus;
        this.stocks = stocks;
        this.approvals = approvals;
        this.goods = goods;
    }

    // ---- 手工建单(进货/出货) ----

    @Transactional
    public long createManual(long orgId, Integer type, List<ItemCmd> items, String remark,
            Long orderId, long operatorId) {
        require(type != null && (type == TYPE_CG || type == TYPE_XG), CODE_PARAM, "inventory.param.invalid");
        require(items != null && !items.isEmpty(), CODE_EMPTY_ITEMS, "inventory.items.empty");
        long manifestId = insertManifest(orgId, type, STATUS_INIT, orderId, null, null, operatorId, remark);
        List<StockManifestCreatedEvent.Item> eventItems = new ArrayList<>();
        int sort = 0;
        for (ItemCmd cmd : items) {
            require(cmd != null && cmd.goodsId() != null && cmd.num() != null,
                    CODE_PARAM, "inventory.param.invalid");
            require(cmd.num() > 0, StockService.CODE_NUM_POSITIVE, "inventory.num.positive");
            StockService.Snapshot snapshot = stocks.snapshot(orgId, cmd.goodsId(),
                    nvl(cmd.colorId()), nvl(cmd.formatId()));
            long price = nvl(cmd.price());
            require(price >= 0, CODE_PARAM, "inventory.param.invalid");
            long itemId;
            if (type == TYPE_CG) {
                require(cmd.warehouseIn() != null, CODE_PARAM, "inventory.param.invalid");
                stocks.requireWarehouse(orgId, cmd.warehouseIn());
                itemId = insertItem(orgId, manifestId, snapshot, cmd.warehouseIn(), null,
                        cmd.num(), price, cmd.num(), cmd.salesItemId(), null, null, null, sort++);
                stocks.in(orgId, cmd.goodsId(), nvl(cmd.colorId()), nvl(cmd.formatId()),
                        cmd.warehouseIn(), cmd.num());
            } else {
                require(cmd.warehouseOut() != null, CODE_PARAM, "inventory.param.invalid");
                stocks.requireWarehouse(orgId, cmd.warehouseOut());
                itemId = insertItem(orgId, manifestId, snapshot, null, cmd.warehouseOut(),
                        cmd.num(), price, cmd.num(), cmd.salesItemId(), null, null, null, sort++);
                stocks.out(orgId, cmd.goodsId(), nvl(cmd.colorId()), nvl(cmd.formatId()),
                        cmd.warehouseOut(), cmd.num());
            }
            eventItems.add(new StockManifestCreatedEvent.Item(itemId, cmd.goodsId(),
                    nvl(cmd.colorId()), nvl(cmd.formatId()), cmd.warehouseIn(), cmd.warehouseOut(),
                    cmd.num(), price, cmd.salesItemId(), null, null));
        }
        publishCreated(orgId, manifestId, type, orderId, eventItems, operatorId);
        return manifestId;
    }

    /** 确认(仅手工单 0→1;账面锁定,派生单落库即确认不走此端点)。 */
    @Transactional
    public void confirm(long orgId, long manifestId) {
        Map<String, Object> row = loadManifest(orgId, manifestId);
        int type = ((Number) row.get("type")).intValue();
        require(type == TYPE_CG || type == TYPE_XG, CODE_BAD_STATE, "inventory.state.illegal");
        require(((Number) row.get("status")).intValue() == STATUS_INIT, CODE_BAD_STATE,
                "inventory.state.illegal");
        int updated = jdbc.update("""
                update inventory_manifest set status = ?, confirmed_at = now()
                where id = ? and org_id = ? and status = ?
                """, STATUS_CONFIRMED, manifestId, orgId, STATUS_INIT);
        require(updated > 0, CODE_BAD_STATE, "inventory.state.illegal");
    }

    /** 初始态删行(修老 INIT 删行不回冲:库存原子回补 + 事件通知订阅方回写自己的账)。 */
    @Transactional
    public void removeItem(long orgId, long manifestId, long itemId, long operatorId) {
        Map<String, Object> manifest = loadManifest(orgId, manifestId);
        require(((Number) manifest.get("status")).intValue() == STATUS_INIT, CODE_BAD_STATE,
                "inventory.state.illegal");
        int type = ((Number) manifest.get("type")).intValue();
        List<Map<String, Object>> items = jdbc.queryForList("""
                select id, goods_id, color_id, format_id, warehouse_in, warehouse_out, num, sales_item_id
                from inventory_manifest_item where id = ? and org_id = ? and manifest_id = ?
                """, itemId, orgId, manifestId);
        require(!items.isEmpty(), CODE_NOT_FOUND, "inventory.notfound");
        Map<String, Object> item = items.get(0);
        long num = ((Number) item.get("num")).longValue();
        Long warehouseIn = item.get("warehouse_in") == null ? null : ((Number) item.get("warehouse_in")).longValue();
        Long warehouseOut = item.get("warehouse_out") == null ? null : ((Number) item.get("warehouse_out")).longValue();
        long goodsId = ((Number) item.get("goods_id")).longValue();
        long colorId = ((Number) item.get("color_id")).longValue();
        long formatId = ((Number) item.get("format_id")).longValue();
        jdbc.update("delete from inventory_manifest_item where id = ?", itemId);
        if (type == TYPE_CG) {
            stocks.out(orgId, goodsId, colorId, formatId, warehouseIn, num);
        } else {
            stocks.in(orgId, goodsId, colorId, formatId, warehouseOut, num);
        }
        eventBus.publish(new StockManifestItemRemovedEvent(manifestId, orgId, type, itemId,
                        goodsId, colorId, formatId, warehouseIn, warehouseOut, num,
                        item.get("sales_item_id") == null ? null
                                : ((Number) item.get("sales_item_id")).longValue(),
                        operatorId),
                "manifest:" + manifestId + ":itemRemoved");
    }

    // ---- 冲销 = 反向类型新单(进退 CR / 出返 XB) ----

    @Transactional
    public long createReversal(long orgId, Integer type, Long sourceManifestId,
            List<ReversalLineCmd> lines, String remark, long operatorId) {
        require(type != null && (type == TYPE_CR || type == TYPE_XB), CODE_PARAM, "inventory.param.invalid");
        require(sourceManifestId != null && lines != null && !lines.isEmpty(),
                CODE_EMPTY_ITEMS, "inventory.items.empty");
        Map<String, Object> source = loadManifest(orgId, sourceManifestId);
        int sourceType = ((Number) source.get("type")).intValue();
        int wantSource = type == TYPE_CR ? TYPE_CG : TYPE_XG;
        require(sourceType == wantSource, CODE_PARAM, "inventory.param.invalid");
        require(((Number) source.get("status")).intValue() == STATUS_CONFIRMED, CODE_BAD_STATE,
                "inventory.state.illegal");
        long manifestId = insertManifest(orgId, type, STATUS_CONFIRMED,
                source.get("order_id") == null ? null : ((Number) source.get("order_id")).longValue(),
                SOURCE_STOCK_MANIFEST, sourceManifestId, operatorId, remark);
        jdbc.update("update inventory_manifest set confirmed_at = now() where id = ?", manifestId);
        List<StockManifestCreatedEvent.Item> eventItems = new ArrayList<>();
        int sort = 0;
        for (ReversalLineCmd line : lines) {
            require(line != null && line.sourceItemId() != null && line.num() != null,
                    CODE_PARAM, "inventory.param.invalid");
            require(line.num() > 0, StockService.CODE_NUM_POSITIVE, "inventory.num.positive");
            // 源单明细 surplus 原子条件扣减(并发冲销只成功一个)
            int deducted = jdbc.update("""
                    update inventory_manifest_item set surplus = surplus - ?
                    where id = ? and org_id = ? and manifest_id = ? and surplus >= ?
                    """, line.num(), line.sourceItemId(), orgId, sourceManifestId, line.num());
            require(deducted > 0, CODE_SOURCE_SURPLUS, "inventory.reversal.surplus");
            Map<String, Object> src = jdbc.queryForMap("""
                    select goods_id, color_id, format_id, warehouse_in, warehouse_out, price, sales_item_id
                    from inventory_manifest_item where id = ?
                    """, line.sourceItemId());
            long goodsId = ((Number) src.get("goods_id")).longValue();
            long colorId = ((Number) src.get("color_id")).longValue();
            long formatId = ((Number) src.get("format_id")).longValue();
            long price = ((Number) src.get("price")).longValue();
            Long salesItemId = src.get("sales_item_id") == null ? null
                    : ((Number) src.get("sales_item_id")).longValue();
            StockService.Snapshot snapshot = stocks.snapshot(orgId, goodsId, colorId, formatId);
            long itemId;
            if (type == TYPE_CR) {
                // 进货退出:从原入库仓出库
                long warehouseOut = ((Number) src.get("warehouse_in")).longValue();
                itemId = insertItem(orgId, manifestId, snapshot, null, warehouseOut, line.num(),
                        price, null, salesItemId, null, null, line.sourceItemId(), sort++);
                stocks.out(orgId, goodsId, colorId, formatId, warehouseOut, line.num());
                eventItems.add(new StockManifestCreatedEvent.Item(itemId, goodsId, colorId, formatId,
                        null, warehouseOut, line.num(), price, salesItemId, null, line.sourceItemId()));
            } else {
                // 出货退回:退回原出库仓入库
                long warehouseIn = ((Number) src.get("warehouse_out")).longValue();
                itemId = insertItem(orgId, manifestId, snapshot, warehouseIn, null, line.num(),
                        price, null, salesItemId, null, null, line.sourceItemId(), sort++);
                stocks.in(orgId, goodsId, colorId, formatId, warehouseIn, line.num());
                eventItems.add(new StockManifestCreatedEvent.Item(itemId, goodsId, colorId, formatId,
                        warehouseIn, null, line.num(), price, salesItemId, null, line.sourceItemId()));
            }
        }
        publishCreated(orgId, manifestId, type, orderIdOf(source), eventItems, operatorId);
        return manifestId;
    }

    // ---- 调拨(内核轻量审批;审批期不占库存,通过过账/驳回置 3) ----

    @Transactional
    public long submitTransfer(long orgId, List<TransferLineCmd> lines, Long assigneeId,
            String remark, long operatorId) {
        require(lines != null && !lines.isEmpty(), CODE_EMPTY_ITEMS, "inventory.items.empty");
        require(assigneeId != null, CODE_PARAM, "inventory.param.invalid");
        long manifestId = insertManifest(orgId, TYPE_DB, STATUS_APPROVING, null, null, null,
                operatorId, remark);
        List<Map<String, Object>> itemRows = new ArrayList<>();
        int sort = 0;
        for (TransferLineCmd line : lines) {
            require(line != null && line.goodsId() != null && line.num() != null,
                    CODE_PARAM, "inventory.param.invalid");
            require(line.num() > 0, StockService.CODE_NUM_POSITIVE, "inventory.num.positive");
            require(line.warehouseOut() != null && line.warehouseIn() != null
                    && !line.warehouseOut().equals(line.warehouseIn()),
                    CODE_TRANSFER_SAME_WH, "inventory.transfer.sameWarehouse");
            stocks.requireWarehouse(orgId, line.warehouseOut());
            stocks.requireWarehouse(orgId, line.warehouseIn());
            StockService.Snapshot snapshot = stocks.snapshot(orgId, line.goodsId(),
                    nvl(line.colorId()), nvl(line.formatId()));
            long itemId = insertItem(orgId, manifestId, snapshot, line.warehouseIn(),
                    line.warehouseOut(), line.num(), 0, null, null, null, null, null, sort++);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemId", itemId);
            row.put("goodsId", line.goodsId());
            row.put("colorId", nvl(line.colorId()));
            row.put("formatId", nvl(line.formatId()));
            row.put("warehouseIn", line.warehouseIn());
            row.put("warehouseOut", line.warehouseOut());
            row.put("num", line.num());
            itemRows.add(row);
        }
        String code = codeOf(manifestId);
        long approvalId = approvals.submit(new ApprovalService.SubmitCmd(orgId, operatorId,
                msgArgs("inventory.transfer.title", code), BIZ_ADDON, BIZ_TYPE_TRANSFER,
                String.valueOf(manifestId), assigneeId));
        jdbc.update("update inventory_manifest set approval_id = ? where id = ?", approvalId, manifestId);
        return manifestId;
    }

    /** 审批办结回写(TransferApprovalListener 委托;通过 → 双向过账+1,驳回/撤回 → 3)。 */
    @Transactional
    public void executeTransfer(long orgId, long manifestId, long approvalId, boolean approved,
            long operatorId) {
        Map<String, Object> row = loadManifest(orgId, manifestId);
        require(((Number) row.get("status")).intValue() == STATUS_APPROVING, CODE_BAD_STATE,
                "inventory.state.illegal");
        require(row.get("approval_id") == null
                || ((Number) row.get("approval_id")).longValue() == approvalId, CODE_BAD_STATE,
                "inventory.state.illegal");
        if (!approved) {
            jdbc.update("""
                    update inventory_manifest set status = ? where id = ? and status = ?
                    """, STATUS_REJECTED, manifestId, STATUS_APPROVING);
            return;
        }
        List<Map<String, Object>> items = jdbc.queryForList("""
                select id, goods_id, color_id, format_id, warehouse_in, warehouse_out, num
                from inventory_manifest_item where manifest_id = ? order by sort
                """, manifestId);
        List<StockManifestCreatedEvent.Item> eventItems = new ArrayList<>();
        for (Map<String, Object> item : items) {
            long goodsId = ((Number) item.get("goods_id")).longValue();
            long colorId = ((Number) item.get("color_id")).longValue();
            long formatId = ((Number) item.get("format_id")).longValue();
            long num = ((Number) item.get("num")).longValue();
            long warehouseOut = ((Number) item.get("warehouse_out")).longValue();
            long warehouseIn = ((Number) item.get("warehouse_in")).longValue();
            stocks.out(orgId, goodsId, colorId, formatId, warehouseOut, num);
            stocks.in(orgId, goodsId, colorId, formatId, warehouseIn, num);
            eventItems.add(new StockManifestCreatedEvent.Item(
                    ((Number) item.get("id")).longValue(), goodsId, colorId, formatId,
                    warehouseIn, warehouseOut, num, 0L, null, null, null));
        }
        int updated = jdbc.update("""
                update inventory_manifest set status = ?, confirmed_at = now()
                where id = ? and status = ?
                """, STATUS_CONFIRMED, manifestId, STATUS_APPROVING);
        require(updated > 0, CODE_BAD_STATE, "inventory.state.illegal");
        publishCreated(orgId, manifestId, TYPE_DB, orderIdOf(row), eventItems, operatorId);
    }

    // ---- 盘盈/盘亏(事件闭环;盘点孤表不迁) ----

    @Transactional
    public long adjust(long orgId, Integer type, List<AdjustItemCmd> items, String remark,
            long operatorId) {
        require(type != null && (type == TYPE_CP || type == TYPE_CL), CODE_PARAM, "inventory.param.invalid");
        require(items != null && !items.isEmpty(), CODE_EMPTY_ITEMS, "inventory.items.empty");
        long manifestId = insertManifest(orgId, type, STATUS_CONFIRMED, null, null, null,
                operatorId, remark);
        jdbc.update("update inventory_manifest set confirmed_at = now() where id = ?", manifestId);
        List<StockManifestCreatedEvent.Item> eventItems = new ArrayList<>();
        int sort = 0;
        for (AdjustItemCmd cmd : items) {
            require(cmd != null && cmd.goodsId() != null && cmd.warehouseId() != null
                    && cmd.num() != null, CODE_PARAM, "inventory.param.invalid");
            require(cmd.num() > 0, StockService.CODE_NUM_POSITIVE, "inventory.num.positive");
            stocks.requireWarehouse(orgId, cmd.warehouseId());
            StockService.Snapshot snapshot = stocks.snapshot(orgId, cmd.goodsId(),
                    nvl(cmd.colorId()), nvl(cmd.formatId()));
            long itemId;
            if (type == TYPE_CP) {
                itemId = insertItem(orgId, manifestId, snapshot, cmd.warehouseId(), null,
                        cmd.num(), 0, null, null, null, null, null, sort++);
                stocks.in(orgId, cmd.goodsId(), nvl(cmd.colorId()), nvl(cmd.formatId()),
                        cmd.warehouseId(), cmd.num());
            } else {
                itemId = insertItem(orgId, manifestId, snapshot, null, cmd.warehouseId(),
                        cmd.num(), 0, null, null, null, null, null, sort++);
                stocks.out(orgId, cmd.goodsId(), nvl(cmd.colorId()), nvl(cmd.formatId()),
                        cmd.warehouseId(), cmd.num());
            }
            eventItems.add(new StockManifestCreatedEvent.Item(itemId, cmd.goodsId(),
                    nvl(cmd.colorId()), nvl(cmd.formatId()),
                    type == TYPE_CP ? cmd.warehouseId() : null,
                    type == TYPE_CL ? cmd.warehouseId() : null,
                    cmd.num(), 0L, null, null, null));
        }
        publishCreated(orgId, manifestId, type, null, eventItems, operatorId);
        return manifestId;
    }

    // ---- 套合/套拆(A5:9 型出零件入套餐/10 型出套餐入零件;老 mergePackage/splitPackage 语义) ----

    public record PackageOpCmd(Long packageGoodsId, Long colorId, Long formatId, Long warehouseId,
            Long num) {
    }

    /**
     * 套餐拆合(落单即确认,零迁移):套拆 10 型=出套餐+入成员(num×成员量),套合 9 型=出成员+入套餐。
     * 商品必须是套餐(type=1,经 GoodsApi 契约)且已配置成员;成员色规继承套餐行色规
     * (营造套餐成员无色规配置——老系统成员绑定色规的配置维度随套餐配置 V2);
     * 成员量千分位×套餐数÷1000 精确乘算(HALF_UP)。sourceType=package(sourceId=套餐商品 id)。
     */
    @Transactional
    public long packageOp(long orgId, int type, PackageOpCmd cmd, long operatorId) {
        require(type == TYPE_PM || type == TYPE_PS, CODE_PARAM, "inventory.param.invalid");
        require(cmd != null && cmd.packageGoodsId() != null && cmd.warehouseId() != null
                && cmd.num() != null, CODE_PARAM, "inventory.param.invalid");
        require(cmd.num() > 0, StockService.CODE_NUM_POSITIVE, "inventory.num.positive");
        stocks.requireWarehouse(orgId, cmd.warehouseId());
        GoodsApi.GoodsSummary pkg = goods.queryGoods(orgId, cmd.packageGoodsId())
                .orElseThrow(() -> new BizException(StockService.CODE_GOODS_NOT_FOUND,
                        msg("inventory.goods.notFound")));
        require(pkg.type() == 1, CODE_GOODS_NOT_PACKAGE, "inventory.package.notPackage");
        List<GoodsApi.PackageItem> members = goods.listPackageItems(orgId, cmd.packageGoodsId());
        require(!members.isEmpty(), CODE_PACKAGE_NO_MEMBERS, "inventory.package.noMembers");
        long colorId = nvl(cmd.colorId());
        long formatId = nvl(cmd.formatId());
        StockService.Snapshot pkgSnap = stocks.snapshot(orgId, cmd.packageGoodsId(), colorId, formatId);
        long manifestId = insertManifest(orgId, type, STATUS_CONFIRMED, null, SOURCE_PACKAGE,
                cmd.packageGoodsId(), operatorId, null);
        jdbc.update("update inventory_manifest set confirmed_at = now() where id = ?", manifestId);
        boolean split = type == TYPE_PS;
        List<StockManifestCreatedEvent.Item> eventItems = new ArrayList<>();
        int sort = 0;
        // 套餐行:拆=出 / 合=入
        long pkgItemId = insertItem(orgId, manifestId, pkgSnap, split ? null : cmd.warehouseId(),
                split ? cmd.warehouseId() : null, cmd.num(), 0, null, null, null, null, null, sort++);
        if (split) {
            stocks.out(orgId, pkgSnap.goodsId(), colorId, formatId, cmd.warehouseId(), cmd.num());
        } else {
            stocks.in(orgId, pkgSnap.goodsId(), colorId, formatId, cmd.warehouseId(), cmd.num());
        }
        eventItems.add(new StockManifestCreatedEvent.Item(pkgItemId, pkgSnap.goodsId(), colorId,
                formatId, split ? null : cmd.warehouseId(), split ? cmd.warehouseId() : null,
                cmd.num(), 0L, null, null, null));
        // 成员行:拆=入 / 合=出(色规继承套餐行;数量=套餐数×成员量/1000 千分位精确)
        for (GoodsApi.PackageItem member : members) {
            long memberNum = java.math.BigDecimal.valueOf(cmd.num())
                    .multiply(java.math.BigDecimal.valueOf(member.num()))
                    .divide(java.math.BigDecimal.valueOf(1000), 0, java.math.RoundingMode.HALF_UP)
                    .longValue();
            StockService.Snapshot memberSnap = stocks.snapshot(orgId, member.goodsId(), colorId, formatId);
            long memberItemId = insertItem(orgId, manifestId, memberSnap,
                    split ? cmd.warehouseId() : null, split ? null : cmd.warehouseId(),
                    memberNum, 0, null, null, null, null, null, sort++);
            if (split) {
                stocks.in(orgId, member.goodsId(), colorId, formatId, cmd.warehouseId(), memberNum);
            } else {
                stocks.out(orgId, member.goodsId(), colorId, formatId, cmd.warehouseId(), memberNum);
            }
            eventItems.add(new StockManifestCreatedEvent.Item(memberItemId, memberSnap.goodsId(),
                    colorId, formatId, split ? cmd.warehouseId() : null,
                    split ? null : cmd.warehouseId(), memberNum, 0L, null, null, null));
        }
        publishCreated(orgId, manifestId, type, null, eventItems, operatorId);
        return manifestId;
    }

    // ---- 查询 ----

    public Map<String, Object> page(long orgId, Integer type, Integer status, String code,
            int pageNo, int size) {
        StringBuilder where = new StringBuilder(" where org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        if (type != null) {
            where.append(" and type = ?");
            args.add(type);
        }
        if (status != null) {
            where.append(" and status = ?");
            args.add(status);
        }
        if (notBlank(code)) {
            where.append(" and code like ?");
            args.add("%" + code.trim() + "%");
        }
        int safeSize = Math.min(Math.max(size, 1), 100);
        Long total = jdbc.queryForObject("select count(*) from inventory_manifest" + where,
                Long.class, args.toArray());
        List<Object> allArgs = new ArrayList<>(args);
        allArgs.add(safeSize);
        allArgs.add((Math.max(pageNo, 1) - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, code, type, status, order_id, source_type, source_id, approval_id,
                       remark, created_by_name, confirmed_at, created_at
                from inventory_manifest""" + where + " order by id desc limit ? offset ?",
                allArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    public Map<String, Object> detail(long orgId, long manifestId) {
        Map<String, Object> manifest = new LinkedHashMap<>(loadManifest(orgId, manifestId));
        manifest.put("items", jdbc.queryForList("""
                select id, goods_id, goods_name, color_name, format_name, warehouse_in, warehouse_out,
                       num, price, surplus, sales_item_id, requisition_item_id, purchase_item_id,
                       source_item_id, sort
                from inventory_manifest_item where manifest_id = ? order by sort
                """, manifestId));
        return manifest;
    }

    // ---- 派生单入口(领料/采购域调用,包内协作;落单即确认) ----

    @Transactional
    public long createDerived(long orgId, int type, Long orderId, String sourceType, Long sourceId,
            List<DerivedItem> items, long operatorId) {
        require(items != null && !items.isEmpty(), CODE_EMPTY_ITEMS, "inventory.items.empty");
        long manifestId = insertManifest(orgId, type, STATUS_CONFIRMED, orderId, sourceType,
                sourceId, operatorId, null);
        jdbc.update("update inventory_manifest set confirmed_at = now() where id = ?", manifestId);
        List<StockManifestCreatedEvent.Item> eventItems = new ArrayList<>();
        int sort = 0;
        for (DerivedItem item : items) {
            long itemId = insertItem(orgId, manifestId, item.snapshot(), item.warehouseIn(),
                    item.warehouseOut(), item.num(), item.price(), item.surplus(),
                    item.salesItemId(), item.requisitionItemId(), item.purchaseItemId(),
                    item.sourceItemId(), sort++);
            if (item.warehouseIn() != null) {
                stocks.in(orgId, item.snapshot().goodsId(), item.snapshot().colorId(),
                        item.snapshot().formatId(), item.warehouseIn(), item.num());
            }
            if (item.warehouseOut() != null) {
                stocks.out(orgId, item.snapshot().goodsId(), item.snapshot().colorId(),
                        item.snapshot().formatId(), item.warehouseOut(), item.num());
            }
            eventItems.add(new StockManifestCreatedEvent.Item(itemId, item.snapshot().goodsId(),
                    item.snapshot().colorId(), item.snapshot().formatId(), item.warehouseIn(),
                    item.warehouseOut(), item.num(), item.price(), item.salesItemId(),
                    item.requisitionItemId(), item.sourceItemId()));
        }
        publishCreated(orgId, manifestId, type, orderId, eventItems, operatorId);
        return manifestId;
    }

    public record DerivedItem(StockService.Snapshot snapshot, Long warehouseIn, Long warehouseOut,
                              long num, long price, Long surplus, Long salesItemId,
                              Long requisitionItemId, Long purchaseItemId, Long sourceItemId) {
    }

    // ---- 内部 ----

    private long insertManifest(long orgId, int type, int status, Long orderId, String sourceType,
            Long sourceId, long operatorId, String remark) {
        jdbc.update("""
                insert into inventory_manifest(org_id, code, type, status, order_id, source_type,
                    source_id, remark, created_by, created_by_name)
                values (?,?,?,?,?,?,?,?,?,?)
                """, orgId, nextCode(orgId, PREFIX.get(type)), type, status, orderId, sourceType,
                sourceId, blankToNull(remark), operatorId, displayName(operatorId));
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    private long insertItem(long orgId, long manifestId, StockService.Snapshot snapshot,
            Long warehouseIn, Long warehouseOut, long num, long price, Long surplus,
            Long salesItemId, Long requisitionItemId, Long purchaseItemId, Long sourceItemId,
            int sort) {
        jdbc.update("""
                insert into inventory_manifest_item(org_id, manifest_id, goods_id, color_id, format_id,
                    goods_name, color_name, format_name, warehouse_in, warehouse_out, num, price,
                    surplus, sales_item_id, requisition_item_id, purchase_item_id, source_item_id, sort)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, orgId, manifestId, snapshot.goodsId(), snapshot.colorId(), snapshot.formatId(),
                snapshot.goodsName(), snapshot.colorName(), snapshot.formatName(), warehouseIn,
                warehouseOut, num, price, surplus, salesItemId, requisitionItemId, purchaseItemId,
                sourceItemId, sort);
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    /** 单号 = 前缀+yyMMdd+4 位流水;计数器原子自增(修老读改写竞态),(org,code) 唯一键兜底。 */
    private String nextCode(long orgId, String prefix) {
        int dateKey = Integer.parseInt(java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd")));
        for (int attempt = 0; attempt < 3; attempt++) {
            int updated = jdbc.update(
                    "update inventory_counter set last_seq = last_insert_id(last_seq + 1) "
                            + "where org_id = ? and prefix = ? and date_key = ?",
                    orgId, prefix, dateKey);
            if (updated > 0) {
                long seq = jdbc.queryForObject("select last_insert_id()", Long.class);
                return String.format("%s%s%04d", prefix, dateKey, seq);
            }
            try {
                jdbc.update(
                        "insert into inventory_counter(org_id, prefix, date_key, last_seq) values (?,?,?,1)",
                        orgId, prefix, dateKey);
                return String.format("%s%s%04d", prefix, dateKey, 1);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发首插撞唯一键,重试自增
            }
        }
        throw new BizException(CODE_BAD_STATE, msg("inventory.state.illegal"));
    }

    private String codeOf(long manifestId) {
        return jdbc.queryForObject("select code from inventory_manifest where id = ?", String.class,
                manifestId);
    }

    private void publishCreated(long orgId, long manifestId, int type, Long orderId,
            List<StockManifestCreatedEvent.Item> items, long operatorId) {
        eventBus.publish(new StockManifestCreatedEvent(manifestId, orgId, type, codeOf(manifestId),
                orderId, items, operatorId), "manifest:" + manifestId + ":created");
    }

    private Map<String, Object> loadManifest(long orgId, long manifestId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, code, type, status, order_id, source_type, source_id, approval_id, created_by
                from inventory_manifest where id = ? and org_id = ?
                """, manifestId, orgId);
        require(!rows.isEmpty(), CODE_NOT_FOUND, "inventory.notfound");
        return rows.get(0);
    }

    private Long orderIdOf(Map<String, Object> row) {
        return row.get("order_id") == null ? null : ((Number) row.get("order_id")).longValue();
    }

    private String displayName(long userId) {
        return jdbc.queryForObject("select display_name from yz_user where id = ?", String.class, userId);
    }

    private static long nvl(Long value) {
        return value == null ? 0L : value;
    }

    private void require(boolean cond, int code, String msgKey) {
        if (!cond) {
            throw new BizException(code, msg(msgKey));
        }
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private String msgArgs(String key, Object... args) {
        return messages.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
