package com.yingzao.addon.construction;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.yingzao.addons.api.ConstructionApi;

/**
 * ConstructionApi 只读契约实现(construction 侧):装修决算六项检查「施工」子项取数。
 * 只读本域排期快照(account=1 快照列,点火时拷贝自节点模板),零跨域依赖。
 */
@Component
public class ConstructionApiImpl implements ConstructionApi {

    private final JdbcTemplate jdbc;

    public ConstructionApiImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AccountNodesCheck accountNodes(long orgId, long orderId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select node_name, status from construction_schedule
                where org_id = ? and order_id = ? and account = 1
                order by sort, id
                """, orgId, orderId);
        int total = rows.size();
        int finished = 0;
        String firstPending = null;
        for (Map<String, Object> row : rows) {
            boolean done = ((Number) row.get("status")).intValue() == ConstructionService.SC_FINISH;
            if (done) {
                finished++;
            } else if (firstPending == null) {
                firstPending = (String) row.get("node_name");
            }
        }
        return new AccountNodesCheck(total > 0 && finished == total, total, finished, firstPending);
    }

    /** C 端进度脱敏视图数据源:节点名+状态+计划/实际时间,零金额零人员。 */
    @Override
    public java.util.List<ProgressNode> progress(long orgId, long orderId) {
        return jdbc.queryForList("""
                select node_name, sort, status, plan_start, plan_end, end_time
                from construction_schedule where org_id = ? and order_id = ?
                order by sort, id
                """, orgId, orderId).stream()
                .map(r -> new ProgressNode((String) r.get("node_name"),
                        ((Number) r.get("sort")).intValue(),
                        ((Number) r.get("status")).intValue(),
                        r.get("plan_start") == null ? null : String.valueOf(r.get("plan_start")),
                        r.get("plan_end") == null ? null : String.valueOf(r.get("plan_end")),
                        r.get("end_time") == null ? null : String.valueOf(r.get("end_time"))))
                .toList();
    }

    /** C 端工地相册数据源:带图日记行 images 平铺时间倒序;images JSON 解析失败按空表跳过(脏数据不炸投影)。 */
    @Override
    public java.util.List<AlbumPhoto> customerAlbum(long orgId, long orderId) {
        record DiaryRow(long id, String nodeName, String createdAt, java.util.List<Long> images) {
        }
        List<DiaryRow> diaries = jdbc.queryForList("""
                select d.id, s.node_name, d.created_at, d.images
                from construction_diary d join construction_schedule s on s.id = d.schedule_id
                where d.org_id = ? and d.order_id = ?
                order by d.id desc
                """, orgId, orderId).stream()
                .map(r -> new DiaryRow(((Number) r.get("id")).longValue(),
                        (String) r.get("node_name"),
                        String.valueOf(r.get("created_at")),
                        parseImages(r.get("images"))))
                .toList();
        return diaries.stream()
                .flatMap(d -> d.images().stream().map(fid -> new AlbumPhoto(fid, d.nodeName(), d.createdAt())))
                .toList();
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper ALBUM_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private java.util.List<Long> parseImages(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return java.util.List.of();
        }
        try {
            return ALBUM_MAPPER.readValue(String.valueOf(raw),
                    ALBUM_MAPPER.getTypeFactory().constructCollectionType(java.util.List.class, Long.class));
        } catch (Exception e) {
            return java.util.List.of();
        }
    }
}
