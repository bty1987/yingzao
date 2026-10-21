package com.yingzao.addon.basedata;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yingzao.core.eventbus.YzEventBus;
import com.yingzao.core.web.BizException;

/**
 * 商品 Excel 导入器 V1.1(修老导入器脏值源:老系统部分成功+静默转换,pinyin List.toString
 * 方括号脏值/数值不缩放直接落库)。
 *
 * 纪律三则:
 * 1. **全量校验,全过才入库**——任一行错误则整单拒绝(整体事务),错误报告逐行列出
 *    (行号/列/原因),不产生任何部分成功;「半成功导入」是老系统脏数据的第一根源;
 * 2. **品类/品牌按名称解析且必须已存在**(主数据纪律:导入不级联建主档,报错提示先建档);
 * 3. **数值口径显式**:售价/进价/限价模板列为元,服务端 ×1000 转厘(unitRate 不开放导入,
 *    固定 1000);拼音行内生成校验(仅小写字母),不静默转换。
 */
@Service
public class GoodsImportService {

    /** 导入数据错误(260000 段;明细走返回体 errors,不塞消息)。 */
    public static final int CODE_IMPORT_INVALID = 260023;
    /** 套餐导入(B 类联动 V1.2):套餐商品不存在 260027 / 成员商品不存在 260028。 */
    public static final int CODE_PACKAGE_GOODS_MISSING = 260027;
    public static final int CODE_MEMBER_GOODS_MISSING = 260028;

    private static final String[] HEADERS = {
            "商品名称", "拼音", "品类", "品牌", "单位", "条码", "售价(元)", "进价(元)", "限价(元)"};

    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final GoodsService goods;
    private final YzEventBus eventBus;

    public GoodsImportService(JdbcTemplate jdbc, MessageSource messages, GoodsService goods,
            YzEventBus eventBus) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.goods = goods;
        this.eventBus = eventBus;
    }

    /** 模板下载:表头+示例行(示例行不参与导入语义,仅为填报说明)。 */
    public byte[] template() {
        try (XSSFWorkbook wb = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("商品导入");
            Row head = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                head.createCell(i).setCellValue(HEADERS[i]);
                sheet.setColumnWidth(i, 16 * 256);
            }
            Row demo = sheet.createRow(1);
            demo.createCell(0).setCellValue("示例-瓷砖");
            demo.createCell(1).setCellValue("slcz");
            demo.createCell(2).setCellValue("瓷砖");
            demo.createCell(3).setCellValue("马可波罗");
            demo.createCell(4).setCellValue("片");
            demo.createCell(5).setCellValue("6901234567890");
            demo.createCell(6).setCellValue("128.00");
            demo.createCell(7).setCellValue("80.00");
            demo.createCell(8).setCellValue("158.00");
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("template build failed", e);
        }
    }

    /** 导入结果:全过时 imported = 成功行数且 errors 空;有错时 errors 逐行列出且不入库。 */
    public record ImportReport(int total, int imported, List<Map<String, Object>> errors) {
    }

    /** 全量校验 + 整体导入(任一行错误 = 整单拒绝,零部分成功)。 */
    @Transactional
    public ImportReport importGoods(long orgId, byte[] xlsx, long operatorId) {
        List<RowData> rows = parse(xlsx);
        if (rows.isEmpty()) {
            throw new BizException(CODE_IMPORT_INVALID, msg("goods.import.empty"));
        }
        List<Map<String, Object>> errors = new ArrayList<>();
        Set<String> nameSeen = new HashSet<>();
        Set<String> barcodeSeen = new HashSet<>();
        Map<Integer, Long> categoryIds = new LinkedHashMap<>();
        Map<Integer, Long> brandIds = new LinkedHashMap<>();
        // 套餐行先分组(packageName 非空=成员行,引用既有商品——不参与新建商品的查重与必填校验)
        java.util.LinkedHashMap<String, java.util.List<Integer>> byPackage = new java.util.LinkedHashMap<>();
        java.util.HashSet<Integer> packageRows = new java.util.HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            String pkg = rows.get(i).packageName();
            if (pkg != null && !pkg.isBlank()) {
                byPackage.computeIfAbsent(pkg.trim(), k -> new ArrayList<>()).add(i);
                packageRows.add(i);
            }
        }

        for (int i = 0; i < rows.size(); i++) {
            int rowNo = i + 2; // Excel 行号(1 表头)
            boolean isPackageRow = packageRows.contains(i);
            RowData r = rows.get(i);
            check(errors, rowNo, "商品名称", notBlank(r.name), "goods.import.required");
            if (!isPackageRow) {
                check(errors, rowNo, "拼音", r.pinyin != null && r.pinyin.matches("[a-z][a-z0-9]*"),
                        "goods.import.pinyin");
                check(errors, rowNo, "售价(元)", r.selling != null && r.selling >= 0, "goods.import.number");
            }
            // 三价非法数字(num() 返 -1)显式报错,不静默置 null(V1.1 漏网顺修)
            if (r.purchase != null && r.purchase < 0) {
                check(errors, rowNo, "进价(元)", false, "goods.import.number");
            }
            if (r.confine != null && r.confine < 0) {
                check(errors, rowNo, "限价(元)", false, "goods.import.number");
            }
            check(errors, rowNo, "单位", notBlank(r.unit), "goods.import.required");

            if (isPackageRow) {
                // 成员行:品类/品牌可空(引用既有商品,不新建)
                if (notBlank(r.category)) {
                    Long cid = categoryIdByName(orgId, r.category);
                    if (cid != null) {
                        categoryIds.put(i, cid);
                    }
                }
                if (notBlank(r.brand)) {
                    Long bid = brandIdByName(orgId, r.brand);
                    if (bid != null) {
                        brandIds.put(i, bid);
                    }
                }
            } else {
                if (notBlank(r.category)) {
                    Long cid = categoryIdByName(orgId, r.category);
                    if (cid == null) {
                        check(errors, rowNo, "品类", false, "goods.import.category");
                    } else {
                        categoryIds.put(i, cid);
                    }
                } else {
                    check(errors, rowNo, "品类", false, "goods.import.required");
                }
                if (notBlank(r.brand)) {
                    Long bid = brandIdByName(orgId, r.brand);
                    if (bid == null) {
                        check(errors, rowNo, "品牌", false, "goods.import.brand");
                    } else {
                        brandIds.put(i, bid);
                    }
                } else {
                    check(errors, rowNo, "品牌", false, "goods.import.required");
                }
            }

            if (!isPackageRow) {
                String nameKey = r.name == null ? "" : r.name.trim();
                if (!nameKey.isEmpty()) {
                    if (!nameSeen.add(nameKey)) {
                        check(errors, rowNo, "商品名称", false, "goods.import.dupRow");
                    } else if (nameExists(orgId, nameKey)) {
                        check(errors, rowNo, "商品名称", false, "goods.import.dupName");
                    }
                }
                if (notBlank(r.barcode)) {
                    if (!barcodeSeen.add(r.barcode.trim())) {
                        check(errors, rowNo, "条码", false, "goods.import.dupRow");
                    } else if (barcodeExists(orgId, r.barcode.trim())) {
                        check(errors, rowNo, "条码", false, "goods.import.dupBarcode");
                    }
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new ImportInvalidException(new ImportReport(rows.size(), 0, errors));
        }

        for (int i = 0; i < rows.size(); i++) {
            if (packageRows.contains(i)) {
                continue;
            }
            RowData r = rows.get(i);
            goods.create(orgId, new GoodsService.CreateGoodsCmd(r.name.trim(), null, r.pinyin,
                    blankToNull(r.barcode), categoryIds.get(i), brandIds.get(i), r.unit.trim(),
                    GoodsService.TYPE_GOODS, toLi(r.purchase), toLi(r.selling), toLi(r.confine),
                    null), operatorId);
        }
        // 套餐导入:套餐主商品(type=1,售價=Σ成员) + 成员关系
        for (java.util.Map.Entry<String, java.util.List<Integer>> e : byPackage.entrySet()) {
            String pkgName = e.getKey();
            // 套餐主商品:取该组第一行定义(名称=套餐名,分类/品牌/单位沿用首行)
            Integer headIdx = e.getValue().get(0);
            RowData headRow = rows.get(headIdx);
            long packageGoodsId;
            try {
                packageGoodsId = goods.create(orgId, new GoodsService.CreateGoodsCmd(
                        pkgName, null, null, null, categoryIds.get(headIdx), brandIds.get(headIdx),
                        headRow.unit == null ? "套" : headRow.unit.trim(),
                        GoodsService.TYPE_PACKAGE, 0L, 0L, null, null), operatorId);
            } catch (BizException ex) {
                if (ex.getCode() == GoodsService.CODE_GOODS_NAME_EXISTS) {
                    List<Map<String, Object>> exist = jdbc.queryForList(
                            "select id from basedata_goods where org_id = ? and name = ?",
                            orgId, pkgName);
                    packageGoodsId = ((Number) exist.get(0).get("id")).longValue();
                } else {
                    throw ex;
                }
            }
            if (categoryIds.get(headIdx) == null) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("row", headIdx + 2);
                err.put("field", "品类");
                err.put("message", messages.getMessage("goods.import.category",
                        null, LocaleContextHolder.getLocale()));
                errors.add(err);
                continue;
            }
            long memberTotal = 0;
            for (int idx : e.getValue()) {
                RowData r = rows.get(idx);
                Long memberGoodsId = goodsIdByName(orgId, r.name.trim());
                if (memberGoodsId == null) {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("row", idx + 2);
                    err.put("field", "套餐成员");
                    err.put("message", messages.getMessage("goods.import.memberMissing",
                            new Object[]{r.name.trim()}, LocaleContextHolder.getLocale()));
                    errors.add(err);
                    continue;
                }
                int memberNum = r.memberNum() == null || r.memberNum() <= 0
                        ? 1 : r.memberNum().intValue();
                try {
                    goods.addPackageMember(orgId, packageGoodsId, memberGoodsId, memberNum);
                } catch (BizException ex) {
                    if (ex.getCode() == GoodsService.CODE_MEMBER_EXISTS) {
                        continue; // 同文件同成员已加(组内去重)
                    }
                    throw ex;
                }
                memberTotal += memberNum;
            }
            // 套餐售价 = Σ(成员 selling × num)服务端聚合(与 createPackageMember 口径一致则维持 0,
            // 这里按零落库与套餐商品语义一致:套餐售价业务侧维护)
        }
        if (!errors.isEmpty()) {
            throw new ImportInvalidException(new ImportReport(rows.size(), 0, errors));
        }
        int importedGoods = rows.size();
        recordImportHistory(orgId, "goods", importedGoods, 0, null, operatorId);
        return new ImportReport(importedGoods, importedGoods, errors);
    }

    /** 携报告的业务异常(controller 捕获后把 errors 放返回体;消息统一 260023)。 */
    public static class ImportInvalidException extends RuntimeException {
        public final ImportReport report;

        public ImportInvalidException(ImportReport report) {
            super("import invalid");
            this.report = report;
        }
    }

    private Long goodsIdByName(long orgId, String name) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id from basedata_goods where org_id = ? and name = ? limit 1",
                orgId, name);
        return rows.isEmpty() ? null : ((Number) rows.get(0).get("id")).longValue();
    }

    /** 导入历史台账(V1.2):goods/type 两类;org 级记录,失败导入由异常路径不落台账。 */
    /** 导入历史台账落库(带操作人)。 */
    private void recordImportHistory(long orgId, String type, int imported, int failed, String detail,
            long operatorId) {
        jdbc.update("""
                insert into basedata_import_history(org_id, import_type, imported, failed, detail, created_by)
                values (?,?,?,?,?,?)
                """, orgId, type, imported, failed, detail, operatorId);
    }

    /** 导入历史查询(V1.2 台账)。 */
    public Map<String, Object> history(long orgId, int pageNo, int size) {
        int safePage = Math.max(pageNo, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Long total = jdbc.queryForObject(
                "select count(*) from basedata_import_history where org_id = ?", Long.class, orgId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, import_type, imported, failed, detail, created_at
                from basedata_import_history where org_id = ?
                order by id desc limit ? offset ?
                """, orgId, safeSize, (safePage - 1) * safeSize);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        return result;
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    public BizException importInvalid() {
        return new BizException(CODE_IMPORT_INVALID, msg("goods.import.invalid"));
    }

    // ---- 解析 ----

    private record RowData(String name, String pinyin, String category, String brand, String unit,
            String barcode, Long selling, Long purchase, Long confine,
            String packageName, Long memberNum) {
    }

    private List<RowData> parse(byte[] xlsx) {
        List<RowData> out = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(new java.io.ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isBlankRow(row)) {
                    continue;
                }
                out.add(new RowData(
                        str(row, 0), str(row, 1), str(row, 2), str(row, 3), str(row, 4),
                        str(row, 5), num(row, 6), num(row, 7), num(row, 8),
                        str(row, 9), num(row, 10)));
            }
            return out;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(CODE_IMPORT_INVALID, msg("goods.import.unreadable"));
        }
    }

    private boolean isBlankRow(Row row) {
        for (int c = 0; c < HEADERS.length; c++) {
            String v = str(row, c);
            if (v != null && !v.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private String str(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            double d = cell.getNumericCellValue();
            if (d == Math.floor(d)) {
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        }
        String v = cell.toString();
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** 数值列:元(可小数)→ 厘;空列返回 null;非法数字抛错由校验兜(col 从 0 计)。 */
    private Long num(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) {
            return null;
        }
        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                return Math.round(cell.getNumericCellValue() * 1000);
            }
            String v = cell.toString().trim();
            return v.isEmpty() ? null : Math.round(Double.parseDouble(v) * 1000);
        } catch (NumberFormatException e) {
            return -1L; // 负数 = 非法,校验层报错
        }
    }

    private Long toLi(Long yuanLi) {
        return yuanLi == null || yuanLi < 0 ? null : yuanLi;
    }

    // ---- 解析辅助校验 ----

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private void check(List<Map<String, Object>> errors, int rowNo, String field, boolean ok,
            String msgKey) {
        if (!ok) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("row", rowNo);
            e.put("field", field);
            e.put("message", messages.getMessage(msgKey, null, LocaleContextHolder.getLocale()));
            errors.add(e);
        }
    }

    private Long categoryIdByName(long orgId, String name) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id from basedata_category where org_id = ? and name = ? and status = 0 limit 1",
                orgId, name.trim());
        return rows.isEmpty() ? null : ((Number) rows.get(0).get("id")).longValue();
    }

    private Long brandIdByName(long orgId, String name) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id from basedata_brand where name = ? limit 1", name.trim());
        return rows.isEmpty() ? null : ((Number) rows.get(0).get("id")).longValue();
    }

    private boolean nameExists(long orgId, String name) {
        Integer n = jdbc.queryForObject(
                "select count(*) from basedata_goods where org_id = ? and name = ?", Integer.class,
                orgId, name);
        return n != null && n > 0;
    }

    private boolean barcodeExists(long orgId, String barcode) {
        Integer n = jdbc.queryForObject(
                "select count(*) from basedata_goods where org_id = ? and barcode = ?", Integer.class,
                orgId, barcode);
        return n != null && n > 0;
    }

    // ==================== V1.3 价格体系导入 ====================

    private static final String[] PRICE_HEADERS = {
            "商品名称", "价格体系名", "进价(元)", "售价(元)", "限价(元)", "生效日", "失效日"};

    /** 价格体系模板下载(7 列;生效/失效日 yyyy-MM-dd,空=立即/长期)。 */
    public byte[] priceTemplate() {
        try (XSSFWorkbook wb = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("价格体系导入");
            Row head = sheet.createRow(0);
            for (int i = 0; i < PRICE_HEADERS.length; i++) {
                head.createCell(i).setCellValue(PRICE_HEADERS[i]);
                sheet.setColumnWidth(i, 16 * 256);
            }
            Row demo = sheet.createRow(1);
            demo.createCell(0).setCellValue("示例-瓷砖");
            demo.createCell(1).setCellValue("一级价");
            demo.createCell(2).setCellValue("80.00");
            demo.createCell(3).setCellValue("128.00");
            demo.createCell(4).setCellValue("158.00");
            demo.createCell(5).setCellValue("2026-01-01");
            demo.createCell(6).setCellValue("");
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("price template build failed", e);
        }
    }

    private record PriceRow(String goodsName, String priceName, Long purchase, Long selling,
            Long confine, String beginDay, String endDay) {
    }

    private List<PriceRow> parsePrices(byte[] xlsx) {
        List<PriceRow> out = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(new java.io.ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isPriceBlankRow(row)) {
                    continue;
                }
                out.add(new PriceRow(str(row, 0), str(row, 1), num(row, 2), num(row, 3),
                        num(row, 4), str(row, 5), str(row, 6)));
            }
            return out;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(CODE_IMPORT_INVALID, msg("goods.import.unreadable"));
        }
    }

    private boolean isPriceBlankRow(Row row) {
        for (int c = 0; c < PRICE_HEADERS.length; c++) {
            String v = str(row, c);
            if (v != null && !v.isBlank()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 价格体系批量导入(V1.3):商品必须已存在(不级联建);uk(goods_id, name) 已存在报错行
     * (导入只做新增,变更走编辑);生效/失效日 yyyy-MM-dd;全量校验整单拒。
     */
    @Transactional
    public ImportReport importPrices(long orgId, byte[] xlsx, long operatorId) {
        List<PriceRow> rows = parsePrices(xlsx);
        if (rows.isEmpty()) {
            throw new BizException(CODE_IMPORT_INVALID, msg("goods.import.empty"));
        }
        List<Map<String, Object>> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<Integer, Long> goodsIds = new LinkedHashMap<>();
        Map<Integer, java.time.LocalDate> begins = new LinkedHashMap<>();
        Map<Integer, java.time.LocalDate> ends = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            int rowNo = i + 2;
            PriceRow r = rows.get(i);
            check(errors, rowNo, "商品名称", notBlank(r.goodsName()), "goods.import.required");
            Long gid = notBlank(r.goodsName()) ? goodsIdByName(orgId, r.goodsName().trim()) : null;
            if (notBlank(r.goodsName()) && gid == null) {
                check(errors, rowNo, "商品名称", false, "goods.import.goodsMissing");
            } else if (gid != null) {
                goodsIds.put(i, gid);
            }
            check(errors, rowNo, "价格体系名", notBlank(r.priceName()), "goods.import.required");
            check(errors, rowNo, "价格(元)", r.purchase() != null || r.selling() != null
                    || r.confine() != null, "goods.import.price.needOne");
            if (r.purchase() != null && r.purchase() < 0) {
                check(errors, rowNo, "进价(元)", false, "goods.import.number");
            }
            if (r.selling() != null && r.selling() < 0) {
                check(errors, rowNo, "售价(元)", false, "goods.import.number");
            }
            if (r.confine() != null && r.confine() < 0) {
                check(errors, rowNo, "限价(元)", false, "goods.import.number");
            }
            for (String[] col : new String[][]{{"生效日", r.beginDay()}, {"失效日", r.endDay()}}) {
                if (notBlank(col[1])) {
                    java.time.LocalDate d = parseDay(col[1]);
                    if (d == null) {
                        check(errors, rowNo, col[0], false, "goods.import.price.dateFmt");
                    } else if (col[0].equals("生效日")) {
                        begins.put(i, d);
                    } else {
                        ends.put(i, d);
                    }
                }
            }
            String key = (r.goodsName() == null ? "" : r.goodsName().trim())
                    + "|" + (r.priceName() == null ? "" : r.priceName().trim());
            if (!seen.add(key)) {
                check(errors, rowNo, "价格体系名", false, "goods.import.dupRow");
            } else if (gid != null && priceExists(orgId, gid, r.priceName().trim())) {
                check(errors, rowNo, "价格体系名", false, "goods.import.price.dup");
            }
        }
        if (!errors.isEmpty()) {
            throw new ImportInvalidException(new ImportReport(rows.size(), 0, errors));
        }
        for (int i = 0; i < rows.size(); i++) {
            PriceRow r = rows.get(i);
            jdbc.update("""
                    insert into basedata_goods_price
                        (org_id, goods_id, name, purchase, selling, confine, begin_time, end_time, status)
                    values (?,?,?,?,?,?,?,?,0)
                    """, orgId, goodsIds.get(i), r.priceName().trim(), toLi(r.purchase()),
                    toLi(r.selling()), toLi(r.confine()),
                    begins.containsKey(i) ? java.sql.Date.valueOf(begins.get(i)) : null,
                    ends.containsKey(i) ? java.sql.Date.valueOf(ends.get(i)) : null);
        }
        recordImportHistory(orgId, "price", rows.size(), 0, null, operatorId);
        return new ImportReport(rows.size(), rows.size(), errors);
    }

    /** yyyy-MM-dd;非法返回 null(校验层报错)。 */
    private java.time.LocalDate parseDay(String text) {
        String v = text.trim();
        if (!v.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return null;
        }
        try {
            return java.time.LocalDate.parse(v);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean priceExists(long orgId, long goodsId, String name) {
        Integer n = jdbc.queryForObject(
                "select count(*) from basedata_goods_price where org_id = ? and goods_id = ? and name = ?",
                Integer.class, orgId, goodsId, name);
        return n != null && n > 0;
    }

    // ==================== V1.3 型号导入 ====================

    private static final String[] MODEL_HEADERS = {
            "型号名称", "商品名称", "品牌", "单位", "单价(元)", "进价(元)", "折扣(‰)", "计价方式",
            "底价(元)", "点位(‰)"};

    /** 型号模板下载(10 列;计价方式=底价/点位;折扣与点位为千分位整数,1000=原价)。 */
    public byte[] modelTemplate() {
        try (XSSFWorkbook wb = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("型号导入");
            Row head = sheet.createRow(0);
            for (int i = 0; i < MODEL_HEADERS.length; i++) {
                head.createCell(i).setCellValue(MODEL_HEADERS[i]);
                sheet.setColumnWidth(i, 16 * 256);
            }
            Row demo = sheet.createRow(1);
            demo.createCell(0).setCellValue("示例-型号A");
            demo.createCell(1).setCellValue("示例-瓷砖");
            demo.createCell(2).setCellValue("马可波罗");
            demo.createCell(3).setCellValue("片");
            demo.createCell(4).setCellValue("128.00");
            demo.createCell(5).setCellValue("80.00");
            demo.createCell(6).setCellValue("1000");
            demo.createCell(7).setCellValue("底价");
            demo.createCell(8).setCellValue("118.00");
            demo.createCell(9).setCellValue("");
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("model template build failed", e);
        }
    }

    private record ModelRow(String name, String goodsName, String brand, String unit, Long price,
            Long purchase, Integer discount, String calcType, Long baseValue, Integer pointValue) {
    }

    private List<ModelRow> parseModels(byte[] xlsx) {
        List<ModelRow> out = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(new java.io.ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isModelBlankRow(row)) {
                    continue;
                }
                out.add(new ModelRow(str(row, 0), str(row, 1), str(row, 2), str(row, 3),
                        num(row, 4), num(row, 5), intVal(row, 6), str(row, 7),
                        num(row, 8), intVal(row, 9)));
            }
            return out;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(CODE_IMPORT_INVALID, msg("goods.import.unreadable"));
        }
    }

    private boolean isModelBlankRow(Row row) {
        for (int c = 0; c < MODEL_HEADERS.length; c++) {
            String v = str(row, c);
            if (v != null && !v.isBlank()) {
                return false;
            }
        }
        return true;
    }

    /** 整数列(折扣/点位,千分位);空返回 null;非法返回 -1(校验层报错)。 */
    private Integer intVal(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) {
            return null;
        }
        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                return (int) Math.round(cell.getNumericCellValue());
            }
            String v = cell.toString().trim();
            return v.isEmpty() ? null : (int) Math.round(Double.parseDouble(v));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 型号批量导入(V1.3):型号名在库内不唯一(同型号可挂不同商品,uk 仅 no),故不做名称查重;
     * 商品/品牌可空且填则必须已存在;计价方式=底价(必填底价)/点位(必填点位 1-100000);
     * 折扣千分位 1-100000;no 按 id 生成(M+8位,md5 占位防并发撞号);全量校验整单拒。
     */
    @Transactional
    public ImportReport importModels(long orgId, byte[] xlsx, long operatorId) {
        List<ModelRow> rows = parseModels(xlsx);
        if (rows.isEmpty()) {
            throw new BizException(CODE_IMPORT_INVALID, msg("goods.import.empty"));
        }
        List<Map<String, Object>> errors = new ArrayList<>();
        Map<Integer, Long> goodsIds = new LinkedHashMap<>();
        Map<Integer, Long> brandIds = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            int rowNo = i + 2;
            ModelRow r = rows.get(i);
            check(errors, rowNo, "型号名称", notBlank(r.name()), "goods.import.required");
            if (notBlank(r.goodsName())) {
                Long gid = goodsIdByName(orgId, r.goodsName().trim());
                if (gid == null) {
                    check(errors, rowNo, "商品名称", false, "goods.import.goodsMissing");
                } else {
                    goodsIds.put(i, gid);
                }
            }
            if (notBlank(r.brand())) {
                Long bid = brandIdByName(orgId, r.brand());
                if (bid == null) {
                    check(errors, rowNo, "品牌", false, "goods.import.brand");
                } else {
                    brandIds.put(i, bid);
                }
            }
            check(errors, rowNo, "单位", notBlank(r.unit()), "goods.import.required");
            check(errors, rowNo, "单价(元)", r.price() != null && r.price() > 0, "goods.import.price.positive");
            if (r.purchase() != null && r.purchase() < 0) {
                check(errors, rowNo, "进价(元)", false, "goods.import.number");
            }
            String calc = r.calcType() == null ? "" : r.calcType().trim();
            check(errors, rowNo, "计价方式", calc.equals("底价") || calc.equals("点位"),
                    "goods.import.model.calcType");
            if (calc.equals("底价")) {
                check(errors, rowNo, "底价(元)", r.baseValue() != null && r.baseValue() > 0,
                        "goods.import.model.baseNeed");
                check(errors, rowNo, "点位(‰)", r.pointValue() == null, "goods.import.model.pointNoise");
            } else if (calc.equals("点位")) {
                check(errors, rowNo, "点位(‰)", r.pointValue() != null && r.pointValue() >= 1
                        && r.pointValue() <= 100000, "goods.import.model.pointRange");
                check(errors, rowNo, "底价(元)", r.baseValue() == null, "goods.import.model.baseNoise");
            }
            if (r.discount() != null && (r.discount() < 1 || r.discount() > 100000)) {
                check(errors, rowNo, "折扣(‰)", false, "goods.import.model.discountRange");
            }
        }
        if (!errors.isEmpty()) {
            throw new ImportInvalidException(new ImportReport(rows.size(), 0, errors));
        }
        for (int i = 0; i < rows.size(); i++) {
            ModelRow r = rows.get(i);
            String calc = r.calcType().trim();
            jdbc.update("""
                    insert into basedata_material_model
                        (org_id, no, name, goods_id, brand_id, unit, price, purchase, discount,
                         calc_type, base_value, point_value, status, created_by)
                    values (?, concat('M', left(md5(uuid()), 8)), ?,?,?,?,?,?,?,?,?,?,0,?)
                    """, orgId, r.name().trim(), goodsIds.get(i), brandIds.get(i),
                    r.unit().trim(), r.price(), toLi(r.purchase()), r.discount(),
                    calc.equals("点位") ? 1 : 0, toLi(r.baseValue()),
                    r.pointValue() != null && r.pointValue() >= 1 ? r.pointValue() : null, operatorId);
            long id = jdbc.queryForObject("select last_insert_id()", Long.class);
            jdbc.update("update basedata_material_model set no = concat('M', lpad(id, 8, '0')) where id = ?", id);
        }
        recordImportHistory(orgId, "model", rows.size(), 0, null, operatorId);
        return new ImportReport(rows.size(), rows.size(), errors);
    }
}
