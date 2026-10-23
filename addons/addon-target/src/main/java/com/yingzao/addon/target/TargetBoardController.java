package com.yingzao.addon.target;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yingzao.addons.api.DecorationApi;
import com.yingzao.core.org.OrgScopeService;
import com.yingzao.core.web.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 经营目标看板数据源(/api/target/board;菜单闸同 /target/plans)。
 * V1 诚实口径:实绩=公司级装修单签约额(业务单据尚无分区/部门归属,分区节点实绩恒 0)。
 */
@RestController
public class TargetBoardController {

    private final JdbcTemplate jdbc;
    private final DecorationApi decorations;
    private final OrgScopeService orgScopes;
    private final MessageSource messages;

    public TargetBoardController(JdbcTemplate jdbc, DecorationApi decorations,
            OrgScopeService orgScopes, MessageSource messages) {
        this.jdbc = jdbc;
        this.decorations = decorations;
        this.orgScopes = orgScopes;
        this.messages = messages;
    }

    /** 节点月度看板:目标/实绩/达成率 + scope 内逐组织实绩明细。 */
    @GetMapping("/api/target/board")
    public ApiResponse<Map<String, Object>> board(@RequestParam long node,
            @RequestParam String ym, HttpServletRequest request) {
        Long target = jdbc.queryForObject("""
                select amount from target_plan
                where org_node = ? and ym = ? and line = 'contract' and status = 0
                order by id limit 1
                """, Long.class, node, ym);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select o.id as org_id, o.name as org_name
                from organization o
                where o.org_path like concat((select org_path from organization where id = ?), '%')
                  and o.status = 0
                order by o.id
                """, node);
        long total = 0;
        for (Map<String, Object> r : rows) {
            long org = ((Number) r.get("org_id")).longValue();
            long actual = decorations.contractSignedTotalByMonth(org, ym);
            r.put("actual", actual);
            total += actual;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("node", node);
        out.put("yearMonth", ym);
        out.put("target", target);
        out.put("actual", total);
        out.put("rate", target == null || target == 0 ? null : (long) Math.min(total * 1000 / target, 999999));
        out.put("note", messages.getMessage("target.board.companyScopeNote", null,
                LocaleContextHolder.getLocale()));
        return ApiResponse.ok(out);
    }
}
