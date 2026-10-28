package com.yingzao.addon.quotation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.addons.api.GoodsApi;
import com.yingzao.core.web.BizException;

/**
 * 定额档案层(组织级主数据,docs/prd/addon-quotation-norm.md 按推荐拍板 2026-10-28):
 * 三 kind 单表(0 材料/1 人工/2 工时,挂 V404 报价项库)+附加项档案(公司级模板)+
 * 材料定额套餐(老 Normpack/NormpackItem)。材料档案定材必填(经 GoodsApi 契约 231204);
 * 施工节点存 id+名称快照不跨域校验(诚实边界:节点模板属 addon-construction,
 * 只读契约随 R3 NormApi 一并评估);删除被实例引用拒 231207(改停用)。
 * 档案名唯一性:同 catalog+kind+name 查重(老无 update,改=删了重加,V1 带 update 白名单)。
 */
@Service
public class NormArchiveService {

    public static final int KIND_MATERIAL = 0;
    public static final int KIND_LABOR = 1;
    public static final int KIND_TIME = 2;

    public static final int CODE_ADDITION_BAD_STATE = 231201;
    public static final int CODE_CATALOG_NOT_FOUND = 231203;
    public static final int CODE_GOODS_NOT_FOUND = 231204;
    public static final int CODE_NODE_REQUIRED = 231205;
    public static final int CODE_ARCHIVE_REFERENCED = 231207;
    public static final int CODE_ADDITION_NOT_FOUND = 231208;
    public static final int CODE_NUM_ILLEGAL = 231209;
    public static final int CODE_NAME_DUP = 231211;

    public record ArchiveRequest(Integer kind, Long catalogItemId, String name, Long goodsId,
            String goodsName, String normUnit, Long normNum, String storeUnit, Long storeNum,
            Long constructionNodeId, String nodeName, String unit, Long trannum, Long salary,
            Long workdays) {
    }

    public record AdditionRequest(String name, Long constructionNodeId, String nodeName,
            String unit, Long price) {
    }

    public record PackRequest(String name, Long catalogItemId) {
    }

    public record PackItemRequest(Long goodsId, String goodsName, String normUnit, Long normNum,
            String storeUnit, Long storeNum) {
    }

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final GoodsApi goods;

    public NormArchiveService(JdbcTemplate jdbc, MessageSource messages, GoodsApi goods) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.goods = goods;
    }

    private String msg(String key, Object... args) {
        return messages.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }

    private void require(boolean cond, int code, String key, Object... args) {
        if (!cond) {
            throw new BizException(code, msg(key, args));
        }
    }

    private void validateKindFields(long orgId, int kind, ArchiveRequest req) {
        require(req.name != null && !req.name.isBlank(), CODE_NUM_ILLEGAL, "quotation.norm.num.illegal");
        require(req.catalogItemId != null, CODE_CATALOG_NOT_FOUND, "quotation.norm.catalog.notFound");
        Integer catalog = jdbc.queryForObject(
                "select count(*) from quotation_item where id = ? and org_id = ?",
                Integer.class, req.catalogItemId, orgId);
        require(catalog != null && catalog > 0, CODE_CATALOG_NOT_FOUND,
                "quotation.norm.catalog.notFound");
        if (kind == KIND_MATERIAL) {
            require(req.goodsId != null, CODE_GOODS_NOT_FOUND, "quotation.norm.goods.notFound");
            require(goods.queryGoods(orgId, req.goodsId).isPresent(), CODE_GOODS_NOT_FOUND,
                    "quotation.norm.goods.notFound");
            require(req.normNum != null && req.normNum > 0, CODE_NUM_ILLEGAL,
                    "quotation.norm.num.illegal");
        } else {
            require(req.constructionNodeId != null, CODE_NODE_REQUIRED, "quotation.norm.node.required");
        }
        // 同 catalog+kind+name 查重(建档事故防线,对齐 V404 报价项库同名单价纪律)
        Integer dup = jdbc.queryForObject("""
                select count(*) from quotation_norm_archive
                where org_id = ? and kind = ? and catalog_item_id = ? and name = ? and status = 0""",
                Integer.class, orgId, kind, req.catalogItemId, req.name.trim());
        require(dup == null || dup == 0, CODE_NAME_DUP, "quotation.norm.name.dup", req.name.trim());
    }

    // ---- 三 kind 档案 CRUD ----

    public Map<String, Object> pageArchives(long orgId, Integer kind, Long catalogItemId,
            String keyword, int page, int size) {
        StringBuilder w = new StringBuilder(" where org_id = ?");
        List<Object> args = new java.util.ArrayList<>(List.of(orgId));
        if (kind != null) {
            w.append(" and kind = ?");
            args.add(kind);
        }
        if (catalogItemId != null) {
            w.append(" and catalog_item_id = ?");
            args.add(catalogItemId);
        }
        if (keyword != null && !keyword.isBlank()) {
            w.append(" and name like ?");
            args.add("%" + keyword.trim() + "%");
        }
        int safePage = Math.max(1, page), safeSize = Math.min(Math.max(1, size), 100);
        Integer count = jdbc.queryForObject(
                "select count(*) from quotation_norm_archive" + w, Integer.class, args.toArray());
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, kind, catalog_item_id, catalog_name, name, goods_id, goods_name,
                       norm_unit, norm_num, store_unit, store_num, construction_node_id, node_name,
                       unit, trannum, salary, workdays, status
                from quotation_norm_archive""" + w + " order by id desc limit ? offset ?",
                append(args, safeSize, (safePage - 1) * safeSize).toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", count == null ? 0 : count);
        result.put("rows", rows);
        return result;
    }

    private List<Object> append(List<Object> args, Object... extra) {
        List<Object> all = new java.util.ArrayList<>(args);
        for (Object e : extra) {
            all.add(e);
        }
        return all;
    }

    @Transactional
    public Long createArchive(long orgId, ArchiveRequest req) {
        require(req.kind != null && req.kind >= KIND_MATERIAL && req.kind <= KIND_TIME,
                CODE_NUM_ILLEGAL, "quotation.norm.num.illegal");
        validateKindFields(orgId, req.kind, req);
        jdbc.update("""
                insert into quotation_norm_archive(org_id, kind, catalog_item_id, catalog_name, name,
                    goods_id, goods_name, norm_unit, norm_num, store_unit, store_num,
                    construction_node_id, node_name, unit, trannum, salary, workdays)
                select ?, ?, i.id, i.name, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                from quotation_item i where i.id = ? and i.org_id = ?""",
                orgId, req.kind, req.name.trim(), req.goodsId, blankToNull(req.goodsName),
                req.normUnit, req.normNum, req.storeUnit, req.storeNum,
                req.constructionNodeId, blankToNull(req.nodeName), req.unit,
                req.trannum == null ? 1000L : req.trannum, req.salary, req.workdays,
                req.catalogItemId, orgId);
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    @Transactional
    public void updateArchive(long orgId, long id, ArchiveRequest req) {
        Map<String, Object> row = loadArchive(orgId, id);
        int kind = ((Number) row.get("kind")).intValue();
        require(statusOf(row) == 0, CODE_NUM_ILLEGAL, "quotation.norm.num.illegal");
        validateKindFields(orgId, kind, req);
        jdbc.update("""
                update quotation_norm_archive set name = ?, goods_id = ?, goods_name = ?,
                    norm_unit = ?, norm_num = ?, store_unit = ?, store_num = ?,
                    construction_node_id = ?, node_name = ?, unit = ?, trannum = ?, salary = ?,
                    workdays = ?
                where id = ? and org_id = ?""",
                req.name.trim(), req.goodsId, blankToNull(req.goodsName), req.normUnit,
                req.normNum, req.storeUnit, req.storeNum, req.constructionNodeId,
                blankToNull(req.nodeName), req.unit,
                req.trannum == null ? 1000L : req.trannum, req.salary, req.workdays, id, orgId);
    }

    /** 删除被实例引用拒(改停用);停用档案不参与新展开,存量实例行不动(快照自洽) */
    @Transactional
    public void deleteArchive(long orgId, long id) {
        loadArchive(orgId, id);
        Integer used = jdbc.queryForObject(
                "select count(*) from quotation_norm_item where archive_id = ? and org_id = ?",
                Integer.class, id, orgId);
        require(used == null || used == 0, CODE_ARCHIVE_REFERENCED,
                "quotation.norm.archive.referenced");
        jdbc.update("delete from quotation_norm_archive where id = ? and org_id = ?", id, orgId);
    }

    @Transactional
    public void statusArchive(long orgId, long id, int status) {
        loadArchive(orgId, id);
        jdbc.update("update quotation_norm_archive set status = ? where id = ? and org_id = ?",
                status, id, orgId);
    }

    private Map<String, Object> loadArchive(long orgId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, org_id, kind, status from quotation_norm_archive where id = ? and org_id = ?",
                id, orgId);
        require(!rows.isEmpty(), CODE_CATALOG_NOT_FOUND, "quotation.norm.archive.notFound");
        return rows.get(0);
    }

    private int statusOf(Map<String, Object> row) {
        return ((Number) row.get("status")).intValue();
    }

    // ---- 附加项档案 ----

    public List<Map<String, Object>> listAdditions(long orgId, String keyword) {
        StringBuilder w = new StringBuilder(" where org_id = ?");
        List<Object> args = new java.util.ArrayList<>(List.of(orgId));
        if (keyword != null && !keyword.isBlank()) {
            w.append(" and name like ?");
            args.add("%" + keyword.trim() + "%");
        }
        return jdbc.queryForList(
                "select id, name, construction_node_id, node_name, unit, price, status "
                        + "from quotation_norm_addition" + w + " order by id desc",
                args.toArray());
    }

    @Transactional
    public Long createAddition(long orgId, AdditionRequest req) {
        require(req.name != null && !req.name.isBlank() && req.constructionNodeId != null,
                CODE_ADDITION_NOT_FOUND, "quotation.norm.addition.notFound");
        jdbc.update("""
                insert into quotation_norm_addition(org_id, name, construction_node_id, node_name,
                    unit, price) values (?, ?, ?, ?, ?, ?)""",
                orgId, req.name.trim(), req.constructionNodeId, blankToNull(req.nodeName),
                req.unit, req.price == null ? 0L : req.price);
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    @Transactional
    public void updateAddition(long orgId, long id, AdditionRequest req) {
        loadAddition(orgId, id);
        jdbc.update("""
                update quotation_norm_addition set name = ?, construction_node_id = ?, node_name = ?,
                    unit = ?, price = ? where id = ? and org_id = ?""",
                req.name.trim(), req.constructionNodeId, blankToNull(req.nodeName), req.unit,
                req.price == null ? 0L : req.price, id, orgId);
    }

    @Transactional
    public void deleteAddition(long orgId, long id) {
        loadAddition(orgId, id);
        jdbc.update("delete from quotation_norm_addition where id = ? and org_id = ?", id, orgId);
    }

    @Transactional
    public void statusAddition(long orgId, long id, int status) {
        loadAddition(orgId, id);
        jdbc.update("update quotation_norm_addition set status = ? where id = ? and org_id = ?",
                status, id, orgId);
    }

    private void loadAddition(long orgId, long id) {
        Integer n = jdbc.queryForObject(
                "select count(*) from quotation_norm_addition where id = ? and org_id = ?",
                Integer.class, id, orgId);
        require(n != null && n > 0, CODE_ADDITION_NOT_FOUND, "quotation.norm.addition.notFound");
    }

    // ---- 材料定额套餐 ----

    public List<Map<String, Object>> listPacks(long orgId) {
        return jdbc.queryForList(
                "select id, name, catalog_item_id, catalog_name, status from quotation_norm_pack "
                        + "where org_id = ? order by id desc", orgId);
    }

    @Transactional
    public Long createPack(long orgId, PackRequest req) {
        require(req.name != null && !req.name.isBlank() && req.catalogItemId != null,
                CODE_CATALOG_NOT_FOUND, "quotation.norm.catalog.notFound");
        jdbc.update("""
                insert into quotation_norm_pack(org_id, name, catalog_item_id, catalog_name)
                select ?, ?, i.id, i.name from quotation_item i
                where i.id = ? and i.org_id = ?""",
                orgId, req.name.trim(), req.catalogItemId, orgId);
        Long id = jdbc.queryForObject("select last_insert_id()", Long.class);
        require(id != null && id > 0, CODE_CATALOG_NOT_FOUND, "quotation.norm.catalog.notFound");
        return id;
    }

    @Transactional
    public void statusPack(long orgId, long id, int status) {
        Integer n = jdbc.queryForObject(
                "select count(*) from quotation_norm_pack where id = ? and org_id = ?",
                Integer.class, id, orgId);
        require(n != null && n > 0, CODE_ADDITION_NOT_FOUND, "quotation.norm.pack.notFound");
        jdbc.update("update quotation_norm_pack set status = ? where id = ? and org_id = ?",
                status, id, orgId);
    }

    public List<Map<String, Object>> listPackItems(long orgId, long packId) {
        return jdbc.queryForList("""
                select id, pack_id, goods_id, goods_name, norm_unit, norm_num, store_unit, store_num
                from quotation_norm_pack_item where org_id = ? and pack_id = ? order by id""",
                orgId, packId);
    }

    @Transactional
    public void savePackItem(long orgId, long packId, PackItemRequest req) {
        Integer pack = jdbc.queryForObject(
                "select count(*) from quotation_norm_pack where id = ? and org_id = ?",
                Integer.class, packId, orgId);
        require(pack != null && pack > 0, CODE_ADDITION_NOT_FOUND, "quotation.norm.pack.notFound");
        require(req.goodsId != null && req.normNum != null && req.normNum > 0,
                CODE_NUM_ILLEGAL, "quotation.norm.num.illegal");
        require(goods.queryGoods(orgId, req.goodsId).isPresent(), CODE_GOODS_NOT_FOUND,
                "quotation.norm.goods.notFound");
        // uk(pack, goods) upsert:同成员换参数=覆盖(老 NormpackItem 全 CRUD,成员唯一)
        jdbc.update("""
                update quotation_norm_pack_item set goods_name = ?, norm_unit = ?, norm_num = ?,
                    store_unit = ?, store_num = ? where pack_id = ? and goods_id = ? and org_id = ?""",
                blankToNull(req.goodsName), req.normUnit, req.normNum, req.storeUnit,
                req.storeNum, packId, req.goodsId, orgId);
        jdbc.update("""
                insert ignore into quotation_norm_pack_item(org_id, pack_id, goods_id, goods_name,
                    norm_unit, norm_num, store_unit, store_num)
                select org_id, ?, ?, ?, ?, ?, ?, ? from quotation_norm_pack where id = ? and org_id = ?""",
                packId, req.goodsId, blankToNull(req.goodsName), req.normUnit, req.normNum,
                req.storeUnit, req.storeNum, packId, orgId);
    }

    @Transactional
    public void deletePackItem(long orgId, long packId, long itemId) {
        jdbc.update("delete from quotation_norm_pack_item where id = ? and pack_id = ? and org_id = ?",
                itemId, packId, orgId);
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
