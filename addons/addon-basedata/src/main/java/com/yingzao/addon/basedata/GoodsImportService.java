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

        for (int i = 0; i < rows.size(); i++) {
            int rowNo = i + 2; // Excel 行号(1 表头)
            RowData r = rows.get(i);
            check(errors, rowNo, "商品名称", notBlank(r.name), "goods.import.required");
            check(errors, rowNo, "拼音", r.pinyin != null && r.pinyin.matches("[a-z][a-z0-9]*"),
                    "goods.import.pinyin");
            check(errors, rowNo, "单位", notBlank(r.unit), "goods.import.required");
            check(errors, rowNo, "售价(元)", r.selling != null, "goods.import.number");

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

        if (!errors.isEmpty()) {
            throw new ImportInvalidException(new ImportReport(rows.size(), 0, errors));
        }

        for (int i = 0; i < rows.size(); i++) {
            RowData r = rows.get(i);
            goods.create(orgId, new GoodsService.CreateGoodsCmd(r.name.trim(), null, r.pinyin,
                    blankToNull(r.barcode), categoryIds.get(i), brandIds.get(i), r.unit.trim(),
                    GoodsService.TYPE_GOODS, toLi(r.purchase), toLi(r.selling), toLi(r.confine),
                    null), operatorId);
        }
        return new ImportReport(rows.size(), rows.size(), errors);
    }

    /** 携报告的业务异常(controller 捕获后把 errors 放返回体;消息统一 260023)。 */
    public static class ImportInvalidException extends RuntimeException {
        public final ImportReport report;

        public ImportInvalidException(ImportReport report) {
            super("import invalid");
            this.report = report;
        }
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    public BizException importInvalid() {
        return new BizException(CODE_IMPORT_INVALID, msg("goods.import.invalid"));
    }

    // ---- 解析 ----

    private record RowData(String name, String pinyin, String category, String brand, String unit,
            String barcode, Long selling, Long purchase, Long confine) {
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
                        str(row, 5), num(row, 6), num(row, 7), num(row, 8)));
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
}
