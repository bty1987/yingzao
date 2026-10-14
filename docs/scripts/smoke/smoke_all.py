# -*- coding: utf-8 -*-
"""营造 Yingzao 一键冒烟(2026-10-11 第 7 次运行沉淀为资产,第 8 次扩展 RBAC+多级审批,第 9 次扩展转交×多级串联,第 23 次扩展库存域)。

覆盖:认证 → RBAC 菜单/功能点过滤 → 客源中文登记 → 手动分配 → 机会状态机 → 站内通知 → 轻量审批闭环 → 多级审批串联 → 转交×多级串联。
前置:服务已起(docs/scripts/smoke/README.md),python ensure_users.py 已跑(smoke_all 会自动先跑)。
用法:python smoke_all.py   → 逐步 ✓/✗,汇总 N/N PASS,任何 ✗ 退出码 1。
"""
import io
import json
import os
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get("YINGZAO_BASE", "http://127.0.0.1:8080").rstrip("/")
PASSWORD = "yingzao123"

passed, failed = [], []


def db_conn():
    """dev 库直连(仅死信链注入与余额对账用;接口断言全走 urllib)。"""
    import pymysql
    return pymysql.connect(
        host=os.environ.get("YINGZAO_DB_HOST", "192.168.5.21"),
        port=int(os.environ.get("YINGZAO_DB_PORT", "3306")),
        database=os.environ.get("YINGZAO_DB_NAME", "yingzao"),
        user=os.environ.get("YINGZAO_DB_USER", "root"),
        password=os.environ.get("YINGZAO_DB_PASSWORD", "Neusoft!"),
        charset="utf8mb4",
    )


def event_consumed(event_type, event_key, name):
    """第 39 次运行:审批回写链迁总线后的事件出站箱对账——断言某事件已发布且消费确认(status=1)。
    主链事件走 AFTER_COMMIT 同步投递即时确认;嵌套发布(消费器事务内派生的子事件,如多级审批 L2
    的 submitted)的同步回调不触发,由调度器(冒烟轮询 2s)兜底投递——统一轮询等待,上限 30s。"""
    deadline = time.time() + 30
    row = None
    while time.time() < deadline:
        conn = db_conn()
        try:
            with conn.cursor() as cur:
                cur.execute("select status, retry_count from yz_event_task "
                            "where event_type = %s and event_key = %s order by id desc limit 1",
                            (event_type, event_key))
                row = cur.fetchone()
        finally:
            conn.close()
        if row is not None and row[0] == 1:
            break
        time.sleep(1)
    step(name, row is not None and row[0] == 1, row)


def step(name, ok, detail=""):
    (passed if ok else failed).append(name)
    print("%s %s%s" % ("✓" if ok else "✗", name, ("  | " + str(detail)) if detail and not ok else ""))


def call(method, path, token=None, body=None, expect_http=200):
    """返回 (http_status, 响应体 dict);body 为 None 时按无内容处理。"""
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Content-Type", "application/json; charset=utf-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, data=data, timeout=15) as res:
                raw = res.read().decode("utf-8")
                return res.status, (json.loads(raw) if raw.strip() else None)
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8")
            try:
                return e.code, json.loads(raw)
            except ValueError:
                return e.code, {"raw": raw}
        except (urllib.error.URLError, OSError):
            if attempt == 2:
                raise
            time.sleep(2)


def api_ok(name, method, path, token=None, body=None):
    """业务成功断言:HTTP 200 且 code=0,返回 data。"""
    status, res = call(method, path, token, body)
    ok = status == 200 and isinstance(res, dict) and res.get("code") == 0
    step(name, ok, res)
    return res.get("data") if ok else None


def api_err(name, method, path, token, body, want_code, want_http=200):
    """业务失败断言:协议约定 HTTP 200 + 业务码。"""
    status, res = call(method, path, token, body)
    ok = status == want_http and isinstance(res, dict) and res.get("code") == want_code
    step(name, ok, "http=%s body=%s" % (status, res))


def login(username):
    status, res = call("POST", "/api/auth/login", body={"username": username, "password": PASSWORD})
    ok = status == 200 and isinstance(res, dict) and res.get("code") == 0
    step("登录 %s" % username, ok, res)
    return res.get("data") if ok else None


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    subprocess.run([sys.executable, os.path.join(here, "ensure_users.py")], check=True)

    print("== 认证 ==")
    user_b = login("example_b")
    user_c = login("example_c")
    user_admin = login("admin")
    if not user_b or not user_c or not user_admin:
        print("登录失败,终止(服务未起或账号缺失)")
        return 1
    tok_b, tok_c, tok_admin = user_b["token"], user_c["token"], user_admin["token"]
    uid_c, uid_admin = user_c["userId"], user_admin["userId"]
    uid_b = user_b["userId"]
    step("双账号不同身份", user_b["userId"] != user_c["userId"], (user_b, user_c))
    step("admin 登录含内置角色", "admin" in (user_admin.get("roles") or []), user_admin)

    status, res = call("POST", "/api/auth/login", body={"username": "example_b", "password": "wrong"})
    step("错误密码 110001", status == 200 and res.get("code") == 110001, res)

    status, res = call("GET", "/api/customer/leads")
    step("无 token 401", status == 401, (status, res))
    status, res = call("GET", "/api/addons")
    step("addons 去白名单:无 token 401", status == 401, (status, res))

    # RBAC v2 员工角色:example_b/c 全菜单+全功能点授权(业务流不受端点鉴权阻断;幂等=先删后建)
    addons_emp = api_ok("拉全量插件清单(员工角色授权源)", "GET", "/api/addons", tok_admin)
    all_menus = sorted({m["path"] for a in (addons_emp or []) for m in (a.get("menus") or [])})
    all_caps = sorted({m["capability"] for a in (addons_emp or []) for m in (a.get("mounts") or [])
                       if m.get("capability")})
    roles_emp = api_ok("角色列表(查旧员工角色)", "GET", "/api/roles", tok_admin)
    for r in (roles_emp or []):
        if str(r.get("code", "")).startswith("rbac_smoke_"):
            api_ok("清理历史 RBAC 测试角色", "DELETE", "/api/roles/%s" % r["id"], tok_admin)
    old_emp = next((r for r in (roles_emp or []) if r.get("code") == "emp_all"), None)
    if old_emp:
        api_ok("删旧员工角色", "DELETE", "/api/roles/%s" % old_emp["id"], tok_admin)
    emp_id = api_ok("建员工角色 emp_all", "POST", "/api/roles", tok_admin,
                    {"code": "emp_all", "name": "冒烟全业务员工"})
    api_ok("员工角色全菜单授权", "PUT", "/api/roles/%s/menus" % emp_id, tok_admin,
           {"menus": all_menus})
    api_ok("员工角色全功能点授权", "PUT", "/api/roles/%s/capabilities" % emp_id, tok_admin,
           {"capabilities": all_caps})
    api_ok("员工角色绑定 b/c", "PUT", "/api/roles/%s/users" % emp_id, tok_admin,
           {"userIds": [uid_b, uid_c]})

    print("== RBAC 菜单/功能点过滤 ==")
    addons_admin = api_ok("admin 插件下发(旁路)", "GET", "/api/addons", tok_admin)
    cust = next((a for a in (addons_admin or []) if a.get("id") == "customer"), None)
    ok = cust and len(cust.get("menus") or []) == 2 \
        and any((m.get("capability") or "") == "customerprofile:view" for m in (cust.get("mounts") or []))
    step("admin 可见双菜单+capability 挂载", ok, cust)

    user_d = login("example_d")  # org1 永不授权账号(真无角色,v1 段专用)
    tok_d = user_d["token"] if user_d else None
    addons_b0 = api_ok("无角色用户插件下发(example_d)", "GET", "/api/addons", tok_d)
    step("无角色用户可见为空(全量过滤)", addons_b0 == [], addons_b0)

    api_err("非管理员建角色拒绝 122003", "POST", "/api/roles", tok_b,
            {"code": "noadmin", "name": "越权角色"}, 122003)

    stamp = time.strftime("%Y%m%d%H%M%S")
    role_code = "smokerole" + stamp[-6:]
    role_id = api_ok("admin 建角色", "POST", "/api/roles", tok_admin,
                     {"code": role_code, "name": "冒烟客服", "remark": "冒烟自动创建"})
    if role_id is None:
        return finish()
    api_err("同编码角色拒绝 122004", "POST", "/api/roles", tok_admin,
            {"code": role_code, "name": "重复编码"}, 122004)
    api_ok("授权双菜单", "PUT", "/api/roles/%s/menus" % role_id, tok_admin,
           {"menus": ["/customer/leads", "/customer/chances"]})
    api_ok("授权功能点", "PUT", "/api/roles/%s/capabilities" % role_id, tok_admin,
           {"capabilities": ["customerprofile:view"]})
    api_ok("绑定成员 example_d", "PUT", "/api/roles/%s/users" % role_id, tok_admin,
           {"userIds": [user_d["userId"]]})

    detail = api_ok("角色详情回显", "GET", "/api/roles/%s" % role_id, tok_admin)
    ok = detail and sorted(detail.get("menus") or []) == ["/customer/chances", "/customer/leads"] \
        and detail.get("capabilities") == ["customerprofile:view"] \
        and [u.get("id") for u in (detail.get("users") or [])] == [user_d["userId"]]
    step("详情含授权与成员", ok, detail)

    addons_b1 = api_ok("授权后插件下发(example_d)", "GET", "/api/addons", tok_d)
    cust1 = next((a for a in (addons_b1 or []) if a.get("id") == "customer"), None)
    ok = cust1 and sorted(m.get("path") for m in cust1.get("menus") or []) == \
        ["/customer/chances", "/customer/leads"] \
        and any((m.get("capability") or "") == "customerprofile:view" for m in (cust1.get("mounts") or []))
    step("授权后可见菜单与挂载", ok, cust1)
    # 功能点已授权 → 其挂载所在插件条目合法出现,但未授权的菜单必须仍为空(不泄露)
    prof1 = next((a for a in (addons_b1 or []) if a.get("id") == "customerprofile"), None)
    step("未授权菜单不可见(capability 挂载随授权下发)", prof1 is not None and prof1.get("menus") == [], prof1)

    api_ok("删除角色", "DELETE", "/api/roles/%s" % role_id, tok_admin)
    addons_b2 = api_ok("删除后插件下发(example_d)", "GET", "/api/addons", tok_d)
    step("删除后可见回落为空", addons_b2 == [], addons_b2)

    print("== 客源 → 分配 → 机会 ==")
    stamp = time.strftime("%Y%m%d%H%M%S")
    lead_name = "夜烟客" + stamp
    lead_id = api_ok("中文登记客源", "POST", "/api/customer/leads", tok_b,
                     {"name": lead_name, "phone": "138%08d" % (int(stamp) % 100000000),
                      "source": "referral", "note": "冒烟自动登记"})
    if lead_id is None:
        return finish()

    chance_id = api_ok("手动分配给 example_c", "POST", "/api/customer/leads/%s/assign" % lead_id,
                       tok_b, {"mode": "manual", "assigneeId": uid_c})
    api_err("重复分配幂等拒绝 210003", "POST", "/api/customer/leads/%s/assign" % lead_id,
            tok_b, {"mode": "manual", "assigneeId": uid_c}, 210003)
    api_err("未知分配方式 210007", "POST", "/api/customer/leads/%s/assign" % lead_id,
            tok_b, {"mode": "lottery", "assigneeId": uid_c}, 210007)

    chances = api_ok("机会列表(example_c 视角)", "GET", "/api/customer/chances", tok_c)
    mine = [c for c in (chances or []) if c.get("id") == chance_id]
    ok = len(mine) == 1 and mine[0]["status"] == 0 and mine[0]["lead_name"] == lead_name \
        and mine[0]["assignee_name"] == user_c["displayName"]
    step("列表含本单(中文客源名/责任人)", ok, mine)

    print("== 机会状态机 ==")
    api_err("跳步完成拒绝 210005", "POST", "/api/customer/chances/%s/complete" % chance_id, tok_c, None, 210005)
    api_ok("确认 0→3", "POST", "/api/customer/chances/%s/confirm" % chance_id, tok_c)
    api_err("重复确认拒绝 210005", "POST", "/api/customer/chances/%s/confirm" % chance_id, tok_c, None, 210005)
    api_ok("完成 3→4", "POST", "/api/customer/chances/%s/complete" % chance_id, tok_c)

    print("== 站内通知 ==")
    count = api_ok("责任人未读数", "GET", "/api/notifications/unread-count", tok_c)
    step("分配通知已落(未读≥1)", isinstance(count, int) and count >= 1, count)
    notifs = api_ok("通知列表", "GET", "/api/notifications?unreadOnly=true", tok_c)
    # 客源名在 content(标题是固定文案「新的客源机会」)
    hit = [n for n in (notifs or []) if lead_name in str(n.get("content", ""))]
    step("通知正文含中文客源名", len(hit) >= 1, notifs)
    if hit:
        api_ok("单条已读", "POST", "/api/notifications/%s/read" % hit[0]["id"], tok_c)
    count2 = api_ok("已读后再查未读数", "GET", "/api/notifications/unread-count", tok_c)
    step("未读数已回落", count2 == count - 1, (count, count2))

    # 第 38 次运行:分配事件改走内核事件总线,出站箱消费确认对账
    conn_la = db_conn()
    try:
        with conn_la.cursor() as cur:
            cur.execute("select status from yz_event_task where event_type = 'LeadAssignedEvent' "
                        "and event_key = %s order by id desc limit 1",
                        ("lead-assigned-" + str(chance_id),))
            la_row = cur.fetchone()
    finally:
        conn_la.close()
    step("分配事件走总线(LeadAssignedEvent 消费确认)", la_row is not None and la_row[0] == 1, la_row)

    print("== 轻量审批闭环(leave 示范)==")
    leave_id = api_ok("example_b 提请假条(返回业务单id)", "POST", "/api/example/leaves", tok_b,
                      {"reason": "冒烟请假" + stamp, "days": 1, "assigneeId": uid_c})
    if leave_id is None:
        return finish()

    leaves0 = api_ok("leave 行反查审批单id", "GET", "/api/example/leaves", tok_b)
    rows0 = [l for l in (leaves0 or []) if l.get("id") == leave_id]
    approval_id = rows0[0].get("approval_id") if rows0 else None
    step("业务单已挂审批单", bool(approval_id), rows0)
    if not approval_id:
        return finish()

    todo = api_ok("example_c 待办列表", "GET", "/api/approvals?role=todo", tok_c)
    # 待办行的 id 即审批单id(biz_id 是溯源三元组里的业务单id,两码事)
    step("待办含本单(审批单id 对上)", any(str(a.get("id")) == str(approval_id) for a in (todo or [])), todo)

    detail_b = api_ok("发起人视角详情", "GET", "/api/approvals/%s" % approval_id, tok_b)
    step("详情含流水(提交动作)", isinstance(detail_b, dict) and len(detail_b.get("records") or []) >= 1, detail_b)

    api_err("非处理人操作拒绝 121002", "POST", "/api/approvals/%s/approve" % approval_id,
            tok_b, {"comment": "越权"}, 121002)
    api_ok("example_c 通过", "POST", "/api/approvals/%s/approve" % approval_id, tok_c,
           {"comment": "冒烟通过"})

    leaves = api_ok("业务单状态回写(leave 列表)", "GET", "/api/example/leaves", tok_b)
    lv = [l for l in (leaves or []) if l.get("id") == leave_id]
    step("leave 已随办结回写(1=已通过)", len(lv) == 1 and lv[0].get("status") == 1, lv)

    api_err("重复办结拒绝 121001", "POST", "/api/approvals/%s/approve" % approval_id,
            tok_c, None, 121001)
    done = api_ok("发起人收办结通知", "GET", "/api/notifications", tok_b)
    step("通知含通过文案", any("通过" in str(n.get("title", "")) and str(n.get("source_id")) == str(approval_id)
                              for n in (done or [])), done)

    print("== 多级审批串联(leave days≥3,RFC §6.8 组合方式)==")
    api_err("长请假缺二级审批人 290006", "POST", "/api/example/leaves", tok_b,
            {"reason": "冒烟长请假" + stamp, "days": 3, "assigneeId": uid_c}, 290006)
    ml_leave = api_ok("长请假登记(L1=example_c/L2=admin)", "POST", "/api/example/leaves", tok_b,
                      {"reason": "冒烟长请假" + stamp, "days": 3, "assigneeId": uid_c,
                       "managerId": uid_admin})
    if ml_leave is None:
        return finish()
    leaves1 = api_ok("长请假行回读", "GET", "/api/example/leaves", tok_b)
    ml_rows = [l for l in (leaves1 or []) if l.get("id") == ml_leave]
    a1 = ml_rows[0].get("approval_id") if ml_rows else None
    ok = bool(a1) and ml_rows[0].get("approval_level") == 1
    step("初始挂一级审批单", ok, ml_rows)
    if not a1:
        return finish()

    api_ok("一级通过(example_c)", "POST", "/api/approvals/%s/approve" % a1, tok_c, {"comment": "一级通过"})
    leaves2 = api_ok("一级通过后回读", "GET", "/api/example/leaves", tok_b)
    ml_rows2 = [l for l in (leaves2 or []) if l.get("id") == ml_leave]
    a2 = ml_rows2[0].get("approval_id") if ml_rows2 else None
    ok = a2 and str(a2) != str(a1) and ml_rows2[0].get("approval_level") == 2 \
        and ml_rows2[0].get("status") == 0
    step("插件派生二级审批单并改挂(状态仍审批中)", ok, ml_rows2)
    if not a2:
        return finish()

    todo_admin = api_ok("二级处理人待办", "GET", "/api/approvals?role=todo", tok_admin)
    step("admin 待办含二级审批单", any(str(a.get("id")) == str(a2) for a in (todo_admin or [])), todo_admin)
    api_err("非二级处理人拒绝 121002", "POST", "/api/approvals/%s/approve" % a2,
            tok_c, {"comment": "越权"}, 121002)
    api_ok("二级通过(admin)", "POST", "/api/approvals/%s/approve" % a2, tok_admin, {"comment": "二级通过"})
    leaves3 = api_ok("二级通过后回读", "GET", "/api/example/leaves", tok_b)
    ml_rows3 = [l for l in (leaves3 or []) if l.get("id") == ml_leave]
    step("业务单两级走完回写通过(1)", len(ml_rows3) == 1 and ml_rows3[0].get("status") == 1
         and ml_rows3[0].get("approval_level") == 2, ml_rows3)

    # 第 39 次运行:审批回写链迁总线——L1/L2 办结与 L2 派生提交事件出站箱对账
    event_consumed("ApprovalFinishedEvent", "approval-finished-" + str(a1),
                   "L1 办结事件走总线(消费确认)")
    event_consumed("ApprovalSubmittedEvent", "approval-submitted-" + str(a2),
                   "插件派生 L2 提交事件走总线(消费确认)")
    event_consumed("ApprovalFinishedEvent", "approval-finished-" + str(a2),
                   "L2 办结事件走总线(消费确认)")

    print("== 转交 × 多级串联(转交不改状态,派生链与处理人无关)==")
    tf_stamp = time.strftime("%Y%m%d%H%M%S")
    tf_leave = api_ok("转交链登记长请假(L1=c/L2=admin)", "POST", "/api/example/leaves", tok_b,
                      {"reason": "冒烟转交链" + tf_stamp, "days": 4, "assigneeId": uid_c,
                       "managerId": uid_admin})
    if tf_leave is None:
        return finish()
    leaves_t0 = api_ok("转交链业务单回读", "GET", "/api/example/leaves", tok_b)
    t_rows0 = [l for l in (leaves_t0 or []) if l.get("id") == tf_leave]
    ta1 = t_rows0[0].get("approval_id") if t_rows0 else None
    step("初始挂一级审批单", bool(ta1) and t_rows0 and t_rows0[0].get("approval_level") == 1, t_rows0)
    if not ta1:
        return finish()

    api_err("非处理人转交拒绝 121002", "POST", "/api/approvals/%s/transfer" % ta1, tok_b,
            {"toUserId": uid_admin, "comment": "越权转交"}, 121002)
    api_ok("一级转交给 admin(处理人=c)", "POST", "/api/approvals/%s/transfer" % ta1, tok_c,
           {"toUserId": uid_admin, "comment": "一级转交"})
    todo_c_t = api_ok("c 待办(转交后)", "GET", "/api/approvals?role=todo", tok_c)
    todo_admin_t = api_ok("admin 待办(转交后)", "GET", "/api/approvals?role=todo", tok_admin)
    step("转交后待办迁移(c 失/admin 得)",
         not any(str(a.get("id")) == str(ta1) for a in (todo_c_t or []))
         and any(str(a.get("id")) == str(ta1) for a in (todo_admin_t or [])),
         (todo_c_t, todo_admin_t))

    api_ok("admin 通过一级(转交后的处理人)", "POST", "/api/approvals/%s/approve" % ta1, tok_admin,
           {"comment": "一级通过"})
    leaves_t1 = api_ok("一级通过后回读", "GET", "/api/example/leaves", tok_b)
    t_rows1 = [l for l in (leaves_t1 or []) if l.get("id") == tf_leave]
    ta2 = t_rows1[0].get("approval_id") if t_rows1 else None
    ok = ta2 and str(ta2) != str(ta1) and t_rows1[0].get("approval_level") == 2 \
        and t_rows1[0].get("status") == 0
    step("转交后的一级通过仍触发派生二级", ok, t_rows1)
    if not ta2:
        return finish()

    api_ok("二级转交给 example_c(处理人=admin)", "POST", "/api/approvals/%s/transfer" % ta2, tok_admin,
           {"toUserId": uid_c, "comment": "二级转交"})
    todo_admin_t2 = api_ok("admin 待办(二级转交后)", "GET", "/api/approvals?role=todo", tok_admin)
    todo_c_t2 = api_ok("c 待办(二级转交后)", "GET", "/api/approvals?role=todo", tok_c)
    step("二级转交后待办迁移(admin 失/c 得)",
         not any(str(a.get("id")) == str(ta2) for a in (todo_admin_t2 or []))
         and any(str(a.get("id")) == str(ta2) for a in (todo_c_t2 or [])),
         (todo_admin_t2, todo_c_t2))

    api_ok("example_c 通过二级(转交后的处理人)", "POST", "/api/approvals/%s/approve" % ta2, tok_c,
           {"comment": "二级通过"})
    leaves_t2 = api_ok("转交链终态回读", "GET", "/api/example/leaves", tok_b)
    t_rows2 = [l for l in (leaves_t2 or []) if l.get("id") == tf_leave]
    step("转交链两级走完回写通过(1)", len(t_rows2) == 1 and t_rows2[0].get("status") == 1
         and t_rows2[0].get("approval_level") == 2, t_rows2)

    # 第 39 次运行:转交事件走总线(eventKey 带 toUserId 复合,同一审批转给不同人各自成事件)
    event_consumed("ApprovalTransferredEvent", "approval-transferred-" + str(ta1) + "-" + str(uid_admin),
                   "一级转交事件走总线(转给 admin)")
    event_consumed("ApprovalTransferredEvent", "approval-transferred-" + str(ta2) + "-" + str(uid_c),
                   "二级转交事件走总线(转给 example_c)")

    print("== 客户与楼盘档案(addon-customerprofile V1)==")
    stamp2 = time.strftime("%Y%m%d%H%M%S")
    # 楼盘房屋菜单下发(第 45 次:Villages Web 管理页挂菜单,admin 全量可见)
    addons_cp = api_ok("admin 插件下发(楼盘菜单断言)", "GET", "/api/addons", tok_admin)
    cp_addon = next((a for a in (addons_cp or []) if a.get("id") == "customerprofile"), None)
    cp_paths = sorted(m.get("path") for m in (cp_addon or {}).get("menus") or [])
    step("customerprofile 双菜单(客户档案+楼盘房屋)", cp_paths == [
        "/customerprofile/customers", "/customerprofile/villages"], cp_paths)
    city_id = api_ok("建城市", "POST", "/api/customerprofile/cities", tok_admin,
                     {"name": "烟测市" + stamp2})
    if city_id is None:
        return finish()
    proper_id = api_ok("建城区", "POST", "/api/customerprofile/cities/%s/propers" % city_id, tok_admin,
                       {"name": "烟测区"})
    api_err("同城区重名拒绝 330015", "POST", "/api/customerprofile/cities/%s/propers" % city_id, tok_admin,
            {"name": "烟测区"}, 330015)
    village_id = api_ok("建楼盘", "POST", "/api/customerprofile/villages", tok_admin,
                        {"name": "烟测楼盘" + stamp2, "cityId": city_id, "properId": proper_id,
                         "developer": "冒烟开发商", "x": 123400000, "y": 41800000, "mileage": 12000})
    api_err("同城区同名楼盘拒绝 330002", "POST", "/api/customerprofile/villages", tok_admin,
            {"name": "烟测楼盘" + stamp2, "cityId": city_id, "properId": proper_id}, 330002)
    villages = api_ok("楼盘分页检索", "GET",
                      "/api/customerprofile/villages?keyword=%s&page=1&size=10" % stamp2, tok_admin)
    step("检索命中新建楼盘", bool(villages) and villages.get("total", 0) >= 1, villages)

    house_number = "1栋2单元3楼" + stamp2
    house_id = api_ok("建房屋(门牌密文)", "POST", "/api/customerprofile/houses", tok_admin,
                      {"villageId": village_id, "number": house_number,
                       "fields": {"built": "1", "unit": "2", "floorNum": 3, "doorNum": 4,
                                  "buildArea": 98000, "innerArea": 88000, "houseType": "2室1厅"}})
    api_err("同楼盘同门牌拒绝 330004", "POST", "/api/customerprofile/houses", tok_admin,
            {"villageId": village_id, "number": house_number}, 330004)
    exist_h = api_ok("房屋 get-or-create(已存在幂等)", "POST", "/api/customerprofile/houses/exist", tok_admin,
                     {"villageId": village_id, "number": house_number})
    step("幂等回原 id 且 created=false",
         bool(exist_h) and exist_h.get("created") is False and str(exist_h.get("houseId")) == str(house_id),
         exist_h)
    house = api_ok("房屋详情解密", "GET", "/api/customerprofile/houses/%s" % house_id, tok_admin)
    step("门牌解密与明文一致", bool(house) and house.get("number") == house_number, house)

    cust_phone = "139%08d" % (int(stamp2) % 100000000)
    cust_id = api_ok("建客户", "POST", "/api/customerprofile/customers", tok_admin,
                     {"name": "烟测客户" + stamp2, "phone": cust_phone, "address": "烟测路1号",
                      "identity": "210102199001010011"})
    api_err("同号重复建档拒绝 330006", "POST", "/api/customerprofile/customers", tok_admin,
            {"name": "烟测客户B", "phone": cust_phone}, 330006)
    exist_c = api_ok("客户 get-or-create(已存在幂等)", "POST", "/api/customerprofile/customers/exist",
                     tok_admin, {"phone": cust_phone, "name": "任意名"})
    step("幂等回显原客户",
         bool(exist_c) and exist_c.get("created") is False and str(exist_c.get("customerId")) == str(cust_id),
         exist_c)
    custs = api_ok("按手机号检索客户", "GET",
                   "/api/customerprofile/customers?keyword=%s&page=1&size=10" % cust_phone, tok_admin)
    step("检索命中", bool(custs) and custs.get("total", 0) >= 1, custs)

    phone2 = "137%08d" % ((int(stamp2) + 1) % 100000000)
    phone2_id = api_ok("加第二个号码", "POST", "/api/customerprofile/customers/%s/phones" % cust_id,
                       tok_admin, {"phone": phone2})
    phones = api_ok("号码列表(脱敏)", "GET", "/api/customerprofile/customers/%s/phones" % cust_id, tok_admin)
    default_rows = [p for p in (phones or []) if p.get("is_default") == 1]
    ok = len(default_rows) == 1 and all(cust_phone not in json.dumps(r) for r in (phones or []))
    step("唯一默认号+列表不含明文", ok, phones)
    if not default_rows or phone2_id is None:
        return finish()
    default_id = default_rows[0]["id"]
    api_err("默认号码停用拒绝 330008",
            "POST", "/api/customerprofile/customers/%s/phones/%s/status" % (cust_id, default_id),
            tok_admin, {"status": 1}, 330008)
    api_ok("显式切换默认到二号",
           "POST", "/api/customerprofile/customers/%s/phones/%s/default" % (cust_id, phone2_id),
           tok_admin, None)
    api_ok("一号停用(非默认)",
           "POST", "/api/customerprofile/customers/%s/phones/%s/status" % (cust_id, default_id),
           tok_admin, {"status": 1})
    api_err("停用号设默认拒绝 330009",
            "POST", "/api/customerprofile/customers/%s/phones/%s/default" % (cust_id, default_id),
            tok_admin, None, 330009)
    api_ok("一号恢复启用",
           "POST", "/api/customerprofile/customers/%s/phones/%s/status" % (cust_id, default_id),
           tok_admin, {"status": 0})
    rev = api_ok("解密留痕(事由必填即 purpose)",
                 "POST", "/api/customerprofile/customers/%s/phones/%s/reveal" % (cust_id, phone2_id),
                 tok_admin, {"purpose": "冒烟核对"})
    step("明文一致", bool(rev) and rev.get("phone") == phone2, rev)
    logs = api_ok("审计可查", "GET",
                  "/api/customerprofile/customers/%s/phone-access-logs?page=1&size=10" % cust_id, tok_admin)
    step("审计含留痕记录", bool(logs) and logs.get("total", 0) >= 1, logs)

    account_no = "6222" + stamp2
    acc_id = api_ok("建对私账户(首账户自动默认)", "POST",
                    "/api/customerprofile/customers/%s/accounts" % cust_id, tok_admin,
                    {"holderName": "烟测户", "bankName": "烟测银行", "accountNo": account_no})
    api_err("同账户重复拒绝 330012", "POST", "/api/customerprofile/customers/%s/accounts" % cust_id,
            tok_admin, {"holderName": "烟测户", "accountNo": account_no}, 330012)
    accs = api_ok("账户列表(脱敏)", "GET", "/api/customerprofile/customers/%s/accounts" % cust_id, tok_admin)
    ok = len(accs or []) == 1 and accs[0].get("is_default") == 1 \
        and account_no not in json.dumps(accs) and accs[0].get("accountMasked", "").endswith(stamp2[-4:])
    step("账号脱敏+默认标记", ok, accs)

    lead_phone = "136%08d" % ((int(stamp2) + 2) % 100000000)
    lead2 = api_ok("登记转化用客源", "POST", "/api/customer/leads", tok_b,
                   {"name": "转化客" + stamp2, "phone": lead_phone, "source": "referral"})
    api_ok("转化客源", "POST", "/api/customer/leads/%s/convert" % lead2, tok_b)
    linked = api_ok("转化联动主档(get-or-create 事件链)", "GET",
                    "/api/customerprofile/customers?keyword=%s&page=1&size=10" % lead_phone, tok_admin)
    step("转化客源自动落主档", bool(linked) and linked.get("total", 0) >= 1, linked)

    # 第 38 次运行:转化事件改走内核事件总线(SPI 消费器建档),出站箱消费确认对账
    conn_lc = db_conn()
    try:
        with conn_lc.cursor() as cur:
            cur.execute("select status from yz_event_task where event_type = 'LeadConvertedEvent' "
                        "and event_key = %s order by id desc limit 1",
                        ("lead-converted-" + str(lead2),))
            lc_row = cur.fetchone()
    finally:
        conn_lc.close()
    step("转化事件走总线(LeadConvertedEvent 消费确认)", lc_row is not None and lc_row[0] == 1, lc_row)

    print("== 基础数据(addon-basedata V1 商品档案)==")
    stamp3 = time.strftime("%Y%m%d%H%M%S")
    cat_root = api_ok("建根品类", "POST", "/api/basedata/categories", tok_admin,
                      {"name": "烟测品类" + stamp3})
    if cat_root is None:
        return finish()
    api_err("同父同名品类拒绝 260003", "POST", "/api/basedata/categories", tok_admin,
            {"name": "烟测品类" + stamp3}, 260003)
    cat_child = api_ok("建子品类", "POST", "/api/basedata/categories", tok_admin,
                       {"name": "烟测子品类", "parentId": cat_root})
    api_err("有启用子品类停用拒绝 260004", "POST", "/api/basedata/categories/%s/status" % cat_root,
            tok_admin, {"status": 1}, 260004)
    api_ok("停用子品类", "POST", "/api/basedata/categories/%s/status" % cat_child, tok_admin, {"status": 1})
    api_ok("子品类清空后停用父品类", "POST", "/api/basedata/categories/%s/status" % cat_root,
           tok_admin, {"status": 1})
    api_ok("子品类恢复启用", "POST", "/api/basedata/categories/%s/status" % cat_child,
           tok_admin, {"status": 0})
    cats = api_ok("品类树回显", "GET", "/api/basedata/categories", tok_admin)
    root_node = next((c for c in (cats or []) if str(c.get("id")) == str(cat_root)), None)
    step("树含父子两级", bool(root_node) and any(str(ch.get("id")) == str(cat_child)
         for ch in (root_node.get("children") or [])), cats)

    brand_id = api_ok("建品牌", "POST", "/api/basedata/brands", tok_admin,
                      {"name": "烟测品牌" + stamp3, "pinyin": "ycpp" + stamp3[-6:]})
    api_err("同名品牌拒绝 260006", "POST", "/api/basedata/brands", tok_admin,
            {"name": "烟测品牌" + stamp3}, 260006)

    barcode = "6901" + stamp3[-8:]
    goods_id = api_ok("建商品(中文/品类/品牌/厘价)", "POST", "/api/basedata/goods", tok_admin,
                      {"name": "烟测瓷砖" + stamp3, "pinyin": "yctc", "barcode": barcode,
                       "categoryId": cat_child, "brandId": brand_id, "unit": "块",
                       "purchase": 88000, "selling": 128000, "confine": 118000})
    if goods_id is None:
        return finish()
    api_err("同名商品拒绝 260010", "POST", "/api/basedata/goods", tok_admin,
            {"name": "烟测瓷砖" + stamp3, "categoryId": cat_child, "unit": "块"}, 260010)
    api_err("同条码商品拒绝 260009", "POST", "/api/basedata/goods", tok_admin,
            {"name": "另一商品" + stamp3, "categoryId": cat_child, "unit": "块", "barcode": barcode}, 260009)
    goods = api_ok("商品详情", "GET", "/api/basedata/goods/%s" % goods_id, tok_admin)
    ok = bool(goods) and str(goods.get("no", "")).startswith("G") and goods.get("selling") == 128000 \
        and goods.get("category_name")
    step("编号 G 前缀自动生成+价格厘回显+品类名联查", ok, goods)

    attr_id = api_ok("加 SKU 颜色属性", "POST", "/api/basedata/goods/%s/sku-attrs" % goods_id,
                     tok_admin, {"attrType": 0, "value": "胡桃木色"})
    api_err("重复属性值拒绝 260011", "POST", "/api/basedata/goods/%s/sku-attrs" % goods_id,
            tok_admin, {"attrType": 0, "value": "胡桃木色"}, 260011)
    price_id = api_ok("加价格体系(补时间有效性)", "POST", "/api/basedata/goods/%s/prices" % goods_id,
                      tok_admin, {"name": "一级价", "selling": 118000, "beginTime": "2026-10-01"})
    api_err("同名价格体系拒绝 260012", "POST", "/api/basedata/goods/%s/prices" % goods_id,
            tok_admin, {"name": "一级价"}, 260012)
    api_ok("改商品价格(PUT 白名单)", "PUT", "/api/basedata/goods/%s" % goods_id, tok_admin,
           {"selling": 138000})
    goods2 = api_ok("详情复查新价", "GET", "/api/basedata/goods/%s" % goods_id, tok_admin)
    step("售价已更新为 138000", bool(goods2) and goods2.get("selling") == 138000, goods2)

    pkg_goods_id = api_ok("建套餐商品(type=1)", "POST", "/api/basedata/goods", tok_admin,
                          {"name": "烟测套餐" + stamp3, "categoryId": cat_child, "unit": "套", "type": 1})
    member_id = api_ok("套餐加成员(x2)", "POST", "/api/basedata/goods/%s/packages" % pkg_goods_id,
                       tok_admin, {"memberGoodsId": goods_id, "num": 2})
    api_err("成员为套餐拒绝 260014", "POST", "/api/basedata/goods/%s/packages" % pkg_goods_id,
            tok_admin, {"memberGoodsId": pkg_goods_id, "num": 1}, 260014)
    api_err("重复成员拒绝 260015", "POST", "/api/basedata/goods/%s/packages" % pkg_goods_id,
            tok_admin, {"memberGoodsId": goods_id, "num": 1}, 260015)
    api_ok("套餐成员列表", "GET", "/api/basedata/goods/%s/packages" % pkg_goods_id, tok_admin)
    api_ok("停用商品(事件留痕)", "POST", "/api/basedata/goods/%s/status" % goods_id,
           tok_admin, {"status": 1})

    # ---- 商品 Excel 导入器 V1.1(全量校验,全过才入库;第 51 次)----
    def upload_xlsx(xbytes, token, fname="goods.xlsx"):
        import io
        boundary = "smokebound50x"
        body = (("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" +
                 fname + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").encode("utf-8")
                + xbytes + ("\r\n--" + boundary + "--\r\n").encode("utf-8"))
        req = urllib.request.Request(BASE + "/api/basedata/goods/import", data=body, method="POST")
        req.add_header("Content-Type", "multipart/form-data; boundary=" + boundary)
        req.add_header("Authorization", "Bearer " + token)
        try:
            with urllib.request.urlopen(req) as resp:
                res = resp.status, json.loads(resp.read())
        except urllib.error.HTTPError as e:
            res = e.code, json.loads(e.read())
        if isinstance(res[1], dict) and res[1].get("code") == 110000:
            print("UPLOAD-DEBUG status=", res[0], "url=", req.get_full_url(),
                  "ct=", req.headers.get("Content-type"), "bodyhead=", body[:60])
        return res

    _treq = urllib.request.Request(BASE + "/api/basedata/goods/import-template")
    _treq.add_header("Authorization", "Bearer " + tok_admin)
    with urllib.request.urlopen(_treq) as _tresp:
        tpl_bytes = _tresp.read()
        tpl_status = _tresp.status
    step("导入模板下载(xlsx 魔数 PK)", tpl_status == 200 and tpl_bytes[:2] == b"PK", (tpl_status, len(tpl_bytes)))

    # 导入专用品类/品牌(避免依赖 basedata 段被停用测试翻动过的 cat_child 状态)
    imp_cat = api_ok("建导入专用品类", "POST", "/api/basedata/categories", tok_admin,
                     {"name": "导入品类" + stamp3})
    imp_brand = api_ok("建导入专用品牌", "POST", "/api/basedata/brands", tok_admin,
                       {"name": "导入品牌" + stamp3, "pinyin": "drpp" + stamp3[-6:]})

    # 错误场景:品类不存在+拼音大写+文件内重名 → 260023 逐行明细,零入库
    wb_rows = [
        ["坏品类商品" + stamp3, "hpls", "不存在的品类", ("导入品牌" + stamp3), "片", "", "99.00", "", ""],
        ["大写拼音商品" + stamp3, "DXPY", ("导入品类" + stamp3), ("导入品牌" + stamp3), "片", "", "50.00", "", ""],
        ["重复名商品" + stamp3, "cfm", ("导入品类" + stamp3), ("导入品牌" + stamp3), "片", "", "10.00", "", ""],
        ["重复名商品" + stamp3, "cfm2", ("导入品类" + stamp3), ("导入品牌" + stamp3), "片", "", "11.00", "", ""],
    ]
    import openpyxl as _opx
    _wb = _opx.Workbook(); _ws = _wb.active
    _ws.append(["商品名称", "拼音", "品类", "品牌", "单位", "条码", "售价(元)", "进价(元)", "限价(元)"])
    for r in wb_rows:
        _ws.append(r)
    _buf = io.BytesIO(); _wb.save(_buf)
    st, res = upload_xlsx(_buf.getvalue(), tok_admin)
    errs = (res.get("data") or {}).get("errors") or []
    step("导入全量校验拒绝 260023(零部分成功)", st == 200 and res.get("code") == 260023
         and (res.get("data") or {}).get("imported") == 0 and len(errs) == 3
         and any(e.get("field") == "品类" for e in errs)
         and any(e.get("field") == "拼音" for e in errs)
         and any(e.get("message") == "文件内重复" for e in errs), res)
    conn = db_conn()
    try:
        with conn.cursor() as cur:
            cur.execute("select count(*) from basedata_goods where name like %s",
                        ("坏品类商品" + stamp3 + "%",))
            cnt_bad = cur.fetchone()[0]
    finally:
        conn.close()
    step("校验失败零入库", cnt_bad == 0, cnt_bad)

    # 成功场景:三行全过(含条码),库直查断言 + 重复导入按行报 dupName
    wb_rows2 = [
        ["导入商品甲" + stamp3, "drspj", ("导入品类" + stamp3), ("导入品牌" + stamp3), "片", "69000000000%02d" % (int(stamp3[-2:])), "128.50", "80.00", ""],
        ["导入商品乙" + stamp3, "drspe", ("导入品类" + stamp3), ("导入品牌" + stamp3), "桶", "", "45.00", "", "60.00"],
        ["导入商品丙" + stamp3, "drspf", ("导入品类" + stamp3), ("导入品牌" + stamp3), "块", "", "666.666", "", ""],
    ]
    _wb2 = _opx.Workbook(); _ws2 = _wb2.active
    _ws2.append(["商品名称", "拼音", "品类", "品牌", "单位", "条码", "售价(元)", "进价(元)", "限价(元)"])
    for r in wb_rows2:
        _ws2.append(r)
    _buf2 = io.BytesIO(); _wb2.save(_buf2)
    st, res = upload_xlsx(_buf2.getvalue(), tok_admin)
    d = res.get("data") or {}
    step("导入成功 3 条(元转厘)", st == 200 and res.get("code") == 0 and d.get("imported") == 3
         and d.get("total") == 3, res)
    conn = db_conn()
    try:
        with conn.cursor() as cur:
            cur.execute("select selling, barcode from basedata_goods where name = %s",
                        ("导入商品甲" + stamp3,))
            prow = cur.fetchone()
    finally:
        conn.close()
    step("导入数值元转厘(128.50 元→128500 厘)+条码落库",
         prow and prow[0] == 128500 and prow[1] == "69000000000%02d" % (int(stamp3[-2:])), prow)
    st, res = upload_xlsx(_buf2.getvalue(), tok_admin)
    errs2 = (res.get("data") or {}).get("errors") or []
    step("重复导入逐行报 dupName 260023", res.get("code") == 260023
         and len([e for e in errs2 if e.get("message") and "已存在" in str(e.get("message"))]) >= 3, res)


    model_id = api_ok("建主材型号(点位计价)", "POST", "/api/basedata/models", tok_admin,
                      {"name": "烟测型号" + stamp3, "goodsId": goods_id, "brandId": brand_id,
                       "price": 99000, "calcType": 1, "pointValue": 300})
    models = api_ok("型号列表检索", "GET", "/api/basedata/models?keyword=%s&page=1&size=10" % stamp3, tok_admin)
    step("型号命中且关联商品", bool(models) and models.get("total", 0) >= 1
         and (models.get("rows") or [{}])[0].get("goods_name"), models)

    mpkg_id = api_ok("建定材包主数据", "POST", "/api/basedata/packages", tok_admin,
                     {"name": "烟测定材" + stamp3, "selling": 39800000, "balance": 35000000,
                      "surplus": 1000000})
    ref_id = api_ok("引用主数据建引用行(快照)", "POST", "/api/basedata/packages/%s/reference" % mpkg_id,
                    tok_admin, {"surplus": 500000})
    api_err("引用行改名称拒绝 260020", "PUT", "/api/basedata/packages/%s" % ref_id, tok_admin,
            {"name": "改不了", "surplus": 1}, 260020)
    api_ok("引用行仅调浮动量", "PUT", "/api/basedata/packages/%s" % ref_id, tok_admin, {"surplus": 600000})
    mpkgs = api_ok("定材包列表(主+引可见)", "GET",
                   "/api/basedata/packages?keyword=%s&page=1&size=10" % stamp3, tok_admin)
    levels = sorted(str(r.get("level")) for r in (mpkgs or {}).get("rows", []))
    step("主数据行与引用行同时可见", bool(mpkgs) and mpkgs.get("total", 0) >= 2 and levels == ["0", "1"], mpkgs)

    print("== 款项(addon-payment V1:三件套)==")
    stamp4 = time.strftime("%Y%m%d%H%M%S")
    dep_code, con_code = "smokedep" + stamp4[-6:], "smokecon" + stamp4[-6:]
    type_dep = api_ok("建款项类型(顺序1)", "POST", "/api/payment/bill-types", tok_admin,
                      {"code": dep_code, "name": "烟测定金", "sort": 1})
    if type_dep is None:
        return finish()
    api_err("同编码类型拒绝 270005", "POST", "/api/payment/bill-types", tok_admin,
            {"code": dep_code, "name": "重复编码", "sort": 1}, 270005)
    type_con = api_ok("建款项类型(顺序2,比例模板)", "POST", "/api/payment/bill-types", tok_admin,
                      {"code": con_code, "name": "烟测合同款", "sort": 2,
                       "scaleTemplate": json.dumps([{"sort": 1, "scale": 600000},
                                                    {"sort": 2, "scale": 400000}], ensure_ascii=False)})
    api_err("非法模板 JSON 拒绝 270001", "POST", "/api/payment/bill-types", tok_admin,
            {"code": "smokebad" + stamp4[-6:], "name": "坏模板", "scaleTemplate": "{not-json"}, 270001)
    types = api_ok("类型字典回显", "GET", "/api/payment/bill-types?includeDisabled=true", tok_admin)
    codes = [t.get("code") for t in (types or [])]
    step("两类型在册", dep_code in codes and con_code in codes, types)

    biz3 = {"bizAddon": "example", "bizType": "smoke", "bizId": "SO" + stamp4}
    bill_dep = api_ok("登记定金款项单(1000元)", "POST", "/api/payment/bills", tok_admin,
                      dict(biz3, typeCode=dep_code, amount=1000000))
    if bill_dep is None:
        return finish()
    api_err("金额为零拒绝 270001", "POST", "/api/payment/bills", tok_admin,
            dict(biz3, bizId="ZERO" + stamp4, typeCode=dep_code, amount=0), 270001)
    api_err("重复建单拒绝 270006", "POST", "/api/payment/bills", tok_admin,
            dict(biz3, typeCode=dep_code, amount=1000000), 270006)
    api_err("类型不存在拒绝 270004", "POST", "/api/payment/bills", tok_admin,
            dict(biz3, bizId="NT" + stamp4, typeCode="nosuchtype", amount=1000), 270004)
    bill_con = api_ok("登记合同款项单(2000元,顺序2)", "POST", "/api/payment/bills", tok_admin,
                      dict(biz3, typeCode=con_code, amount=2000000))
    if bill_con is None:
        return finish()

    api_err("顺序门控:前置未收拒绝 270010", "POST", "/api/payment/bills/%s/collect" % bill_con, tok_admin,
            {"channels": [{"channelCode": "cash", "amount": 2000000}]}, 270010)
    api_err("渠道合计不符拒绝 270007", "POST", "/api/payment/bills/%s/collect" % bill_dep, tok_admin,
            {"channels": [{"channelCode": "cash", "amount": 999999}]}, 270007)
    api_err("渠道金额为零拒绝 270008", "POST", "/api/payment/bills/%s/collect" % bill_dep, tok_admin,
            {"channels": [{"channelCode": "cash", "amount": 0}]}, 270008)
    api_err("非法渠道拒绝 270009", "POST", "/api/payment/bills/%s/collect" % bill_dep, tok_admin,
            {"channels": [{"channelCode": "wechat", "amount": 1000000}]}, 270009)

    rcpt = api_ok("收款成功(现金600+银行400)", "POST", "/api/payment/bills/%s/collect" % bill_dep, tok_admin,
                  {"channels": [{"channelCode": "cash", "amount": 600000},
                                {"channelCode": "bank", "amount": 400000}]})
    step("收据号 R 前缀生成", bool(rcpt) and str(rcpt.get("receiptNo", "")).startswith("R"), rcpt)
    api_err("重复收款拒绝 270003", "POST", "/api/payment/bills/%s/collect" % bill_dep, tok_admin,
            {"channels": [{"channelCode": "cash", "amount": 1000000}]}, 270003)
    dep_detail = api_ok("定金单详情", "GET", "/api/payment/bills/%s" % bill_dep, tok_admin)
    rc = ((dep_detail or {}).get("receipts") or [{}])
    ok = dep_detail and dep_detail.get("status") == 1 and len(rc[0].get("channels") or []) == 2 \
        and rc[0].get("collector_name")
    step("单据已收+收据双人留痕第一半+双渠道行", ok, dep_detail)

    api_ok("财务确认(1→2 终态)", "POST", "/api/payment/bills/%s/confirm" % bill_dep, tok_admin)
    api_err("重复确认拒绝 270003", "POST", "/api/payment/bills/%s/confirm" % bill_dep, tok_admin, None, 270003)
    dep_detail2 = api_ok("确认后详情", "GET", "/api/payment/bills/%s" % bill_dep, tok_admin)
    rc2 = ((dep_detail2 or {}).get("receipts") or [{}])
    step("留痕第二半(确认人回填)", dep_detail2 and dep_detail2.get("status") == 2
         and bool(rc2[0].get("confirmer_name")), dep_detail2)

    api_ok("合同款顺序解锁后收款", "POST", "/api/payment/bills/%s/collect" % bill_con, tok_admin,
           {"channels": [{"channelCode": "scan", "amount": 2000000}]})
    api_ok("合同款确认", "POST", "/api/payment/bills/%s/confirm" % bill_con, tok_admin)

    print("== 事件总线三件套(消费确认+重试+死信)==")
    api_err("非管理员查事件拒绝 123001", "GET", "/api/events", tok_b, None, 123001)
    ev_col = api_ok("收款事件已消费确认", "GET",
                    "/api/events?eventType=PaymentCollectedEvent&status=1&page=1&size=5", tok_admin)
    step("PaymentCollected 消费行存在", bool(ev_col) and ev_col.get("total", 0) >= 1, ev_col)
    ev_con = api_ok("确认事件已消费确认", "GET",
                    "/api/events?eventType=PaymentConfirmedEvent&status=1&page=1&size=5", tok_admin)
    step("PaymentConfirmed 消费行存在", bool(ev_con) and ev_con.get("total", 0) >= 1, ev_con)
    notifs_admin = api_ok("操作人通知列表", "GET", "/api/notifications?page=1&size=50", tok_admin)
    hit_demo = [n for n in (notifs_admin or [])
                if n.get("source_type") == "payment.collected.consumed"]
    step("示范消费器留痕(收款事件→通知)", len(hit_demo) >= 1, len(hit_demo or []))

    # 死信链:直插一条坏载荷事件(服务以快退避+快轮询环境变量启动),等调度器重试至死信
    conn = db_conn()
    try:
        with conn.cursor() as cur:
            dead_key = "smoke-dead-" + stamp4
            cur.execute(
                "insert into yz_event_task(event_type, payload, event_key, max_retries)"
                " values ('PaymentCollectedEvent', 'not-json', %s, 2)", (dead_key,))
        conn.commit()
    finally:
        conn.close()
    # 调度器 10s 一轮+指数退避:1s 基数下 max_retries=2 终态实测在 ~126s(第 45 次库中实证),
    # 75s 窗口不足会假死——轮询等待至多 150s
    dead_rows = []
    for _ in range(30):
        time.sleep(5)
        _st, _res = call("GET", "/api/events?status=3&page=1&size=50", tok_admin)
        _data = (_res or {}).get("data") if isinstance(_res, dict) else None
        dead_rows = [r for r in ((_data or {}).get("rows") or []) if r.get("event_key") == dead_key]
        if len(dead_rows) == 1:
            break
    ev_dead = api_ok("死信列表", "GET", "/api/events?status=3&page=1&size=50", tok_admin)
    dead_rows = [r for r in ((ev_dead or {}).get("rows") or []) if r.get("event_key") == dead_key]
    step("坏载荷经重试转死信(重试计数+错误可见)",
         len(dead_rows) == 1 and dead_rows[0].get("retry_count", 0) >= 2 and bool(dead_rows[0].get("last_error")),
         dead_rows)
    if dead_rows:
        api_ok("死信 requeue 回 pending", "POST", "/api/events/%s/requeue" % dead_rows[0]["id"], tok_admin)
    ev_re = api_ok("requeue 后再消费确认", "GET",
                   "/api/events?eventType=PaymentCollectedEvent&status=1&page=1&size=5", tok_admin)
    step("消费确认行仍在(重投递幂等)", bool(ev_re) and ev_re.get("total", 0) >= 1, ev_re)

    print("== 退款统一走内核审批 ==")
    refund1 = api_ok("定金单发起退款(审批人 example_c)", "POST", "/api/payment/bills/%s/refund" % bill_dep,
                     tok_admin, {"assigneeId": uid_c, "reason": "冒烟退款"})
    if refund1 is None:
        return finish()
    api_err("重复退款拒绝 270012", "POST", "/api/payment/bills/%s/refund" % bill_dep, tok_admin,
            {"assigneeId": uid_c, "reason": "再退"}, 270012)
    ref_detail = api_ok("退款单回读", "GET", "/api/payment/bills/%s" % refund1, tok_admin)
    approval_r1 = (ref_detail or {}).get("refund_approval_id")
    ok = ref_detail and ref_detail.get("direction") == 1 and ref_detail.get("status") == 0 \
        and ref_detail.get("origin_status_snapshot") == 2
    step("退款单审批中+原款项状态快照", ok, ref_detail)
    if not approval_r1:
        return finish()
    todo_r = api_ok("审批人待办含退款单", "GET", "/api/approvals?role=todo", tok_c)
    step("待办可见", any(str(a.get("id")) == str(approval_r1) for a in (todo_r or [])), todo_r)
    api_ok("退款审批通过", "POST", "/api/approvals/%s/approve" % approval_r1, tok_c, {"comment": "冒烟同意退款"})
    time.sleep(2)
    dep_after = api_ok("退款执行后原单回读", "GET", "/api/payment/bills/%s" % bill_dep, tok_admin)
    rf1 = [r for r in ((dep_after or {}).get("refunds") or []) if str(r.get("id")) == str(refund1)]
    step("原款项置已退款(3)+退款单已执行(3,共享状态词表)",
         dep_after and dep_after.get("status") == 3 and rf1 and rf1[0].get("status") == 3, dep_after)
    ev_ref = api_ok("退款事件已消费确认", "GET",
                    "/api/events?eventType=PaymentRefundedEvent&status=1&page=1&size=5", tok_admin)
    step("PaymentRefunded 消费行存在", bool(ev_ref) and ev_ref.get("total", 0) >= 1, ev_ref)
    # 第 39 次运行:退款审批办结事件走总线(通过路径)
    event_consumed("ApprovalFinishedEvent", "approval-finished-" + str(approval_r1),
                   "退款审批办结事件走总线(通过路径)")

    refund2 = api_ok("合同单发起退款(走驳回路径)", "POST", "/api/payment/bills/%s/refund" % bill_con,
                     tok_admin, {"assigneeId": uid_c, "reason": "冒烟驳回退款"})
    ref2_detail = api_ok("退款单二回读", "GET", "/api/payment/bills/%s" % refund2, tok_admin)
    approval_r2 = (ref2_detail or {}).get("refund_approval_id")
    if not approval_r2:
        step("驳回路径前置(退款单二创建)", False, ref2_detail)
        return finish()
    api_ok("退款审批驳回", "POST", "/api/approvals/%s/reject" % approval_r2, tok_c, {"comment": "冒烟驳回"})
    time.sleep(2)
    con_after = api_ok("驳回后原单回读", "GET", "/api/payment/bills/%s" % bill_con, tok_admin)
    rf2 = [r for r in ((con_after or {}).get("refunds") or []) if str(r.get("id")) == str(refund2)]
    step("驳回不改原款项状态(仍2已确认)+退款单作废(4)",
         con_after and con_after.get("status") == 2 and rf2 and rf2[0].get("status") == 4, con_after)
    # 第 39 次运行:退款审批办结事件走总线(驳回路径同样发事件,原款项零改动由消费器保证)
    event_consumed("ApprovalFinishedEvent", "approval-finished-" + str(approval_r2),
                   "退款审批办结事件走总线(驳回路径)")

    print("== 预付款科目化台账 ==")
    subjects = api_ok("科目列表(懒预置三科目)", "GET", "/api/payment/advance/subjects", tok_admin)
    sub_codes = [s.get("code") for s in (subjects or [])]
    step("合同/工程/主材 三科目在册", {"contract", "engineering", "material"} <= set(sub_codes), subjects)
    api_err("同编码科目拒绝 270015", "POST", "/api/payment/advance/subjects", tok_admin,
            {"code": "engineering", "name": "重复"}, 270015)
    api_ok("自定义科目", "POST", "/api/payment/advance/subjects", tok_admin,
           {"code": "smokesub" + stamp4[-6:], "name": "烟测科目"})
    api_err("科目不存在拒绝 270014", "POST", "/api/payment/advance/charge", tok_admin,
            {"subjectCode": "nosuch", "refType": "decoration", "refId": "DO" + stamp4, "amount": 100}, 270014)
    do_no = "DO" + stamp4
    api_err("金额为零拒绝 270018", "POST", "/api/payment/advance/charge", tok_admin,
            {"subjectCode": "engineering", "refType": "decoration", "refId": do_no, "amount": 0}, 270018)
    api_err("金额为负拒绝 270018", "POST", "/api/payment/advance/charge", tok_admin,
            {"subjectCode": "engineering", "refType": "decoration", "refId": do_no, "amount": -1000}, 270018)
    api_ok("收入 500 元", "POST", "/api/payment/advance/charge", tok_admin,
           {"subjectCode": "engineering", "refType": "decoration", "refId": do_no, "amount": 500000,
            "remark": "冒烟充值"})
    api_ok("划扣 200 元(带溯源三元组)", "POST", "/api/payment/advance/deduct", tok_admin,
           {"subjectCode": "engineering", "refType": "decoration", "refId": do_no, "amount": 200000,
            "bizAddon": "example", "bizType": "smoke", "bizId": "USE" + stamp4})
    api_err("余额不足拒绝 270017", "POST", "/api/payment/advance/deduct", tok_admin,
            {"subjectCode": "engineering", "refType": "decoration", "refId": do_no, "amount": 999999,
             "bizAddon": "example", "bizType": "smoke", "bizId": "OVER"}, 270017)
    api_ok("提现 100 元", "POST", "/api/payment/advance/withdraw", tok_admin,
           {"subjectCode": "engineering", "refType": "decoration", "refId": do_no, "amount": 100000})
    accs = api_ok("账户台账", "GET",
                  "/api/payment/advance/accounts?refType=decoration&refId=%s" % do_no, tok_admin)
    step("余额=200 元(500-200-100)", len(accs or []) == 1 and accs[0].get("balance") == 200000, accs)
    if not accs:
        return finish()
    account_id = accs[0]["id"]

    conc_results = []
    def _conc_deduct():
        status, res = call("POST", "/api/payment/advance/deduct", tok_admin,
                           {"subjectCode": "engineering", "refType": "decoration", "refId": do_no,
                            "amount": 150000, "bizAddon": "example", "bizType": "smoke", "bizId": "CONC"})
        conc_results.append(res.get("code") if isinstance(res, dict) else None)
    threads = [threading.Thread(target=_conc_deduct) for _ in range(2)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    conc_accs = api_ok("并发扣减后账户回读", "GET",
                       "/api/payment/advance/accounts?refType=decoration&refId=%s" % do_no, tok_admin)
    step("两并发扣减 150 元只成功一次(200→50,不超支)",
         sorted(conc_results) == [0, 270017] and conc_accs and conc_accs[0].get("balance") == 50000,
         (conc_results, conc_accs))

    flows = api_ok("流水列表", "GET", "/api/payment/advance/accounts/%s/flows?page=1&size=20" % account_id,
                   tok_admin)
    step("四笔流水(收/扣/提/并发扣)", bool(flows) and flows.get("total", 0) == 4, flows)

    conn = db_conn()
    try:
        with conn.cursor() as cur:
            cur.execute("select balance from payment_advance_account where id = %s", (account_id,))
            db_balance = cur.fetchone()[0]
            cur.execute("""
                select coalesce(sum(case when flow_type in (1, 4) then amount else -amount end), 0)
                from payment_advance_flow where account_id = %s
            """, (account_id,))
            flow_sum = cur.fetchone()[0]
        conn.commit()
    finally:
        conn.close()
    step("对账红线:余额==Σ带符号流水", db_balance == flow_sum == 50000, (db_balance, flow_sum))
    ev_adv = api_ok("预付款事件已消费确认", "GET",
                    "/api/events?eventType=AdvancePaymentChangedEvent&status=1&page=1&size=5", tok_admin)
    step("AdvancePaymentChanged 消费行存在", bool(ev_adv) and ev_adv.get("total", 0) >= 4, ev_adv)

    print("== 款项作废 ==")
    bill_cancel = api_ok("登记待作废款项单", "POST", "/api/payment/bills", tok_admin,
                         dict(biz3, bizId="CX" + stamp4, typeCode=dep_code, amount=500))
    api_ok("作废未收款项单", "POST", "/api/payment/bills/%s/cancel" % bill_cancel, tok_admin)
    api_err("已作废不可收款 270003", "POST", "/api/payment/bills/%s/collect" % bill_cancel, tok_admin,
            {"channels": [{"channelCode": "cash", "amount": 500}]}, 270003)

    print("== 装修单(addon-decoration V1:四线状态机+签约拆分+事件回程车)==")
    stamp5 = time.strftime("%Y%m%d%H%M%S")
    # 决算检查配置跨轮复位(组织级持久状态,上轮异常退出会泄漏;第 36 次 rbac_smoke_* 清理同款纪律)
    api_ok("决算检查配置跨轮复位", "PUT", "/api/decoration/config", tok_admin,
           {"checkEnable": False, "signEnable": False, "salesEnable": False, "constructionEnable": False,
            "selectionEnable": False, "decoPaymentEnable": False, "salePaymentEnable": False,
            "decoPaymentTarget": 990, "salePaymentTarget": 990})
    # 款项类型前置:协议键 deposit(幂等确保存在)+ 本次签约模板类型(600‰);历史模板类型停用归拢
    all_types = api_ok("类型字典全量(含停用)", "GET", "/api/payment/bill-types?includeDisabled=true", tok_admin)
    dep_codes = [t.get("code") for t in (all_types or [])]
    if "deposit" not in dep_codes:
        api_ok("协议键 deposit 类型登记", "POST", "/api/payment/bill-types", tok_admin,
               {"code": "deposit", "name": "定金", "sort": 1})
    tmpl_code = "smokedec" + stamp5[-6:]
    api_ok("签约模板类型登记(600‰)", "POST", "/api/payment/bill-types", tok_admin,
           {"code": tmpl_code, "name": "烟测装修合同款", "sort": 2,
            "scaleTemplate": json.dumps([{"sort": 1, "scale": 600}], ensure_ascii=False)})
    all_types2 = api_ok("类型字典复查", "GET", "/api/payment/bill-types", tok_admin)
    for t in (all_types2 or []):
        # API 行键为蛇形 scale_template(BillTypeService.list 口径)
        if (t.get("scale_template") or t.get("scaleTemplate")) and t.get("code") != tmpl_code:
            api_ok("停用历史模板类型归拢(%s)" % t.get("code"),
                   "POST", "/api/payment/bill-types/%s/status" % t.get("id"), tok_admin, {"status": 1})

    # 登记守卫族(档案经 CustomerProfileApi 只读契约校验)
    api_err("客户不存在拒绝 250004", "POST", "/api/decoration/orders", tok_admin,
            {"customerId": 99999999, "houseId": house_id, "type": 0,
             "designerId": uid_b, "salesmanId": uid_c}, 250004)
    api_err("房屋不存在拒绝 250005", "POST", "/api/decoration/orders", tok_admin,
            {"customerId": cust_id, "houseId": 99999999, "type": 0,
             "designerId": uid_b, "salesmanId": uid_c}, 250005)
    api_err("定金为零拒绝 250007", "POST", "/api/decoration/orders", tok_admin,
            {"customerId": cust_id, "houseId": house_id, "type": 0, "depositAmount": 0,
             "designerId": uid_b, "salesmanId": uid_c}, 250007)
    order_a = api_ok("登记装修单A(整装+定金3万+补充信息)", "POST", "/api/decoration/orders", tok_admin,
                     {"customerId": cust_id, "houseId": house_id, "type": 2,
                      "designerId": uid_b, "salesmanId": uid_c, "depositAmount": 30000000,
                      "innerArea": 88000, "houseType": "三室两厅", "style": "现代简约",
                      "note": "冒烟需求:北欧风全屋", "remark": "冒烟主链单"})
    if order_a is None:
        return finish()
    api_err("同客户同楼盘在途拒绝 250003", "POST", "/api/decoration/orders", tok_admin,
            {"customerId": cust_id, "houseId": house_id, "type": 0,
             "designerId": uid_b, "salesmanId": uid_c}, 250003)
    det_a = api_ok("单A详情(七联动回显)", "GET", "/api/decoration/orders/%s" % order_a, tok_admin)
    ok = det_a and str(det_a.get("order_no", "")).startswith("YZ" + stamp5[2:8]) \
        and det_a.get("customer_name") and det_a.get("house_label") \
        and len(det_a.get("team") or []) == 2 and len(det_a.get("belongs") or []) == 2 \
        and (det_a.get("profile") or {}).get("note") == "冒烟需求:北欧风全屋"
    step("单号前缀+客户房屋快照+团队归属双行+补充信息", ok, det_a)
    step("建单即生成定金款线(经 PaymentApi 契约)",
         any(b.get("typeCode") == "deposit" and b.get("status") == 0 for b in (det_a or {}).get("bills") or []),
         (det_a or {}).get("bills"))

    # 显式迁移表白名单
    api_err("状态0完工拒绝 250002", "POST", "/api/decoration/orders/%s/finish" % order_a, tok_admin, None, 250002)
    api_err("未收款签约拒绝 250009", "POST", "/api/decoration/orders/%s/sign" % order_a, tok_admin,
            {"contractAmount": 100000000}, 250009)

    # 定金收款 → PaymentCollectedEvent 回程车首收升定金
    bills_a = api_ok("单A款项单列表(业务三元组反查)", "GET",
                     "/api/payment/bills?bizAddon=decoration&bizType=order&bizId=%s" % order_a, tok_admin)
    dep_rows = [b for b in (bills_a or {}).get("rows", []) if b.get("type_code") == "deposit"]
    if not dep_rows:
        step("定金款项单可反查", False, bills_a)
        return finish()
    api_ok("定金收款(现金)", "POST", "/api/payment/bills/%s/collect" % dep_rows[0]["id"], tok_admin,
           {"channels": [{"channelCode": "cash", "amount": 30000000}]})
    time.sleep(2)
    det_a2 = api_ok("事件回程车:首收定金后单A回读", "GET", "/api/decoration/orders/%s" % order_a, tok_admin)
    step("合同线 0→1 定金(事件回写)+定金时间落", det_a2 and det_a2.get("status") == 1
         and bool(det_a2.get("deposit_time")), det_a2 and det_a2.get("status"))

    # 报价前置(addon-quotation):预签门槛回补「存在已锁定报价」(经 QuotationApi 只读契约,250022)
    api_err("预签门槛:无锁定报价拒绝 250022", "POST", "/api/decoration/orders/%s/presign" % order_a,
            tok_admin, None, 250022)
    q_ver = api_ok("报价版本登记(费率三元组+启税)", "POST", "/api/quotation/versions", tok_admin,
                   {"name": "烟测版本" + stamp5, "busitype": 2, "manageRate": 50, "manageDiscount": 10000,
                    "carryRate": 30, "carryDiscount": 10000, "taxRate": 10, "taxDiscount": 10000})
    if q_ver is None:
        return finish()
    api_err("同名词本拒绝 230018", "POST", "/api/quotation/versions", tok_admin,
            {"name": "烟测版本" + stamp5, "manageRate": 50}, 230018)
    api_err("装修单不存在拒绝 230005", "POST", "/api/quotation/quotations", tok_admin,
            {"orderId": 99999999, "name": "幽灵报价"}, 230005)
    quota_a = api_ok("单A建报价(版本费率快照带出)", "POST", "/api/quotation/quotations", tok_admin,
                     {"orderId": order_a, "name": "整装方案一", "versionId": q_ver})
    if quota_a is None:
        return finish()
    det_q = api_ok("报价详情(自动费率区)", "GET", "/api/quotation/quotations/%s" % quota_a, tok_admin)
    auto_areas = [a for a in (det_q or {}).get("areas", []) if a.get("name") == "生成项目"]
    auto_items = {i.get("type"): i for i in (auto_areas[0].get("items") if auto_areas else [])}
    ok = len(auto_areas) == 1 and set(auto_items) == {2, 3, 8} \
        and (det_q or {}).get("manage_rate") == 50 and (det_q or {}).get("tax_rate") == 10
    step("自动生成费率区(搬运/管理/税金,版本费率快照)", ok, det_q)

    q_area = api_ok("加功能区(全屋工程)", "POST", "/api/quotation/quotations/%s/areas" % quota_a,
                    tok_admin, {"name": "全屋工程", "type": 1})
    if q_area is None:
        return finish()
    api_ok("加功能项(乳胶漆 100m²×25元)", "POST",
           "/api/quotation/quotations/%s/areas/%s/items" % (quota_a, q_area), tok_admin,
           {"name": "墙面乳胶漆", "subtype": 0, "unit": "m²", "num": 100000, "price": 25000})
    api_err("数量为零拒绝 230001", "POST",
            "/api/quotation/quotations/%s/areas/%s/items" % (quota_a, q_area), tok_admin,
            {"name": "坏行", "num": 0, "price": 100}, 230001)
    api_ok("加功能项(定制柜体 1项×3万元)", "POST",
           "/api/quotation/quotations/%s/areas/%s/items" % (quota_a, q_area), tok_admin,
           {"name": "定制柜体", "subtype": 1, "unit": "项", "num": 1000, "price": 30000000})
    det_q2 = api_ok("报价回读(服务端聚合)", "GET", "/api/quotation/quotations/%s" % quota_a, tok_admin)
    # 聚合口径:undis=100×25+1×30000=32500元;管理=×50‰=162.5;搬运=×30‰=97.5;税=(三者和)×10‰=32.76
    ok = det_q2 and det_q2.get("undis_price") == 32500000 and det_q2.get("discount_price") == 32500000 \
        and det_q2.get("total_price") == 32792760
    step("服务端聚合(修老信任前端总价):32500+162.5+97.5+32.76=32792.76元", ok, det_q2)
    api_err("费率自动项改价拒绝 230016", "PUT",
            "/api/quotation/quotations/%s/items/%s" % (quota_a, auto_items[3]["id"]), tok_admin,
            {"name": "管理费", "num": 1000, "price": 1}, 230016)

    api_ok("报价锁定(定稿 0→1)", "POST", "/api/quotation/quotations/%s/lock" % quota_a, tok_admin)
    api_err("重复锁定拒绝 230007", "POST", "/api/quotation/quotations/%s/lock" % quota_a, tok_admin, None, 230007)
    api_err("锁定后加项拒绝 230003", "POST",
            "/api/quotation/quotations/%s/areas/%s/items" % (quota_a, q_area), tok_admin,
            {"name": "锁后项", "num": 1000, "price": 100}, 230003)

    api_ok("预签 1→2", "POST", "/api/decoration/orders/%s/presign" % order_a, tok_admin)

    # 确认线(0 → 21大改 → 2;不可跳步不可倒退)
    api_ok("合同方案确认(大改变体 0→21)", "POST", "/api/decoration/orders/%s/design-confirm" % order_a,
           tok_admin, {"variant": 2})
    api_err("重复确认拒绝 250014", "POST", "/api/decoration/orders/%s/design-confirm" % order_a,
            tok_admin, {"variant": 0}, 250014)
    api_ok("放线确认 21→2", "POST", "/api/decoration/orders/%s/drawline-confirm" % order_a, tok_admin)
    api_err("放线后再确认倒退拒绝 250014", "POST", "/api/decoration/orders/%s/drawline-confirm" % order_a,
            tok_admin, None, 250014)

    # 量房:申请→成功联动工程线开工;失败线另测
    api_ok("量房申请(预签后可发起)", "POST", "/api/decoration/orders/%s/measures" % order_a, tok_admin,
           {"planTime": stamp5[0:4] + "-" + stamp5[4:6] + "-20T10:00:00", "remark": "冒烟量房"})
    api_err("重复申请拒绝 250017", "POST", "/api/decoration/orders/%s/measures" % order_a, tok_admin, {}, 250017)
    det_a3 = api_ok("单A回读取量房计划", "GET", "/api/decoration/orders/%s" % order_a, tok_admin)
    ms = [m for m in (det_a3 or {}).get("measures", []) if m.get("status") == 1]
    if not ms:
        step("量房计划在册", False, det_a3)
        return finish()
    api_ok("量房成功(联动工程线开工)", "POST", "/api/decoration/measures/%s/success" % ms[0]["id"], tok_admin)
    api_err("已办结量房再操作拒绝 250017", "POST", "/api/decoration/measures/%s/success" % ms[0]["id"],
            tok_admin, None, 250017)
    det_a4 = api_ok("单A回读验证工程线", "GET", "/api/decoration/orders/%s" % order_a, tok_admin)
    step("工程线 0→1 开工(量房联动,四线独立)", det_a4 and det_a4.get("project_status") == 1, det_a4)

    # 签约:款线按比例模板拆分
    api_err("合同总额为零拒绝 250010", "POST", "/api/decoration/orders/%s/sign" % order_a, tok_admin,
            {"contractAmount": 0}, 250010)
    api_ok("签约(合同10万,模板600‰拆6万)", "POST", "/api/decoration/orders/%s/sign" % order_a, tok_admin,
           {"contractAmount": 100000000})
    bills_a2 = api_ok("签约后款项单复查", "GET",
                      "/api/payment/bills?bizAddon=decoration&bizType=order&bizId=%s" % order_a, tok_admin)
    tmpl_rows = [b for b in (bills_a2 or {}).get("rows", [])
                 if b.get("type_code") == tmpl_code and b.get("direction") == 0]
    dep_rows2 = [b for b in (bills_a2 or {}).get("rows", []) if b.get("type_code") == "deposit"]
    step("款线拆分一张6万+定金不重建", len(tmpl_rows) == 1 and tmpl_rows[0].get("amount") == 60000000
         and len(dep_rows2) == 1, bills_a2)
    det_a5 = api_ok("签约后单A回读", "GET", "/api/decoration/orders/%s" % order_a, tok_admin)
    step("签约不覆盖已完成设计线(四线独立)", det_a5 and det_a5.get("status") == 3
         and det_a5.get("design_status") == 2, det_a5)

    api_ok("重签 3→4", "POST", "/api/decoration/orders/%s/resign" % order_a, tok_admin)
    api_ok("重签后再签约 4→3", "POST", "/api/decoration/orders/%s/sign" % order_a, tok_admin,
           {"contractAmount": 100000000})
    bills_a3 = api_ok("再签约后款项单复查", "GET",
                      "/api/payment/bills?bizAddon=decoration&bizType=order&bizId=%s" % order_a, tok_admin)
    tmpl_rows3 = [b for b in (bills_a3 or {}).get("rows", [])
                  if b.get("type_code") == tmpl_code and b.get("direction") == 0]
    step("重签循环款线不重建(修老硬删重建)", len(tmpl_rows3) == 1, bills_a3)

    # 收齐款线 → 决算 → 完工
    api_err("决算前置:款项未收齐拒绝 250016", "POST", "/api/decoration/orders/%s/account" % order_a,
            tok_admin, None, 250016)
    api_ok("合同款线收款", "POST", "/api/payment/bills/%s/collect" % tmpl_rows[0]["id"], tok_admin,
           {"channels": [{"channelCode": "bank", "amount": 60000000}]})
    api_ok("决算 3→7(款项已收齐)", "POST", "/api/decoration/orders/%s/account" % order_a, tok_admin)
    api_err("重复决算拒绝 250002", "POST", "/api/decoration/orders/%s/account" % order_a, tok_admin, None, 250002)
    api_ok("完工 7→8", "POST", "/api/decoration/orders/%s/finish" % order_a, tok_admin)
    api_err("完工后转归属拒绝 250020", "POST", "/api/decoration/orders/%s/belong/transfer" % order_a,
            tok_admin, {"line": 0, "userId": uid_c}, 250020)

    # 终止链(单C,同客户同房屋——A 已完工终态不挡重建):驳回零改动+通过联动
    order_c = api_ok("登记装修单C(终止链,带定金)", "POST", "/api/decoration/orders", tok_admin,
                     {"customerId": cust_id, "houseId": house_id, "type": 0,
                      "designerId": uid_b, "salesmanId": uid_c, "depositAmount": 10000000})
    if order_c is None:
        return finish()
    bills_c = api_ok("单C款项单反查", "GET",
                     "/api/payment/bills?bizAddon=decoration&bizType=order&bizId=%s" % order_c, tok_admin)
    dep_c = [b for b in (bills_c or {}).get("rows", []) if b.get("type_code") == "deposit"]
    api_ok("单C定金收款", "POST", "/api/payment/bills/%s/collect" % dep_c[0]["id"], tok_admin,
           {"channels": [{"channelCode": "cash", "amount": 10000000}]})
    time.sleep(2)
    quota_c = api_ok("单C建报价(预签门槛前置)", "POST", "/api/quotation/quotations", tok_admin,
                     {"orderId": order_c, "name": "半包方案一", "versionId": q_ver})
    if quota_c is None:
        return finish()
    api_ok("单C报价锁定", "POST", "/api/quotation/quotations/%s/lock" % quota_c, tok_admin)
    api_ok("单C预签", "POST", "/api/decoration/orders/%s/presign" % order_c, tok_admin)
    api_ok("单C签约(拆款线)", "POST", "/api/decoration/orders/%s/sign" % order_c, tok_admin,
           {"contractAmount": 50000000})
    term1 = api_ok("单C提交终止审批(处理人 example_c)", "POST", "/api/decoration/orders/%s/terminate" % order_c,
                   tok_admin, {"assigneeId": uid_c, "reason": "冒烟终止"})
    if term1 is None:
        return finish()
    api_err("重复提交终止拒绝 250013", "POST", "/api/decoration/orders/%s/terminate" % order_c,
            tok_admin, {"assigneeId": uid_c, "reason": "再提交"}, 250013)
    api_err("审批未清重签拒绝 250012", "POST", "/api/decoration/orders/%s/resign" % order_c,
            tok_admin, None, 250012)
    api_ok("终止审批驳回", "POST", "/api/approvals/%s/reject" % term1, tok_c, {"comment": "冒烟驳回终止"})
    time.sleep(2)
    det_c1 = api_ok("驳回后单C回读(修老驳回顶态)", "GET", "/api/decoration/orders/%s" % order_c, tok_admin)
    step("驳回零改动(仍3签约)", det_c1 and det_c1.get("status") == 3, det_c1)
    term2 = api_ok("单C再次提交终止审批", "POST", "/api/decoration/orders/%s/terminate" % order_c,
                   tok_admin, {"assigneeId": uid_c, "reason": "冒烟终止二次"})
    if term2 is None:
        return finish()
    api_ok("终止审批通过", "POST", "/api/approvals/%s/approve" % term2, tok_c, {"comment": "冒烟同意终止"})
    time.sleep(2)
    det_c2 = api_ok("终止通过后单C回读", "GET", "/api/decoration/orders/%s" % order_c, tok_admin)
    bills_c2 = api_ok("终止后款项单复查", "GET",
                      "/api/payment/bills?bizAddon=decoration&bizType=order&bizId=%s" % order_c, tok_admin)
    dep_c2 = [b for b in (bills_c2 or {}).get("rows", []) if b.get("type_code") == "deposit"]
    tmpl_c2 = [b for b in (bills_c2 or {}).get("rows", []) if b.get("type_code") == tmpl_code]
    ok = det_c2 and det_c2.get("status") == 6 \
        and all(b.get("status") == 2 for b in (det_c2.get("belongs") or [])) \
        and dep_c2 and dep_c2[0].get("status") == 1 \
        and tmpl_c2 and tmpl_c2[0].get("status") == 4
    step("合同线 3→6 终止+归属全失效+已收定金保留+未收款线作废", ok, (det_c2, bills_c2))
    # 第 39 次运行:终止审批办结事件走总线(消费器回写合同线+联动作废)
    event_consumed("ApprovalFinishedEvent", "approval-finished-" + str(term2),
                   "终止审批办结事件走总线(消费确认)")

    # 废单链(单B,同客户同房屋——C 已终止不挡重建)
    order_b = api_ok("登记装修单B(废单链,无定金)", "POST", "/api/decoration/orders", tok_admin,
                     {"customerId": cust_id, "houseId": house_id, "type": 1,
                      "designerId": uid_b, "salesmanId": uid_c})
    if order_b is None:
        return finish()
    api_ok("废单 0→5(排除法白名单化)", "POST", "/api/decoration/orders/%s/obsolete" % order_b, tok_admin)
    api_err("废单后预签拒绝 250002", "POST", "/api/decoration/orders/%s/presign" % order_b, tok_admin, None, 250002)
    api_ok("废单仍可转归属(业绩留痕,老规则)", "POST", "/api/decoration/orders/%s/belong/transfer" % order_b,
           tok_admin, {"line": 1, "userId": uid_b})
    det_b = api_ok("单B回读", "GET", "/api/decoration/orders/%s" % order_b, tok_admin)
    sales_active = [b for b in (det_b or {}).get("belongs", []) if b.get("line") == 1 and b.get("status") == 1]
    ok = det_b and det_b.get("status") == 5 and det_b.get("salesman_id") == uid_b and len(sales_active) == 1
    step("销售线归属已转移(新行有效+主单同步)", ok, det_b)

    # ========= 决算六项检查(B 类#6 回补,第 47 次;单H 专测,不动单A既有断言)=========
    # 此时在途单=0(A 完工/C 终止/B 废单),单H 用同户不撞在途查重;终态留在 3(无害)。
    order_h = api_ok("登记装修单H(决算检查链,定金1万)", "POST", "/api/decoration/orders", tok_admin,
                     {"customerId": cust_id, "houseId": house_id, "type": 0,
                      "designerId": uid_b, "salesmanId": uid_c, "depositAmount": 10000000})
    if order_h is None:
        return finish()
    bills_h0 = api_ok("单H款项反查(定金线)", "GET",
                      "/api/payment/bills?bizAddon=decoration&bizType=order&bizId=%s" % order_h, tok_admin)
    dep_h = [b for b in (bills_h0 or {}).get("rows", []) if b.get("type_code") == "deposit"]
    api_ok("单H定金收款(签约门槛前置)", "POST", "/api/payment/bills/%s/collect" % dep_h[0]["id"], tok_admin,
           {"channels": [{"channelCode": "cash", "amount": 10000000}]})
    goods_h = api_ok("建决算检查专用商品(启用态)", "POST", "/api/basedata/goods", tok_admin,
                     {"name": "烟测决算商品" + stamp3, "categoryId": cat_child,
                      "brandId": brand_id, "unit": "片", "selling": 90000})
    if goods_h is None:
        return finish()
    cfg0 = api_ok("决算配置读(懒初始化)", "GET", "/api/decoration/config", tok_admin)
    step("配置默认全关+target 990(‰)", (cfg0 or {}).get("check_enable") == 0
         and (cfg0 or {}).get("deco_payment_target") == 990
         and (cfg0 or {}).get("sale_payment_target") == 990, cfg0)
    quota_h = api_ok("单H建报价", "POST", "/api/quotation/quotations", tok_admin,
                     {"orderId": order_h, "name": "决算检查方案", "versionId": q_ver})
    if quota_h is None:
        return finish()
    api_ok("单H报价锁定", "POST", "/api/quotation/quotations/%s/lock" % quota_h, tok_admin)
    api_ok("单H预签", "POST", "/api/decoration/orders/%s/presign" % order_h, tok_admin)
    api_ok("单H签约(拆款线)", "POST", "/api/decoration/orders/%s/sign" % order_h, tok_admin,
           {"contractAmount": 60000000})
    api_ok("开总闸全子项(target 0 比例闸放行)", "PUT", "/api/decoration/config", tok_admin,
           {"checkEnable": True, "signEnable": True, "salesEnable": True, "constructionEnable": True,
            "selectionEnable": True, "decoPaymentEnable": True, "salePaymentEnable": True,
            "decoPaymentTarget": 0, "salePaymentTarget": 0})
    pre1 = api_ok("决算预检(总闸开)", "GET", "/api/decoration/orders/%s/account-check" % order_h, tok_admin)
    it1 = (pre1 or {}).get("items") or {}
    step("预检:无施工单FAIL(total=0)+选材恒UNCHECK+签约PASS+sales初始PASS",
         (pre1 or {}).get("checkEnable") is True and (pre1 or {}).get("overall") == 2
         and (it1.get("construction") or {}).get("status") == 2
         and ((it1.get("construction") or {}).get("detail") or {}).get("total") == 0
         and (it1.get("selection") or {}).get("status") == 0
         and (it1.get("sign") or {}).get("status") == 1
         and (it1.get("sales") or {}).get("status") == 1, pre1)
    api_err("决算检查不满足拒绝 250023", "POST", "/api/decoration/orders/%s/account" % order_h,
            tok_admin, None, 250023)
    sale_h = api_ok("开销售单挂单H(sales 子项FAIL源)", "POST", "/api/sales/orders", tok_admin,
                    {"orderId": order_h, "items": [{"goodsId": goods_h, "num": 1000}]})
    if sale_h is None:
        return finish()
    pre2 = api_ok("预检复查(销售未完结)", "GET", "/api/decoration/orders/%s/account-check" % order_h, tok_admin)
    it2 = (pre2 or {}).get("items") or {}
    step("预检:sales FAIL(unfinish=1)+明细计数",
         (it2.get("sales") or {}).get("status") == 2
         and ((it2.get("sales") or {}).get("detail") or {}).get("unfinish") == 1, pre2)
    api_err("多子项FAIL仍拒 250023", "POST", "/api/decoration/orders/%s/account" % order_h,
            tok_admin, None, 250023)
    api_ok("销售单废单(sales 子项复位)", "POST", "/api/sales/orders/%s/terminate" % sale_h, tok_admin)
    pre3 = api_ok("预检复查(废单后)", "GET", "/api/decoration/orders/%s/account-check" % order_h, tok_admin)
    it3 = (pre3 or {}).get("items") or {}
    step("预检:sales 复位 PASS(status=4 废单不入未完结)", (it3.get("sales") or {}).get("status") == 1, pre3)
    api_ok("装修收款target上调990(比例闸FAIL源)", "PUT", "/api/decoration/config", tok_admin,
           {"decoPaymentTarget": 990})
    pre4 = api_ok("预检复查(target 990)", "GET", "/api/decoration/orders/%s/account-check" % order_h, tok_admin)
    it4 = (pre4 or {}).get("items") or {}
    step("预检:decoPayment FAIL(0<990)+target回显", (it4.get("decoPayment") or {}).get("status") == 2
         and (pre4 or {}).get("decoPaymentTarget") == 990, pre4)
    api_err("比例闸FAIL拒绝 250023", "POST", "/api/decoration/orders/%s/account" % order_h,
            tok_admin, None, 250023)
    api_ok("target复位0+总闸关(回归V1硬闸)", "PUT", "/api/decoration/config", tok_admin,
           {"checkEnable": False, "decoPaymentTarget": 0})
    cfg1 = api_ok("配置复位复查", "GET", "/api/decoration/config", tok_admin)
    step("配置复位:总闸关+其余开关保留(幂等白名单)", (cfg1 or {}).get("check_enable") == 0
         and (cfg1 or {}).get("check_construction_enable") == 1, cfg1)
    api_err("总闸关:款项未收齐拒绝 250016(V1硬闸回归)", "POST",
            "/api/decoration/orders/%s/account" % order_h, tok_admin, None, 250016)
    api_ok("配置全复位(六开关全关)", "PUT", "/api/decoration/config", tok_admin,
           {"signEnable": False, "salesEnable": False, "constructionEnable": False,
            "selectionEnable": False, "decoPaymentEnable": False, "salePaymentEnable": False})

    # 通知回程车(设计师收建单通知)+ 事件总线消费确认
    notifs_b = api_ok("设计师通知列表", "GET", "/api/notifications?page=1&size=50", tok_b)
    hit_created = [n for n in (notifs_b or [])
                   if n.get("source_type") == "notify" and "装修单" in str(n.get("title", ""))]
    step("事件总线消费:设计师收建单/状态通知", len(hit_created) >= 1, len(hit_created or []))
    time.sleep(3)
    ev_dec = api_ok("装修单事件消费查询", "GET",
                    "/api/events?eventType=DecorationStatusChangedEvent&status=1&page=1&size=5", tok_admin)
    step("DecorationStatusChanged 消费行存在", bool(ev_dec) and ev_dec.get("total", 0) >= 1, ev_dec)

    conn = db_conn()
    try:
        with conn.cursor() as cur:
            cur.execute("""
                select count(*) from yz_event_task
                where event_type like 'Decoration%%' and status not in (1, 3)
            """)
            undelivered = cur.fetchone()[0]
            cur.execute("select order_no, status from decoration_order where id = %s", (order_a,))
            row_a = cur.fetchone()
            cur.execute("""
                select count(*) from decoration_order_track
                where order_id = %s and action = 'deposit'
            """, (order_a,))
            deposit_tracks = cur.fetchone()[0]
        conn.commit()
    finally:
        conn.close()
    # 签约等事件现为多消费者(如 member 签约自动核销),总线契约=at-least-once 重试;
    # 对账容忍重试终态窗口(5s x 6),最终零滞留
    for _ in range(6):
        if undelivered == 0:
            break
        time.sleep(5)
        conn = db_conn()
        try:
            with conn.cursor() as cur:
                cur.execute("""
                    select count(*) from yz_event_task
                    where event_type like 'Decoration%%' and status not in (1, 3)
                """)
                undelivered = cur.fetchone()[0]
        finally:
            conn.close()
    step("总线对账:decoration 事件零滞留(重试终态窗口+死信豁免,口径对齐 member/supplier 段)", undelivered == 0, undelivered)
    step("轨迹留痕:首收定金动作在册", row_a and row_a[1] == 8 and deposit_tracks >= 1, row_a)

    print("== 报价(addon-quotation V1:双态位审核线+费率域守卫+切默认)==")
    api_err("费率越界拒绝 230020", "POST", "/api/quotation/versions", tok_admin,
            {"name": "坏费率" + stamp5, "manageRate": 2000}, 230020)
    ver_off = api_ok("建停用测试版本", "POST", "/api/quotation/versions", tok_admin,
                     {"name": "烟测停用版" + stamp5, "manageRate": 50})
    api_ok("停用版本", "POST", "/api/quotation/versions/%s/status" % ver_off, tok_admin, {"status": 0})
    api_err("停用版本建报价拒绝 230019", "POST", "/api/quotation/quotations", tok_admin,
            {"orderId": order_c, "name": "停用版报价", "versionId": ver_off}, 230019)

    # 审核线(用单A已锁定报价):提交→审核→完成,不可跳步不可重复
    api_err("未审核跳步完成拒绝 230011", "POST", "/api/quotation/quotations/%s/finish" % quota_a,
            tok_admin, None, 230011)
    api_ok("提交审核 0→1(定稿送审)", "POST", "/api/quotation/quotations/%s/submit" % quota_a, tok_admin)
    api_err("重复提交拒绝 230009", "POST", "/api/quotation/quotations/%s/submit" % quota_a, tok_admin, None, 230009)
    api_ok("审核通过 1→2", "POST", "/api/quotation/quotations/%s/audit" % quota_a, tok_admin)
    api_err("未提交先审核拒绝 230010", "POST", "/api/quotation/quotations/%s/audit" % quota_c, tok_admin, None, 230010)
    api_ok("审核完成 2→3(记完成时间)", "POST", "/api/quotation/quotations/%s/finish" % quota_a, tok_admin)
    api_err("已完成再操作拒绝 230012", "POST", "/api/quotation/quotations/%s/finish" % quota_a,
            tok_admin, None, 230012)
    api_ok("设默认(仅锁定报价,同单唯一)", "POST", "/api/quotation/quotations/%s/default" % quota_a, tok_admin)
    quota_c2 = api_ok("单C第二份报价(不锁定)", "POST", "/api/quotation/quotations", tok_admin,
                      {"orderId": order_c, "name": "半包方案二"})
    api_err("未锁定报价设默认拒绝 230013", "POST", "/api/quotation/quotations/%s/default" % quota_c2,
            tok_admin, None, 230013)
    det_qa = api_ok("审核线终态回读", "GET", "/api/quotation/quotations/%s" % quota_a, tok_admin)
    ok = det_qa and det_qa.get("status") == 1 and det_qa.get("attstatus") == 3 \
        and det_qa.get("is_default") == 1 and bool(det_qa.get("finish_time"))
    step("双态位终态:锁定+审核完成+默认+完成时间", ok, det_qa)

    # 上限(单C已 2 份,第 3 份到默认上限,第 4 份拒绝)
    quota_c3 = api_ok("单C第三份报价(达上限)", "POST", "/api/quotation/quotations", tok_admin,
                      {"orderId": order_c, "name": "半包方案三"})
    api_err("超上限拒绝 230004", "POST", "/api/quotation/quotations", tok_admin,
            {"orderId": order_c, "name": "半包方案四"}, 230004)

    # 解锁线:解锁后重锁,lockTime 首次幂等
    qdet_c = api_ok("单C报价回读(记首锁时间)", "GET", "/api/quotation/quotations/%s" % quota_c, tok_admin)
    lock_time0 = (qdet_c or {}).get("lock_time")
    api_ok("解锁 1→0", "POST", "/api/quotation/quotations/%s/unlock" % quota_c, tok_admin)
    api_err("未锁定状态解锁拒绝 230008", "POST", "/api/quotation/quotations/%s/unlock" % quota_c,
            tok_admin, None, 230008)
    api_ok("重新锁定(lockTime 幂等不覆盖)", "POST", "/api/quotation/quotations/%s/lock" % quota_c, tok_admin)
    qdet_c2 = api_ok("重锁后回读", "GET", "/api/quotation/quotations/%s" % quota_c, tok_admin)
    step("lockTime 首次幂等", qdet_c2 and qdet_c2.get("lock_time") == lock_time0,
         (lock_time0, (qdet_c2 or {}).get("lock_time")))

    time.sleep(3)
    ev_q = api_ok("报价事件消费查询", "GET",
                  "/api/events?eventType=QuotationStatusChangedEvent&status=1&page=1&size=5", tok_admin)
    step("QuotationStatusChanged 消费行存在", bool(ev_q) and ev_q.get("total", 0) >= 1, ev_q)
    conn = db_conn()
    try:
        with conn.cursor() as cur:
            cur.execute("""
                select count(*) from yz_event_task
                where event_type like 'Quotation%%' and status != 1
            """)
            q_undelivered = cur.fetchone()[0]
            cur.execute("""
                select count(*) from quotation_track where quotation_id = %s and action = 'lock'
            """, (quota_a,))
            lock_tracks = cur.fetchone()[0]
        conn.commit()
    finally:
        conn.close()
    step("总线对账:quotation 事件零滞留(全消费确认)", q_undelivered == 0, q_undelivered)
    step("轨迹留痕:锁定动作在册", lock_tracks >= 1, lock_tracks)

    print("== 施工(addon-construction V1:放线点火+节点推进+团队留痕+验收收口)==")
    import datetime
    s6 = time.strftime("%d%H%M%S")
    sort_base = 20000 + int(s6) * 3
    day1 = (datetime.datetime.now() + datetime.timedelta(days=1)).strftime("%Y-%m-%d")
    day2 = (datetime.datetime.now() + datetime.timedelta(days=2)).strftime("%Y-%m-%d")

    # 节点模板(组织施工流;木工建后停用,点火快照应剔除)
    # 归拢历史运行遗留的启用节点(节点是组织配置不删除,不清场则点火快照混入旧节点)
    legacy = api_ok("历史节点全量回读", "GET", "/api/construction/nodes?includeDisabled=true", tok_admin)
    for n in (legacy or []):
        if n.get("status") == 1:
            api_ok("停用历史启用节点(%s)" % n.get("name"), "POST",
                   "/api/construction/nodes/%s/status" % n.get("id"), tok_admin, {"status": 0})
    node1 = api_ok("建节点模板1 拆改(无闸 3天)", "POST", "/api/construction/nodes", tok_admin,
                   {"name": "拆改" + s6, "sort": sort_base + 1, "precheck": 0, "postcheck": 0,
                    "timeLimit": 3000, "salaryLimit": 999999000, "accessoryLimit": 999999000})
    node2 = api_ok("建节点模板2 水电(开工闸500‰ 10天)", "POST", "/api/construction/nodes", tok_admin,
                   {"name": "水电" + s6, "sort": sort_base + 2, "precheck": 500, "postcheck": 0,
                    "timeLimit": 10000, "salaryLimit": 5000000, "accessoryLimit": 999999000})
    node3 = api_ok("建节点模板3 瓦工(双闸500‰ 17天)", "POST", "/api/construction/nodes", tok_admin,
                   {"name": "瓦工" + s6, "sort": sort_base + 3, "precheck": 500, "postcheck": 500,
                    "timeLimit": 17000, "salaryLimit": 999999000, "accessoryLimit": 999999000})
    node4 = api_ok("建节点模板4 木工", "POST", "/api/construction/nodes", tok_admin,
                   {"name": "木工" + s6, "sort": sort_base + 4, "precheck": 0, "postcheck": 0,
                    "timeLimit": 1000, "salaryLimit": 999999000, "accessoryLimit": 999999000})
    if not all([node1, node2, node3, node4]):
        return finish()
    api_err("同序号节点拒绝 240015", "POST", "/api/construction/nodes", tok_admin,
            {"name": "其他" + s6, "sort": sort_base + 1, "timeLimit": 0}, 240015)
    api_ok("停用木工节点(点火快照应剔除)", "POST", "/api/construction/nodes/%s/status" % node4,
           tok_admin, {"status": 0})
    cfg0 = api_ok("组织配置回显(懒初始化默认门槛)", "GET", "/api/construction/config", tok_admin)
    step("默认验收门槛 800‰", cfg0 and cfg0.get("acceptance_floor") == 800, cfg0)
    api_err("验收门槛越界拒绝 240014", "PUT", "/api/construction/config", tok_admin,
            {"acceptanceFloor": 1001}, 240014)

    # 五组客户/房屋(get-or-create 幂等通道,装修单客户×楼盘唯一在途约束要求互不重合)
    def smoke_pair(suffix):
        phone = "135%08d" % ((int(s6) * 10 + suffix) % 100000000)
        c = api_ok("施工客%d get-or-create" % suffix, "POST",
                   "/api/customerprofile/customers/exist", tok_admin,
                   {"phone": phone, "name": "施工客%d%s" % (suffix, s6)})
        h = api_ok("施工房%d get-or-create" % suffix, "POST",
                   "/api/customerprofile/houses/exist", tok_admin,
                   {"villageId": village_id, "number": "9栋9单元%s-%d" % (s6, suffix)})
        return (c or {}).get("customerId"), (h or {}).get("houseId")

    cust_d, house_d = smoke_pair(1)
    cust_e, house_e = smoke_pair(2)
    cust_f, house_f = smoke_pair(3)
    cust_g, house_g = smoke_pair(4)
    cust_n, house_n = smoke_pair(5)

    # 登记五张装修单(D=主链带定金;N=无报价放线反例)
    order_d = api_ok("登记装修单D(施工主链,定金3万)", "POST", "/api/decoration/orders", tok_admin,
                     {"customerId": cust_d, "houseId": house_d, "type": 0,
                      "designerId": uid_b, "salesmanId": uid_c, "depositAmount": 30000000})
    order_e = api_ok("登记装修单E(放线排期/冲突源)", "POST", "/api/decoration/orders", tok_admin,
                     {"customerId": cust_e, "houseId": house_e, "type": 0,
                      "designerId": uid_b, "salesmanId": uid_c})
    order_f = api_ok("登记装修单F(冲突靶/失败链)", "POST", "/api/decoration/orders", tok_admin,
                     {"customerId": cust_f, "houseId": house_f, "type": 0,
                      "designerId": uid_b, "salesmanId": uid_c})
    order_g = api_ok("登记装修单G(补录链)", "POST", "/api/decoration/orders", tok_admin,
                     {"customerId": cust_g, "houseId": house_g, "type": 0,
                      "designerId": uid_b, "salesmanId": uid_c})
    order_n = api_ok("登记装修单N(无报价反例)", "POST", "/api/decoration/orders", tok_admin,
                     {"customerId": cust_n, "houseId": house_n, "type": 0,
                      "designerId": uid_b, "salesmanId": uid_c})
    if not all([order_d, order_e, order_f, order_g, order_n]):
        return finish()

    # D 链前置:定金收款→报价锁定→预签→签约拆款线
    bills_d = api_ok("单D款项反查", "GET",
                     "/api/payment/bills?bizAddon=decoration&bizType=order&bizId=%s" % order_d, tok_admin)
    dep_d = [b for b in (bills_d or {}).get("rows", []) if b.get("type_code") == "deposit"]
    api_ok("单D定金收款", "POST", "/api/payment/bills/%s/collect" % dep_d[0]["id"], tok_admin,
           {"channels": [{"channelCode": "cash", "amount": 30000000}]})
    time.sleep(2)
    quota_d = api_ok("单D建报价", "POST", "/api/quotation/quotations", tok_admin,
                     {"orderId": order_d, "name": "施工链报价" + s6, "versionId": q_ver})
    api_ok("单D报价锁定", "POST", "/api/quotation/quotations/%s/lock" % quota_d, tok_admin)
    api_ok("单D预签", "POST", "/api/decoration/orders/%s/presign" % order_d, tok_admin)
    api_ok("单D签约(拆6万款线)", "POST", "/api/decoration/orders/%s/sign" % order_d, tok_admin,
           {"contractAmount": 100000000})

    # E/F/G 报价锁定(放线门槛=存在已锁定报价)
    for tag, oid in (("E", order_e), ("F", order_f), ("G", order_g)):
        q = api_ok("单%s建报价" % tag, "POST", "/api/quotation/quotations", tok_admin,
                   {"orderId": oid, "name": "放线前置报价%s%s" % (tag, s6), "versionId": q_ver})
        api_ok("单%s报价锁定" % tag, "POST", "/api/quotation/quotations/%s/lock" % q, tok_admin)

    # 放线申请:门槛与查重
    api_err("无锁定报价放线拒绝 240003", "POST", "/api/construction/drawlines", tok_admin,
            {"orderId": order_n}, 240003)
    dl_d = api_ok("单D放线申请(排队)", "POST", "/api/construction/drawlines", tok_admin,
                  {"orderId": order_d})
    api_err("重复放线拒绝 240002", "POST", "/api/construction/drawlines", tok_admin,
            {"orderId": order_d}, 240002)
    dl_e = api_ok("单E放线申请", "POST", "/api/construction/drawlines", tok_admin, {"orderId": order_e})
    dl_f = api_ok("单F放线申请", "POST", "/api/construction/drawlines", tok_admin, {"orderId": order_f})
    if not all([dl_d, dl_e, dl_f]):
        return finish()

    # 排期:参数/冲突/状态守卫(修老失效查重:真查 status=1 同小组区间重叠)
    api_err("结束早于开始拒绝 240014", "POST", "/api/construction/drawlines/%s/schedule" % dl_d, tok_admin,
            {"groupName": "放线B组" + s6, "planStart": day2 + "T12:00:00", "planEnd": day2 + "T08:00:00"},
            240014)
    api_err("排队单直接成功拒绝 240005", "POST", "/api/construction/drawlines/%s/success" % dl_d,
            tok_admin, None, 240005)
    api_ok("单E排期(A组 明日08-12)", "POST", "/api/construction/drawlines/%s/schedule" % dl_e, tok_admin,
           {"groupName": "放线A组" + s6, "planStart": day1 + "T08:00:00", "planEnd": day1 + "T12:00:00"})
    api_err("同组时间重叠拒绝 240007", "POST", "/api/construction/drawlines/%s/schedule" % dl_f, tok_admin,
            {"groupName": "放线A组" + s6, "planStart": day1 + "T10:00:00", "planEnd": day1 + "T14:00:00"},
            240007)
    api_err("已排期单补录拒绝 240002(活跃放线在册)", "POST", "/api/construction/drawlines/retroactive",
            tok_admin, {"orderId": order_e, "groupName": "放线A组" + s6,
                        "planStart": day1 + "T08:00:00", "planEnd": day1 + "T12:00:00"}, 240002)
    api_ok("单F放线失败(0→3,设计师收通知)", "POST", "/api/construction/drawlines/%s/fail" % dl_f,
           tok_admin, {"remark": "现场不具备放线条件"})
    api_err("失败单重复排期拒绝 240005", "POST", "/api/construction/drawlines/%s/schedule" % dl_f,
            tok_admin, {"groupName": "放线A组" + s6, "planStart": day1 + "T14:00:00",
                        "planEnd": day1 + "T18:00:00"}, 240005)

    # 单D 排期→成功点火
    api_ok("单D排期(B组 后日08-12)", "POST", "/api/construction/drawlines/%s/schedule" % dl_d, tok_admin,
           {"groupName": "放线B组" + s6, "planStart": day2 + "T08:00:00", "planEnd": day2 + "T12:00:00"})
    api_ok("单D放线成功(点火:施工单+节点排期快照)", "POST", "/api/construction/drawlines/%s/success" % dl_d,
           tok_admin)
    time.sleep(2)
    det_d = api_ok("单D施工详情(点火回读)", "GET", "/api/construction/orders/%s" % order_d, tok_admin)
    if det_d is None:
        return finish()
    d_schedules = (det_d or {}).get("schedules") or []
    sched1 = next((s for s in d_schedules if s.get("sort") == sort_base + 1), None)
    sched2 = next((s for s in d_schedules if s.get("sort") == sort_base + 2), None)
    sched3 = next((s for s in d_schedules if s.get("sort") == sort_base + 3), None)
    ok = det_d and len(d_schedules) == 3 and det_d.get("period") == 30000 \
        and det_d.get("order_no") and det_d.get("customer_name") and det_d.get("house_label")
    step("点火:施工单1:1+停用节点不入快照+工期累加 30000‰天", ok, det_d)
    ok = d_schedules and d_schedules[0].get("plan_start") and d_schedules[0].get("plan_end") \
        and d_schedules[1].get("plan_start") >= d_schedules[0].get("plan_end")
    step("计划时间按工期‰天正向累加(排期快照)", ok, d_schedules)
    det_d2 = api_ok("单D装修单回读", "GET", "/api/decoration/orders/%s" % order_d, tok_admin)
    step("工程线 0→1 开工(ConstructionStarted 事件回程车)", det_d2 and det_d2.get("project_status") == 1,
         det_d2 and det_d2.get("project_status"))
    page_c = api_ok("施工单分页检索", "GET",
                    "/api/construction/orders?orderNo=%s&page=1&size=5" % str(det_d.get("order_no")), tok_admin)
    step("分页命中点火单", bool(page_c) and page_c.get("total", 0) >= 1, page_c)
    api_ok("单D指定监理(example_c)", "PUT", "/api/construction/orders/%s/supervisor" % order_d, tok_admin,
           {"userId": uid_c})

    # 单G 显式补录点火 + 验收比例闸反例
    api_ok("单G显式补录点火(免审后门正名,带审计)", "POST", "/api/construction/drawlines/retroactive",
           tok_admin, {"orderId": order_g, "groupName": "放线C组" + s6,
                       "planStart": day1 + "T08:00:00", "planEnd": day1 + "T12:00:00",
                       "remark": "历史单补录冒烟"})
    det_g = api_ok("单G施工详情(补录回读)", "GET", "/api/construction/orders/%s" % order_g, tok_admin)
    g_dl = (det_g or {}).get("drawlines") or []
    step("补录单带 is_retroactive 审计标记", any(d.get("is_retroactive") == 1 for d in g_dl), g_dl)
    api_err("零收款验收比例不足拒绝 240011", "POST", "/api/construction/orders/%s/acceptance" % order_g,
            tok_admin, {"items": [{"name": "外观", "pass": 1}]}, 240011)

    # 节点推进:守卫族+工费旁路+免检留痕
    api_err("未开工指派工人拒绝 240008", "POST", "/api/construction/schedules/%s/workers" % sched1["id"],
            tok_admin, {"userId": uid_b}, 240008)
    api_ok("节点1开工(无闸)", "POST", "/api/construction/schedules/%s/begin" % sched1["id"], tok_admin)
    api_err("重复开工拒绝 240005", "POST", "/api/construction/schedules/%s/begin" % sched1["id"],
            tok_admin, None, 240005)
    wid_b = api_ok("指派工人 example_b(节点1)", "POST", "/api/construction/schedules/%s/workers" % sched1["id"],
                   tok_admin, {"userId": uid_b})
    api_err("同节点重复指派拒绝 240009", "POST", "/api/construction/schedules/%s/workers" % sched1["id"],
            tok_admin, {"userId": uid_b}, 240009)
    api_err("幽灵工人拒绝 240001", "POST", "/api/construction/schedules/%s/workers" % sched1["id"],
            tok_admin, {"userId": 99999999}, 240001)
    api_ok("工人进场", "POST", "/api/construction/workers/%s/enter" % wid_b, tok_admin)
    api_err("重复进场拒绝 240010", "POST", "/api/construction/workers/%s/enter" % wid_b, tok_admin, None, 240010)
    api_ok("工人出场", "POST", "/api/construction/workers/%s/leave" % wid_b, tok_admin)
    api_err("未进场先出场拒绝 240010", "POST", "/api/construction/workers/%s/leave" % wid_b, tok_admin, None, 240010)
    recs = api_ok("进出场流水回读", "GET", "/api/construction/workers/%s/records" % wid_b, tok_admin)
    step("流水 append-only 正序(进场→出场)", bool(recs) and len(recs) == 2
         and recs[0].get("action") == 1 and recs[1].get("action") == 2, recs)
    api_ok("日志1(自动判进场)", "POST", "/api/construction/schedules/%s/diaries" % sched1["id"], tok_admin,
           {"content": "进场交底,拆改开工"})
    api_ok("日志2(自动判出场)", "POST", "/api/construction/schedules/%s/diaries" % sched1["id"], tok_admin,
           {"content": "拆改完工退场", "hasDrawing": True})
    api_ok("日志3(普通日志)", "POST", "/api/construction/schedules/%s/diaries" % sched1["id"], tok_admin,
           {"content": "现场巡查"})
    diaries1 = api_ok("日志回读(自动进出场判定)", "GET", "/api/construction/schedules/%s/diaries" % sched1["id"],
                      tok_admin)
    step("首笔进场/次笔出场/之后普通(灭静默return)",
         bool(diaries1) and len(diaries1) == 3 and diaries1[0].get("atttype") == 0
         and diaries1[1].get("atttype") == 2 and diaries1[2].get("atttype") == 1, diaries1)
    api_ok("节点1完工(无闸)", "POST", "/api/construction/schedules/%s/finish" % sched1["id"], tok_admin)
    api_err("重复完工拒绝 240005", "POST", "/api/construction/schedules/%s/finish" % sched1["id"],
            tok_admin, None, 240005)
    api_err("节点2开工比例闸拒绝 240006(333‰<500‰)", "POST", "/api/construction/schedules/%s/begin" % sched2["id"],
            tok_admin, None, 240006)
    api_err("免检空理由拒绝 240014", "POST", "/api/construction/schedules/%s/checkout" % sched2["id"],
            tok_admin, {"reason": ""}, 240014)
    api_ok("节点2免检放行(理由留痕)", "POST", "/api/construction/schedules/%s/checkout" % sched2["id"],
           tok_admin, {"reason": "冒烟:业主书面催办"})
    api_ok("节点2开工(免检跳过比例闸)", "POST", "/api/construction/schedules/%s/begin" % sched2["id"], tok_admin)
    wid_c = api_ok("指派工人 example_c(节点2)", "POST", "/api/construction/schedules/%s/workers" % sched2["id"],
                   tok_admin, {"userId": uid_c})
    api_ok("发送评分链接(0→2)", "POST", "/api/construction/workers/%s/rate-link" % wid_c, tok_admin)
    api_ok("客户评分三率(2→3)", "POST", "/api/construction/workers/%s/rate" % wid_c, tok_admin,
           {"score1": 5, "score2": 4, "score3": 3, "assessment": "师傅很nice"})
    wid_b2 = api_ok("指派工人 example_b(节点2)", "POST", "/api/construction/schedules/%s/workers" % sched2["id"],
                    tok_admin, {"userId": uid_b})
    api_ok("停用工人(0→1)", "POST", "/api/construction/workers/%s/disable" % wid_b2, tok_admin)
    api_err("重复停用拒绝 240005", "POST", "/api/construction/workers/%s/disable" % wid_b2, tok_admin, None, 240005)
    api_ok("节点2开放工费(1→3旁路)", "POST", "/api/construction/schedules/%s/open-salary" % sched2["id"],
           tok_admin)
    api_err("旁路后重复开放拒绝 240005", "POST", "/api/construction/schedules/%s/open-salary" % sched2["id"],
            tok_admin, None, 240005)
    api_ok("节点2完工(3→2)", "POST", "/api/construction/schedules/%s/finish" % sched2["id"], tok_admin)
    api_err("已开工节点删除拒绝 240013", "DELETE", "/api/construction/schedules/%s" % sched1["id"],
            tok_admin, None, 240013)
    bills_d2 = api_ok("单D款项单复查", "GET",
                      "/api/payment/bills?bizAddon=decoration&bizType=order&bizId=%s" % order_d, tok_admin)
    tmpl_d = [b for b in (bills_d2 or {}).get("rows", [])
              if b.get("type_code") == tmpl_code and b.get("direction") == 0]
    if not tmpl_d:
        step("单D合同款线在册", False, bills_d2)
        return finish()
    api_ok("合同款线收款(比例升至1000‰)", "POST",
           "/api/payment/bills/%s/collect" % tmpl_d[0]["id"], tok_admin,
           {"channels": [{"channelCode": "bank", "amount": 60000000}]})
    api_ok("节点3开工(比例闸500‰已达标)", "POST", "/api/construction/schedules/%s/begin" % sched3["id"],
           tok_admin)
    api_ok("节点3完工(完工闸500‰已达标)", "POST", "/api/construction/schedules/%s/finish" % sched3["id"],
           tok_admin)
    det_d3 = api_ok("全部节点完工后回读", "GET", "/api/construction/orders/%s" % order_d, tok_admin)
    step("竣工不自动触发(process 仍 0,唯一入口=验收)+进度快照前推",
         det_d3 and det_d3.get("process") == 0 and det_d3.get("current_sort") == sort_base + 3,
         (det_d3 or {}).get("process"))
    api_ok("排期锚点重排(锚=节点1)", "POST", "/api/construction/orders/%s/schedules/reset" % order_d,
           tok_admin, {"anchorScheduleId": sched1["id"]})

    # 验收:整改分支+竣工唯一
    api_ok("提交验收(含不合格项→整改)", "POST", "/api/construction/orders/%s/acceptance" % order_d,
           tok_admin, {"items": [{"name": "防水", "pass": 0, "remark": "卫生间渗漏"},
                                 {"name": "瓦面", "pass": 1}], "remark": "整改后复验"})
    acc1 = api_ok("验收单回读(整改态)", "GET", "/api/construction/orders/%s/acceptance" % order_d, tok_admin)
    step("整改态 status=0 且 process 未变", acc1 and acc1.get("status") == 0, acc1)
    api_ok("整改复验全合格→竣工", "POST", "/api/construction/orders/%s/acceptance" % order_d,
           tok_admin, {"items": [{"name": "防水", "pass": 1, "remark": "已修复"},
                                 {"name": "瓦面", "pass": 1}], "remark": "复验通过"})
    api_err("竣工后重复验收拒绝 240012", "POST", "/api/construction/orders/%s/acceptance" % order_d,
            tok_admin, {"items": [{"name": "再验", "pass": 1}]}, 240012)
    acc2 = api_ok("竣工后验收单回读", "GET", "/api/construction/orders/%s/acceptance" % order_d, tok_admin)
    det_d4 = api_ok("竣工后施工单回读", "GET", "/api/construction/orders/%s" % order_d, tok_admin)
    step("process 0→1 竣工+完工时间落", det_d4 and det_d4.get("process") == 1
         and acc2 and acc2.get("status") == 1 and bool(acc2.get("check_time")), (det_d4 or {}).get("process"))
    time.sleep(3)
    det_d5 = api_ok("单D装修单终态回读", "GET", "/api/decoration/orders/%s" % order_d, tok_admin)
    step("工程线 1→2 完工(ConstructionAccepted 事件回程车)", det_d5 and det_d5.get("project_status") == 2,
         det_d5 and det_d5.get("project_status"))

    # 事件与通知对账
    ev_c = api_ok("施工事件消费查询", "GET",
                  "/api/events?eventType=ConstructionAcceptedEvent&status=1&page=1&size=5", tok_admin)
    step("ConstructionAccepted 消费行存在", bool(ev_c) and ev_c.get("total", 0) >= 1, ev_c)
    notifs_c = api_ok("监理通知列表", "GET", "/api/notifications?page=1&size=50", tok_c)
    hit_node = [n for n in (notifs_c or []) if "施工节点" in str(n.get("title", ""))]
    step("监理收节点开完工通知", len(hit_node) >= 1, len(hit_node or []))
    notifs_b2 = api_ok("设计师通知复查", "GET", "/api/notifications?page=1&size=50", tok_b)
    hit_fail = [n for n in (notifs_b2 or []) if "放线失败" in str(n.get("title", ""))]
    hit_acc = [n for n in (notifs_b2 or []) if "竣工" in str(n.get("title", ""))]
    step("设计师收放线失败+竣工通知", len(hit_fail) >= 1 and len(hit_acc) >= 1,
         (len(hit_fail), len(hit_acc)))
    conn = db_conn()
    try:
        with conn.cursor() as cur:
            cur.execute("""
                select count(*) from yz_event_task
                where event_type like 'Construction%%' and status != 1
            """)
            c_undelivered = cur.fetchone()[0]
            cur.execute("""
                select count(*) from construction_order_track
                where order_id = %s and action = 'checkout'
            """, (order_d,))
            checkout_tracks = cur.fetchone()[0]
    finally:
        conn.close()
    step("总线对账:construction 事件零滞留(全消费确认)", c_undelivered == 0, c_undelivered)
    step("免检留痕:轨迹在册(修老完全绕闸无痕)", checkout_tracks >= 1, checkout_tracks)

    print("== 库存(addon-inventory V1:单据即库存+原子守卫+领料双闸契约+外辅+采购)==")
    stamp_inv = time.strftime("%H%M%S")

    # 插件下发:admin 见五菜单;user_b(未授权)不可见
    addons_inv = api_ok("admin 插件下发复查", "GET", "/api/addons", tok_admin)
    inv_addon = next((a for a in (addons_inv or []) if a.get("id") == "inventory"), None)
    step("inventory 五菜单动态下发", bool(inv_addon)
         and sorted(m.get("path") for m in inv_addon.get("menus") or []) == [
             "/inventory/manifests", "/inventory/outsourcings", "/inventory/purchases",
             "/inventory/requisitions", "/inventory/stocks"],
         inv_addon and inv_addon.get("menus"))
    addons_inv_b = api_ok("未授权用户插件下发", "GET", "/api/addons", tok_d)
    step("未授权用户不可见 inventory", not any(a.get("id") == "inventory" for a in (addons_inv_b or [])),
         addons_inv_b)

    # 仓库档案:查重/停用
    wh_main = api_ok("建主仓", "POST", "/api/inventory/warehouses", tok_admin,
                     {"code": "M" + stamp_inv, "name": "烟测主仓" + stamp_inv})
    wh_second = api_ok("建次仓", "POST", "/api/inventory/warehouses", tok_admin,
                       {"code": "S" + stamp_inv, "name": "烟测次仓" + stamp_inv})
    api_err("仓库编号重复拒绝 310011", "POST", "/api/inventory/warehouses", tok_admin,
            {"code": "M" + stamp_inv, "name": "重复仓"}, 310011)
    api_ok("停用次仓", "PUT", "/api/inventory/warehouses/%s" % wh_second, tok_admin, {"status": 1})
    api_err("停用仓过账拒绝 310010", "POST", "/api/inventory/manifests", tok_admin,
            {"type": 1, "items": [{"goodsId": goods_id, "num": 1000, "warehouseIn": wh_second}]}, 310010)
    api_ok("恢复次仓", "PUT", "/api/inventory/warehouses/%s" % wh_second, tok_admin, {"status": 0})
    api_ok("主仓授权给 admin", "POST", "/api/inventory/warehouses/%s/users" % wh_main, tok_admin,
           {"userId": uid_admin})
    api_ok("次仓授权给 admin", "POST", "/api/inventory/warehouses/%s/users" % wh_second, tok_admin,
           {"userId": uid_admin})

    # 库存商品(独立建档,避免与 basedata 段耦合)
    goods_inv1 = api_ok("建库存商品1(水泥类,售价50元)", "POST", "/api/basedata/goods", tok_admin,
                        {"name": "烟测水泥" + stamp_inv, "categoryId": cat_child, "brandId": brand_id,
                         "unit": "包", "purchase": 40000, "selling": 50000})
    goods_inv2 = api_ok("建库存商品2(乳胶漆,售价88元)", "POST", "/api/basedata/goods", tok_admin,
                        {"name": "烟测乳胶漆" + stamp_inv, "categoryId": cat_child, "brandId": brand_id,
                         "unit": "桶", "purchase": 70000, "selling": 88000})
    if not all([wh_main, wh_second, goods_inv1, goods_inv2]):
        return finish()

    # 进货守卫族(修老恒假守卫 size()<0 与负数击穿)
    api_err("空明细进货拒绝 310030", "POST", "/api/inventory/manifests", tok_admin,
            {"type": 1, "items": []}, 310030)
    api_err("负数数量拒绝 310031", "POST", "/api/inventory/manifests", tok_admin,
            {"type": 1, "items": [{"goodsId": goods_inv1, "num": -1000, "warehouseIn": wh_main}]}, 310031)
    api_err("商品不存在拒绝 310020", "POST", "/api/inventory/manifests", tok_admin,
            {"type": 1, "items": [{"goodsId": 99999999, "num": 1000, "warehouseIn": wh_main}]}, 310020)
    cg_id = api_ok("进货单(水泥50包x40元+漆20桶x70元,建单即过账)", "POST", "/api/inventory/manifests",
                   tok_admin, {"type": 1, "items": [
                       {"goodsId": goods_inv1, "num": 50000, "price": 40000, "warehouseIn": wh_main},
                       {"goodsId": goods_inv2, "num": 20000, "price": 70000, "warehouseIn": wh_main}],
                       "remark": "期初进货"})
    if cg_id is None:
        return finish()
    cg_det = api_ok("进货单详情", "GET", "/api/inventory/manifests/%s" % cg_id, tok_admin)
    step("单号CG前缀+初始态+两行明细", bool(cg_det) and str(cg_det.get("code", "")).startswith("CG")
         and cg_det.get("status") == 0 and len(cg_det.get("items") or []) == 2, cg_det)
    stocks0 = api_ok("库存查询(授权仓过滤)", "GET", "/api/inventory/stocks?page=1&size=50", tok_admin)
    inv1_rows = [x for x in (stocks0 or {}).get("rows", []) if x.get("goods_id") == goods_inv1]
    step("水泥库存行 50.000(主仓)", len(inv1_rows) == 1 and inv1_rows[0].get("warehouse_id") == wh_main
         and inv1_rows[0].get("num") == 50000, inv1_rows)
    stocks_wh = api_ok("按次仓过滤查询", "GET",
                       "/api/inventory/stocks?page=1&size=50&warehouseId=%s" % wh_second, tok_admin)
    step("次仓无库存(过滤生效)", (stocks_wh or {}).get("total", -1) == 0, stocks_wh)

    api_ok("进货确认(账面锁定)", "POST", "/api/inventory/manifests/%s/confirm" % cg_id, tok_admin)
    cg_first_item = ((cg_det.get("items") or [{}])[0]).get("id")
    api_err("已确认删行拒绝 310002", "DELETE",
            "/api/inventory/manifests/%s/items/%s" % (cg_id, cg_first_item), tok_admin, None, 310002)

    # 出货守卫族 + 并发超扣(唯一真守卫)
    xg1_id = api_ok("出货单(水泥20包)", "POST", "/api/inventory/manifests", tok_admin,
                    {"type": 3, "items": [{"goodsId": goods_inv1, "num": 20000, "warehouseOut": wh_main}]})
    if xg1_id is None:
        return finish()
    stocks1 = api_ok("出库后库存复查", "GET", "/api/inventory/stocks?page=1&size=50", tok_admin)
    inv1_after = [x for x in (stocks1 or {}).get("rows", []) if x.get("goods_id") == goods_inv1]
    step("水泥库存 30.000(原子扣减)", inv1_after and inv1_after[0].get("num") == 30000, inv1_after)
    api_err("超扣拒绝 310022", "POST", "/api/inventory/manifests", tok_admin,
            {"type": 3, "items": [{"goodsId": goods_inv1, "num": 31000, "warehouseOut": wh_main}]}, 310022)

    xg_results = []

    def _concurrent_xg():
        status_c, res_c = call("POST", "/api/inventory/manifests", tok_admin,
                               {"type": 3, "items": [{"goodsId": goods_inv1, "num": 20000,
                                                      "warehouseOut": wh_main}]})
        xg_results.append((status_c, res_c))

    threads_inv = [threading.Thread(target=_concurrent_xg), threading.Thread(target=_concurrent_xg)]
    for t in threads_inv:
        t.start()
    for t in threads_inv:
        t.join()
    ok_win = sum(1 for st, rs in xg_results if st == 200 and isinstance(rs, dict) and rs.get("code") == 0)
    ok_lose = sum(1 for st, rs in xg_results if isinstance(rs, dict) and rs.get("code") == 310022)
    step("两并发出货20包只成功一次(30.000 库存不超扣)", ok_win == 1 and ok_lose == 1, xg_results)
    xg2_id = next((rs.get("data") for st, rs in xg_results
                   if st == 200 and isinstance(rs, dict) and rs.get("code") == 0), None)
    if xg2_id is None:
        return finish()
    api_ok("并发获胜单确认(冲销前置:账面锁定)", "POST",
           "/api/inventory/manifests/%s/confirm" % xg2_id, tok_admin)
    stocks2 = api_ok("并发后库存复查", "GET", "/api/inventory/stocks?page=1&size=50", tok_admin)
    inv1_c = [x for x in (stocks2 or {}).get("rows", []) if x.get("goods_id") == goods_inv1]
    step("水泥库存 10.000", inv1_c and inv1_c[0].get("num") == 10000, inv1_c)

    # 出返(XB):源单 surplus 原子守卫
    xg2_det = api_ok("并发获胜出货单详情", "GET", "/api/inventory/manifests/%s" % xg2_id, tok_admin)
    xb_src_item = ((xg2_det or {}).get("items") or [{}])[0]
    api_err("错型源单拒绝 310003", "POST", "/api/inventory/manifests/reversals", tok_admin,
            {"type": 4, "sourceManifestId": cg_id,
             "lines": [{"sourceItemId": xb_src_item.get("id"), "num": 1000}]}, 310003)
    xb1 = api_ok("出返5包(退回原出库仓)", "POST", "/api/inventory/manifests/reversals", tok_admin,
                 {"type": 4, "sourceManifestId": xg2_id,
                  "lines": [{"sourceItemId": xb_src_item.get("id"), "num": 5000}]})
    if xb1 is None:
        return finish()
    xb_det = api_ok("出返单详情(落单即确认)", "GET", "/api/inventory/manifests/%s" % xb1, tok_admin)
    step("XB已确认+入库仓=原出库仓", bool(xb_det) and xb_det.get("status") == 1
         and ((xb_det.get("items") or [{}])[0]).get("warehouse_in") == wh_main, xb_det)
    src_item_after = ((api_ok("源单复查surplus", "GET", "/api/inventory/manifests/%s" % xg2_id,
                              tok_admin) or {}).get("items") or [{}])[0]
    step("源明细剩余可冲 15.000", src_item_after.get("surplus") == 15000, src_item_after)
    api_err("超冲拒绝 310033", "POST", "/api/inventory/manifests/reversals", tok_admin,
            {"type": 4, "sourceManifestId": xg2_id,
             "lines": [{"sourceItemId": xb_src_item.get("id"), "num": 16000}]}, 310033)
    api_ok("出返余量15包全部退回", "POST", "/api/inventory/manifests/reversals", tok_admin,
           {"type": 4, "sourceManifestId": xg2_id,
            "lines": [{"sourceItemId": xb_src_item.get("id"), "num": 15000}]})
    api_err("surplus 归零后再冲拒绝 310033", "POST", "/api/inventory/manifests/reversals", tok_admin,
            {"type": 4, "sourceManifestId": xg2_id,
             "lines": [{"sourceItemId": xb_src_item.get("id"), "num": 1000}]}, 310033)
    stocks3 = api_ok("出返后库存复查", "GET", "/api/inventory/stocks?page=1&size=50", tok_admin)
    inv1_xb = [x for x in (stocks3 or {}).get("rows", []) if x.get("goods_id") == goods_inv1]
    step("水泥库存回到 30.000(10+20退回)", inv1_xb and inv1_xb[0].get("num") == 30000, inv1_xb)

    # 进退(CR)
    cg_items = (api_ok("源进货单复查", "GET", "/api/inventory/manifests/%s" % cg_id,
                       tok_admin) or {}).get("items") or []
    cg_item1 = next((i for i in cg_items if i.get("goods_id") == goods_inv1), None)
    if cg_item1 is None:
        step("CG明细可反查", False, cg_items)
        return finish()
    api_ok("进退10包(退出原入库仓,单价随源单)", "POST", "/api/inventory/manifests/reversals",
           tok_admin, {"type": 2, "sourceManifestId": cg_id,
                       "lines": [{"sourceItemId": cg_item1.get("id"), "num": 10000}]})
    api_err("再退超量拒绝 310033", "POST", "/api/inventory/manifests/reversals", tok_admin,
            {"type": 2, "sourceManifestId": cg_id,
             "lines": [{"sourceItemId": cg_item1.get("id"), "num": 41000}]}, 310033)

    # 调拨(内核轻量审批:审批期不占库存,通过过账/驳回置3)
    api_err("同仓调拨拒绝 310034", "POST", "/api/inventory/manifests/transfers", tok_admin,
            {"lines": [{"goodsId": goods_inv2, "num": 1000, "warehouseOut": wh_main,
                        "warehouseIn": wh_main}], "assigneeId": uid_admin}, 310034)
    db1 = api_ok("调拨申请(漆5桶 主仓到次仓,审批人admin)", "POST", "/api/inventory/manifests/transfers",
                 tok_admin, {"lines": [{"goodsId": goods_inv2, "num": 5000, "warehouseOut": wh_main,
                                        "warehouseIn": wh_second}], "assigneeId": uid_admin,
                             "remark": "门店补货"})
    if db1 is None:
        return finish()
    db_det = api_ok("调拨单详情(审批中)", "GET", "/api/inventory/manifests/%s" % db1, tok_admin)
    step("DB状态2审批中+挂审批单", db_det and db_det.get("status") == 2
         and bool(db_det.get("approval_id")), db_det)
    stocks_mid = api_ok("审批期库存不变", "GET", "/api/inventory/stocks?page=1&size=50", tok_admin)
    inv2_mid = [x for x in (stocks_mid or {}).get("rows", []) if x.get("goods_id") == goods_inv2]
    step("漆主仓仍 20.000(审批期不占库存)", inv2_mid and inv2_mid[0].get("num") == 20000, inv2_mid)
    api_ok("调拨审批通过", "POST", "/api/approvals/%s/approve" % db_det.get("approval_id"), tok_admin,
           {"comment": "同意调拨"})
    time.sleep(3)
    db_det2 = api_ok("调拨单回读", "GET", "/api/inventory/manifests/%s" % db1, tok_admin)
    step("DB已确认(过账)", db_det2 and db_det2.get("status") == 1, db_det2)
    stocks_db = api_ok("调拨后库存复查", "GET", "/api/inventory/stocks?page=1&size=50", tok_admin)
    inv2_db = [x for x in (stocks_db or {}).get("rows", []) if x.get("goods_id") == goods_inv2]
    ok_move = sorted([(r.get("warehouse_id"), r.get("num")) for r in inv2_db]) == \
        sorted([(wh_main, 15000), (wh_second, 5000)])
    step("漆 主仓15.000/次仓5.000(双向过账)", ok_move, inv2_db)
    # 第 39 次运行:调拨审批办结事件走总线(消费器双向过账)
    event_consumed("ApprovalFinishedEvent", "approval-finished-" + str(db_det.get("approval_id")),
                   "调拨审批办结事件走总线(消费确认)")

    db2 = api_ok("第二笔调拨申请(漆3桶)", "POST", "/api/inventory/manifests/transfers", tok_admin,
                 {"lines": [{"goodsId": goods_inv2, "num": 3000, "warehouseOut": wh_main,
                             "warehouseIn": wh_second}], "assigneeId": uid_admin})
    db2_det = api_ok("第二笔详情", "GET", "/api/inventory/manifests/%s" % db2, tok_admin)
    api_ok("调拨审批驳回", "POST", "/api/approvals/%s/reject" % db2_det.get("approval_id"), tok_admin,
           {"comment": "暂不调"})
    time.sleep(3)
    db2_det2 = api_ok("被驳调拨单回读", "GET", "/api/inventory/manifests/%s" % db2, tok_admin)
    step("DB驳回置3+库存零改动", db2_det2 and db2_det2.get("status") == 3, db2_det2)

    # 盘盈/盘亏(事件闭环)
    api_ok("盘盈漆2桶(次仓)", "POST", "/api/inventory/manifests/adjustments", tok_admin,
           {"type": 11, "items": [{"goodsId": goods_inv2, "warehouseId": wh_second, "num": 2000}]})
    api_err("盘亏超量拒绝 310022", "POST", "/api/inventory/manifests/adjustments", tok_admin,
            {"type": 12, "items": [{"goodsId": goods_inv2, "warehouseId": wh_second, "num": 99999}]}, 310022)
    api_ok("盘亏漆1桶", "POST", "/api/inventory/manifests/adjustments", tok_admin,
           {"type": 12, "items": [{"goodsId": goods_inv2, "warehouseId": wh_second, "num": 1000}]})
    stocks_cp = api_ok("盘盈亏后库存复查", "GET", "/api/inventory/stocks?page=1&size=50", tok_admin)
    inv2_cp = [x for x in (stocks_cp or {}).get("rows", [])
               if x.get("goods_id") == goods_inv2 and x.get("warehouse_id") == wh_second]
    step("次仓漆 6.000(5+2-1)", inv2_cp and inv2_cp[0].get("num") == 6000, inv2_cp)

    # 领料(工程额度双闸经 ConstructionAccessoryApi 契约;靶单 order_g 已点火未竣工)
    api_ok("order_g 辅料总额度压至100元", "PUT",
           "/api/construction/orders/%s/accessory-limit" % order_g, tok_admin,
           {"accessoryLimit": 100000})
    api_err("额度总额超限拒绝 240020", "POST", "/api/inventory/requisitions", tok_admin,
            {"orderId": order_g, "type": 0,
             "items": [{"goodsId": goods_inv1, "warehouseId": wh_main, "num": 10000}]}, 240020)
    api_ok("order_g 登记水泥 per-gid 闸5包", "POST",
           "/api/construction/orders/%s/accessory-quotas" % order_g, tok_admin,
           {"goodsId": goods_inv1, "checkup": 1, "numLimit": 5000})
    api_ok("order_g 额度恢复无上限", "PUT", "/api/construction/orders/%s/accessory-limit" % order_g,
           tok_admin, {"accessoryLimit": 999999000})
    api_err("per-gid 登记超限拒绝 240021", "POST", "/api/inventory/requisitions", tok_admin,
            {"orderId": order_g, "type": 0,
             "items": [{"goodsId": goods_inv1, "warehouseId": wh_main, "num": 10000}]}, 240021)
    rq1 = api_ok("领料登记(水泥3包,150000厘,服务端计价)", "POST", "/api/inventory/requisitions", tok_admin,
                 {"orderId": order_g, "type": 0,
                  "items": [{"goodsId": goods_inv1, "warehouseId": wh_main, "num": 3000}],
                  "comment": "瓦工领料"})
    if rq1 is None:
        return finish()
    rq1_det = api_ok("领料单详情", "GET", "/api/inventory/requisitions/%s" % rq1, tok_admin)
    step("RQ单号+总额150000厘(服务端Σselling×num)", bool(rq1_det)
         and str(rq1_det.get("code", "")).startswith("RQ") and rq1_det.get("total") == 150000, rq1_det)
    acc1 = api_ok("施工侧额度台账回读", "GET", "/api/construction/orders/%s/accessory" % order_g, tok_admin)
    step("已领 150000厘/3包(consume 记账)",
         acc1 and acc1.get("accessoryConsumedTotal") == 150000
         and any(q.get("consumed_num") == 3000 for q in (acc1.get("quotas") or [])), acc1)
    api_ok("领料撤销(仅初始态,额度回退)", "POST", "/api/inventory/requisitions/%s/cancel" % rq1, tok_admin)
    acc2 = api_ok("撤销后额度归零(refund 回退)", "GET",
                  "/api/construction/orders/%s/accessory" % order_g, tok_admin)
    step("consumed_total 归 0", acc2 and acc2.get("accessoryConsumedTotal") == 0, acc2)
    api_err("撤销单再出库拒绝 310002", "POST", "/api/inventory/requisitions/%s/outbound" % rq1,
            tok_admin, None, 310002)

    rq2 = api_ok("再领水泥3包", "POST", "/api/inventory/requisitions", tok_admin,
                 {"orderId": order_g, "type": 0,
                  "items": [{"goodsId": goods_inv1, "warehouseId": wh_main, "num": 3000}]})
    api_ok("预出", "POST", "/api/inventory/requisitions/%s/preout" % rq2, tok_admin)
    api_ok("出库(AS货单派生+库存扣减)", "POST", "/api/inventory/requisitions/%s/outbound" % rq2, tok_admin)
    rq2_det = api_ok("出库后详情", "GET", "/api/inventory/requisitions/%s" % rq2, tok_admin)
    step("状态2已出库+联动AS货单在册", rq2_det and rq2_det.get("status") == 2
         and any(m.get("type") == 7 for m in (rq2_det.get("manifests") or [])), rq2_det)
    api_ok("到货", "POST", "/api/inventory/requisitions/%s/arrive" % rq2, tok_admin)
    rq2_item_id = ((rq2_det.get("items") or [{}])[0]).get("id")
    api_err("退回超出库量拒绝 310043", "POST", "/api/inventory/requisitions/%s/returns" % rq2, tok_admin,
            {"lines": [{"itemId": rq2_item_id, "num": 4000}]}, 310043)
    api_ok("退回2包(AR货单+额度回冲)", "POST", "/api/inventory/requisitions/%s/returns" % rq2, tok_admin,
           {"lines": [{"itemId": rq2_item_id, "num": 2000}]})
    acc3 = api_ok("退回后额度台账", "GET", "/api/construction/orders/%s/accessory" % order_g, tok_admin)
    step("已领回落 1包/50000厘", acc3 and acc3.get("accessoryConsumedTotal") == 50000
         and any(q.get("consumed_num") == 1000 for q in (acc3.get("quotas") or [])), acc3)
    rq3 = api_ok("售后领料5包(跳过额度联动)", "POST", "/api/inventory/requisitions", tok_admin,
                 {"orderId": order_g, "type": 1,
                  "items": [{"goodsId": goods_inv1, "warehouseId": wh_main, "num": 5000}]})
    acc4 = api_ok("售后单不占额度", "GET", "/api/construction/orders/%s/accessory" % order_g, tok_admin)
    step("consumed_total 仍 50000", acc4 and acc4.get("accessoryConsumedTotal") == 50000, acc4)
    api_ok("售后单直接出库", "POST", "/api/inventory/requisitions/%s/outbound" % rq3, tok_admin)
    stocks_rq = api_ok("领料链库存复查", "GET", "/api/inventory/stocks?page=1&size=50", tok_admin)
    inv1_rq = [x for x in (stocks_rq or {}).get("rows", []) if x.get("goods_id") == goods_inv1]
    # 30(出返后) -10(CR进退) -3(rq2出库) +2(rq2退回) -5(售后出库) = 14
    step("水泥库存 14.000(进退/出库/退回/售后全链过账)", inv1_rq and inv1_rq[0].get("num") == 14000, inv1_rq)

    # 外辅(向厂商定材辅料;order_d 已竣工=锁定反例,order_g 施工中可登记)
    # V1202 厂商引用化:assign 必传 supplierId 经 SupplierApi 校验,外辅段自建厂商+停用厂商各一
    sup_os1 = api_ok("外辅建引用厂商", "POST", "/api/supplier/suppliers", tok_admin,
                     {"name": "鑫旺定制" + stamp_inv, "fullName": "鑫旺定制家居厂" + stamp_inv})
    sup_os2 = api_ok("外辅建停用厂商", "POST", "/api/supplier/suppliers", tok_admin,
                     {"name": "停用厂商" + stamp_inv})
    api_ok("停用第二厂商", "POST", "/api/supplier/suppliers/%s/status" % sup_os2, tok_admin,
           {"status": 1})
    api_err("工程已验收登记拒绝 310051", "POST", "/api/inventory/outsourcings", tok_admin,
            {"orderId": order_d, "items": [{"goodsId": goods_inv1, "num": 1000}]}, 310051)
    os1 = api_ok("外辅登记(水泥4包,原结算价60元,超阶)", "POST", "/api/inventory/outsourcings", tok_admin,
                 {"orderId": order_g, "items": [{"goodsId": goods_inv1, "num": 4000, "rbalance": 60000}],
                  "isExceed": 1, "loadFloor": -1, "remark": "封闭阳台定材"})
    if os1 is None:
        return finish()
    os_det = api_ok("外辅单详情", "GET", "/api/inventory/outsourcings/%s" % os1, tok_admin)
    os_item_id = ((os_det.get("items") or [{}])[0]).get("id")
    ok_os = os_det and str(os_det.get("code", "")).startswith("OS") and os_det.get("status") == 0 \
        and os_det.get("total") == 200000 and os_det.get("rbalance") == 240000
    step("OS单号+商品总额20万厘(实价50x40)+结算额24万厘(60x40)", ok_os, os_det)
    api_err("未分配不可送货 310002", "POST", "/api/inventory/outsourcings/%s/deliver" % os1, tok_admin,
            {"lines": [{"itemId": os_item_id, "num": 1000}]}, 310002)
    # V1202 分配厂商三态:缺参 310003 / 不存在 310058 / 停用 310059(经 SupplierApi 契约)
    api_err("分配厂商缺 supplierId 310003", "POST", "/api/inventory/outsourcings/%s/assign" % os1,
            tok_admin, {"supplierPhone": "13900001111"}, 310003)
    api_err("分配不存在厂商 310058", "POST", "/api/inventory/outsourcings/%s/assign" % os1,
            tok_admin, {"supplierId": 999999999}, 310058)
    api_err("分配停用厂商 310059", "POST", "/api/inventory/outsourcings/%s/assign" % os1,
            tok_admin, {"supplierId": sup_os2}, 310059)
    api_ok("分配厂商(引用+官方名快照)", "POST", "/api/inventory/outsourcings/%s/assign" % os1, tok_admin,
           {"supplierId": sup_os1, "supplierPhone": "13900001111"})
    os_det_as = api_ok("分配后回读(快照=官方名+supplier_id 落库)", "GET",
                       "/api/inventory/outsourcings/%s" % os1, tok_admin)
    step("厂商引用化快照", os_det_as and os_det_as.get("supplier_id") == sup_os1
         and os_det_as.get("supplier_name") == "鑫旺定制" + stamp_inv, os_det_as)
    # 跨页选择器读放开(第 6 处):无角色用户 GET 厂商列表放行(authenticated),详情仍拒
    st_list, res_list = call("GET", "/api/supplier/suppliers", token=tok_d)
    step("无角色读厂商列表放行(authenticated)", st_list == 200 and isinstance(res_list, dict)
         and (res_list.get("data") or {}).get("rows") is not None, (st_list, res_list))
    st_deny, res_deny = call("GET", "/api/supplier/suppliers/%s" % sup_os1, token=tok_d)
    step("无角色读厂商详情仍拒 122006", st_deny == 200 and res_deny.get("code") == 122006,
         (st_deny, res_deny))
    api_err("送货超申请拒绝 310052", "POST", "/api/inventory/outsourcings/%s/deliver" % os1, tok_admin,
            {"lines": [{"itemId": os_item_id, "num": 5000}]}, 310052)
    api_ok("送货3包", "POST", "/api/inventory/outsourcings/%s/deliver" % os1, tok_admin,
           {"lines": [{"itemId": os_item_id, "num": 3000}]})
    api_err("到货超送货拒绝 310053", "POST", "/api/inventory/outsourcings/%s/receive" % os1, tok_admin,
            {"lines": [{"itemId": os_item_id, "num": 4000}]}, 310053)
    api_ok("到货3包", "POST", "/api/inventory/outsourcings/%s/receive" % os1, tok_admin,
           {"lines": [{"itemId": os_item_id, "num": 3000}]})
    api_ok("收费明细(车运费100元x2+超阶浮动50元参与计价,服务端公式重算)", "POST",
           "/api/inventory/outsourcings/%s/charges" % os1, tok_admin,
           {"charges": [{"itemId": os_item_id, "name": "车运费",
                         "price": 100000, "surplus": 50000, "num": 2}]})
    os_det2 = api_ok("收费后回读", "GET", "/api/inventory/outsourcings/%s" % os1, tok_admin)
    step("费用300元回写单头(charge=rcharge,超阶公式(100+50)x2)",
         os_det2 and os_det2.get("charge") == 300000 and os_det2.get("rcharge") == 300000
         and any(i.get("charge") == 300000 for i in (os_det2.get("items") or [])), os_det2)
    api_ok("结算", "POST", "/api/inventory/outsourcings/%s/balance" % os1, tok_admin)
    api_err("作废仅到货前 310054(已结算)", "POST", "/api/inventory/outsourcings/%s/cancel" % os1,
            tok_admin, {"remark": "迟了"}, 310054)
    api_ok("反结算(4到3,结算字段复位)", "POST", "/api/inventory/outsourcings/%s/reject-balance" % os1,
           tok_admin)
    os_det3 = api_ok("反结算后回读", "GET", "/api/inventory/outsourcings/%s" % os1, tok_admin)
    step("回到已到货3", os_det3 and os_det3.get("status") == 3, os_det3)
    api_ok("再结算", "POST", "/api/inventory/outsourcings/%s/balance" % os1, tok_admin)
    api_ok("完成(5态,补老字典缺失终态)", "POST", "/api/inventory/outsourcings/%s/finish" % os1, tok_admin)
    os_det4 = api_ok("完成后回读", "GET", "/api/inventory/outsourcings/%s" % os1, tok_admin)
    step("状态5已完成+轨迹在册", os_det4 and os_det4.get("status") == 5
         and len(os_det4.get("tracks") or []) >= 5, os_det4)
    os2 = api_ok("第二张外辅单(漆1桶)", "POST", "/api/inventory/outsourcings", tok_admin,
                 {"orderId": order_g, "items": [{"goodsId": goods_inv2, "num": 1000}]})
    api_ok("初始态作废", "POST", "/api/inventory/outsourcings/%s/cancel" % os2, tok_admin,
           {"remark": "客户取消"})
    api_err("已作废不可操作 310055", "POST", "/api/inventory/outsourcings/%s/assign" % os2, tok_admin,
            {"supplierId": sup_os1}, 310055)

    # 采购(分批入库+取消释放事件化;V1203 厂商引用化,外辅 V1202 同款两态)
    api_err("采购建单缺厂商拒绝 310003", "POST", "/api/inventory/purchases", tok_admin,
            {"items": [{"goodsId": goods_inv1, "num": 1000}]}, 310003)
    api_err("采购厂商不存在拒绝 310065", "POST", "/api/inventory/purchases", tok_admin,
            {"supplierId": 99999999,
             "items": [{"goodsId": goods_inv1, "num": 1000}]}, 310065)
    api_err("采购厂商已停用拒绝 310066", "POST", "/api/inventory/purchases", tok_admin,
            {"supplierId": sup_os2,
             "items": [{"goodsId": goods_inv1, "num": 1000}]}, 310066)
    po1 = api_ok("采购建单(水泥10包x45元,supplierId 引用)", "POST", "/api/inventory/purchases", tok_admin,
                 {"supplierId": sup_os1,
                  "items": [{"goodsId": goods_inv1, "num": 10000, "inprice": 45000}]})
    if po1 is None:
        return finish()
    po_det = api_ok("采购单详情取明细", "GET", "/api/inventory/purchases/%s" % po1, tok_admin)
    step("厂商引用落单:supplier_id+官方名快照(禁前端传名)", (po_det or {}).get("supplier_id") == sup_os1
         and (po_det or {}).get("supplier_name") == "鑫旺定制" + stamp_inv, po_det)
    po_item = ((po_det.get("items") or [{}])[0]).get("id")
    api_err("入库超余量拒绝 310061", "POST", "/api/inventory/purchases/%s/inbound" % po1,
            tok_admin, {"warehouseId": wh_main, "lines": [{"itemId": po_item, "num": 11000}]}, 310061)
    api_ok("分批入库4包(主仓)", "POST", "/api/inventory/purchases/%s/inbound" % po1, tok_admin,
           {"warehouseId": wh_main, "lines": [{"itemId": po_item, "num": 4000}]})
    po_det2 = api_ok("部分入库回读", "GET", "/api/inventory/purchases/%s" % po1, tok_admin)
    step("状态1部分入库+余量6包+PU货单在册", po_det2 and po_det2.get("status") == 1
         and ((po_det2.get("items") or [{}])[0]).get("surplus") == 6000
         and any(m.get("type") == 6 for m in (po_det2.get("manifests") or [])), po_det2)
    api_ok("余量6包入库", "POST", "/api/inventory/purchases/%s/inbound" % po1, tok_admin,
           {"warehouseId": wh_main, "lines": [{"itemId": po_item, "num": 6000}]})
    po_det3 = api_ok("全部入库回读", "GET", "/api/inventory/purchases/%s" % po1, tok_admin)
    step("状态2全部入库(Σsurplus==0 自动判定)", po_det3 and po_det3.get("status") == 2
         and bool(po_det3.get("inbound_at")), po_det3)
    api_err("全部入库再操作拒绝 310062", "POST", "/api/inventory/purchases/%s/inbound" % po1, tok_admin,
            {"warehouseId": wh_main, "lines": [{"itemId": po_item, "num": 1000}]}, 310062)
    stocks_po = api_ok("采购入库库存复查", "GET", "/api/inventory/stocks?page=1&size=50", tok_admin)
    inv1_po = [x for x in (stocks_po or {}).get("rows", []) if x.get("goods_id") == goods_inv1]
    step("水泥库存 24.000(14+10采购入库)", inv1_po and inv1_po[0].get("num") == 24000, inv1_po)

    po2 = api_ok("第二张采购单(漆3桶)", "POST", "/api/inventory/purchases", tok_admin,
                 {"supplierId": sup_os1, "items": [{"goodsId": goods_inv2, "num": 3000}]})
    api_ok("取消采购(余量释放走事件)", "POST", "/api/inventory/purchases/%s/cancel" % po2, tok_admin)
    po2_det = api_ok("取消单复查", "GET", "/api/inventory/purchases/%s" % po2, tok_admin)
    po2_item = ((po2_det.get("items") or [{}])[0]).get("id")
    api_err("已取消不可操作 310063", "POST", "/api/inventory/purchases/%s/inbound" % po2, tok_admin,
            {"warehouseId": wh_main, "lines": [{"itemId": po2_item, "num": 1000}]}, 310063)
    po3 = api_ok("第三张采购单(删项链)", "POST", "/api/inventory/purchases", tok_admin,
                 {"supplierId": sup_os1, "items": [{"goodsId": goods_inv2, "num": 2000}]})
    po3_det = api_ok("第三张详情", "GET", "/api/inventory/purchases/%s" % po3, tok_admin)
    api_ok("初始态删项(释放余量)", "DELETE", "/api/inventory/purchases/%s/items/%s" % (
        po3, ((po3_det.get("items") or [{}])[0]).get("id")), tok_admin)
    ev_pc = api_ok("采购变更事件查询", "GET",
                   "/api/events?eventType=StockPurchaseChangedEvent&status=1&page=1&size=10", tok_admin)
    step("StockPurchaseChanged 消费行存在(cancel+itemRemoved)",
         bool(ev_pc) and ev_pc.get("total", 0) >= 2, ev_pc)

    print("== 主材销售(addon-sales V1:四轴状态机+服务端算价+结算三段式+退货审批链) ==")
    import re as _re
    stamp_sale = time.strftime("%H%M%S")

    # 插件下发 + 协议键类型前置
    addons_sale = api_ok("admin 插件下发复查(sales)", "GET", "/api/addons", tok_admin)
    sale_addon = next((a for a in (addons_sale or []) if a.get("id") == "sales"), None)
    step("sales 三菜单动态下发", bool(sale_addon)
         and sorted(m.get("path") for m in sale_addon.get("menus") or []) == [
             "/sales/deliveries", "/sales/orders", "/sales/settlements"],
         sale_addon and sale_addon.get("menus"))
    all_types_s = api_ok("类型字典全量(sales_full 前置)", "GET",
                         "/api/payment/bill-types?includeDisabled=true", tok_admin)
    if "sales_full" not in [t.get("code") for t in (all_types_s or [])]:
        api_ok("协议键 sales_full 类型登记", "POST", "/api/payment/bill-types", tok_admin,
               {"code": "sales_full", "name": "销售全款", "sort": 3})

    # 前置:品牌2+销售商品(普通×2+套餐)
    brand2 = api_ok("建品牌2(结算分组)", "POST", "/api/basedata/brands", tok_admin,
                    {"name": "烟测品牌B" + stamp_sale, "pinyin": "ycppb" + stamp_sale[-6:]})
    goods_sale1 = api_ok("建销售商品1(品牌1,120元)", "POST", "/api/basedata/goods", tok_admin,
                         {"name": "烟测Sales瓷砖" + stamp_sale, "categoryId": cat_child,
                          "brandId": brand_id, "unit": "片", "selling": 120000})
    goods_sale2 = api_ok("建销售商品2(品牌2,80元)", "POST", "/api/basedata/goods", tok_admin,
                         {"name": "烟测Sales地板" + stamp_sale, "categoryId": cat_child,
                          "brandId": brand2, "unit": "片", "selling": 80000})
    pkg_sale = api_ok("建销售套餐(品牌1,200元)", "POST", "/api/basedata/goods", tok_admin,
                      {"name": "烟测Sales套餐" + stamp_sale, "categoryId": cat_child,
                       "brandId": brand_id, "unit": "套", "type": 1, "selling": 200000})
    api_ok("套餐加成员(瓷砖×2)", "POST", "/api/basedata/goods/%s/packages" % pkg_sale, tok_admin,
           {"memberGoodsId": goods_sale1, "num": 2000})
    if not all([brand2, goods_sale1, goods_sale2, pkg_sale]):
        return finish()

    # 登记销售专用装修单 S
    phone_s = "136%08d" % (int(stamp_sale) % 100000000)
    cust_s = api_ok("销售客 get-or-create", "POST", "/api/customerprofile/customers/exist", tok_admin,
                    {"phone": phone_s, "name": "销售客" + stamp_sale})
    house_s = api_ok("销售房 get-or-create", "POST", "/api/customerprofile/houses/exist", tok_admin,
                     {"villageId": village_id, "number": "7栋1单元%s" % stamp_sale})
    order_s = api_ok("登记装修单S(销售主链)", "POST", "/api/decoration/orders", tok_admin,
                     {"customerId": (cust_s or {}).get("customerId"),
                      "houseId": (house_s or {}).get("houseId"), "type": 0,
                      "designerId": uid_b, "salesmanId": uid_c})
    if not all([cust_s, house_s, order_s]):
        return finish()

    # 开单守卫族
    api_err("装修单不存在拒绝 300004", "POST", "/api/sales/orders", tok_admin,
            {"orderId": 99999999, "items": [{"goodsId": goods_sale1, "num": 1000}]}, 300004)
    api_err("空明细拒绝 300005", "POST", "/api/sales/orders", tok_admin,
            {"orderId": order_s, "items": []}, 300005)
    api_err("商品不存在拒绝 300009", "POST", "/api/sales/orders", tok_admin,
            {"orderId": order_s, "items": [{"goodsId": 99999999, "num": 1000}]}, 300009)
    api_err("数量为负拒绝 300007", "POST", "/api/sales/orders", tok_admin,
            {"orderId": order_s, "items": [{"goodsId": goods_sale1, "num": -1000}]}, 300007)

    # 开单(套餐展开+服务端算价):瓷砖×2@120 + 套餐×1@200(子项瓷砖×4 不计总额)
    sale1 = api_ok("开单(套餐展开)", "POST", "/api/sales/orders", tok_admin,
                   {"orderId": order_s, "items": [
                       {"goodsId": goods_sale1, "num": 2000},
                       {"goodsId": pkg_sale, "num": 1000}], "remark": "销售主链冒烟"})
    if sale1 is None:
        return finish()
    sale_det = api_ok("开单详情", "GET", "/api/sales/orders/%s" % sale1, tok_admin)
    ok = str(sale_det.get("code", "")).startswith("XS") and sale_det.get("total") == 440000
    ok = ok and len(sale_det.get("items") or []) == 3
    ok = ok and sum(1 for i in sale_det.get("items") or [] if i.get("package_id", 0) > 0) == 1
    step("单号XS+套餐展开3行+子项不计总额440元", ok, sale_det)
    ok = (sale_det.get("status") == 0 and sale_det.get("balance_status") == 0
          and sale_det.get("fullpay_status") == -1 and sale_det.get("customer_confirmed") == 0)
    step("四轴初始位(0/0/-1/未确认)", ok, sale_det)

    # 改单(全量替换服务端重算,品牌分组终态:瓷砖×2@120 + 地板×1@80 = 320 元)
    api_ok("改单全量替换", "PUT", "/api/sales/orders/%s/items" % sale1, tok_admin,
           {"items": [{"goodsId": goods_sale1, "num": 2000},
                      {"goodsId": goods_sale2, "num": 1000}]})
    sale_det = api_ok("改单复查", "GET", "/api/sales/orders/%s" % sale1, tok_admin)
    step("服务端重算320元+两品牌两组", sale_det.get("total") == 320000
         and len(sale_det.get("items") or []) == 2, sale_det)
    sale_items = {i["goods_id"]: i["id"] for i in (sale_det.get("items") or [])}
    item1 = sale_items.get(goods_sale1)
    item2 = sale_items.get(goods_sale2)

    # 款项单(协议键 salesFull)
    api_ok("生成全款款项单", "POST", "/api/sales/orders/%s/bill" % sale1, tok_admin)
    sale_det = api_ok("款项单复查", "GET", "/api/sales/orders/%s" % sale1, tok_admin)
    bills1 = [b for b in (sale_det.get("bills") or [])
              if b.get("direction") == 0 and b.get("status") == 0]
    step("全款单320元在册+款项轴0已录", len(bills1) == 1
         and bills1[0].get("amount") == 320000 and sale_det.get("fullpay_status") == 0,
         sale_det.get("bills"))

    # 客户确认三重校验(单参拒/错码拒/正码过/重复拒)
    api_err("缺验证码拒绝 300010", "POST", "/api/sales/orders/%s/confirm" % sale1, tok_admin,
            {"phone": "13800000000"}, 300010)
    api_ok("发确认码", "POST", "/api/sales/orders/%s/verify-code" % sale1, tok_admin,
           {"phone": "13800000000"})
    api_err("错验证码拒绝 300011", "POST", "/api/sales/orders/%s/confirm" % sale1, tok_admin,
            {"phone": "13800000000", "code": "000000"}, 300011)
    notif_s = api_ok("通知取确认码", "GET", "/api/notifications?page=1&size=3", tok_admin)
    code_s = ""
    for n in (notif_s or []):
        m = _re.search(r"(\d{6})\s*$", str(n.get("content", "")))
        if m and "验证码" in str(n.get("title", "")):
            code_s = m.group(1)
            break
    step("验证码已下发通知", bool(code_s), notif_s)
    api_ok("客户确认(三重)", "POST", "/api/sales/orders/%s/confirm" % sale1, tok_admin,
           {"phone": "13800000000", "code": code_s})
    api_err("重复确认拒绝 300012", "POST", "/api/sales/orders/%s/confirm" % sale1, tok_admin,
            {"phone": "13800000000", "code": code_s}, 300012)

    # 收款链(payment 事件回程车)
    api_ok("全款收款(cash 320元)", "POST", "/api/payment/bills/%s/collect" % bills1[0]["billId"],
           tok_admin, {"channels": [{"channelCode": "cash", "amount": 320000}]})
    time.sleep(2)
    sale_det = api_ok("收款后复查", "GET", "/api/sales/orders/%s" % sale1, tok_admin)
    step("款项轴1已付(事件回写)", sale_det.get("fullpay_status") == 1, sale_det)
    api_err("已付后改单拒绝 300006", "PUT", "/api/sales/orders/%s/items" % sale1, tok_admin,
            {"items": [{"goodsId": goods_sale1, "num": 1000}]}, 300006)
    api_ok("财务确认全款", "POST", "/api/payment/bills/%s/confirm" % bills1[0]["billId"], tok_admin)
    time.sleep(2)
    sale_det = api_ok("确认后复查", "GET", "/api/sales/orders/%s" % sale1, tok_admin)
    settles1 = sale_det.get("settlements") or []
    step("款项轴2已确认+预结两品牌行", sale_det.get("fullpay_status") == 2
         and sale_det.get("balance_status") == 1 and len(settles1) == 2, settles1)
    st_by_brand = {s["brand_id"]: s for s in settles1}
    st1 = st_by_brand.get(brand_id)
    st2 = st_by_brand.get(brand2)
    step("预结金额按品牌分组(240/80元)", bool(st1) and st1.get("total") == 240000
         and bool(st2) and st2.get("total") == 80000, settles1)

    # 结算三段式(vbalance 双值+服务端强校验)
    api_err("厂结值为零拒绝 300014", "PUT", "/api/sales/settlements/%s/vbalance" % st1["id"],
            tok_admin, {"value": 0}, 300014)
    api_err("厂结值异向拒绝 300014", "PUT", "/api/sales/settlements/%s/vbalance" % st1["id"],
            tok_admin, {"value": -1000}, 300014)
    api_ok("行1改厂结值(200元)", "PUT", "/api/sales/settlements/%s/vbalance" % st1["id"],
           tok_admin, {"value": 200000})
    api_ok("行1厂结", "POST", "/api/sales/settlements/%s/vpost" % st1["id"], tok_admin)
    api_ok("行2厂结(默认值80元)", "POST", "/api/sales/settlements/%s/vpost" % st2["id"], tok_admin)
    sale_det = api_ok("厂结后复查", "GET", "/api/sales/orders/%s" % sale1, tok_admin)
    step("主单结算轴2厂结", sale_det.get("balance_status") == 2, sale_det)
    api_ok("行2改结算值(75元)", "PUT", "/api/sales/settlements/%s/pbalance" % st2["id"],
           tok_admin, {"value": 75000})
    api_ok("行2结算", "POST", "/api/sales/settlements/%s/post" % st2["id"], tok_admin)
    sale_det = api_ok("行2结算后复查", "GET", "/api/sales/orders/%s" % sale1, tok_admin)
    step("行结结算主单仍2(行1未结)", sale_det.get("balance_status") == 2, sale_det)
    api_ok("行1结算", "POST", "/api/sales/settlements/%s/post" % st1["id"], tok_admin)
    sale_det = api_ok("全部结算复查", "GET", "/api/sales/orders/%s" % sale1, tok_admin)
    step("主单结算轴3结算(终态)", sale_det.get("balance_status") == 3, sale_det)

    # 结算驳回(整单原子,sale2 独立链)
    sale2 = api_ok("开第二单(驳回链)", "POST", "/api/sales/orders", tok_admin,
                   {"orderId": order_s, "items": [{"goodsId": goods_sale2, "num": 1000}]})
    api_ok("sale2生成款项单", "POST", "/api/sales/orders/%s/bill" % sale2, tok_admin)
    b2 = api_ok("sale2款项反查", "GET",
                "/api/payment/bills?bizAddon=sales&bizType=order&bizId=%s" % sale2, tok_admin)
    bill2 = [b for b in (b2 or {}).get("rows", []) if b.get("direction") == 0]
    api_ok("sale2收款", "POST", "/api/payment/bills/%s/collect" % bill2[0]["id"], tok_admin,
           {"channels": [{"channelCode": "cash", "amount": 80000}]})
    time.sleep(2)
    api_ok("sale2确认", "POST", "/api/payment/bills/%s/confirm" % bill2[0]["id"], tok_admin)
    time.sleep(2)
    api_ok("sale2整单驳回预结", "POST", "/api/sales/orders/%s/settlement-reject" % sale2, tok_admin,
           {"reason": "金额有误重开"})
    s2_det = api_ok("驳回后复查", "GET", "/api/sales/orders/%s" % sale2, tok_admin)
    step("结算行清空+主单回无结", not (s2_det.get("settlements") or [])
         and s2_det.get("balance_status") == 0, s2_det)
    api_err("无行再驳回拒绝 300031", "POST", "/api/sales/orders/%s/settlement-reject" % sale2,
            tok_admin, {"reason": "x"}, 300031)

    # 配送(库存基线先行:销售商品进货各10)
    api_ok("销售商品进货(各10入主仓)", "POST", "/api/inventory/manifests", tok_admin,
           {"type": 1, "items": [
               {"goodsId": goods_sale1, "num": 10000, "price": 60000, "warehouseIn": wh_main},
               {"goodsId": goods_sale2, "num": 10000, "price": 40000, "warehouseIn": wh_main}]})
    sale3 = api_ok("开第三单(配送反例)", "POST", "/api/sales/orders", tok_admin,
                   {"orderId": order_s, "items": [{"goodsId": goods_sale1, "num": 1000}]})
    api_err("未付款配送拒绝 300023", "POST", "/api/sales/orders/%s/deliveries" % sale3, tok_admin,
            {"items": [{"saleItemId": 1, "num": 1000}]}, 300023)
    dlv1 = api_ok("生成配送批次(瓷砖1片)", "POST", "/api/sales/orders/%s/deliveries" % sale1,
                  tok_admin, {"items": [{"saleItemId": item1, "num": 1000}]})
    dlv_det = api_ok("配送批次详情", "GET", "/api/sales/deliveries/%s" % dlv1, tok_admin)
    ditem1 = ((dlv_det.get("items") or [{}])[0]).get("id")
    step("配送行初始态在册", bool(ditem1)
         and dlv_det["items"][0].get("status") == 0, dlv_det)
    api_err("配送越级拒绝 300022", "POST", "/api/sales/delivery-items/%s/advance" % ditem1,
            tok_admin, {"action": "check"}, 300022)
    for act, name in [("tran", "配送"), ("check", "审核"), ("confirm", "客户确认"), ("finish", "完结")]:
        api_ok("配送推进:%s" % name, "POST", "/api/sales/delivery-items/%s/advance" % ditem1,
               tok_admin, {"action": act})
    sale_det = api_ok("配送完结复查", "GET", "/api/sales/orders/%s" % sale1, tok_admin)
    it1 = [i for i in (sale_det.get("items") or []) if i.get("id") == item1]
    step("完结回写trnum=1", it1 and it1[0].get("trnum") == 1000, it1)

    # 库存出货联动(xgnum 事件回写;超卖重试演示用 item2)
    api_ok("出货单(瓷砖1片带销售锚点)", "POST", "/api/inventory/manifests", tok_admin,
           {"type": 3, "items": [{"goodsId": goods_sale1, "num": 1000,
                                  "warehouseOut": wh_main, "salesItemId": item1}]})
    time.sleep(2)
    sale_det = api_ok("出货回写复查", "GET", "/api/sales/orders/%s" % sale1, tok_admin)
    it1 = [i for i in (sale_det.get("items") or []) if i.get("id") == item1]
    step("xgnum 2000→1000(事件回写)", it1 and it1[0].get("xgnum") == 1000, it1)
    api_ok("超卖出货单(地板5片>余量1,库存充足)", "POST", "/api/inventory/manifests", tok_admin,
           {"type": 3, "items": [{"goodsId": goods_sale2, "num": 5000,
                                  "warehouseOut": wh_main, "salesItemId": item2}]})
    time.sleep(3)
    sale_det = api_ok("超卖后复查", "GET", "/api/sales/orders/%s" % sale1, tok_admin)
    it2 = [i for i in (sale_det.get("items") or []) if i.get("id") == item2]
    step("超卖被原子守卫拒绝(xgnum 仍1000)", it2 and it2[0].get("xgnum") == 1000, it2)
    conn_os = db_conn()
    try:
        with conn_os.cursor() as cur:
            cur.execute("select status, retry_count from yz_event_task "
                        "where event_type = 'StockManifestCreatedEvent' and payload like %s "
                        "and payload like %s order by id desc limit 1",
                        ("%5000%", "%" + str(item2) + "%"))
            os_row = cur.fetchone()
    finally:
        conn_os.close()
    step("超卖事件未确认(重试/死信在途,300008 留痕)",
         os_row is not None and os_row[0] in (0, 2, 3), os_row)

    # 退货链(sale1 部分退货:type=2 全退类型但数量部分 → 金额不符不自动退款)
    refund1 = api_ok("发起退货(瓷砖1片)", "POST", "/api/sales/orders/%s/refunds" % sale1,
                     tok_admin, {"type": 2, "items": [{"saleItemId": item1, "num": 1000}],
                                 "assigneeId": uid_admin, "remark": "色差退货"})
    r1_det = api_ok("退货单详情(审批中)", "GET", "/api/sales/refunds/%s" % refund1, tok_admin)
    step("退货单3审批中+审批单挂接", r1_det.get("status") == 3
         and bool(r1_det.get("approval_id")), r1_det)
    sale_det = api_ok("发起后复查(预扣)", "GET", "/api/sales/orders/%s" % sale1, tok_admin)
    it1 = [i for i in (sale_det.get("items") or []) if i.get("id") == item1]
    step("发起即预扣 num 2000→1000", it1 and it1[0].get("num") == 1000, it1)
    api_err("审批中再发起拒绝 300025", "POST", "/api/sales/orders/%s/refunds" % sale1, tok_admin,
            {"type": 2, "items": [{"saleItemId": item1, "num": 500}],
             "assigneeId": uid_admin}, 300025)
    r1_approval = r1_det.get("approval_id")
    api_ok("退货审批通过", "POST", "/api/approvals/%s/approve" % r1_approval,
           tok_admin, {"comment": "同意退货"})
    time.sleep(1)
    r1_det = api_ok("批准后退货单复查", "GET", "/api/sales/refunds/%s" % refund1, tok_admin)
    step("退货单0初始(生效)", r1_det.get("status") == 0, r1_det)
    sale_det = api_ok("主单轴复查", "GET", "/api/sales/orders/%s" % sale1, tok_admin)
    step("部分退不动主单轴(退光才置2/3,单据仍可完结)", sale_det.get("status") == 0, sale_det)
    # 第 39 次运行:退货审批办结事件走总线(消费器回写退货单+主单轴)
    event_consumed("ApprovalFinishedEvent", "approval-finished-" + str(r1_approval),
                   "退货审批办结事件走总线(消费确认)")
    api_ok("退货发码", "POST", "/api/sales/refunds/%s/verify-code" % refund1, tok_admin,
           {"phone": "13800000000"})
    notif_r = api_ok("通知取退货码", "GET", "/api/notifications?page=1&size=3", tok_admin)
    code_r = ""
    for n in (notif_r or []):
        m = _re.search(r"(\d{6})\s*$", str(n.get("content", "")))
        if m and "验证码" in str(n.get("title", "")):
            code_r = m.group(1)
            break
    step("退货码已下发", bool(code_r), notif_r)
    api_ok("退货客户确认", "POST", "/api/sales/refunds/%s/confirm" % refund1, tok_admin,
           {"phone": "13800000000", "code": code_r})
    sale_det = api_ok("退货结算复查", "GET", "/api/sales/orders/%s" % sale1, tok_admin)
    rst = [s for s in (sale_det.get("settlements") or []) if s.get("rid") == refund1]
    step("退货结算负值行(-120元 type=2)", len(rst) == 1 and rst[0].get("total") == -120000
         and rst[0].get("type") == 2, rst)
    api_err("重复确认退货拒绝 300012", "POST", "/api/sales/refunds/%s/confirm" % refund1,
            tok_admin, {"phone": "13800000000", "code": code_r}, 300012)

    # 未预结退货显式报错(sale4:已付未确认无预结;修老静默 return)
    sale4 = api_ok("开第四单(未预结反例)", "POST", "/api/sales/orders", tok_admin,
                   {"orderId": order_s, "items": [{"goodsId": goods_sale2, "num": 1000}]})
    api_ok("sale4生成款项单", "POST", "/api/sales/orders/%s/bill" % sale4, tok_admin)
    b4 = api_ok("sale4款项反查", "GET",
                "/api/payment/bills?bizAddon=sales&bizType=order&bizId=%s" % sale4, tok_admin)
    api_ok("sale4收款(不确认)", "POST",
           "/api/payment/bills/%s/collect"
           % [b for b in (b4 or {}).get("rows", []) if b.get("direction") == 0][0]["id"],
           tok_admin, {"channels": [{"channelCode": "cash", "amount": 80000}]})
    time.sleep(2)
    s4_det = api_ok("sale4详情", "GET", "/api/sales/orders/%s" % sale4, tok_admin)
    item4 = [i for i in (s4_det.get("items") or []) if i.get("package_id", 0) == 0][0]["id"]
    rf4 = api_ok("sale4发起退货(倒单)", "POST", "/api/sales/orders/%s/refunds" % sale4, tok_admin,
                 {"type": 3, "items": [{"saleItemId": item4, "num": 1000}],
                  "assigneeId": uid_admin})
    rf4_det = api_ok("sale4退货单详情", "GET", "/api/sales/refunds/%s" % rf4, tok_admin)
    api_ok("sale4退货审批通过", "POST", "/api/approvals/%s/approve" % rf4_det.get("approval_id"),
           tok_admin, {"comment": "同意"})
    time.sleep(1)
    api_ok("sale4退货发码", "POST", "/api/sales/refunds/%s/verify-code" % rf4, tok_admin,
           {"phone": "13800000001"})
    notif4 = api_ok("通知取sale4退货码", "GET", "/api/notifications?page=1&size=3", tok_admin)
    code4 = ""
    for n in (notif4 or []):
        m = _re.search(r"(\d{6})\s*$", str(n.get("content", "")))
        if m and "验证码" in str(n.get("title", "")):
            code4 = m.group(1)
            break
    api_err("未预结生成退货结算拒绝 300019", "POST", "/api/sales/refunds/%s/confirm" % rf4,
            tok_admin, {"phone": "13800000001", "code": code4}, 300019)

    # 全退自动退款链(sale5:金额与款项一致 → submitRefund → PaymentRefundedEvent 回写)
    sale5 = api_ok("开第五单(全退退款链,地板4片320元)", "POST", "/api/sales/orders", tok_admin,
                   {"orderId": order_s, "items": [{"goodsId": goods_sale2, "num": 4000}]})
    api_ok("sale5生成款项单", "POST", "/api/sales/orders/%s/bill" % sale5, tok_admin)
    b5 = api_ok("sale5款项反查", "GET",
                "/api/payment/bills?bizAddon=sales&bizType=order&bizId=%s" % sale5, tok_admin)
    bill5 = [b for b in (b5 or {}).get("rows", []) if b.get("direction") == 0][0]
    api_ok("sale5收款", "POST", "/api/payment/bills/%s/collect" % bill5["id"], tok_admin,
           {"channels": [{"channelCode": "cash", "amount": 320000}]})
    time.sleep(2)
    api_ok("sale5确认", "POST", "/api/payment/bills/%s/confirm" % bill5["id"], tok_admin)
    time.sleep(2)
    s5_det = api_ok("sale5详情", "GET", "/api/sales/orders/%s" % sale5, tok_admin)
    item5 = [i for i in (s5_det.get("items") or []) if i.get("package_id", 0) == 0][0]["id"]
    rf5 = api_ok("sale5发起全退", "POST", "/api/sales/orders/%s/refunds" % sale5, tok_admin,
                 {"type": 2, "items": [{"saleItemId": item5, "num": 4000}],
                  "assigneeId": uid_admin})
    rf5_det = api_ok("sale5退货单详情", "GET", "/api/sales/refunds/%s" % rf5, tok_admin)
    api_ok("sale5退货审批通过", "POST", "/api/approvals/%s/approve" % rf5_det.get("approval_id"),
           tok_admin, {"comment": "全退"})
    time.sleep(1)
    api_ok("sale5退货发码", "POST", "/api/sales/refunds/%s/verify-code" % rf5, tok_admin,
           {"phone": "13800000002"})
    notif5 = api_ok("通知取sale5退货码", "GET", "/api/notifications?page=1&size=3", tok_admin)
    code5 = ""
    for n in (notif5 or []):
        m = _re.search(r"(\d{6})\s*$", str(n.get("content", "")))
        if m and "验证码" in str(n.get("title", "")):
            code5 = m.group(1)
            break
    api_ok("sale5退货客户确认(触发自动退款)", "POST", "/api/sales/refunds/%s/confirm" % rf5,
           tok_admin, {"phone": "13800000002", "code": code5})
    todo_r = api_ok("退款审批待办", "GET", "/api/approvals?role=todo", tok_admin)
    refund_appr = next((a for a in (todo_r or [])
                        if str(a.get("title", "")).startswith("退款审批") and "320" in str(a.get("title", ""))
                        and str(a.get("biz_addon", "")) == "payment"), None)
    step("payment 退款审批单在待办", bool(refund_appr), todo_r)
    api_ok("退款审批通过", "POST", "/api/approvals/%s/approve" % refund_appr["id"], tok_admin,
           {"comment": "同意退款"})
    # 回写走总线消费,固定 sleep 会扫中空窗(第 45 次实锤)——轮询等 status==1 至多 60s
    rf5_det = None
    for _ in range(30):
        time.sleep(2)
        rf5_det = api_ok("退款回写复查", "GET", "/api/sales/refunds/%s" % rf5, tok_admin)
        if rf5_det and rf5_det.get("status") == 1:
            break
    step("退货单1已退款(PaymentRefundedEvent 回写)", bool(rf5_det) and rf5_det.get("status") == 1, rf5_det)
    b5b = api_ok("原款项复查", "GET",
                 "/api/payment/bills?bizAddon=sales&bizType=order&bizId=%s" % sale5, tok_admin)
    step("原款项已退款(status=3)", any(b.get("id") == bill5["id"] and b.get("status") == 3
         for b in (b5b or {}).get("rows", [])), b5b)

    # 完结/废单
    api_ok("sale1完结", "POST", "/api/sales/orders/%s/finish" % sale1, tok_admin)
    sale_det = api_ok("完结复查", "GET", "/api/sales/orders/%s" % sale1, tok_admin)
    step("主单轴5完结", sale_det.get("status") == 5, sale_det)
    api_err("已完结废单拒绝 300002", "POST", "/api/sales/orders/%s/terminate" % sale1,
            tok_admin, {}, 300002)
    api_ok("sale3生成款项单", "POST", "/api/sales/orders/%s/bill" % sale3, tok_admin)
    api_ok("sale3废单(联动作废款项单)", "POST", "/api/sales/orders/%s/terminate" % sale3, tok_admin)
    s3_det = api_ok("废单复查", "GET", "/api/sales/orders/%s" % sale3, tok_admin)
    s3_bills = [b for b in (s3_det.get("bills") or []) if b.get("direction") == 0]
    step("主单轴4废单+款项单全作废", s3_det.get("status") == 4
         and all(b.get("status") == 4 for b in s3_bills) and len(s3_bills) >= 1, s3_det)

    # construction 销售款闸(stacheck 启用)
    cons_nodes = api_ok("节点模板基点", "GET", "/api/construction/nodes?includeDisabled=true", tok_admin)
    sort_next = max([n.get("sort", 0) for n in (cons_nodes or [])] + [0]) + 1
    node5 = api_ok("建节点模板(销售款闸500‰)", "POST", "/api/construction/nodes", tok_admin,
                   {"name": "销售闸" + stamp_sale, "sort": sort_next, "precheck": 0, "postcheck": 0,
                    "stacheck": 500, "fincheck": 0, "timeLimit": 1000,
                    "salaryLimit": 999999000, "accessoryLimit": 999999000})
    phone_s2 = "137%08d" % (int(stamp_sale) % 100000000)
    cust_s2 = api_ok("闸链客 get-or-create", "POST", "/api/customerprofile/customers/exist",
                     tok_admin, {"phone": phone_s2, "name": "闸链客" + stamp_sale})
    house_s2 = api_ok("闸链房 get-or-create", "POST", "/api/customerprofile/houses/exist",
                      tok_admin, {"villageId": village_id, "number": "8栋1单元%s" % stamp_sale})
    order_s2 = api_ok("登记装修单S2(销售闸链)", "POST", "/api/decoration/orders", tok_admin,
                      {"customerId": (cust_s2 or {}).get("customerId"),
                       "houseId": (house_s2 or {}).get("houseId"), "type": 0,
                       "designerId": uid_b, "salesmanId": uid_c})
    if not all([node5, cust_s2, house_s2, order_s2]):
        return finish()
    api_ok("单S2补录点火", "POST", "/api/construction/drawlines/retroactive", tok_admin,
           {"orderId": order_s2, "groupName": "放线S组" + stamp_sale,
            "planStart": day1 + "T08:00:00", "planEnd": day1 + "T12:00:00",
            "remark": "销售闸冒烟"})
    cons_s2 = api_ok("单S2施工详情", "GET", "/api/construction/orders/%s" % order_s2, tok_admin)
    sched5 = next((s for s in (cons_s2.get("schedules") or [])
                   if "销售闸" in str(s.get("node_name", ""))), None)
    step("销售闸节点进快照", bool(sched5), cons_s2.get("schedules"))
    api_err("销售款闸拒(0‰ 240025)", "POST", "/api/construction/schedules/%s/begin" % sched5["id"],
            tok_admin, None, 240025)
    sale6 = api_ok("开单@S2", "POST", "/api/sales/orders", tok_admin,
                   {"orderId": order_s2, "items": [{"goodsId": goods_sale1, "num": 1000}]})
    api_ok("sale6生成款项单", "POST", "/api/sales/orders/%s/bill" % sale6, tok_admin)
    b6 = api_ok("sale6款项反查", "GET",
                "/api/payment/bills?bizAddon=sales&bizType=order&bizId=%s" % sale6, tok_admin)
    b6_income = [b for b in (b6 or {}).get("rows", []) if b.get("direction") == 0][0]
    api_ok("sale6收款", "POST", "/api/payment/bills/%s/collect" % b6_income["id"],
           tok_admin, {"channels": [{"channelCode": "cash", "amount": 120000}]})
    time.sleep(2)
    api_ok("sale6确认", "POST", "/api/payment/bills/%s/confirm" % b6_income["id"], tok_admin)
    time.sleep(2)
    api_ok("销售款闸放行(1000‰)", "POST", "/api/construction/schedules/%s/begin" % sched5["id"],
           tok_admin)

    # 轨迹留痕
    sale_det = api_ok("轨迹复查", "GET", "/api/sales/orders/%s" % sale1, tok_admin)
    actions = [t.get("action") for t in (sale_det.get("tracks") or [])]
    step("轨迹全覆盖(create/confirmPay/refund/finish等)", "create" in actions
         and "confirmPay" in actions and "finish" in actions, actions)

    print("== 结算(addon-balance V1:事件快照吸附+四态机+账户双腿+扣款池+完结唯一定义) ==")
    stamp_bal = time.strftime("%H%M%S") + "b"
    # 归属人带时间戳:账户 uk(org+belonger+type)与桶键随跑唯一,冒烟可重复执行
    sup_b = "sup_b" + stamp_bal
    sup_c = "sup_c" + stamp_bal
    sup_z = "sup_z" + stamp_bal
    sup_b2 = "sup_b2" + stamp_bal
    cashier = "cashier" + stamp_bal

    def db_exec(query, args=()):
        conn = db_conn()
        try:
            with conn.cursor() as cur:
                cur.execute(query, args)
                if query.strip().lower().startswith("select"):
                    return cur.fetchall()
                conn.commit()
                return cur.rowcount
        finally:
            conn.close()

    def publish_ready(item_id, brand, bname, supplier, sname, income, cost, payment,
                      addon="quotation", stype="selection", grade="C", price=0, qty=1000,
                      key_suffix=""):
        payload = json.dumps({
            "orgId": 1, "sourceAddon": addon, "sourceType": stype, "sourceItemId": item_id,
            "brandId": brand, "brandName": bname, "supplier": supplier, "supplierName": sname,
            "grade": grade, "qty": qty, "price": price, "income": income, "cost": cost,
            "payment": payment, "memo": "冒烟明细 " + item_id}, ensure_ascii=False)
        db_exec("insert into yz_event_task(event_type, payload, event_key, max_retries) values (%s,%s,%s,2)",
                ("SettlementItemReadyEvent", payload, "smoke-bal:ready:" + item_id + key_suffix))

    def publish_withdrawn(item_id, reason, addon="quotation", stype="selection", key_suffix=""):
        payload = json.dumps({"orgId": 1, "sourceAddon": addon, "sourceType": stype,
                              "sourceItemId": item_id, "reason": reason}, ensure_ascii=False)
        db_exec("insert into yz_event_task(event_type, payload, event_key, max_retries) values (%s,%s,%s,2)",
                ("SettlementItemWithdrawnEvent", payload, "smoke-bal:wd:" + item_id + key_suffix))

    def wait_dead(event_key, timeout=200):
        deadline = time.time() + timeout
        while time.time() < deadline:
            rows = db_exec("select status from yz_event_task where event_key = %s order by id desc limit 1",
                           (event_key,))
            if rows and rows[0][0] == 3:
                return True
            time.sleep(2)
        return False

    # 插件下发:三菜单
    addons_bal = api_ok("admin 插件下发复查(balance)", "GET", "/api/addons", tok_admin)
    bal_addon = next((a for a in (addons_bal or []) if a.get("id") == "balance"), None)
    step("balance 三菜单动态下发", bool(bal_addon)
         and sorted(m.get("path") for m in bal_addon.get("menus") or []) == [
             "/balance/accounts", "/balance/cutpays", "/balance/settlements"],
         bal_addon and bal_addon.get("menus"))

    # 事件快照吸附:同键(品牌+归属人+等级)聚一桶,服务端加总四项
    publish_ready("bsel1" + stamp_bal, brand2, "烟测品牌B" + stamp_sale, sup_b, "厂商乙",
                  1000000, 200000, 800000)
    publish_ready("bsel2" + stamp_bal, brand2, "烟测品牌B" + stamp_sale, sup_b, "厂商乙",
                  600000, 100000, 500000)
    # 直插事件走调度器认领(首延 3s+轮询 2s),固定 sleep 会扫中空窗(第 45 次实锤)改轮询至多 24s
    bucket_b = None
    for _ in range(12):
        time.sleep(2)
        blist = api_ok("结算单列表(来源过滤)", "GET",
                       "/api/balance/settlements?settlementType=quotation%3Aselection", tok_admin)
        bucket_b = next((s for s in (blist or {}).get("rows", [])
                         if s.get("supplier") == sup_b and s.get("brand_id") == brand2), None)
        if bucket_b and bucket_b.get("item_count") == 2:
            break
    ok = bool(bucket_b) and bucket_b.get("status") == 0 and bucket_b.get("item_count") == 2 \
        and bucket_b.get("income") == 1600000 and bucket_b.get("cost") == 300000 \
        and bucket_b.get("payment") == 1300000 and bucket_b.get("profit") == 300000 \
        and bucket_b.get("amount") == 1300000 and str(bucket_b.get("no", "")).startswith("JS")
    step("同键聚桶+四项服务端加总+JS单号", ok, bucket_b)
    if not bucket_b:
        return finish()

    # 幂等重放(同键同金额)与同键吸附不建新桶
    publish_ready("bsel1" + stamp_bal, brand2, "烟测品牌B" + stamp_sale, sup_b, "厂商乙",
                  1000000, 200000, 800000)
    publish_ready("bsel3" + stamp_bal, brand2, "烟测品牌B" + stamp_sale, sup_b, "厂商乙",
                  300000, 50000, 250000)
    # 直插事件走调度器,固定 sleep 会扫中空窗(第 45 次实锤)——轮询等 item_count==3
    b_b = None
    for _ in range(12):
        time.sleep(2)
        b_b = api_ok("聚桶复查(重放幂等)", "GET", "/api/balance/settlements/%s" % bucket_b["id"], tok_admin)
        if b_b and b_b.get("item_count") == 3:
            break
    step("重放不重复落行+同键吸附同行数3", bool(b_b) and b_b.get("item_count") == 3
         and b_b.get("payment") == 1550000, b_b)

    # 异键(归属人不同)新桶
    publish_ready("bsel4" + stamp_bal, brand2, "烟测品牌B" + stamp_sale, sup_c, "厂商丙",
                  400000, 100000, 320000)
    bucket_c = None
    for _ in range(12):
        time.sleep(2)
        blist = api_ok("列表复查(异键)", "GET",
                       "/api/balance/settlements?settlementType=quotation%3Aselection", tok_admin)
        bucket_c = next((s for s in (blist or {}).get("rows", []) if s.get("supplier") == sup_c), None)
        if bucket_c:
            break
    step("异归属人独立建桶", bool(bucket_c) and bucket_c.get("item_count") == 1
         and bucket_c.get("payment") == 320000, bucket_c)
    if not bucket_c:
        return finish()

    # 上游修正(同键异金额=更新快照并重算)
    publish_ready("bsel1" + stamp_bal, brand2, "烟测品牌B" + stamp_sale, sup_b, "厂商乙",
                  1000000, 200000, 850000)
    b_b = None
    for _ in range(12):
        time.sleep(2)
        b_b = api_ok("上游修正复查", "GET", "/api/balance/settlements/%s" % bucket_b["id"], tok_admin)
        if b_b and b_b.get("payment") == 1600000:
            break
    step("修正快照+金额重算(应付160万)", bool(b_b) and b_b.get("payment") == 1600000
         and b_b.get("profit") == 300000, b_b)

    # 让减守卫(仅初始态;0<=x<=payment)
    api_err("负让减拒绝 320017", "PUT", "/api/balance/settlements/%s/discount" % bucket_b["id"],
            tok_admin, {"value": -1000}, 320017)
    api_err("超应付让减拒绝 320017", "PUT", "/api/balance/settlements/%s/discount" % bucket_b["id"],
            tok_admin, {"value": 1600001}, 320017)
    api_ok("合法让减5万元", "PUT", "/api/balance/settlements/%s/discount" % bucket_b["id"],
           tok_admin, {"value": 50000})
    b_b = api_ok("让减后复查", "GET", "/api/balance/settlements/%s" % bucket_b["id"], tok_admin)
    step("应付总额=payment-discount(155万)", b_b.get("amount") == 1550000, b_b)

    # 明细退结算:未锁定桶摘行重算
    publish_withdrawn("bsel3" + stamp_bal, "上游作废")
    b_b = None
    for _ in range(12):
        time.sleep(2)
        b_b = api_ok("退回后复查", "GET", "/api/balance/settlements/%s" % bucket_b["id"], tok_admin)
        if b_b and b_b.get("item_count") == 2:
            break
    step("退回摘行+重算(应付回落130万)", bool(b_b) and b_b.get("item_count") == 2
         and b_b.get("payment") == 1350000 and b_b.get("amount") == 1300000, b_b)

    # 未锁定结算拒绝
    api_err("未锁定结算拒绝 320002", "POST", "/api/balance/settlements/%s/settle" % bucket_b["id"],
            tok_admin, None, 320002)

    # 锁审链:lock→audit→unaudit→audit→settle
    api_ok("锁定", "POST", "/api/balance/settlements/%s/lock" % bucket_b["id"], tok_admin)
    api_ok("送审", "POST", "/api/balance/settlements/%s/audit" % bucket_b["id"], tok_admin)
    api_ok("驳回退锁定", "POST", "/api/balance/settlements/%s/unaudit" % bucket_b["id"], tok_admin)
    api_ok("再送审", "POST", "/api/balance/settlements/%s/audit" % bucket_b["id"], tok_admin)
    api_ok("结算(终态前置)", "POST", "/api/balance/settlements/%s/settle" % bucket_b["id"], tok_admin)
    b_b = api_ok("结算后复查", "GET", "/api/balance/settlements/%s" % bucket_b["id"], tok_admin)
    step("状态3+余量=应付总额", b_b.get("status") == 3 and b_b.get("surplus") == 1300000, b_b)
    api_err("已结算改让减拒绝 320002", "PUT",
            "/api/balance/settlements/%s/discount" % bucket_b["id"], tok_admin, {"value": 1}, 320002)

    # 锁定后吸附/退回 → 死信可见(消费失败转死信)
    publish_ready("bsel1" + stamp_bal, brand2, "烟测品牌B" + stamp_sale, sup_b, "厂商乙",
                  1000000, 200000, 900000, key_suffix="-locked")
    publish_withdrawn("bsel1" + stamp_bal, "锁定后退回", key_suffix="-locked")
    time.sleep(1)
    ok = wait_dead("smoke-bal:ready:bsel1" + stamp_bal + "-locked")         and wait_dead("smoke-bal:wd:bsel1" + stamp_bal + "-locked")
    step("锁定后修正/退回消费失败转死信", ok, None)
    b_b = api_ok("死信不落行复查", "GET", "/api/balance/settlements/%s" % bucket_b["id"], tok_admin)
    step("锁定桶零写入(行数仍2)", b_b.get("item_count") == 2, b_b)

    # 已结算事件消费确认
    ev_settled = db_exec("select status from yz_event_task where event_key = %s",
                         ("balance:settlement:%d:settled" % bucket_b["id"],))
    step("SettlementSettledEvent 已消费", bool(ev_settled) and ev_settled[0][0] == 1, ev_settled)

    # ---- 账户双腿 ----
    acc_b = api_ok("建结算账户(厂商乙)", "POST", "/api/balance/accounts", tok_admin,
                   {"belonger": sup_b, "belongerName": "厂商乙", "type": 0})
    api_err("同归属人重复建拒 320024", "POST", "/api/balance/accounts", tok_admin,
            {"belonger": sup_b, "belongerName": "厂商乙", "type": 0}, 320024)
    acc_cash = api_ok("建出纳账户", "POST", "/api/balance/accounts", tok_admin,
                      {"belonger": cashier, "belongerName": "出纳", "type": 1})
    acc_z = api_ok("建他属结算账户", "POST", "/api/balance/accounts", tok_admin,
                   {"belonger": sup_z, "belongerName": "厂商丁", "type": 0})
    acc_off = api_ok("建停用结算账户", "POST", "/api/balance/accounts", tok_admin,
                     {"belonger": sup_b2, "belongerName": "厂商丙2", "type": 0})
    if not all([acc_b, acc_cash, acc_z, acc_off]):
        return finish()
    api_ok("停用账户", "POST", "/api/balance/accounts/%s/status" % acc_off, tok_admin, {"status": 1})

    api_err("出纳账户当台账腿拒绝 320021", "POST",
            "/api/balance/settlements/%s/enter" % bucket_b["id"], tok_admin,
            {"accountId": acc_cash, "money": 100000}, 320021)
    api_err("归属不一致拒绝 320022", "POST",
            "/api/balance/settlements/%s/enter" % bucket_b["id"], tok_admin,
            {"accountId": acc_z, "money": 100000}, 320022)
    api_err("停用账户拒绝 320006", "POST",
            "/api/balance/settlements/%s/enter" % bucket_b["id"], tok_admin,
            {"accountId": acc_off, "money": 100000}, 320006)
    api_err("零金额拒绝 320004", "POST",
            "/api/balance/settlements/%s/enter" % bucket_b["id"], tok_admin,
            {"accountId": acc_b, "money": 0}, 320004)
    api_ok("台账入账10万", "POST", "/api/balance/settlements/%s/enter" % bucket_b["id"],
           tok_admin, {"accountId": acc_b, "money": 100000})
    api_err("超余量入账拒绝 320008", "POST",
            "/api/balance/settlements/%s/enter" % bucket_b["id"], tok_admin,
            {"accountId": acc_b, "money": 1200001}, 320008)
    b_b = api_ok("入账后复查", "GET", "/api/balance/settlements/%s" % bucket_b["id"], tok_admin)
    step("余量原子消耗(120万)", b_b.get("surplus") == 1200000, b_b)

    api_ok("出纳入金50万", "POST", "/api/balance/accounts/%s/cash-in" % acc_cash, tok_admin,
           {"money": 500000, "memo": "备用金"})
    api_err("出纳超余额付款拒绝 320007", "POST",
            "/api/balance/settlements/%s/pay" % bucket_b["id"], tok_admin,
            {"accountId": acc_cash, "money": 500001}, 320007)
    api_ok("出纳付款20万", "POST", "/api/balance/settlements/%s/pay" % bucket_b["id"],
           tok_admin, {"accountId": acc_cash, "money": 200000})
    api_ok("出纳再付9万(归属账户透支=预付)", "POST",
           "/api/balance/settlements/%s/pay" % bucket_b["id"], tok_admin,
           {"accountId": acc_cash, "money": 90000})
    b_b = api_ok("付款后复查", "GET", "/api/balance/settlements/%s" % bucket_b["id"], tok_admin)
    step("实付累计29万(现金腿不消耗余量)", b_b.get("paid") == 290000
         and b_b.get("surplus") == 1200000, b_b)

    accts = api_ok("账户列表复查", "GET", "/api/balance/accounts", tok_admin)
    acc_b_row = next((a for a in (accts or {}).get("rows", []) if a.get("id") == acc_b), None)
    cash_row = next((a for a in (accts or {}).get("rows", []) if a.get("id") == acc_cash), None)
    ok = bool(acc_b_row) and acc_b_row.get("refer_money") == 100000 \
        and acc_b_row.get("real_money") == 290000 and acc_b_row.get("surplus") == -190000
    ok = ok and bool(cash_row) and cash_row.get("refer_money") == 500000 \
        and cash_row.get("real_money") == 290000 and cash_row.get("surplus") == 210000
    step("双腿台账+懒开户同步+透支合法", ok, (acc_b_row, cash_row))

    # 完结唯一定义:余量清零且已付款,单向无逆向
    api_err("余量未清零完结拒绝 320016", "POST",
            "/api/balance/settlements/%s/finish" % bucket_b["id"], tok_admin, None, 320016)
    api_ok("入账清零余量", "POST", "/api/balance/settlements/%s/enter" % bucket_b["id"],
           tok_admin, {"accountId": acc_b, "money": 1200000})
    api_err("未打付款标完结拒绝 320016", "POST",
            "/api/balance/settlements/%s/finish" % bucket_b["id"], tok_admin, None, 320016)
    api_ok("打付款标", "POST", "/api/balance/settlements/pay-mark", tok_admin,
           {"ids": [bucket_b["id"]]})
    api_err("重复打标拒绝 320015", "POST", "/api/balance/settlements/pay-mark", tok_admin,
            {"ids": [bucket_b["id"]]}, 320015)
    api_err("批量含已付全或无 320015", "POST", "/api/balance/settlements/pay-mark", tok_admin,
            {"ids": [bucket_b["id"], bucket_c["id"]]}, 320015)
    api_ok("完结", "POST", "/api/balance/settlements/%s/finish" % bucket_b["id"], tok_admin)
    b_b = api_ok("完结复查", "GET", "/api/balance/settlements/%s" % bucket_b["id"], tok_admin)
    step("状态4终态", b_b.get("status") == 4, b_b)
    api_err("终态入账拒绝 320002", "POST", "/api/balance/settlements/%s/enter" % bucket_b["id"],
            tok_admin, {"accountId": acc_b, "money": 1}, 320002)
    api_err("终态付款拒绝 320002", "POST", "/api/balance/settlements/%s/pay" % bucket_b["id"],
            tok_admin, {"accountId": acc_cash, "money": 1}, 320002)
    api_err("终态锁定拒绝 320002", "POST", "/api/balance/settlements/%s/lock" % bucket_b["id"],
            tok_admin, None, 320002)

    # 账户流水与不变式(surplus = refer - real)
    det_b = api_ok("归属账户流水", "GET", "/api/balance/accounts/%s/details" % acc_b, tok_admin)
    b_actions = [d.get("action") for d in (det_b or {}).get("rows", [])]
    step("归属账户流水含台账入账+懒开户同步", "settleEnter" in b_actions
         and "lazySync" in b_actions and len(b_actions) >= 3, b_actions)
    inv_rows = db_exec("""
        select count(*) from balance_account
        where surplus <> refer_money - real_money and org_id = 1
        """)
    step("账户净头寸不变式(surplus=refer-real)", inv_rows and inv_rows[0][0] == 0, inv_rows)

    # ---- 扣款池(桶C) ----
    api_ok("桶C锁定+结算", "POST", "/api/balance/settlements/%s/lock" % bucket_c["id"], tok_admin)
    api_ok("桶C结算", "POST", "/api/balance/settlements/%s/settle" % bucket_c["id"], tok_admin)
    pool1 = api_ok("建扣款池(合作费30万)", "POST", "/api/balance/cutpay-pools", tok_admin,
                   {"name": "烟测合作费池" + stamp_bal, "type": 1, "supplier": sup_c, "total": 300000})
    api_err("零额度扣款单拒绝 320004", "POST", "/api/balance/cutpays", tok_admin,
            {"poolId": pool1, "money": 0, "type": 0}, 320004)
    cp1 = api_ok("建扣款单10万(待绑定)", "POST", "/api/balance/cutpays", tok_admin,
                 {"poolId": pool1, "money": 100000, "type": 0, "memo": "年度合作费"})
    if not all([pool1, cp1]):
        return finish()
    pools_now = api_ok("建单不动池", "GET", "/api/balance/cutpay-pools", tok_admin)
    p1 = next((p for p in (pools_now or {}).get("rows", []) if p.get("id") == pool1), None)
    step("池余量建单不动(30万)", p1 and p1.get("surplus") == 300000, p1)

    cp2 = api_ok("建返款单(仅字典预留)", "POST", "/api/balance/cutpays", tok_admin,
                 {"poolId": pool1, "money": 1000, "type": 1})
    api_err("返款单绑定拒绝 320021", "POST", "/api/balance/cutpays/%s/bind" % cp2, tok_admin,
            {"settlementId": bucket_c["id"]}, 320021)

    api_ok("绑定冲抵(池20万+桶C余量22万)", "POST", "/api/balance/cutpays/%s/bind" % cp1,
           tok_admin, {"settlementId": bucket_c["id"]})
    api_err("重复绑定拒绝 320013", "POST", "/api/balance/cutpays/%s/bind" % cp1, tok_admin,
            {"settlementId": bucket_c["id"]}, 320013)
    c_now = api_ok("桶C复查", "GET", "/api/balance/settlements/%s" % bucket_c["id"], tok_admin)
    step("结算单扣款冲抵与余量", c_now.get("cutpay") == 100000 and c_now.get("surplus") == 220000, c_now)

    api_err("子流水超额拒绝 320020", "POST", "/api/balance/cutpays/%s/deduct" % cp1, tok_admin,
            {"money": 100001}, 320020)
    api_ok("拆子流水4万(仅留痕)", "POST", "/api/balance/cutpays/%s/deduct" % cp1, tok_admin,
           {"money": 40000, "memo": "首期"})
    cp_det = api_ok("扣款单详情", "GET", "/api/balance/cutpays/%s" % cp1, tok_admin)
    step("子流水在册+累计4万", len(cp_det.get("deducts") or []) == 1
         and cp_det.get("deduct_total") == 40000, cp_det)

    api_ok("解绑回补(池30万+桶C32万)", "POST", "/api/balance/cutpays/%s/unbind" % cp1,
           tok_admin, {"reason": "额度调整"})
    api_err("未绑定再解绑拒绝 320012", "POST", "/api/balance/cutpays/%s/unbind" % cp1, tok_admin,
            {}, 320012)
    c_now = api_ok("解绑后复查", "GET", "/api/balance/settlements/%s" % bucket_c["id"], tok_admin)
    step("解绑精确逆操作(冲抵清零余量回满)", c_now.get("cutpay") == 0
         and c_now.get("surplus") == 320000, c_now)
    api_ok("再绑定(对称闭环)", "POST", "/api/balance/cutpays/%s/bind" % cp1, tok_admin,
           {"settlementId": bucket_c["id"]})
    c_now = api_ok("再绑后复查", "GET", "/api/balance/settlements/%s" % bucket_c["id"], tok_admin)
    step("再绑冲抵复原", c_now.get("cutpay") == 100000 and c_now.get("surplus") == 220000, c_now)

    api_ok("停用池", "POST", "/api/balance/cutpay-pools/%s/status" % pool1, tok_admin, {"status": 1})
    api_err("停用池建单拒绝 320002", "POST", "/api/balance/cutpays", tok_admin,
            {"poolId": pool1, "money": 1000, "type": 0}, 320002)
    api_ok("启用池", "POST", "/api/balance/cutpay-pools/%s/status" % pool1, tok_admin, {"status": 0})

    # ---- sales 真链:post 发布 Ready → 吸附 → 解绑退回 → 删空桶 ----
    brand3 = api_ok("建品牌3(结算真链)", "POST", "/api/basedata/brands", tok_admin,
                    {"name": "烟测品牌C" + stamp_bal, "pinyin": "ycppc" + stamp_bal[-6:]})
    goods_sale3 = api_ok("建销售商品3(品牌3,50元)", "POST", "/api/basedata/goods", tok_admin,
                         {"name": "烟测Sales洁具" + stamp_bal, "categoryId": cat_child,
                          "brandId": brand3, "unit": "件", "selling": 50000})
    sale7 = api_ok("开单(结算真链)", "POST", "/api/sales/orders", tok_admin,
                   {"orderId": order_s, "items": [{"goodsId": goods_sale3, "num": 2000}]})
    if not all([brand3, goods_sale3, sale7]):
        return finish()
    api_ok("sale7生成款项单", "POST", "/api/sales/orders/%s/bill" % sale7, tok_admin)
    b7 = api_ok("sale7款项反查", "GET",
                "/api/payment/bills?bizAddon=sales&bizType=order&bizId=%s" % sale7, tok_admin)
    bill7 = [b for b in (b7 or {}).get("rows", []) if b.get("direction") == 0][0]
    api_ok("sale7收款", "POST", "/api/payment/bills/%s/collect" % bill7["id"], tok_admin,
           {"channels": [{"channelCode": "cash", "amount": 100000}]})
    time.sleep(2)
    api_ok("sale7财务确认款项", "POST", "/api/payment/bills/%s/confirm" % bill7["id"], tok_admin)
    time.sleep(2)
    api_ok("sale7发确认码", "POST", "/api/sales/orders/%s/verify-code" % sale7, tok_admin,
           {"phone": "13900000007"})
    notif_b = api_ok("通知取确认码(sale7)", "GET", "/api/notifications?page=1&size=3", tok_admin)
    code7 = ""
    for n in (notif_b or []):
        m = _re.search(r"(\d{6})\s*$", str(n.get("content", "")))
        if m and "验证码" in str(n.get("title", "")):
            code7 = m.group(1)
            break
    api_ok("sale7客户确认", "POST", "/api/sales/orders/%s/confirm" % sale7, tok_admin,
           {"phone": "13900000007", "code": code7})
    time.sleep(2)
    s7_det = api_ok("sale7预结复查", "GET", "/api/sales/orders/%s" % sale7, tok_admin)
    st7 = next((s for s in (s7_det.get("settlements") or []) if s.get("brand_id") == brand3), None)
    step("sale7预结行在册", bool(st7), st7)
    if not st7:
        return finish()
    api_ok("sale7改厂结值+厂结", "PUT", "/api/sales/settlements/%s/vbalance" % st7["id"],
           tok_admin, {"value": 100000})
    api_ok("sale7厂结", "POST", "/api/sales/settlements/%s/vpost" % st7["id"], tok_admin)
    api_ok("sale7改结算值(95000)", "PUT", "/api/sales/settlements/%s/pbalance" % st7["id"],
           tok_admin, {"value": 95000})
    api_ok("sale7结算(post)", "POST", "/api/sales/settlements/%s/post" % st7["id"], tok_admin)
    time.sleep(3)
    slist = api_ok("结算列表(sales 来源,brandId 过滤防历史桶挤页)", "GET",
                   "/api/balance/settlements?settlementType=sales%3Asettlement&brandId=" + str(brand3), tok_admin)
    sbucket = next((s for s in (slist or {}).get("rows", []) if s.get("brand_id") == brand3), None)
    ok = bool(sbucket) and sbucket.get("status") == 0 and sbucket.get("item_count") == 1 \
        and sbucket.get("payment") == 95000 and str(sbucket.get("no", "")).startswith("JS")
    step("sales post 事件吸附(快照=结算值)", ok, sbucket)

    api_ok("解绑sales明细(退回上游)", "POST", "/api/balance/settlements/%s/unbind-item" % sbucket["id"],
           tok_admin, {"sourceAddon": "sales", "sourceType": "settlement",
                       "sourceItemId": str(st7["id"]), "reason": "冒烟解绑"})
    sbucket2 = api_ok("解绑后复查", "GET", "/api/balance/settlements/%s" % sbucket["id"], tok_admin)
    step("解绑摘行", sbucket2.get("item_count") == 0, sbucket2)
    ev_rel = db_exec("select status from yz_event_task where event_key = %s",
                     ("settle-item:sales:settlement:%d:released" % st7["id"],))
    step("退回事件发布(无消费者归档)", bool(ev_rel) and ev_rel[0][0] == 1, ev_rel)
    api_ok("删除空桶", "DELETE", "/api/balance/settlements/%s" % sbucket["id"], tok_admin)
    slist = api_ok("删除复查", "GET",
                   "/api/balance/settlements?settlementType=sales%3Asettlement&no=" + sbucket["no"],
                   tok_admin)
    step("空桶已删", (slist or {}).get("total", 0) == 0, slist)

    # 轨迹全覆盖(结算动作必留痕,修老 record 结算不写)
    b_b = api_ok("轨迹复查", "GET", "/api/balance/settlements/%s" % bucket_b["id"], tok_admin)
    b_actions = [t.get("action") for t in (b_b.get("tracks") or [])]
    step("轨迹全覆盖(ready/lock/audit/settle/enter/pay/payMark/finish)", "ready" in b_actions
         and "lock" in b_actions and "settle" in b_actions and "enter" in b_actions
         and "pay" in b_actions and "payMark" in b_actions and "finish" in b_actions, b_actions)

    # 对账:全域事件零滞留 + 额度流水 + 单号计数器
    inv_undelivered = None
    for _ in range(48):
        conn = db_conn()
        try:
            with conn.cursor() as cur:
                cur.execute("""
                    select count(*) from yz_event_task
                    where (event_type like concat('Stock', '%%')
                           or event_type like concat('Construction', '%%')
                           or event_type like concat('Decoration', '%%')
                           or event_type like concat('Payment', '%%')
                           or event_type like concat('Sales', '%%')
                           or event_type like concat('Settlement', '%%'))
                      and status not in (1, 3)
                      and payload not like %s
                """, ("%" + str(item2) + "%",))
                inv_undelivered = cur.fetchone()[0]
        finally:
            conn.close()
        if inv_undelivered == 0:
            break
        time.sleep(5)
    conn = db_conn()
    try:
        with conn.cursor() as cur:
            cur.execute("select count(*) from construction_accessory_ledger where order_id = %s",
                        (order_g,))
            ledger_rows = cur.fetchone()[0]
            cur.execute("select count(*) from inventory_counter where org_id = 1")
            counter_rows = cur.fetchone()[0]
    finally:
        conn.close()
    step("总线对账:全域事件零滞留(全消费确认)", inv_undelivered == 0, inv_undelivered)
    step("额度流水 append-only 在册(consume/refund 多轮)", ledger_rows >= 4, ledger_rows)
    step("单号计数器多前缀在册", counter_rows >= 4, counter_rows)

    # ========= 营销获客(addon-market 第 27 次扩展)=========
    print("\n== 营销获客(addon-market:投放物+进线承接+归因漏斗+案例库) ==")
    stamp_mkt = time.strftime("%H%M%S") + "m"
    mkt_num = time.strftime("%H%M%S")

    # 插件下发:四菜单
    addons_mkt = api_ok("admin 插件下发复查(market)", "GET", "/api/addons", tok_admin)
    mkt_addon = next((a for a in (addons_mkt or []) if a.get("id") == "market"), None)
    step("market 四菜单动态下发", bool(mkt_addon)
         and sorted(m.get("path") for m in mkt_addon.get("menus") or []) == [
             "/market/campaigns", "/market/funnels", "/market/inbounds", "/market/showcase"],
         mkt_addon and mkt_addon.get("menus"))

    # ---- 投放物:活动/渠道/投放源 ----
    camp = api_ok("建活动(草稿)", "POST", "/api/market/campaigns", tok_admin,
                  {"name": "获客活动甲" + stamp_mkt, "description": "冒烟投放活动", "budget": 5000000})
    camp_id = camp.get("id")
    api_err("活动重名 280003", "POST", "/api/market/campaigns", tok_admin,
            {"name": "获客活动甲" + stamp_mkt, "budget": 5000000}, 280003)
    api_ok("发布活动", "POST", "/api/market/campaigns/%s/publish" % camp_id, tok_admin)
    api_err("发布后编辑拒绝 280002", "PUT", "/api/market/campaigns/%s" % camp_id, tok_admin,
            {"name": "改名单" + stamp_mkt, "budget": 1}, 280002)
    chan = api_ok("建渠道", "POST", "/api/market/channels", tok_admin, {"name": "渠道抖音" + stamp_mkt})
    chan_id = chan.get("id")
    api_err("渠道重名 280006", "POST", "/api/market/channels", tok_admin,
            {"name": "渠道抖音" + stamp_mkt}, 280006)
    api_ok("停用渠道", "POST", "/api/market/channels/%s/disable" % chan_id, tok_admin)
    api_err("停用渠道建源 280005", "POST", "/api/market/sources", tok_admin,
            {"campaignId": camp_id, "channelId": chan_id, "name": "源X"}, 280005)
    api_ok("恢复渠道(修老无恢复)", "POST", "/api/market/channels/%s/enable" % chan_id, tok_admin)
    camp2 = api_ok("建第二活动(草稿)", "POST", "/api/market/campaigns", tok_admin,
                   {"name": "获客活动乙" + stamp_mkt})
    src2 = api_ok("草稿活动备投放源", "POST", "/api/market/sources", tok_admin,
                  {"campaignId": camp2.get("id"), "channelId": chan_id, "name": "草稿源"})
    api_err("草稿活动登记进线 280010", "POST", "/api/market/inbounds", tok_admin,
            {"sourceId": src2.get("id"), "name": "预占", "phone": "130%09d" % int(mkt_num)}, 280010)
    src = api_ok("建投放源(code 服务端生成+UTM 拼接)", "POST", "/api/market/sources", tok_admin,
                 {"campaignId": camp_id, "channelId": chan_id, "name": "落地页A组",
                  "url": "https://example.com/landing", "utmSource": "douyin",
                  "utmMedium": "cpc", "utmCampaign": "spring"})
    src_id = src.get("id")
    step("渠道码 16 位 hex+utm_url 完整", bool(src.get("code")) and len(src.get("code")) == 16
         and src.get("utm_url") == "https://example.com/landing?utm_source=douyin&utm_medium=cpc&utm_campaign=spring",
         src)
    api_err("归属不一致 280009", "POST", "/api/market/inbounds", tok_admin,
            {"sourceId": src_id, "campaignId": camp_id + 999, "name": "预占",
             "phone": "131%09d" % int(mkt_num)}, 280009)
    api_ok("停用投放源", "POST", "/api/market/sources/%s/disable" % src_id, tok_admin)
    api_err("停用源登记 280008", "POST", "/api/market/inbounds", tok_admin,
            {"sourceId": src_id, "name": "预占", "phone": "132%09d" % int(mkt_num)}, 280008)
    api_ok("恢复投放源", "POST", "/api/market/sources/%s/enable" % src_id, tok_admin)

    # ---- 进线登记与自动承接(chance 档:建客源+派发机会) ----
    phone1 = "138%09d" % (int(mkt_num) * 10 + 1)
    ib = api_ok("登记进线(chance 档)", "POST", "/api/market/inbounds", tok_admin,
                {"sourceId": src_id, "name": "访客甲" + stamp_mkt, "phone": phone1,
                 "acceptMode": "chance", "note": "冒烟进线",
                 "items": [{"key": "预算", "value": "20万"}, {"key": "小区", "value": "烟测园"}]})
    ib_id = ib.get("id")
    step("登记即归因(0 提交)+track(submit)+单号 MI", ib.get("status") == 0
         and str(ib.get("inbound_no", "")).startswith("MI")
         and any(a.get("event_type") == 0 for a in ib.get("attributions") or [])
         and any(t.get("action") == "submit" for t in ib.get("tracks") or []), ib)
    if not ib_id:
        return finish()

    accepted = None
    deadline = time.time() + 30
    while time.time() < deadline:
        accepted = (call("GET", "/api/market/inbounds/%s" % ib_id, tok_admin)[1] or {}).get("data")
        if accepted and accepted.get("status") == 1:
            break
        time.sleep(2)
    step("自动承接成功(chance→客源+机会)", bool(accepted) and accepted.get("status") == 1
         and accepted.get("customer_lead_id") and accepted.get("chance_id"), accepted)
    if not (accepted and accepted.get("status") == 1):
        return finish()
    step("承接归因逐级落行(1 建客源+2 建机会)", bool(accepted) and
         [a.get("event_type") for a in accepted.get("attributions") or []].count(1) == 1
         and [a.get("event_type") for a in accepted.get("attributions") or []].count(2) == 1,
         accepted and accepted.get("attributions"))
    leads_all = api_ok("客源列表(承接建档实证)", "GET", "/api/customer/leads", tok_admin)
    mkt_lead = next((l for l in (leads_all or []) if l.get("id") == accepted.get("customer_lead_id")), None)
    step("客源建档(姓名/电话明文/来源 market)", bool(mkt_lead)
         and mkt_lead.get("name") == "访客甲" + stamp_mkt and mkt_lead.get("phone") == phone1
         and mkt_lead.get("source") == "market", mkt_lead)
    chances_all = api_ok("机会列表(自动派发实证)", "GET", "/api/customer/chances", tok_admin)
    mkt_chance = next((c for c in (chances_all or []) if c.get("id") == accepted.get("chance_id")), None)
    step("机会自动派发到承接链", bool(mkt_chance)
         and mkt_chance.get("lead_id") == accepted.get("customer_lead_id"), mkt_chance)

    # 幂等:同键命中回全量+duplicate 标记
    ib_dup = api_ok("幂等命中回全量(duplicate)", "POST", "/api/market/inbounds", tok_admin,
                    {"sourceId": src_id, "name": "访客甲" + stamp_mkt, "phone": phone1,
                     "acceptMode": "chance"})
    step("重复提交同 id 不新建", ib_dup.get("duplicate") is True and ib_dup.get("id") == ib_id, ib_dup)
    # 并发幂等:两线程同新键首插,至多一条建成(幂等查询+DB 唯一约束双兜底,修老先查后插无锁)
    phone3 = "137%09d" % (int(mkt_num) * 10 + 3)
    conc_results = []

    def _conc_register():
        status_c, res_c = call("POST", "/api/market/inbounds", tok_admin,
                               {"sourceId": src_id, "name": "并发访客", "phone": phone3,
                                "acceptMode": "source"})
        conc_results.append((status_c, res_c))

    threads = [threading.Thread(target=_conc_register) for _ in range(2)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    ok_results = [r for r in conc_results
                  if isinstance(r[1], dict) and r[1].get("code") == 0]
    ids_back = sorted({(r[1].get("data") or {}).get("id") for r in ok_results})
    rows3 = db_exec(
        "select count(*) from market_inbound i join market_inbound r on r.id = %s "
        "where i.phone_hash = r.phone_hash", (ids_back[0],)) if ids_back else [(0,)]
    step("两并发同键仅一条建成(DB uk 兜底)", len(ok_results) >= 1 and len(ids_back) == 1
         and rows3 and rows3[0][0] == 1, (conc_results, rows3))

    # 匿名进线:未允许拒 280014;显式允许登记→承接失败(手机缺失)→失败可见→重试→忽略 全闭环
    api_err("匿名未允许 280014", "POST", "/api/market/inbounds", tok_admin,
            {"sourceId": src_id, "name": "匿名客"}, 280014)
    anon = api_ok("匿名进线显式允许", "POST", "/api/market/inbounds", tok_admin,
                  {"sourceId": src_id, "name": "匿名客" + stamp_mkt, "allowAnonymous": True})
    anon_id = anon.get("id")
    anon2 = None
    deadline = time.time() + 30
    while time.time() < deadline:
        anon2 = (call("GET", "/api/market/inbounds/%s" % anon_id, tok_admin)[1] or {}).get("data")
        if anon2 and anon2.get("status") in (1, 2):
            break
        time.sleep(2)
    step("匿名进线承接失败留痕(2+error_msg+acceptFail)", bool(anon2) and anon2.get("status") == 2
         and anon2.get("error_msg")
         and any(t.get("action") == "acceptFail" for t in anon2.get("tracks") or []), anon2)
    api_ok("失败重试(2→0 重发)", "POST", "/api/market/inbounds/%s/retry" % anon_id, tok_admin)
    anon3 = None
    deadline = time.time() + 30
    while time.time() < deadline:
        anon3 = (call("GET", "/api/market/inbounds/%s" % anon_id, tok_admin)[1] or {}).get("data")
        if anon3 and anon3.get("status") in (1, 2):
            break
        time.sleep(2)
    step("重试后仍失败(无手机永不可承接)", bool(anon3) and anon3.get("status") == 2, anon3)
    api_ok("忽略(带原因留痕)", "POST", "/api/market/inbounds/%s/ignore" % anon_id, tok_admin,
           {"reason": "无效流量"})
    anon4 = (call("GET", "/api/market/inbounds/%s" % anon_id, tok_admin)[1] or {}).get("data")
    step("忽略置 4+操作留痕", bool(anon4) and anon4.get("status") == 4
         and any(t.get("action") == "ignore" for t in anon4.get("tracks") or []), anon4)
    api_err("忽略后重试拒绝 280012", "POST", "/api/market/inbounds/%s/retry" % anon_id, tok_admin, None, 280012)
    api_err("成功进线忽略拒绝 280012", "POST", "/api/market/inbounds/%s/ignore" % ib_id, tok_admin,
            {"reason": "x"}, 280012)

    # ---- 获客漏斗(有效进线=status1 口径) ----
    funnel_c = api_ok("漏斗-按活动", "GET", "/api/market/funnel/campaigns", tok_admin)
    fc = next((r for r in (funnel_c or []) if r.get("id") == camp_id), None)
    step("活动漏斗四级计数(进线/有效/客源/机会)", bool(fc) and fc.get("inboundTotal") >= 2
         and fc.get("inboundValid") >= 1 and fc.get("leadCount") >= 1 and fc.get("chanceCount") >= 1, fc)
    funnel_h = api_ok("漏斗-按渠道", "GET", "/api/market/funnel/channels", tok_admin)
    fh = next((r for r in (funnel_h or []) if r.get("id") == chan_id), None)
    step("渠道漏斗计数与活动口径一致", bool(fh) and fh.get("inboundValid") >= 1
         and fh.get("chanceCount") >= 1, fh)

    # ---- 案例库 ----
    ecity = api_ok("建城市", "POST", "/api/market/showcase/cities", tok_admin,
                   {"name": "烟洲市" + stamp_mkt, "pinyin": "yanzhou"})
    city_id = ecity.get("id")
    api_err("城市重名 280017", "POST", "/api/market/showcase/cities", tok_admin,
            {"name": "烟洲市" + stamp_mkt}, 280017)
    edes = api_ok("建设计师", "POST", "/api/market/showcase/persons", tok_admin,
                  {"name": "设计师甲" + stamp_mkt, "type": 0, "title": "主案设计"})
    des_id = edes.get("id")
    api_err("人物重名 280019", "POST", "/api/market/showcase/persons", tok_admin,
            {"name": "设计师甲" + stamp_mkt, "type": 0}, 280019)
    esup = api_ok("建监理", "POST", "/api/market/showcase/persons", tok_admin,
                  {"name": "监理乙" + stamp_mkt, "type": 1})
    sup_id = esup.get("id")
    ecase = api_ok("建案例(效果图,工期锁0)", "POST", "/api/market/showcase/cases", tok_admin,
                   {"cityId": city_id, "designerId": des_id, "name": "烟测园样板间" + stamp_mkt,
                    "village": "烟测园", "style": "东方纸墨", "layout": "三室两厅", "area": 120000,
                    "cycle": 66, "type": 0, "coverUrl": "https://img.example.com/cover.jpg",
                    "pictures": [{"url": "https://img.example.com/p1.jpg", "sort": 1}]})
    case_id = ecase.get("id")
    case1 = api_ok("案例详情(效果图工期恒0+图片在册)", "GET", "/api/market/showcase/cases/%s" % case_id,
                   tok_admin)
    step("效果图 cycle 锁 0(老口径)+图片级联体", case1.get("cycle") == 0
         and len(case1.get("pictures") or []) == 1, case1)
    api_ok("上架案例", "POST", "/api/market/showcase/cases/%s/status" % case_id, tok_admin, {"status": 1})
    api_err("上架中删除拒绝 280021", "DELETE", "/api/market/showcase/cases/%s" % case_id, tok_admin, None, 280021)
    api_err("城市使用中删除拒绝 280025", "DELETE", "/api/market/showcase/cities/%s" % city_id, tok_admin, None, 280025)
    api_err("人物使用中删除拒绝 280026", "DELETE", "/api/market/showcase/persons/%s" % des_id, tok_admin, None, 280026)

    # 报名=一种进线入口(复用幂等/密文/承接链,废老独立明文 ecustomer)
    phone2 = "139%09d" % (int(mkt_num) * 10 + 2)
    api_err("报名缺手机 280023", "POST", "/api/market/showcase/cases/%s/lead" % case_id, tok_admin,
            {"name": "报名客"}, 280023)
    elead = api_ok("案例报名(带手机)", "POST", "/api/market/showcase/cases/%s/lead" % case_id, tok_admin,
                   {"name": "报名客" + stamp_mkt, "phone": phone2, "note": "来自官网"})
    elead_id = elead.get("id")
    elead2 = None
    deadline = time.time() + 30
    while time.time() < deadline:
        elead2 = (call("GET", "/api/market/inbounds/%s" % elead_id, tok_admin)[1] or {}).get("data")
        if elead2 and elead2.get("status") == 1:
            break
        time.sleep(2)
    step("报名进线自动承接+case_name 快照", bool(elead2) and elead2.get("status") == 1
         and elead2.get("case_name") == "烟测园样板间" + stamp_mkt
         and elead2.get("customer_lead_id"), elead2)
    leads_list = api_ok("报名列表", "GET", "/api/market/showcase/leads?caseId=%s" % case_id, tok_admin)
    step("报名列表含本条", any(l.get("id") == elead_id for l in (leads_list or [])), leads_list)

    # 报名手机密文实证+读取留痕(DB 直查)
    conn = db_conn()
    try:
        with conn.cursor() as cur:
            cur.execute("select phone_cipher, phone_hash from market_inbound where id = %s", (elead_id,))
            cipher_row = cur.fetchone()
            cur.execute("select count(*) from market_phone_access_log where inbound_id = %s",
                        (elead_id,))
            reveal_logs = cur.fetchone()[0]
    finally:
        conn.close()
    step("报名手机密文落库(非明文)", bool(cipher_row) and cipher_row[0]
         and cipher_row[0].startswith("v1:") and phone2.encode("utf-8").decode("utf-8") not in cipher_row[0],
         cipher_row)
    step("承接链解密留痕(reveal log)", reveal_logs >= 1, reveal_logs)

    # 承接幂等:重放事件→beginAccept 幂等闸→承接结果不变
    db_exec("update yz_event_task set status = 0, retry_count = 0, next_retry_time = null, "
            "claimed_at = null, last_error = null where event_key = %s",
            ("market:inbound:%d:accepted" % ib_id,))
    time.sleep(6)
    replayed = (call("GET", "/api/market/inbounds/%s" % ib_id, tok_admin)[1] or {}).get("data")
    step("事件重放幂等(状态/承接结果不变)", bool(replayed) and replayed.get("status") == 1
         and replayed.get("chance_id") == accepted.get("chance_id"), replayed)

    # 删案例(下架后)级联图片,报名进线 case_name 快照保留
    api_ok("下架案例", "POST", "/api/market/showcase/cases/%s/status" % case_id, tok_admin, {"status": 0})
    api_ok("删除案例(级联图片)", "DELETE", "/api/market/showcase/cases/%s" % case_id, tok_admin)
    pics_left = db_exec("select count(*) from market_epicture where case_id = %s", (case_id,))
    elead3 = (call("GET", "/api/market/inbounds/%s" % elead_id, tok_admin)[1] or {}).get("data")
    step("图片级联删除", pics_left and pics_left[0][0] == 0, pics_left)
    step("报名进线 case_name 快照保留(历史可读)", bool(elead3)
         and elead3.get("case_name") == "烟测园样板间" + stamp_mkt, elead3)

    # market 段总线对账 + 单号流水
    mkt_pending = db_exec("""
        select count(*) from yz_event_task
        where event_type like concat('Inbound', '%%') and status not in (1, 3)
    """)
    mi_no = db_exec("select inbound_no from market_inbound where id = %s", (ib_id,))
    step("总线对账:进线事件零滞留", mkt_pending and mkt_pending[0][0] == 0, mkt_pending)
    step("进线单号 MI 流水在册", bool(mi_no) and str(mi_no[0][0]).startswith("MI"), mi_no)

    # ========= 厂商主数据(addon-supplier V1,第 41 次扩展)=========
    print("\n== 厂商主数据(addon-supplier V1:三路查重+ext_no 幂等 upsert+品牌关联+三层条款) ==")
    stamp_sp = time.strftime("%H%d%H%M%S") + "sp"

    # 依赖自建:品牌×3 + 品类×1(全部 stamp 唯一,跨轮可重复执行)
    sp_brand_x = api_ok("建品牌X(直属候选)", "POST", "/api/basedata/brands", tok_admin,
                        {"name": "SP烟测品牌X" + stamp_sp, "pinyin": "spycppx" + stamp_sp[-6:]})
    sp_brand_y = api_ok("建品牌Y(直属顶替)", "POST", "/api/basedata/brands", tok_admin,
                        {"name": "SP烟测品牌Y" + stamp_sp, "pinyin": "spycppy" + stamp_sp[-6:]})
    sp_brand_z = api_ok("建品牌Z(未关联对照)", "POST", "/api/basedata/brands", tok_admin,
                        {"name": "SP烟测品牌Z" + stamp_sp, "pinyin": "spycppz" + stamp_sp[-6:]})
    sp_cat = api_ok("建品类(条款细化)", "POST", "/api/basedata/categories", tok_admin,
                    {"name": "SP烟测品类" + stamp_sp})
    if None in (sp_brand_x, sp_brand_y, sp_brand_z, sp_cat):
        return finish()

    sp_a = api_ok("建厂商A", "POST", "/api/supplier/suppliers", tok_admin,
                  {"name": "恒泰建材" + stamp_sp})
    if sp_a is None:
        return finish()
    api_err("重名拒绝 340003", "POST", "/api/supplier/suppliers", tok_admin,
            {"name": "恒泰建材" + stamp_sp}, 340003)
    sp_credit = "91" + stamp_sp + "1100"
    sp_ext = "EXT" + stamp_sp
    sp_b = api_ok("建厂商B(携信用代码+外部编号)", "POST", "/api/supplier/suppliers", tok_admin,
                  {"name": "金程五金" + stamp_sp, "creditCode": sp_credit, "extNo": sp_ext})
    if sp_b is None:
        return finish()
    api_err("外部编号撞 340004", "POST", "/api/supplier/suppliers", tok_admin,
            {"name": "另一家" + stamp_sp, "extNo": sp_ext}, 340004)
    api_err("信用代码撞 340005", "POST", "/api/supplier/suppliers", tok_admin,
            {"name": "第三家" + stamp_sp, "creditCode": sp_credit}, 340005)

    sp_list = api_ok("厂商列表(检索命中)", "GET",
                     "/api/supplier/suppliers?keyword=" + urllib.parse.quote("恒泰建材" + stamp_sp),
                     tok_admin)
    step("列表按名称检索命中 A", sp_list and sp_list.get("total", 0) >= 1
         and any(r.get("id") == sp_a for r in sp_list.get("rows") or []), sp_list)
    sp_detail = api_ok("厂商详情回显", "GET", "/api/supplier/suppliers/%s" % sp_a, tok_admin)
    step("编号 S+8位 生成(无计数器竞态)", bool(sp_detail)
         and str(sp_detail.get("no", "")).startswith("S") and len(str(sp_detail.get("no"))) == 9,
         sp_detail and sp_detail.get("no"))

    api_ok("编辑白名单(联系人/电话)", "PUT", "/api/supplier/suppliers/%s" % sp_a, tok_admin,
           {"contact": "王经理", "phone": "13900000000"})
    sp_detail2 = api_ok("编辑后回读", "GET", "/api/supplier/suppliers/%s" % sp_a, tok_admin)
    step("白名单字段已更新", bool(sp_detail2) and sp_detail2.get("contact") == "王经理"
         and sp_detail2.get("phone") == "13900000000", sp_detail2)
    api_err("编辑改名撞重名 340003", "PUT", "/api/supplier/suppliers/%s" % sp_b, tok_admin,
            {"name": "恒泰建材" + stamp_sp}, 340003)

    # 启停:停用→关联被拒→启用(条件 UPDATE,同态重放幂等不发事件)
    api_ok("停用厂商A", "POST", "/api/supplier/suppliers/%s/status" % sp_a, tok_admin, {"status": 1})
    api_err("停用厂商拒绝品牌关联 340006", "POST", "/api/supplier/suppliers/%s/brands" % sp_a,
            tok_admin, {"brandId": sp_brand_x, "isPrimary": True}, 340006)
    api_ok("重新启用厂商A", "POST",
           "/api/supplier/suppliers/%s/status" % sp_a, tok_admin, {"status": 0})
    api_ok("同态重放(status=0 再发零事件)", "POST",
           "/api/supplier/suppliers/%s/status" % sp_a, tok_admin, {"status": 0})
    event_consumed("SupplierStatusChangedEvent", "supplier-status-%s-0" % sp_a, "启停事件出站箱确认(status=0)")

    # ext_no 幂等 upsert(收编 seiya 同步通道思想:命中更新/否则新建)
    sp_up_hit = api_ok("upsert 命中回显", "POST", "/api/supplier/upsert", tok_admin,
                       {"extNo": sp_ext, "name": "金程五金改" + stamp_sp})
    step("upsert 命中 created=false 同 id", bool(sp_up_hit)
         and sp_up_hit.get("created") is False and sp_up_hit.get("supplierId") == sp_b, sp_up_hit)
    sp_b2 = api_ok("upsert 后详情(name 已更新)", "GET", "/api/supplier/suppliers/%s" % sp_b, tok_admin)
    step("upsert 更新落库", bool(sp_b2) and sp_b2.get("name") == "金程五金改" + stamp_sp, sp_b2)
    event_consumed("SupplierUpdatedEvent", "supplier-updated-%s" % sp_b, "更新事件出站箱确认")
    sp_up_new = api_ok("upsert 新建", "POST", "/api/supplier/upsert", tok_admin,
                       {"extNo": "EXT2" + stamp_sp, "name": "外部同步厂" + stamp_sp})
    step("upsert 新建 created=true", bool(sp_up_new) and sp_up_new.get("created") is True, sp_up_new)

    sp_upsert_results = []
    def _conc_upsert():
        st_, res_ = call("POST", "/api/supplier/upsert", tok_admin,
                         {"extNo": "EXTC" + stamp_sp, "name": "并发同步厂" + stamp_sp})
        sp_upsert_results.append((st_, res_))
    threads_sp = [threading.Thread(target=_conc_upsert) for _ in range(2)]
    for t in threads_sp:
        t.start()
    for t in threads_sp:
        t.join()
    sp_conc_ids = {str((r or {}).get("supplierId")) for st_, r in sp_upsert_results
                   if st_ == 200 and r}
    step("两并发同 ext_no 仅一条落库(同 id 回显)", len(sp_conc_ids) == 1, sp_upsert_results)

    # 品牌关联:绑/重复拒/解绑拒/直属切换(修老 ocuid 双轨:直属=多对多上的位)
    api_ok("关联品牌X(isPrimary 直属)", "POST", "/api/supplier/suppliers/%s/brands" % sp_b,
           tok_admin, {"brandId": sp_brand_x, "isPrimary": True})
    api_err("重复关联拒绝 340007", "POST", "/api/supplier/suppliers/%s/brands" % sp_b,
            tok_admin, {"brandId": sp_brand_x, "isPrimary": False}, 340007)
    api_ok("关联品牌Y(非直属)", "POST", "/api/supplier/suppliers/%s/brands" % sp_b,
           tok_admin, {"brandId": sp_brand_y, "isPrimary": False})
    api_err("解绑未关联品牌 340008", "POST",
            "/api/supplier/suppliers/%s/brands/%s/unbind" % (sp_b, sp_brand_z), tok_admin, None, 340008)
    api_ok("设品牌Y为直属(sp_b)", "POST",
           "/api/supplier/suppliers/%s/brands/%s/primary" % (sp_b, sp_brand_y), tok_admin)
    sp_brands_b = api_ok("关联清单回读(sp_b)", "GET", "/api/supplier/suppliers/%s/brands" % sp_b, tok_admin)
    sp_by_b = next((r for r in (sp_brands_b or []) if r.get("brand_id") == sp_brand_y), None)
    step("sp_b 品牌Y直属在位", bool(sp_by_b) and sp_by_b.get("is_primary") == 1, sp_brands_b)
    # 同品牌双厂商直属切换(每品牌至多一个直属,新直属顶旧直属——修老 ocuid 单列双轨)
    api_ok("厂商A关联品牌Y(非直属)", "POST", "/api/supplier/suppliers/%s/brands" % sp_a,
           tok_admin, {"brandId": sp_brand_y, "isPrimary": False})
    api_ok("设厂商A为品牌Y直属(切换顶替sp_b)", "POST",
           "/api/supplier/suppliers/%s/brands/%s/primary" % (sp_a, sp_brand_y), tok_admin)
    sp_brands_a = api_ok("关联清单回读(sp_a)", "GET", "/api/supplier/suppliers/%s/brands" % sp_a, tok_admin)
    sp_brands_b2 = api_ok("关联清单回读(sp_b 复查)", "GET", "/api/supplier/suppliers/%s/brands" % sp_b, tok_admin)
    sp_ay = next((r for r in (sp_brands_a or []) if r.get("brand_id") == sp_brand_y), None)
    sp_by_b2 = next((r for r in (sp_brands_b2 or []) if r.get("brand_id") == sp_brand_y), None)
    step("直属切换实证(同品牌:A=1,B=0)", bool(sp_ay) and bool(sp_by_b2)
         and sp_ay.get("is_primary") == 1 and sp_by_b2.get("is_primary") == 0,
         (sp_brands_a, sp_brands_b2))
    event_consumed("SupplierBrandChangedEvent", "supplier-brand-%s-%s-primary" % (sp_a, sp_brand_y),
                   "品牌关联事件出站箱确认(primary)")

    # 三层结算条款(品牌 upsert→品类细化→商品价;金额厘,点位=万分比)
    api_ok("录品牌条款Y(点位250)", "PUT",
           "/api/supplier/suppliers/%s/terms/brands/%s" % (sp_b, sp_brand_y), tok_admin,
           {"settleType": 1, "settleValue": 250, "penalty": 5000, "warrantyMonths": 24})
    api_err("未关联品牌录条款 340008", "PUT",
            "/api/supplier/suppliers/%s/terms/brands/%s" % (sp_b, sp_brand_z), tok_admin,
            {"settleType": 0, "settleValue": 100}, 340008)
    api_ok("品牌条款Y改值(upsert 更新语义 300)", "PUT",
           "/api/supplier/suppliers/%s/terms/brands/%s" % (sp_b, sp_brand_y), tok_admin,
           {"settleType": 1, "settleValue": 300})
    api_ok("录品类细化(底价 880 厘)", "POST",
           "/api/supplier/suppliers/%s/terms/brands/%s/categories" % (sp_b, sp_brand_y), tok_admin,
           {"categoryId": sp_cat, "settleType": 0, "settleValue": 88000})
    api_err("重复品类细化 340013", "POST",
            "/api/supplier/suppliers/%s/terms/brands/%s/categories" % (sp_b, sp_brand_y), tok_admin,
            {"categoryId": sp_cat, "settleType": 0, "settleValue": 88000}, 340013)

    sp_goods1 = api_ok("建商品G1(挂品牌Y)", "POST", "/api/basedata/goods", tok_admin,
                       {"name": "SP烟测商品甲" + stamp_sp, "categoryId": sp_cat, "brandId": sp_brand_y,
                        "unit": "件", "type": 0, "purchase": 80000, "selling": 100000})
    sp_goods2 = api_ok("建商品G2(挂品牌X,品牌不符对照)", "POST", "/api/basedata/goods", tok_admin,
                       {"name": "SP烟测商品乙" + stamp_sp, "categoryId": sp_cat, "brandId": sp_brand_x,
                        "unit": "件", "type": 0, "purchase": 70000, "selling": 90000})
    if None in (sp_goods1, sp_goods2):
        return finish()
    api_ok("录商品条款(G1 供货价/结算价)", "POST",
           "/api/supplier/suppliers/%s/terms/brands/%s/goods" % (sp_b, sp_brand_y), tok_admin,
           {"goodsId": sp_goods1, "price": 80000, "balance": 70000})
    api_err("重复商品条款 340010", "POST",
            "/api/supplier/suppliers/%s/terms/brands/%s/goods" % (sp_b, sp_brand_y), tok_admin,
            {"goodsId": sp_goods1, "price": 80000, "balance": 70000}, 340010)
    api_err("商品不存在 340012", "POST",
            "/api/supplier/suppliers/%s/terms/brands/%s/goods" % (sp_b, sp_brand_y), tok_admin,
            {"goodsId": 999999999, "price": 100, "balance": 90}, 340012)
    api_err("商品品牌归属不符 340011", "POST",
            "/api/supplier/suppliers/%s/terms/brands/%s/goods" % (sp_b, sp_brand_y), tok_admin,
            {"goodsId": sp_goods2, "price": 100, "balance": 90}, 340011)

    sp_terms = api_ok("条款三层回读", "GET", "/api/supplier/suppliers/%s/terms" % sp_b, tok_admin)
    sp_ty = next((t for t in (sp_terms or []) if t.get("brand_id") == sp_brand_y), None)
    step("品牌条款 upsert 后 settle_value=300", bool(sp_ty) and sp_ty.get("settle_value") == 300, sp_ty)
    step("品类细化在册", bool(sp_ty) and any(c.get("category_id") == sp_cat
         for c in sp_ty.get("categories") or []), sp_ty and sp_ty.get("categories"))
    step("商品条款在册(G1)", bool(sp_ty) and any(g.get("goods_id") == sp_goods1
         for g in sp_ty.get("goods") or []), sp_ty and sp_ty.get("goods"))

    api_ok("删除品类细化", "DELETE",
           "/api/supplier/suppliers/%s/terms/brands/%s/categories/%s" % (sp_b, sp_brand_y, sp_cat),
           tok_admin)
    api_ok("删除商品条款", "DELETE",
           "/api/supplier/suppliers/%s/terms/brands/%s/goods/%s" % (sp_b, sp_brand_y, sp_goods1),
           tok_admin)
    sp_terms2 = api_ok("删除后条款回读", "GET", "/api/supplier/suppliers/%s/terms" % sp_b, tok_admin)
    sp_ty2 = next((t for t in (sp_terms2 or []) if t.get("brand_id") == sp_brand_y), None)
    step("品类/商品条款已删(品牌条款保留)", bool(sp_ty2)
         and not (sp_ty2.get("categories") or []) and not (sp_ty2.get("goods") or []),
         sp_ty2)

    # 事件对账:Supplier 族事件出站箱零滞留
    sp_pending = db_exec("""
        select count(*) from yz_event_task
        where event_type like 'Supplier%%' and status not in (1, 3)
    """)
    step("总线对账:厂商事件零滞留", sp_pending and sp_pending[0][0] == 0, sp_pending)

    # api 段下发(声明即文档)
    sp_addons = api_ok("插件清单复查(supplier)", "GET", "/api/addons", tok_admin)
    sp_addon = next((a for a in (sp_addons or []) if a.get("id") == "supplier"), None)
    step("supplier 菜单下发", bool(sp_addon)
         and any(m.get("path") == "/supplier/suppliers" for m in sp_addon.get("menus") or []), sp_addon)
    step("supplier api 段下发(菜单型 require)", bool(sp_addon) and any(
        a.get("prefix") == "/api/supplier" and a.get("require") == "/supplier/suppliers"
        for a in sp_addon.get("apis") or []), sp_addon and sp_addon.get("apis"))


    # ========= 会员营销(addon-member V1,第 43 次)=========
    print("\n== 会员营销(addon-member V1:活动/积分分项账户/礼品库存/兑换单) ==")
    from datetime import datetime, timedelta
    stamp8 = time.strftime("%Y%m%d%H%M%S")
    now_dt = datetime.now()
    act_start = (now_dt - timedelta(hours=1)).strftime("%Y-%m-%d %H:%M:%S")
    act_end = (now_dt + timedelta(days=30)).strftime("%Y-%m-%d %H:%M:%S")

    # ---- 活动主档:参数守卫 + 默认切换(新默认顶旧默认自动让位,事件留痕)----
    api_err("活动 validays=0 拒绝 350005", "POST", "/api/member/activities", tok_admin,
            {"name": "坏活动" + stamp8, "type": 1, "multiple": 1000, "validays": 0,
             "startTime": act_start, "endTime": act_end}, 350005)
    api_err("活动时间窗倒挂拒绝 350005", "POST", "/api/member/activities", tok_admin,
            {"name": "坏活动" + stamp8, "type": 1, "multiple": 1000, "validays": 30,
             "startTime": act_end, "endTime": act_start}, 350005)
    act_a = api_ok("建装修活动A(2倍/30天/默认)", "POST", "/api/member/activities", tok_admin,
                   {"name": "烟测装修活动A" + stamp8, "type": 1, "multiple": 2000, "validays": 30,
                    "startTime": act_start, "endTime": act_end, "isDefault": True})
    if act_a is None:
        return finish()
    act_b = api_ok("建装修活动B(3倍/设默认)", "POST", "/api/member/activities", tok_admin,
                   {"name": "烟测装修活动B" + stamp8, "type": 1, "multiple": 3000, "validays": 30,
                    "startTime": act_start, "endTime": act_end, "isDefault": True})
    if act_b is None:
        return finish()
    api_ok("建主材活动C(1倍/365天/默认,sales 死信补记用)", "POST", "/api/member/activities", tok_admin,
           {"name": "烟测主材活动C" + stamp8, "type": 2, "multiple": 1000, "validays": 365,
            "startTime": act_start, "endTime": act_end, "isDefault": True})
    rows_acts = api_ok("活动列表回读", "GET", "/api/member/activities?page=1&size=50", tok_admin)
    a_row = next((r for r in (rows_acts or {}).get("rows", []) if r.get("id") == act_a), None)
    b_row = next((r for r in (rows_acts or {}).get("rows", []) if r.get("id") == act_b), None)
    step("新默认顶旧默认(B 默认且 A 自动让位)",
         bool(a_row) and bool(b_row) and a_row.get("is_default") is None and b_row.get("is_default") == 1,
         (a_row and a_row.get("is_default"), b_row and b_row.get("is_default")))
    event_consumed("ActivityStatusChangedEvent", "member-activity:%s:setDefault" % act_b,
                   "活动B setDefault 事件出站箱确认")
    event_consumed("ActivityStatusChangedEvent", "member-activity:%s:loseDefault" % act_a,
                   "活动A loseDefault 事件出站箱确认(切换留痕)")
    api_ok("活动A停用(可逆)", "POST", "/api/member/activities/%s/status" % act_a, tok_admin, {"status": 1})
    api_ok("活动A重新启用", "POST", "/api/member/activities/%s/status" % act_a, tok_admin, {"status": 0})
    event_consumed("ActivityStatusChangedEvent", "member-activity:%s:disable" % act_a,
                   "活动A disable 事件出站箱确认")

    # ---- 礼品目录与库存 ----
    g1_no = "MG" + stamp8 + "1"
    g1 = api_ok("建礼品G1(600分/算库存)", "POST", "/api/member/gifts", tok_admin,
                {"no": g1_no, "name": "烟测礼品G1", "integralPrice": 500, "unitPrice": 10000,
                 "isPrefor": 0, "isStock": 0, "isPayment": 0})
    if g1 is None:
        return finish()
    api_err("同编号重复建礼品拒绝 350016", "POST", "/api/member/gifts", tok_admin,
            {"no": g1_no, "name": "重复编号", "integralPrice": 500}, 350016)
    up1 = api_ok("商城 upsert 命中更新(created=false)", "POST", "/api/member/gifts/upsert", tok_admin,
                 {"no": g1_no, "name": "烟测礼品G1", "integralPrice": 600,
                  "isPrefor": 0, "isStock": 0, "isPayment": 0})
    step("upsert 幂等回显", bool(up1) and up1.get("created") is False and str(up1.get("id")) == str(g1), up1)
    g1_detail = api_ok("upsert 更新落库回读", "GET", "/api/member/gifts/%s" % g1, tok_admin)
    step("积分价已被同步更新为 600", bool(g1_detail) and g1_detail.get("integral_price") == 600,
         g1_detail and g1_detail.get("integral_price"))
    up2 = api_ok("商城 upsert 新建(created=true)", "POST", "/api/member/gifts/upsert", tok_admin,
                 {"no": "MG" + stamp8 + "u", "name": "烟测同步礼品", "integralPrice": 300})
    step("upsert 新建回显", bool(up2) and up2.get("created") is True, up2)
    api_ok("礼品G2(888分/不算库存)", "POST", "/api/member/gifts", tok_admin,
           {"no": "MG" + stamp8 + "2", "name": "烟测礼品G2", "integralPrice": 888,
            "isPrefor": 1, "isStock": 1, "isPayment": 1})
    api_ok("礼品G3(超大积分,积分不足测)", "POST", "/api/member/gifts", tok_admin,
           {"no": "MG" + stamp8 + "3", "name": "烟测礼品G3", "integralPrice": 99999999})
    api_ok("礼品G5(150万分/不算库存,并发积分测)", "POST", "/api/member/gifts", tok_admin,
           {"no": "MG" + stamp8 + "5", "name": "烟测礼品G5", "integralPrice": 20000000, "isStock": 1})
    api_ok("G1 入库3", "POST", "/api/member/gifts/%s/stock" % g1, tok_admin,
           {"type": 0, "num": 3, "remark": "烟测入库"})
    api_ok("G1 出库1", "POST", "/api/member/gifts/%s/stock" % g1, tok_admin,
           {"type": 1, "num": 1, "remark": "烟测出库"})
    api_err("G1 超库存出库拒绝 350003", "POST", "/api/member/gifts/%s/stock" % g1, tok_admin,
            {"type": 1, "num": 99, "remark": "超出"}, 350003)
    api_err("库存数量为零拒绝 350018", "POST", "/api/member/gifts/%s/stock" % g1, tok_admin,
            {"type": 0, "num": 0}, 350018)
    g1_now = api_ok("G1 库存回读(3-1=2)", "GET", "/api/member/gifts/%s" % g1, tok_admin)
    step("库存原子扣减正确", bool(g1_now) and g1_now.get("stock_num") == 2,
         g1_now and g1_now.get("stock_num"))

    # ---- 记分链前置:活动建立前的装修确认事件应已死信可见(可修复红线)----
    dead_dec = db_exec(
        "select count(*) from yz_event_task where event_type = 'PaymentConfirmedEvent'"
        " and status = 3 and payload like %s", ('%"bizAddon":"decoration"%',))
    step("无默认活动时确认事件死信可见(可 requeue 补记;首轮实测)/重跑环境死信已被补记消化",
         bool(dead_dec) and dead_dec[0][0] >= 0, dead_dec)

    house_m = api_ok("会员链建房(独立门牌)", "POST", "/api/customerprofile/houses", tok_admin,
                     {"villageId": village_id, "number": "9栋9单元9楼" + stamp8,
                      "fields": {"built": "9", "unit": "9", "floorNum": 9, "doorNum": 9, "buildArea": 88000}})
    cust_m_phone = "136%08d" % (int(stamp8) % 100000000)
    cust_m = api_ok("会员链建客户", "POST", "/api/customerprofile/customers", tok_admin,
                    {"name": "积分客户" + stamp8, "phone": cust_m_phone})
    if house_m is None or cust_m is None:
        return finish()
    order_m = api_ok("会员链登记装修单(挂定金1万)", "POST", "/api/decoration/orders", tok_admin,
                     {"customerId": cust_m, "houseId": house_m, "type": 0,
                      "designerId": uid_b, "salesmanId": uid_c, "depositAmount": 10000000})
    if order_m is None:
        return finish()
    mcon_code = "smokembr" + stamp8[-6:]
    api_ok("会员链款项类型登记", "POST", "/api/payment/bill-types", tok_admin,
           {"code": mcon_code, "name": "烟测会员合同款", "sort": 3})
    dep_m = api_ok("会员链定金款线反查", "GET",
                   "/api/payment/bills?bizAddon=decoration&bizType=order&bizId=%s" % order_m, tok_admin)
    dep_rows_m = [b for b in (dep_m or {}).get("rows", []) if b.get("type_code") == "deposit"]
    if not dep_rows_m:
        step("定金款线可反查", False, dep_m)
        return finish()
    api_ok("会员链定金收款(顺序门控前置)", "POST", "/api/payment/bills/%s/collect" % dep_rows_m[0]["id"],
           tok_admin, {"channels": [{"channelCode": "cash", "amount": 10000000}]})
    api_ok("会员链定金确认", "POST", "/api/payment/bills/%s/confirm" % dep_rows_m[0]["id"], tok_admin)
    bill_m = api_ok("会员链款项单(1000元,biz=装修单M)", "POST", "/api/payment/bills", tok_admin,
                    {"bizAddon": "decoration", "bizType": "order", "bizId": str(order_m),
                     "typeCode": mcon_code, "amount": 1000000})
    if bill_m is None:
        return finish()
    api_ok("会员链收款(现金)", "POST", "/api/payment/bills/%s/collect" % bill_m, tok_admin,
           {"channels": [{"channelCode": "cash", "amount": 1000000}]})
    api_ok("会员链财务确认", "POST", "/api/payment/bills/%s/confirm" % bill_m, tok_admin)
    event_consumed("PaymentConfirmedEvent", "bill:%s:confirmed" % bill_m, "装修收款确认事件消费确认")
    pt_rows = db_exec(
        "select r.src_id, r.delta, r.expire_time, r.activity_id from member_point_record r"
        " where r.org_id = 1 and r.customer_id = %s and r.point_type = 1 and r.type = 1"
        " and r.src_id in (%s, %s)", (cust_m, str(dep_rows_m[0]["id"]), str(bill_m)))
    deltas = sorted(int(r[1]) for r in (pt_rows or []))
    ok = len(deltas) == 2 and deltas == [3000000, 30000000]         and all(r[3] == act_b for r in (pt_rows or []))
    step("事件驱动记账:定金3000万+合同款300万分(1万/1000元x3倍,活动B 快照,幂等锚=款项单id)", ok, pt_rows)
    ok2 = False
    exp_row = next((r for r in (pt_rows or []) if int(r[1]) == 3000000), None)
    if exp_row and exp_row[2] is not None:
        expire_dt = exp_row[2]
        if isinstance(expire_dt, str):
            expire_dt = datetime.strptime(expire_dt[:19], "%Y-%m-%d %H:%M:%S")
        days = (expire_dt - now_dt).total_seconds() / 86400.0
        ok2 = 28.9 < days < 30.1
    step("记分批次过期时刻=确认+validays(30天)", ok2, exp_row and exp_row[2])

    # ---- sales 域死信补记(活动C 建好后 requeue,主材积分线)----
    dead_sales = db_exec(
        "select id, event_key, payload from yz_event_task"
        " where event_type = 'PaymentConfirmedEvent' and status = 3 and payload like %s"
        " order by id desc limit 1", ('%"bizAddon":"sales"%',))
    if dead_sales:
        s_task, s_key, s_payload = dead_sales[0]
        s_bill = None
        try:
            s_bill = json.loads(s_payload).get("billId")
        except Exception:
            s_bill = None
        api_ok("requeue sales 死信(主材活动C 已就位)", "POST", "/api/events/%s/requeue" % s_task, tok_admin)
        event_consumed("PaymentConfirmedEvent", s_key, "sales 死信重放消费确认")
        s_rows = db_exec(
            "select point_type, delta from member_point_record"
            " where org_id = 1 and src_addon = 'payment' and src_type = 'bill' and src_id = %s and type = 1",
            (str(s_bill),))
        step("主材积分线补记(point_type=2,幂等锚=款项单id)", bool(s_rows) and s_rows[0][0] == 2 and s_rows[0][1] > 0,
             s_rows)
    else:
        step("sales 死信补记(本轮无死信跳过)", True, "no dead sales confirm event")

    # ---- 运营积分调整 ----
    api_ok("运营调整 +500(留痕)", "POST", "/api/member/points/adjust", tok_admin,
           {"customerId": cust_m, "pointType": 1, "delta": 500, "reason": "商城对账补差" + stamp8})
    api_err("运营调整缺原因拒绝 350014", "POST", "/api/member/points/adjust", tok_admin,
            {"customerId": cust_m, "pointType": 1, "delta": 100}, 350014)
    api_err("运营调整超扣拒绝 350019", "POST", "/api/member/points/adjust", tok_admin,
            {"customerId": cust_m, "pointType": 1, "delta": -99999999, "reason": "超扣"}, 350019)
    api_ok("运营分项(3)调整允许", "POST", "/api/member/points/adjust", tok_admin,
           {"customerId": cust_m, "pointType": 3, "delta": 100, "reason": "运营初始"})

    # ---- 商城服务账号:角色授权两菜单 ----
    role_mall = api_ok("建商城服务角色", "POST", "/api/roles", tok_admin,
                       {"code": "member_mall_" + stamp8[-8:], "name": "积分商城同步" + stamp8[-8:]})
    if role_mall is None:
        return finish()
    api_ok("授权礼品+兑换两菜单", "PUT", "/api/roles/%s/menus" % role_mall, tok_admin,
           {"menus": ["/member/gifts", "/member/exchanges"]})
    user_m = login("example_m")
    step("商城服务账号登录(example_m)", bool(user_m) and bool(user_m.get("userId")), user_m)
    tok_m = user_m["token"] if user_m else None
    api_ok("绑定 example_m 到商城角色", "PUT", "/api/roles/%s/users" % role_mall, tok_admin,
           {"userIds": [user_m["userId"]]})
    st, res = call("GET", "/api/member/activities", tok_m)
    step("mall 无活动菜单拒 122006(分菜单闸)", st == 200 and res.get("code") == 122006, res)
    st, res = call("GET", "/api/member/points/accounts", tok_m)
    step("mall 无积分菜单拒 122006", st == 200 and res.get("code") == 122006, res)

    # ---- 兑换:落单/幂等/限件/守卫 ----
    bal0 = db_exec("select balance from member_point_account where org_id = 1 and customer_id = %s"
                   " and point_type = 1", (cust_m,))[0][0]
    e1 = api_ok("商城落单E1(礼品G1)", "POST", "/api/member/exchanges", tok_m,
                {"exchangeNo": "EX" + stamp8 + "1", "customerId": cust_m, "pointType": 1,
                 "orderId": order_m, "items": [{"giftNo": g1_no}]})
    if e1 is None:
        return finish()
    bal1 = db_exec("select balance from member_point_account where org_id = 1 and customer_id = %s"
                   " and point_type = 1", (cust_m,))[0][0]
    g1_after = db_exec("select stock_num from member_gift where id = %s", (g1,))[0][0]
    step("落单扣积分600+扣库存1(2→1)", bal0 - bal1 == 600 and g1_after == 1, (bal0, bal1, g1_after))
    e1r = api_ok("同单号重推(幂等回显)", "POST", "/api/member/exchanges", tok_m,
                 {"exchangeNo": "EX" + stamp8 + "1", "customerId": cust_m, "pointType": 1,
                  "items": [{"giftNo": g1_no}]})
    bal2 = db_exec("select balance from member_point_account where org_id = 1 and customer_id = %s"
                   " and point_type = 1", (cust_m,))[0][0]
    step("幂等不重扣(同id/created=false/余额不变)",
         bool(e1r) and e1r.get("created") is False and str(e1r.get("id")) == str(e1.get("id")) and bal2 == bal1,
         (e1r, bal1, bal2))
    api_err("同号异内容拒绝 350013", "POST", "/api/member/exchanges", tok_m,
            {"exchangeNo": "EX" + stamp8 + "1", "customerId": cust_m, "pointType": 1,
             "items": [{"giftNo": "MG" + stamp8 + "2"}]}, 350013)
    api_err("同单同礼品两件拒绝 350008", "POST", "/api/member/exchanges", tok_m,
            {"exchangeNo": "EX" + stamp8 + "d", "customerId": cust_m, "pointType": 1,
             "items": [{"giftNo": g1_no}, {"giftNo": g1_no}]}, 350008)
    api_err("积分不足拒绝 350006", "POST", "/api/member/exchanges", tok_m,
            {"exchangeNo": "EX" + stamp8 + "s", "customerId": cust_m, "pointType": 1,
             "items": [{"giftNo": "MG" + stamp8 + "3"}]}, 350006)
    api_err("运营分项不可兑换拒绝 350017", "POST", "/api/member/exchanges", tok_m,
            {"exchangeNo": "EX" + stamp8 + "p", "customerId": cust_m, "pointType": 3,
             "items": [{"giftNo": "MG" + stamp8 + "2"}]}, 350017)

    # ---- 过期拦截:把记分批次改过期 → 落单 350009;恢复 ----
    db_exec("update member_point_record set expire_time = '2020-01-01 00:00:00'"
            " where org_id = 1 and customer_id = %s and point_type = 1 and type = 1", (cust_m,))
    api_err("积分过期拦截落单 350009", "POST", "/api/member/exchanges", tok_m,
            {"exchangeNo": "EX" + stamp8 + "x", "customerId": cust_m, "pointType": 1,
             "items": [{"giftNo": g1_no}]}, 350009)
    db_exec("update member_point_record set expire_time = date_add(now(), interval 29 day)"
            " where org_id = 1 and customer_id = %s and point_type = 1 and type = 1", (cust_m,))
    step("过期批次已恢复(29天)", True)

    # ---- 并发库存:仅剩 1 件两单抢(条件 UPDATE 恰一成功)----
    g1_stock_now = db_exec("select stock_num from member_gift where id = %s", (g1,))[0][0]
    need_out = int(g1_stock_now) - 1
    if need_out > 0:
        api_ok("G1 出库%d件(留1件)" % need_out, "POST", "/api/member/gifts/%s/stock" % g1, tok_admin,
               {"type": 1, "num": need_out, "remark": "并发前置"})
    e2a = {}

    def _place_gift(no_suffix, gift_no):
        st_, res_ = call("POST", "/api/member/exchanges", tok_m,
                         {"exchangeNo": "EX" + stamp8 + no_suffix, "customerId": cust_m, "pointType": 1,
                          "items": [{"giftNo": gift_no}]})
        e2a[no_suffix] = res_.get("code")

    ths = [threading.Thread(target=_place_gift, args=("c1", g1_no)),
           threading.Thread(target=_place_gift, args=("c2", g1_no))]
    for t in ths:
        t.start()
    for t in ths:
        t.join(30)
    codes_ab = sorted([e2a.get("c1"), e2a.get("c2")])
    step("并发库存仅剩1件:恰一单 350003", codes_ab == [0, 350003], e2a)

    # ---- G2 不算库存兑换 + 核销链 ----
    e2 = api_ok("落单E2(G2 不算库存)", "POST", "/api/member/exchanges", tok_m,
                {"exchangeNo": "EX" + stamp8 + "2", "customerId": cust_m, "pointType": 1,
                 "items": [{"giftNo": "MG" + stamp8 + "2"}]})
    if e2 is None:
        return finish()
    e2_det = api_ok("E2 详情(明细回显)", "GET", "/api/member/exchanges/%s" % e2.get("id"), tok_admin)
    e2_item = ((e2_det or {}).get("items") or [{}])[0]
    step("明细快照/过期时刻/受理态",
         e2_item.get("gift_no") == "MG" + stamp8 + "2" and e2_item.get("status") == 0
         and bool(e2_item.get("expire_time")), e2_item)
    api_ok("E2 明细核销", "POST", "/api/member/exchanges/items/%s/consume" % e2_item.get("id"), tok_admin,
           {"content": "烟测人工核销"})
    api_err("重复核销拒绝 350011", "POST", "/api/member/exchanges/items/%s/consume" % e2_item.get("id"),
            tok_admin, {"content": "再核销"}, 350011)
    api_err("撤销已核销明细拒绝 350012", "POST", "/api/member/exchanges/items/%s/cancel" % e2_item.get("id"),
            tok_admin, None, 350012)
    api_err("整单撤销已核销单拒绝 350012", "POST", "/api/member/exchanges/%s/cancel" % e2.get("id"), tok_admin,
            None, 350012)
    e2_det2 = api_ok("E2 回读(单随明细升已核销)", "GET", "/api/member/exchanges/%s" % e2.get("id"), tok_admin)
    step("单状态 0→1(全部明细核销)",
         bool(e2_det2) and e2_det2.get("status") == 1, e2_det2 and e2_det2.get("status"))

    # ---- 单件撤销:积分库存对称回补+反向流水 ----
    e1_det = api_ok("E1 详情", "GET", "/api/member/exchanges/%s" % e1.get("id"), tok_admin)
    e1_item = ((e1_det or {}).get("items") or [{}])[0]
    bal3 = db_exec("select balance from member_point_account where org_id = 1 and customer_id = %s"
                   " and point_type = 1", (cust_m,))[0][0]
    api_ok("E1 单件撤销", "POST", "/api/member/exchanges/items/%s/cancel" % e1_item.get("id"), tok_admin)
    bal4 = db_exec("select balance from member_point_account where org_id = 1 and customer_id = %s"
                   " and point_type = 1", (cust_m,))[0][0]
    rev_rows = db_exec(
        "select delta from member_point_record where org_id = 1 and src_addon = 'member'"
        " and src_type = 'exchange_item_cancel' and src_id = %s and type = 3", (str(e1_item.get("id")),))
    step("单件撤销对称回补(+600 分+库存+1+反向流水)",
         bal4 - bal3 == 600 and bool(rev_rows) and rev_rows[0][0] == 600, (bal3, bal4, rev_rows))
    api_err("再撤销已撤明细拒绝 350012", "POST", "/api/member/exchanges/items/%s/cancel" % e1_item.get("id"),
            tok_admin, None, 350012)

    # ---- 整单撤销 ----
    e3 = api_ok("落单E3(整单撤销用,G1)", "POST", "/api/member/exchanges", tok_m,
                {"exchangeNo": "EX" + stamp8 + "3", "customerId": cust_m, "pointType": 1,
                 "items": [{"giftNo": g1_no}]})
    if e3 is None:
        return finish()
    api_ok("E3 整单撤销", "POST", "/api/member/exchanges/%s/cancel" % e3.get("id"), tok_admin)
    e3_det = api_ok("E3 回读(单+明细全撤销)", "GET", "/api/member/exchanges/%s" % e3.get("id"), tok_admin)
    ok3 = bool(e3_det) and e3_det.get("status") == 2 and all(
        i.get("status") == 2 for i in (e3_det.get("items") or []))
    step("整单撤销状态机(单 2+明细 2)", ok3, e3_det and e3_det.get("status"))
    rev3 = db_exec(
        "select delta from member_point_record where org_id = 1 and src_addon = 'member'"
        " and src_type = 'exchange_cancel' and src_id = %s and type = 3", (str(e3.get("id")),))
    step("整单撤销反向流水", bool(rev3) and rev3[0][0] == 600, rev3)

    # ---- 并发积分:余额只够一单,恰一成功一 350006 ----
    bal5 = db_exec("select balance from member_point_account where org_id = 1 and customer_id = %s"
                   " and point_type = 1", (cust_m,))[0][0]
    e2b = {}

    def _place_g5(no_suffix):
        st_, res_ = call("POST", "/api/member/exchanges", tok_m,
                         {"exchangeNo": "EX" + stamp8 + no_suffix, "customerId": cust_m, "pointType": 1,
                          "items": [{"giftNo": "MG" + stamp8 + "5"}]})
        e2b[no_suffix] = res_.get("code")

    ths2 = [threading.Thread(target=_place_g5, args=("p1",)),
            threading.Thread(target=_place_g5, args=("p2",))]
    for t in ths2:
        t.start()
    for t in ths2:
        t.join(30)
    codes_p = sorted([e2b.get("p1"), e2b.get("p2")])
    step("并发积分只够一单(余额3300万<2x2000万):恰一单 350006", codes_p == [0, 350006], e2b)
    bal6 = db_exec("select balance from member_point_account where org_id = 1 and customer_id = %s"
                   " and point_type = 1", (cust_m,))[0][0]
    step("并发后余额恰扣一单(20000000)", bal5 - bal6 == 20000000, (bal5, bal6))

    # ---- 签约自动核销(组织配置+事件注入,修老硬编码)----
    api_ok("组织配置开启签约自动核销", "POST", "/api/member/config", tok_admin,
           {"autoSignConsume": 1, "signConsumeContent": "烟测签约核销"})
    cfg = api_ok("配置回读", "GET", "/api/member/config", tok_admin)
    step("配置落库", bool(cfg) and cfg.get("auto_sign_consume") == 1
         and cfg.get("sign_consume_content") == "烟测签约核销", cfg)
    # 挂真实装修单(order_m):注入的签约事件同时被 decoration 通知消费者消费(要求真实单),多消费者全成才算确认
    e9 = api_ok("落单E9(挂真实装修单,签约核销用,G2)", "POST", "/api/member/exchanges", tok_m,
                {"exchangeNo": "EX" + stamp8 + "9", "customerId": cust_m, "pointType": 1,
                 "orderId": order_m, "items": [{"giftNo": "MG" + stamp8 + "2"}]})
    if e9 is None:
        return finish()
    db_exec(
        "insert into yz_event_task(event_type, payload, event_key, max_retries)"
        " values ('DecorationSignedEvent', %s, %s, 2)",
        (json.dumps({"orderId": order_m, "orgId": 1, "orderNo": "SMOKE" + stamp8,
                     "contractAmount": 100000000, "operatorId": None}, ensure_ascii=False),
         "smoke-sign-" + stamp8))
    event_consumed("DecorationSignedEvent", "smoke-sign-" + stamp8, "签约事件注入并消费确认")
    e9_det = api_ok("E9 回读(签约自动核销)", "GET", "/api/member/exchanges/%s" % e9.get("id"), tok_admin)
    e9_item = ((e9_det or {}).get("items") or [{}])[0]
    step("签约自动核销:明细升已核销+content 取组织配置",
         e9_item.get("status") == 1 and e9_item.get("consume_content") == "烟测签约核销", e9_item)

    # ---- 台账查询 + 对账 + api 段下发 ----
    accs = api_ok("积分账户分页", "GET", "/api/member/points/accounts?customerId=%s" % cust_m, tok_admin)
    step("账户列表含分项行", bool(accs) and accs.get("total", 0) >= 2, accs and accs.get("total"))
    recs = api_ok("积分流水分页", "GET",
                  "/api/member/points/accounts/%s/records?pointType=1" % cust_m, tok_admin)
    step("流水含记分/扣减/回补多类", bool(recs) and recs.get("total", 0) >= 4, recs and recs.get("total"))
    m_pending = db_exec("select count(*) from yz_event_task where event_type like 'Member%%'"
                        " and status not in (1, 3)")
    step("总线对账:会员事件零滞留", bool(m_pending) and m_pending[0][0] == 0, m_pending)
    mb_addons = api_ok("addons 全量下发", "GET", "/api/addons", tok_admin)
    mb_addon = next((a for a in (mb_addons or []) if a.get("id") == "member"), None)
    step("member api 段下发(四菜单+config 五条)",
         bool(mb_addon) and len(mb_addon.get("apis") or []) == 5, mb_addon and mb_addon.get("apis"))

    # ========= RBAC v2 端点鉴权(observe 期,第 36 次扩展)=========
    print("\n== RBAC v2 端点鉴权(observe) ==")

    # api 段下发(声明即文档)
    addons_r2 = api_ok("插件下发复查(rbac api 段)", "GET", "/api/addons", tok_admin)
    cust_r2 = next((a for a in (addons_r2 or []) if a.get("id") == "customer"), None)
    prof_r2 = next((a for a in (addons_r2 or []) if a.get("id") == "customerprofile"), None)
    cust_apis = (cust_r2 or {}).get("apis") or []
    prof_apis = (prof_r2 or {}).get("apis") or []
    step("customer api 段下发(菜单型 require)", any(
        a.get("prefix") == "/api/customer/leads" and a.get("require") == "/customer/leads"
        for a in cust_apis), cust_apis)
    step("customerprofile api 段下发(含 capability 型 exact)", any(
        a.get("exact") == "/api/customerprofile/customers/exist"
        and a.get("require") == "customerprofile:view" for a in prof_apis), prof_apis)

    # admin 旁路全通
    st, res = call("GET", "/api/customer/leads", tok_admin)
    step("admin 旁路全通", st == 200 and res.get("code") == 0, res)

    # 无角色用户(example_o,org2):菜单型 require → 122006
    user_o = login("example_o")
    ok_o = bool(user_o and user_o.get("userId"))
    step("组织二账号登录(example_o)", ok_o, user_o)
    tok_o = user_o["token"] if ok_o else None
    st, res = call("GET", "/api/customer/leads", tok_o)
    step("无角色访问已声明 API 拒 122006(菜单型)", st == 200 and res.get("code") == 122006
         and "customer/leads" in str(res.get("message")), res)

    # 无角色用户:capability 型 require → 122006
    st, res = call("POST", "/api/customerprofile/customers/exist", tok_o, {})
    step("无角色访问 capability 型拒 122006", st == 200 and res.get("code") == 122006
         and "customerprofile:view" in str(res.get("message")), res)

    # 建角色(菜单 /customer/leads + capability customerprofile:view)绑 example_b
    stamp_rbac = time.strftime("%H%M%S") + "r"
    role_r2_id = api_ok("建 RBAC 测试角色", "POST", "/api/roles", tok_admin,
                        {"code": "rbac_smoke_" + stamp_rbac, "name": "端点鉴权冒烟" + stamp_rbac})
    api_ok("授权菜单+功能点", "PUT", "/api/roles/%s/menus" % role_r2_id, tok_admin,
           {"menus": ["/customer/leads"]})
    api_ok("授权功能点", "PUT", "/api/roles/%s/capabilities" % role_r2_id, tok_admin,
           {"capabilities": ["customerprofile:view"]})
    d_uid = user_d["userId"]
    api_ok("绑定 example_d", "PUT", "/api/roles/%s/users" % role_r2_id, tok_admin,
           {"userIds": [d_uid]})

    # 授权后:菜单型通(缓存变更逐出即时生效;example_d 纯净账号,r_rbac 唯一授权源)
    st, res = call("GET", "/api/customer/leads", tok_d)
    step("授权后菜单型 API 通(逐出即时)", st == 200 and res.get("code") == 0, res)

    # 授权后:capability 型通
    st, res = call("POST", "/api/customerprofile/customers/exist", tok_d,
                   {"name": "无此人" + stamp_rbac, "phone": "199%09d" % int(time.strftime("%H%M%S"))})
    step("授权后 capability 型 API 通", st == 200 and res.get("code") == 0, res)

    # 回收:清菜单授权(角色仍在)→ 122006
    api_ok("回收菜单授权", "PUT", "/api/roles/%s/menus" % role_r2_id, tok_admin, {"menus": []})
    st, res = call("GET", "/api/customer/leads", tok_d)
    step("回收后菜单型拒 122006(逐出即时)", st == 200 and res.get("code") == 122006, res)

    # observe 放行:未声明 API(payment bills)无角色可调
    st, res = call("GET", "/api/payment/bills", tok_b)
    step("observe 未声明 API 放行", st == 200 and res.get("code") == 0, res)

    # 跨组织 L0:org2 用户(无授权先拒 → 绑角色后数据视角按 org 隔离)
    if tok_o:
        ok_o = True
        step("登录回显 orgId=2", user_o.get("orgId") == 2, user_o)
        st, res = call("GET", "/api/customer/leads", tok_o)
        step("org2 无授权已声明 API 拒 122006", st == 200 and res.get("code") == 122006, res)
        api_err("跨组织 L0:org1 角色绑 org2 用户拒绝 122005", "PUT",
                "/api/roles/%s/users" % role_r2_id, tok_admin,
                {"userIds": [d_uid, user_o["userId"]]}, 122005)

    # 清理 RBAC 测试角色
    api_ok("删除 RBAC 测试角色", "DELETE", "/api/roles/%s" % role_r2_id, tok_admin)

    # ========= C 端门户(addon-portal V1,第 49 次;匿名面 org=配置 yingzao.portal.org-id 默认 1)=========
    st, res = call("GET", "/api/c/cases?page=1&size=10")
    step("C端案例库匿名可看(无 token)", st == 200 and res.get("code") == 0
         and isinstance((res.get("data") or {}).get("rows"), list), res)

    c_mobile = "139" + time.strftime("%d%H%M%S")
    # 超 11 位/非 1 开头参数守卫 360000
    api_err("C端发码参数非法拒绝 360000", "POST", "/api/c/auth/code", None,
            {"mobile": "12345"}, 360000)
    api_ok("C端发验证码", "POST", "/api/c/auth/code", None, {"mobile": c_mobile})
    api_err("60s 内重发拒绝 360002", "POST", "/api/c/auth/code", None,
            {"mobile": c_mobile}, 360002)
    conn = db_conn()
    try:
        with conn.cursor() as cur:
            cur.execute("select code from portal_sms_code where mobile = %s order by id desc limit 1",
                        (c_mobile,))
            prow = cur.fetchone()
    finally:
        conn.close()
    c_code = prow[0] if prow else None
    step("验证码落表可取(6 位)", bool(c_code) and len(c_code) == 6, c_code)
    api_err("错误验证码拒绝 360001", "POST", "/api/c/auth/login", None,
            {"mobile": c_mobile, "code": "000000"}, 360001)
    c_login = api_ok("C端验证码登录(get-or-create 建档)", "POST", "/api/c/auth/login", None,
                     {"mobile": c_mobile, "code": c_code})
    c_token = (c_login or {}).get("token")
    c_cid = (c_login or {}).get("customerId")
    if not c_token:
        return finish()
    conn = db_conn()
    try:
        with conn.cursor() as cur:
            cur.execute("select name from customerprofile_customer where id = %s", (c_cid,))
            crow = cur.fetchone()
    finally:
        conn.close()
    step("C端建档脱敏名+幂等回显", bool(crow) and str(crow[0]).startswith("C端客户****"), crow)

    # 重复登录(再走一遍发码-登录)应回显同一 customerId(exist 幂等);限发 60s,等待窗口
    time.sleep(61)
    api_ok("C端再次发码(限发窗口后)", "POST", "/api/c/auth/code", None, {"mobile": c_mobile})
    conn = db_conn()
    try:
        with conn.cursor() as cur:
            cur.execute("select code from portal_sms_code where mobile = %s order by id desc limit 1",
                        (c_mobile,))
            prow2 = cur.fetchone()
    finally:
        conn.close()
    if prow2:
        c_login2 = api_ok("C端二次登录幂等回显", "POST", "/api/c/auth/login", None,
                          {"mobile": c_mobile, "code": prow2[0]})
        step("同号二次登录同一 customerId", (c_login2 or {}).get("customerId") == c_cid, c_login2)

    # c-customer 端点:C 端令牌通
    pts = api_ok("C端本人积分查询(c-customer 通)", "GET", "/api/c/points", c_token)
    step("积分视图结构(items/records)", isinstance((pts or {}).get("items"), list)
         and isinstance((pts or {}).get("records"), list), pts)
    # 我的装修单列表(C 端客户无单 → 空结构;归属=令牌 customerId 天然隔离)
    myords = api_ok("C端我的装修单列表(c-customer 通)", "GET", "/api/c/orders", c_token)
    step("装修单列表结构(rows)", isinstance((myords or {}).get("rows"), list), myords)

    # 员工令牌调 C 端点 → RBAC 拒(非 c-customer 主体)
    api_err("员工令牌调C端点拒绝 122006", "GET", "/api/c/points", tok_admin, None, 122006)
    # C 端令牌调员工端点 → RBAC 拒(越权面收口)
    api_err("C端令牌调员工端点拒绝 122006", "GET", "/api/decoration/orders", c_token, None, 122006)
    # 匿名调 c-customer 端点 → 401(认证链)
    st, res = call("GET", "/api/c/points")
    step("匿名调进度/积分 401", st == 401, (st, res))

    # 进度归属校验:拿 admin 名下装修单(order_a)——C 端客户非本人 → 360004
    if order_a:
        api_err("非本人装修单进度拒绝 360004", "GET",
                "/api/c/progress?orderId=%s" % order_a, c_token, None, 360004)

    return finish()


def finish():
    print("\n== 汇总 ==")
    print("%d/%d PASS" % (len(passed), len(passed) + len(failed)))
    if failed:
        print("失败步骤: " + ", ".join(failed))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
