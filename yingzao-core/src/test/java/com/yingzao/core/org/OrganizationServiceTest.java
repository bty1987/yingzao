package com.yingzao.core.org;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.yingzao.core.KernelTestBase;
import com.yingzao.core.org.OrganizationService.CreateBody;
import com.yingzao.core.org.OrganizationService.UpdateBody;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组织树单测(F4a 内核高险面,docs/15 R26~R27,docs/08 组织模型):物化路径/懒建根/
 * 停用级联-启用只恢复本节点/数据范围 scopeOrgIds 子树语义。S2 数据范围基座与
 * addon-target 聚合、dashboard 大区排名都复用本树。
 */
class OrganizationServiceTest extends KernelTestBase {

    private OrganizationService orgs;

    @Test
    void lazyRootCreatedOnce() {
        orgs = new OrganizationService(jdbc, STUB_MESSAGES);
        Map<String, Object> row = orgs.rowOf(42);
        assertEquals("/42/", row.get("org_path"));
        assertEquals("company", row.get("level_type"));
        assertEquals(0, ((Number) row.get("status")).intValue());
        assertNull(row.get("parent_id"));
        orgs.rowOf(42);
        Integer n = jdbc.queryForObject("select count(*) from organization where id = 42", Integer.class);
        assertEquals(1, n);
    }

    @Test
    void createGuards() {
        orgs = new OrganizationService(jdbc, STUB_MESSAGES);
        expectBiz(OrganizationService.CODE_NOT_FOUND, () -> orgs.create(1, null, 1));
        expectBiz(OrganizationService.CODE_NOT_FOUND,
                () -> orgs.create(1, new CreateBody(1L, "  ", null), 1));
        expectBiz(OrganizationService.CODE_NOT_FOUND,
                () -> orgs.create(1, new CreateBody(1L, "华东", "bogus"), 1));
        expectBiz(OrganizationService.CODE_PARENT_NOT_FOUND,
                () -> orgs.create(1, new CreateBody(null, "华东", "region"), 1));
        expectBiz(OrganizationService.CODE_PARENT_NOT_FOUND,
                () -> orgs.create(1, new CreateBody(0L, "华东", "region"), 1));
    }

    @Test
    void createRejectsDisabledParentAndDupSibling() {
        orgs = new OrganizationService(jdbc, STUB_MESSAGES);
        long region = orgs.create(1, new CreateBody(1L, "华东", "region"), 1);
        orgs.changeStatus(1, region, 1);
        expectBiz(OrganizationService.CODE_PARENT_NOT_FOUND,
                () -> orgs.create(1, new CreateBody(region, "上海部", null), 1));
        orgs.changeStatus(1, region, 0);
        orgs.create(1, new CreateBody(region, "上海部", null), 1);
        expectBiz(OrganizationService.CODE_DUP_NAME,
                () -> orgs.create(1, new CreateBody(region, "上海部", null), 1));
    }

    @Test
    void createBuildsMaterializedPathAndTreeNesting() {
        orgs = new OrganizationService(jdbc, STUB_MESSAGES);
        orgs.rowOf(1);
        long region = orgs.create(1, new CreateBody(1L, "华东", "region"), 1);
        long dept = orgs.create(1, new CreateBody(region, "上海部", "department"), 1);
        assertEquals("/1/" + region + "/",
                jdbc.queryForObject("select org_path from organization where id = ?", String.class, region));
        assertEquals("/1/" + region + "/" + dept + "/",
                jdbc.queryForObject("select org_path from organization where id = ?", String.class, dept));
        List<Map<String, Object>> tree = orgs.tree();
        assertEquals(1, tree.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> regionChildren =
                (List<Map<String, Object>>) tree.get(0).get("children");
        assertEquals(1, regionChildren.size());
        assertEquals("华东", regionChildren.get(0).get("name"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> deptChildren =
                (List<Map<String, Object>>) regionChildren.get(0).get("children");
        assertEquals("上海部", deptChildren.get(0).get("name"));
    }

    @Test
    void updateRenameWithDupGuard() {
        orgs = new OrganizationService(jdbc, STUB_MESSAGES);
        orgs.rowOf(1);
        long a = orgs.create(1, new CreateBody(1L, "华东", "region"), 1);
        long b = orgs.create(1, new CreateBody(1L, "华南", "region"), 1);
        orgs.update(1, b, new UpdateBody("西南", "region"));
        assertEquals("西南", jdbc.queryForObject(
                "select name from organization where id = ?", String.class, b));
        expectBiz(OrganizationService.CODE_DUP_NAME,
                () -> orgs.update(1, b, new UpdateBody("华东", null)));
        orgs.changeStatus(1, b, 1);
        expectBiz(OrganizationService.CODE_NOT_FOUND,
                () -> orgs.update(1, b, new UpdateBody("华北", null)));
        expectBiz(OrganizationService.CODE_NOT_FOUND, () -> orgs.update(1, a, new UpdateBody(" ", null)));
    }

    @Test
    void disableCascadesSubtreeEnableRestoresNodeOnly() {
        orgs = new OrganizationService(jdbc, STUB_MESSAGES);
        orgs.rowOf(1);
        long region = orgs.create(1, new CreateBody(1L, "华东", "region"), 1);
        long dept = orgs.create(1, new CreateBody(region, "上海部", "department"), 1);
        // 根不可停
        expectBiz(OrganizationService.CODE_ROOT_UNDISABLE, () -> orgs.changeStatus(1, 1, 1));
        // 停用=子树整体停
        orgs.changeStatus(1, region, 1);
        assertEquals(1, jdbc.queryForObject(
                "select status from organization where id = ?", Integer.class, dept));
        // 启用只恢复本节点,子节点保持停用(逐个手动启用)
        orgs.changeStatus(1, region, 0);
        assertEquals(0, jdbc.queryForObject(
                "select status from organization where id = ?", Integer.class, region));
        assertEquals(1, jdbc.queryForObject(
                "select status from organization where id = ?", Integer.class, dept));
    }

    @Test
    void scopeOrgIdsSubtreeSemantics() {
        orgs = new OrganizationService(jdbc, STUB_MESSAGES);
        orgs.rowOf(1);
        long region = orgs.create(1, new CreateBody(1L, "华东", "region"), 1);
        long dept = orgs.create(1, new CreateBody(region, "上海部", "department"), 1);
        long other = orgs.create(1, new CreateBody(1L, "华南", "region"), 1);
        assertEquals(List.of(1L, region, dept, other).stream().sorted().collect(Collectors.toList()),
                orgs.scopeOrgIds(1).stream().sorted().collect(Collectors.toList()));
        // 子树范围:部门节点只含自身与下级,不含兄弟分支
        assertEquals(List.of(region, dept).stream().sorted().collect(Collectors.toList()),
                orgs.scopeOrgIds(region).stream().sorted().collect(Collectors.toList()));
        // 停用子树退出范围;停用节点自身范围=空(不可作为登录数据源)
        orgs.changeStatus(1, region, 1);
        assertTrue(orgs.scopeOrgIds(1).contains(1L) && !orgs.scopeOrgIds(1).contains(dept));
        assertTrue(orgs.scopeOrgIds(region).isEmpty());
        assertEquals(List.of(other), orgs.scopeOrgIds(other));
    }

    @Test
    void childrenOfReturnsEnabledOnly() {
        orgs = new OrganizationService(jdbc, STUB_MESSAGES);
        orgs.rowOf(1);
        long a = orgs.create(1, new CreateBody(1L, "华东", "region"), 1);
        long x = orgs.create(1, new CreateBody(1L, "华南", "region"), 1);
        List<Map<String, Object>> children = orgs.childrenOf(1);
        assertEquals(2, children.size());
        orgs.changeStatus(1, a, 1);
        children = orgs.childrenOf(1);
        assertEquals(1, children.size());
        assertEquals(x, ((Number) children.get(0).get("id")).longValue());
    }
}
