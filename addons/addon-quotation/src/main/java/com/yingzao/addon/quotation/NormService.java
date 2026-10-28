package com.yingzao.addon.quotation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.addons.api.event.NormAdditionPendingChangedEvent;
import com.yingzao.core.eventbus.YzEventBus;
import com.yingzao.core.web.BizException;

/**
 * 定额实例层(生成引擎+附加确认线,docs/prd/addon-quotation-norm.md 按推荐拍板 2026-10-28)。
 * 生成引擎 = 全量重算(老 makeNormtion 语义收敛):按来源先禁用旧行(status 0→1)→
 * 同 uk 行复用置 0 刷新数值 → 新行插入;附加行(source_type=3)不参与重算;
 * 触发点三处:放线成功(ConstructionDrawnEvent)/增减差量款收齐(PaymentCollectedEvent
 * typeCode=ar_diff,bizId=arId 第 81 次库实证)/手动重新生成;完工固化
 * (ConstructionAcceptedEvent)后禁用行物理清+拒再生成 231206。
 * 数量口径:实例行 num=明细量(千分位直拷)+norm_num 定额快照;实际用量=读取期
 * num×norm_num÷1000 HALF_UP(对齐 A5 先例);工费聚合老 SQL 逐字对齐
 * (非附加行 status=0 + 附加行 status=2——拍板 5「确认即计入,追加/手工同权」收口老 quirk)。
 * 🔴附加行 uk 自引用解题(核对清单 5):重算复用锚只覆盖 0/2 来源(archive_id 非空);
 * 附加手工行 archive_id=null(MySQL uk 多 NULL 天然排除);套餐成员行 archive_id=0+
 * pack_item_id 行级区分——三形态互不撞;增减行 source_id=差量明细行 id(同单多行同锚防撞,
 * 单源重算按 arId 查全部明细行)。
 */
@Service
public class NormService {

    public static final int SOURCE_QUOTE = 0;
    public static final int SOURCE_EXT = 1;
    public static final int SOURCE_AR = 2;
    public static final int SOURCE_ADD = 3;

    public static final int KIND_MATERIAL = 0;
    public static final int KIND_LABOR = 1;
    public static final int KIND_TIME = 2;

    public static final int ST_INITIAL = 0;
    public static final int ST_DISABLED = 1;
    public static final int ST_CONFIRMED = 2;
    public static final int ST_REJECTED = 3;

    public static final int ATT_IN = 0;
    public static final int ATT_OUT = 1;

    public static final int CODE_ADDITION_BAD_STATE = 231201;
    public static final int CODE_NORM_NOT_FOUND = 231202;
    public static final int CODE_SEALED = 231206;
    public static final int CODE_NUM_ILLEGAL = 231209;

    public record AdditionItemRequest(Long additionId, String name, Long constructionNodeId,
            String nodeName, String unit, Long num, Long salary) {
    }

    /** 一条展开后的实例行(档案行/套餐成员行/增减 goods 兜底行) */
    private record ExpandedRow(int kind, long archiveId, long packItemId, Long goodsId,
            String goodsName, Long nodeId, String nodeName, String name, String unit,
            long normNum, long trannum, long salary, long workdays) {
    }

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final YzEventBus eventBus;

    public NormService(JdbcTemplate jdbc, MessageSource messages, YzEventBus eventBus) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.eventBus = eventBus;
    }

    private String msg(String key, Object... args) {
        return messages.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }

    private void require(boolean cond, int code, String key, Object... args) {
        if (!cond) {
            throw new BizException(code, msg(key, args));
        }
    }

    /** 千分位精确乘算:A5 先例(读取期实际用量 = num×norm_num÷1000 HALF_UP;R3 NormApi 用) */
    static long milliMul(long a, long b) {
        return BigDecimal.valueOf(a).multiply(BigDecimal.valueOf(b))
                .divide(BigDecimal.valueOf(1000), 0, RoundingMode.HALF_UP).longValue();
    }

    // ---- 单头 ----

    private Map<String, Object> ensureHead(long orgId, long orderId, long operatorId) {
        jdbc.update("insert ignore into quotation_norm(org_id, order_id, creater) values (?, ?, ?)",
                orgId, orderId, operatorId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, quotation_id, sealed from quotation_norm where org_id = ? and order_id = ?",
                orgId, orderId);
        require(!rows.isEmpty(), CODE_NORM_NOT_FOUND, "quotation.norm.notFound");
        return rows.get(0);
    }

    private void requireNotSealed(Map<String, Object> head) {
        require(((Number) head.get("sealed")).intValue() == 0, CODE_SEALED, "quotation.norm.sealed");
    }

    // ---- 生成引擎 ----

    /**
     * 全量重算。arId=null:来源 0(默认报价)+来源 2(全部已确认差量单);
     * arId!=null:仅该差量单(差量款收齐触发,PaymentCollectedEvent typeCode=ar_diff)。
     */
    @Transactional
    public Map<String, Object> generate(long orgId, long orderId, Long arId, long operatorId) {
        Map<String, Object> head = ensureHead(orgId, orderId, operatorId);
        requireNotSealed(head);
        long quotationId = defaultQuotationId(orgId, orderId);
        if (quotationId == 0 && arId == null) {
            // 无默认报价的放线(如先施工后报价)是合法场景——静默零展开,不进死信
            // (消费者吞 BizException 会重试死信;手动 generate 返回零结果调用方可感知)
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("quotationId", 0L);
            empty.put("disabled", 0);
            empty.put("reused", 0);
            empty.put("inserted", 0);
            empty.put("skipped", true);
            return empty;
        }
        jdbc.update("update quotation_norm set quotation_id = ? where id = ?",
                quotationId > 0 ? quotationId : null, head.get("id"));

        // 展开指令集:每项 = 来源/来源行 id/明细锚/方向/明细量
        List<Job> jobs = new ArrayList<>();
        if (arId != null) {
            collectArJobs(orgId, orderId, arId, jobs);
        } else {
            if (quotationId > 0) {
                collectQuoteJobs(orgId, quotationId, jobs);
            }
            collectArJobsAll(orgId, orderId, jobs);
        }

        int disabled = 0, reused = 0, inserted = 0;
        for (Job job : jobs) {
            // 禁用该来源行既有有效行(附加 source=3 恒不在 jobs,天然不动)
            disabled += jdbc.update("""
                    update quotation_norm_item set status = ?
                    where org_id = ? and quotation_id = ? and source_type = ? and source_id = ?
                      and status = ?""", ST_DISABLED, orgId, quotationId, job.sourceType,
                    job.sourceId, ST_INITIAL);
            for (ExpandedRow r : expand(orgId, job)) {
                Map<String, Object> existing = findRow(orgId, quotationId, job.sourceType,
                        job.sourceId, r.kind(), r.archiveId(), r.packItemId());
                if (existing != null
                        && ((Number) existing.get("status")).intValue() == ST_DISABLED) {
                    jdbc.update("""
                            update quotation_norm_item set status = ?, num = ?, norm_num = ?,
                                trannum = ?, salary = ?, workdays = ?, atttype = ?,
                                goods_id = ?, goods_name = ?, node_id = ?, node_name = ?,
                                area_id = ?, item_id = ?, catalog_item_id = ?, name = ?, unit = ?
                            where id = ?""", ST_INITIAL, job.num, r.normNum(), r.trannum(),
                            r.salary(), r.workdays(), job.atttype, r.goodsId(), r.goodsName(),
                            r.nodeId(), r.nodeName(), job.areaId, job.itemId, job.catalogItemId,
                            r.name(), r.unit(), existing.get("id"));
                    reused++;
                } else {
                    jdbc.update("""
                            insert into quotation_norm_item(org_id, order_id, quotation_id,
                                source_type, source_id, kind, archive_id, pack_item_id, area_id,
                                item_id, catalog_item_id, goods_id, goods_name, node_id, node_name,
                                name, unit, atttype, num, norm_num, trannum, salary, workdays)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                            orgId, orderId, quotationId, job.sourceType, job.sourceId, r.kind(),
                            r.archiveId(), r.packItemId(), job.areaId, job.itemId, job.catalogItemId,
                            r.goodsId(), r.goodsName(), r.nodeId(), r.nodeName(), r.name(),
                            r.unit(), job.atttype, job.num, r.normNum(), r.trannum(), r.salary(),
                            r.workdays());
                    inserted++;
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("quotationId", quotationId);
        result.put("disabled", disabled);
        result.put("reused", reused);
        result.put("inserted", inserted);
        return result;
    }

    /** 展开指令 */
    private record Job(int sourceType, long sourceId, Long itemId, Long areaId, Long catalogItemId,
            int atttype, long num, Long goodsFallback) {
    }

    private long defaultQuotationId(long orgId, long orderId) {
        List<Long> ids = jdbc.queryForList("""
                select id from quotation_quotation
                where org_id = ? and order_id = ? and is_default = 1 and status = 1
                order by id desc limit 1""", Long.class, orgId, orderId);
        return ids.isEmpty() ? 0L : ids.get(0);
    }

    private void collectQuoteJobs(long orgId, long quotationId, List<Job> jobs) {
        for (Map<String, Object> item : jdbc.queryForList("""
                select id, area_id, catalog_item_id, num from quotation_function_item
                where quotation_id = ? and org_id = ?""", quotationId, orgId)) {
            long itemId = ((Number) item.get("id")).longValue();
            jobs.add(new Job(SOURCE_QUOTE, itemId, itemId,
                    item.get("area_id") == null ? null : ((Number) item.get("area_id")).longValue(),
                    item.get("catalog_item_id") == null ? null
                            : ((Number) item.get("catalog_item_id")).longValue(),
                    ATT_IN, ((Number) item.get("num")).longValue(), null));
        }
    }

    private void collectArJobsAll(long orgId, long orderId, List<Job> jobs) {
        for (Map<String, Object> ar : jdbc.queryForList("""
                select id from quotation_add_reduce
                where org_id = ? and order_id = ? and status = 3 and withdrawn_at is null""",
                orgId, orderId)) {
            collectArJobs(orgId, orderId, ((Number) ar.get("id")).longValue(), jobs);
        }
    }

    /** 差量单展开:ref_qiid 经原功能项(catalog 锚);纯新增行走 goods 兜底(仅材料档案) */
    private void collectArJobs(long orgId, long orderId, long arId, List<Job> jobs) {
        Long arOrder = jdbc.queryForObject(
                "select order_id from quotation_add_reduce where id = ? and org_id = ?",
                Long.class, arId, orgId);
        require(arOrder != null && arOrder == orderId, CODE_NORM_NOT_FOUND,
                "quotation.norm.notFound");
        for (Map<String, Object> item : jdbc.queryForList("""
                select id, item_type, ref_qiid, goods_id, num
                from quotation_add_reduce_item where ar_id = ? and org_id = ?""", arId, orgId)) {
            long arItemId = ((Number) item.get("id")).longValue();
            int itemType = ((Number) item.get("item_type")).intValue();
            int atttype = itemType == 1 ? ATT_OUT : ATT_IN;
            long absNum = Math.abs(((Number) item.get("num")).longValue());
            Long refQiid = item.get("ref_qiid") == null ? null
                    : ((Number) item.get("ref_qiid")).longValue();
            if (refQiid != null) {
                List<Map<String, Object>> qi = jdbc.queryForList("""
                        select id, area_id, catalog_item_id from quotation_function_item
                        where id = ? and org_id = ?""", refQiid, orgId);
                if (!qi.isEmpty()) {
                    jobs.add(new Job(SOURCE_AR, arItemId,
                            ((Number) qi.get(0).get("id")).longValue(),
                            qi.get(0).get("area_id") == null ? null
                                    : ((Number) qi.get(0).get("area_id")).longValue(),
                            qi.get(0).get("catalog_item_id") == null ? null
                                    : ((Number) qi.get(0).get("catalog_item_id")).longValue(),
                            atttype, absNum, null));
                    continue;
                }
            }
            Long goodsId = item.get("goods_id") == null ? null
                    : ((Number) item.get("goods_id")).longValue();
            jobs.add(new Job(SOURCE_AR, arItemId, null, null, null, atttype, absNum, goodsId));
        }
    }

    /** 展开一个明细锚:档案 by catalog(kind 0/1/2)+套餐 by catalog(成员展开)+goods 兜底(仅材料) */
    private List<ExpandedRow> expand(long orgId, Job job) {
        List<ExpandedRow> rows = new ArrayList<>();
        Long catalogId = job.catalogItemId();
        if (catalogId != null) {
            for (Map<String, Object> a : jdbc.queryForList("""
                    select id, kind, goods_id, goods_name, construction_node_id, node_name, name,
                           unit, norm_num, trannum, salary, workdays
                    from quotation_norm_archive
                    where org_id = ? and catalog_item_id = ? and status = 0""", orgId, catalogId)) {
                rows.add(new ExpandedRow(
                        ((Number) a.get("kind")).intValue(),
                        ((Number) a.get("id")).longValue(), 0L,
                        a.get("goods_id") == null ? null : ((Number) a.get("goods_id")).longValue(),
                        (String) a.get("goods_name"),
                        a.get("construction_node_id") == null ? null
                                : ((Number) a.get("construction_node_id")).longValue(),
                        (String) a.get("node_name"), (String) a.get("name"), (String) a.get("unit"),
                        a.get("norm_num") == null ? 0 : ((Number) a.get("norm_num")).longValue(),
                        a.get("trannum") == null ? 1000 : ((Number) a.get("trannum")).longValue(),
                        a.get("salary") == null ? 0 : ((Number) a.get("salary")).longValue(),
                        a.get("workdays") == null ? 0 : ((Number) a.get("workdays")).longValue()));
            }
            // 套餐:成员一次展开为材料行(archive_id=0+pack_item_id 行级区分;老 NORMTYPE_PACK 位)
            for (Map<String, Object> mi : jdbc.queryForList("""
                    select pi.id, pi.goods_id, pi.goods_name, pi.norm_unit, pi.norm_num,
                           pi.store_unit, pi.store_num
                    from quotation_norm_pack_item pi
                    join quotation_norm_pack p on p.id = pi.pack_id
                    where p.org_id = ? and p.catalog_item_id = ? and p.status = 0""", orgId,
                    catalogId)) {
                rows.add(new ExpandedRow(KIND_MATERIAL, 0L,
                        ((Number) mi.get("id")).longValue(),
                        ((Number) mi.get("goods_id")).longValue(), (String) mi.get("goods_name"),
                        null, null, (String) mi.get("goods_name"), (String) mi.get("norm_unit"),
                        mi.get("norm_num") == null ? 0 : ((Number) mi.get("norm_num")).longValue(),
                        1000L, 0L, 0L));
            }
        }
        // 差量纯新增行 goods 兜底:仅材料档案按 goods 匹配(kind 0)
        if (rows.isEmpty() && job.goodsFallback() != null) {
            for (Map<String, Object> a : jdbc.queryForList("""
                    select id, goods_id, goods_name, name, unit, norm_num, trannum
                    from quotation_norm_archive
                    where org_id = ? and kind = ? and goods_id = ? and status = 0""", orgId,
                    KIND_MATERIAL, job.goodsFallback())) {
                rows.add(new ExpandedRow(KIND_MATERIAL,
                        ((Number) a.get("id")).longValue(), 0L,
                        ((Number) a.get("goods_id")).longValue(), (String) a.get("goods_name"),
                        null, null, (String) a.get("name"), (String) a.get("unit"),
                        a.get("norm_num") == null ? 0 : ((Number) a.get("norm_num")).longValue(),
                        a.get("trannum") == null ? 1000 : ((Number) a.get("trannum")).longValue(),
                        0L, 0L));
            }
        }
        return rows;
    }

    private Map<String, Object> findRow(long orgId, long quotationId, int sourceType, long sourceId,
            int kind, long archiveId, long packItemId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, status from quotation_norm_item
                where org_id = ? and quotation_id = ? and source_type = ? and source_id = ?
                  and kind = ? and archive_id = ? and pack_item_id = ?""", orgId, quotationId,
                sourceType, sourceId, kind, archiveId, packItemId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---- 完工固化(ConstructionAcceptedEvent 消费;核对清单 4) ----

    @Transactional
    public void onAccepted(long orgId, long orderId) {
        Map<String, Object> head = ensureHead(orgId, orderId, 0);
        requireNotSealed(head);
        jdbc.update("update quotation_norm set sealed = 1, sealed_at = now() where id = ?",
                head.get("id"));
        jdbc.update("delete from quotation_norm_item where org_id = ? and order_id = ? and status = ?",
                orgId, orderId, ST_DISABLED);
    }

    // ---- 附加项确认线(source_type=3,kind 恒 1 人工;三态 0/2/3;登记即待审) ----

    @Transactional
    public Long addAddition(long orgId, long orderId, AdditionItemRequest req, long operatorId) {
        Map<String, Object> head = ensureHead(orgId, orderId, operatorId);
        requireNotSealed(head);
        String name = null;
        long nodeId = 0;
        String nodeName = null;
        String unit = null;
        long salary = 0;
        if (req.additionId() != null) { // 附加项档案模板带出(字段快照拷贝)
            Map<String, Object> tpl = jdbc.queryForMap("""
                    select name, construction_node_id, node_name, unit, price
                    from quotation_norm_addition where id = ? and org_id = ?""",
                    req.additionId(), orgId);
            name = (String) tpl.get("name");
            nodeId = ((Number) tpl.get("construction_node_id")).longValue();
            nodeName = (String) tpl.get("node_name");
            unit = (String) tpl.get("unit");
            salary = ((Number) tpl.get("price")).longValue();
        }
        if (req.name() != null && !req.name().isBlank()) {
            name = req.name().trim(); // 手工覆盖模板名
        }
        if (req.constructionNodeId() != null) {
            nodeId = req.constructionNodeId();
            nodeName = req.nodeName();
        }
        if (req.salary() != null) {
            salary = req.salary();
        }
        require(name != null && !name.isBlank() && nodeId > 0, CODE_NUM_ILLEGAL,
                "quotation.norm.num.illegal");
        require(req.num() != null && req.num() > 0, CODE_NUM_ILLEGAL, "quotation.norm.num.illegal");
        jdbc.update("""
                insert into quotation_norm_item(org_id, order_id, quotation_id, source_type, source_id,
                    kind, archive_id, pack_item_id, node_id, node_name, name, unit, atttype, num,
                    trannum, salary, status)
                values (?, ?, ?, ?, 0, ?, null, 0, ?, ?, ?, ?, ?, ?, 1000, ?, ?)""",
                orgId, orderId, head.get("quotation_id"), SOURCE_ADD, KIND_LABOR, nodeId, nodeName,
                name, unit, ATT_IN, req.num(), salary, ST_INITIAL);
        long itemId = jdbc.queryForObject("select last_insert_id()", Long.class);
        publishPending(orgId, orderId, nodeId, operatorId);
        return itemId;
    }

    @Transactional
    public void confirmAddition(long orgId, long orderId, long itemId, long operatorId,
            boolean confirm) {
        int target = confirm ? ST_CONFIRMED : ST_REJECTED;
        int updated = jdbc.update("""
                update quotation_norm_item set status = ?, confirmer = ?, confirm_time = now()
                where id = ? and org_id = ? and order_id = ? and source_type = ? and status = ?""",
                target, operatorId, itemId, orgId, orderId, SOURCE_ADD, ST_INITIAL);
        require(updated == 1, CODE_ADDITION_BAD_STATE, "quotation.norm.addition.badState");
        Long nodeId = jdbc.queryForObject(
                "select node_id from quotation_norm_item where id = ?", Long.class, itemId);
        publishPending(orgId, orderId, nodeId, operatorId);
    }

    @Transactional
    public void deleteAddition(long orgId, long orderId, long itemId, long operatorId) {
        List<Long> nodes = jdbc.queryForList("""
                select node_id from quotation_norm_item
                where id = ? and org_id = ? and order_id = ? and source_type = ? and status = ?""",
                Long.class, itemId, orgId, orderId, SOURCE_ADD, ST_INITIAL);
        require(!nodes.isEmpty(), CODE_ADDITION_BAD_STATE, "quotation.norm.addition.badState");
        jdbc.update("delete from quotation_norm_item where id = ?", itemId);
        publishPending(orgId, orderId, nodes.get(0), operatorId);
    }

    private void publishPending(long orgId, long orderId, Long nodeId, long operatorId) {
        if (nodeId == null) {
            return;
        }
        Long pending = jdbc.queryForObject("""
                select count(*) from quotation_norm_item
                where org_id = ? and order_id = ? and source_type = ? and status = ? and node_id = ?""",
                Long.class, orgId, orderId, SOURCE_ADD, ST_INITIAL, nodeId);
        eventBus.publish(new NormAdditionPendingChangedEvent(orgId, orderId, nodeId,
                pending == null ? 0 : pending, operatorId),
                "norm:addition:" + orderId + ":" + nodeId + ":pending");
    }

    // ---- 查询面 ----

    public Map<String, Object> head(long orgId, long orderId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, order_id, quotation_id, creater, create_time, sealed, sealed_at
                from quotation_norm where org_id = ? and order_id = ?""", orgId, orderId);
        require(!rows.isEmpty(), CODE_NORM_NOT_FOUND, "quotation.norm.notFound");
        return rows.get(0);
    }

    public Map<String, Object> items(long orgId, long orderId, Integer sourceType, Integer kind,
            Integer status, int page, int size) {
        StringBuilder w = new StringBuilder(" where i.org_id = ? and i.order_id = ?");
        List<Object> args = new ArrayList<>(List.of(orgId, orderId));
        if (sourceType != null) {
            w.append(" and i.source_type = ?");
            args.add(sourceType);
        }
        if (kind != null) {
            w.append(" and i.kind = ?");
            args.add(kind);
        }
        if (status != null) {
            w.append(" and i.status = ?");
            args.add(status);
        } else {
            w.append(" and i.status != ?"); // 默认不含禁用行(重算换版中间态)
            args.add(ST_DISABLED);
        }
        int safePage = Math.max(1, page), safeSize = Math.min(Math.max(1, size), 200);
        Integer count = jdbc.queryForObject(
                "select count(*) from quotation_norm_item i" + w, Integer.class, args.toArray());
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select i.id, i.source_type, i.source_id, i.kind, i.archive_id, i.pack_item_id,
                       i.area_id, i.item_id, i.catalog_item_id, i.goods_id, i.goods_name,
                       i.node_id, i.node_name, i.name, i.unit, i.atttype, i.num, i.norm_num,
                       i.trannum, i.salary, i.workdays, i.status, i.confirmer, i.confirm_time
                from quotation_norm_item i""" + w + " order by i.id limit ?, ?",
                appendArgs(args, (safePage - 1) * safeSize, safeSize).toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", count == null ? 0 : count);
        result.put("rows", rows);
        return result;
    }

    private List<Object> appendArgs(List<Object> args, Object... extra) {
        List<Object> all = new ArrayList<>(args);
        for (Object e : extra) {
            all.add(e);
        }
        return all;
    }

    /** 概览聚合(老 amountNormriceItemForSalary 口径):非附加行 status=0 + 附加行 status=2(拍板 5);
     *  工费=Σ±salary×if(trannum=0,1,num/1000×trannum/1000);atttype=1 支向取负 */
    public Map<String, Object> summary(long orgId, long orderId) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int kind = KIND_MATERIAL; kind <= KIND_TIME; kind++) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select count(*) as cnt,
                           coalesce(sum(case when atttype = 0 then salary else -salary end
                               * if(trannum = 0, 1, num / 1000 * trannum / 1000)), 0) as fee,
                           coalesce(sum(workdays), 0) as days
                    from quotation_norm_item
                    where org_id = ? and order_id = ? and kind = ?
                      and ((source_type != 3 and status = 0) or (source_type = 3 and status = 2))""",
                    orgId, orderId, kind);
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("count", rows.get(0).get("cnt"));
            one.put("laborFee", ((Number) rows.get(0).get("fee")).longValue());
            one.put("workdays", ((Number) rows.get(0).get("days")).longValue());
            result.put(String.valueOf(kind), one);
        }
        return result;
    }
}
