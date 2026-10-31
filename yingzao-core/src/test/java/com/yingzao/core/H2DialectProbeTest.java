package com.yingzao.core;

import java.sql.Timestamp;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2 方言探针(F4a 骨架):生产 SQL 的全部 MySQL 习语先在 H2 MODE=MySQL 上过一遍,
 * 任一不支持 = 当场红灯,避免后续逐类试错。探针用例不删——它们同时是「测试库与
 * 生产方言契约」的常驻回归锚(升级 H2/Spring 时先看这里)。
 */
class H2DialectProbeTest extends KernelTestBase {

    @Test
    void lastInsertIdWorksOnSameConnection() {
        jdbc.update("insert into yz_user(org_id, username, password_sha256, display_name) values (1,'u','p','n')");
        Long id = jdbc.queryForObject("select last_insert_id()", Long.class);
        assertNotEquals(0L, id == null ? 0 : id);
    }

    @Test
    void insertIgnoreSupported() {
        jdbc.update("insert into organization(id, parent_id, name, level_type, org_path, status) "
                + "values (7, null, '根', 'company', '/7/', 0)");
        jdbc.update("insert ignore into organization(id, parent_id, name, level_type, org_path, status) "
                + "values (7, null, '根', 'company', '/7/', 0)");
        Integer n = jdbc.queryForObject("select count(*) from organization where id = 7", Integer.class);
        assertEquals(1, n);
    }

    @Test
    void dateSubAndDateAddWithIntervalSupported() {
        // 生产谓词原文(YzEventBus/PlanService claim 与退避)经方言垫片跑通
        Timestamp past = jdbc.queryForObject(
                "select date_sub(now(), interval 5 minute)", Timestamp.class);
        assertTrue(past.before(new Timestamp(System.currentTimeMillis())));
        // 参数化 interval(fail() 退避写入)
        Timestamp future = jdbc.queryForObject(
                "select date_add(now(), interval ? second)", Timestamp.class, 30);
        assertTrue(future.after(new Timestamp(System.currentTimeMillis())));
    }

    @Test
    void productionClaimPredicateRunsVerbatim() {
        // PlanService.claim 的 WHERE 全文照抄——方言垫片必须原样吃下生产文本块
        jdbc.update("insert into yz_plan(org_id, handler) values (1, 'probe')");
        long id = jdbc.queryForObject("select last_insert_id()", Long.class);
        jdbc.update("""
                update yz_plan set claimed_at = now()
                where id = ? and status in (0, 2)
                  and (next_run_time is null or next_run_time <= now())
                  and (claimed_at is null or claimed_at < date_sub(now(), interval 30 minute))
                """, id);
        Integer claimed = jdbc.queryForObject("""
                select count(*) from yz_plan where id = ? and claimed_at >= date_sub(now(), interval 1 minute)
                """, Integer.class, id);
        assertEquals(1, claimed);
    }

    @Test
    void concatAndLikePathSupported() {
        jdbc.update("insert into organization(id, parent_id, name, level_type, org_path, status) "
                + "values (5, null, '根', 'company', '/5/', 0)");
        // 懒建先插 pending 占位行、path 后补(ProductionService.create 同序)
        jdbc.update("insert into organization(id, parent_id, name, level_type, org_path, status) "
                + "values (6, 5, '上海部', 'department', 'pending', 0)");
        jdbc.update("update organization set org_path = concat(?, ?, '/') where id = ?", "/5/", 6, 6);
        String path = jdbc.queryForObject("select org_path from organization where id = 6", String.class);
        assertEquals("/5/6/", path);
        Integer inSubtree = jdbc.queryForObject(
                "select count(*) from organization where org_path like ? and status = 0", Integer.class, "/5/%");
        assertEquals(2, inSubtree);
    }

    @Test
    void insertIgnoreCompositeKeySupported() {
        jdbc.update("insert into yz_event_consumed(task_id, consumer) values (1, 'A')");
        jdbc.update("insert ignore into yz_event_consumed(task_id, consumer) values (1, 'A')");
        Integer n = jdbc.queryForObject(
                "select count(*) from yz_event_consumed where task_id = 1", Integer.class);
        assertEquals(1, n);
    }

    @Test
    void textColumnRoundTrip() {
        jdbc.update("insert into yz_plan(org_id, handler, params, result, last_error) values (1, 'h', ?, ?, ?)",
                "{\"a\":1}", "中文结果", "中文错误");
        Map<String, Object> row = jdbc.queryForList(
                "select params, result, last_error from yz_plan where handler = 'h'").get(0);
        assertEquals("{\"a\":1}", row.get("params"));
        assertEquals("中文结果", row.get("result"));
    }
}
