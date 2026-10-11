# -*- coding: utf-8 -*-
"""营造 Yingzao 一键冒烟(2026-10-11 第 7 次运行沉淀为资产,第 8 次扩展 RBAC+多级审批)。

覆盖:认证 → RBAC 菜单/功能点过滤 → 客源中文登记 → 手动分配 → 机会状态机 → 站内通知 → 轻量审批闭环 → 多级审批串联。
前置:服务已起(docs/scripts/smoke/README.md),python ensure_users.py 已跑(smoke_all 会自动先跑)。
用法:python smoke_all.py   → 逐步 ✓/✗,汇总 N/N PASS,任何 ✗ 退出码 1。
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get("YINGZAO_BASE", "http://127.0.0.1:8080").rstrip("/")
PASSWORD = "yingzao123"

passed, failed = [], []


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
    step("双账号不同身份", user_b["userId"] != user_c["userId"], (user_b, user_c))
    step("admin 登录含内置角色", "admin" in (user_admin.get("roles") or []), user_admin)

    status, res = call("POST", "/api/auth/login", body={"username": "example_b", "password": "wrong"})
    step("错误密码 110001", status == 200 and res.get("code") == 110001, res)

    status, res = call("GET", "/api/customer/leads")
    step("无 token 401", status == 401, (status, res))
    status, res = call("GET", "/api/addons")
    step("addons 去白名单:无 token 401", status == 401, (status, res))

    print("== RBAC 菜单/功能点过滤 ==")
    addons_admin = api_ok("admin 插件下发(旁路)", "GET", "/api/addons", tok_admin)
    cust = next((a for a in (addons_admin or []) if a.get("id") == "customer"), None)
    ok = cust and len(cust.get("menus") or []) == 2 \
        and any((m.get("capability") or "") == "customerprofile:view" for m in (cust.get("mounts") or []))
    step("admin 可见双菜单+capability 挂载", ok, cust)

    addons_b0 = api_ok("无角色用户插件下发", "GET", "/api/addons", tok_b)
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
    api_ok("绑定成员 example_b", "PUT", "/api/roles/%s/users" % role_id, tok_admin,
           {"userIds": [user_b["userId"]]})

    detail = api_ok("角色详情回显", "GET", "/api/roles/%s" % role_id, tok_admin)
    ok = detail and sorted(detail.get("menus") or []) == ["/customer/chances", "/customer/leads"] \
        and detail.get("capabilities") == ["customerprofile:view"] \
        and [u.get("id") for u in (detail.get("users") or [])] == [user_b["userId"]]
    step("详情含授权与成员", ok, detail)

    addons_b1 = api_ok("授权后插件下发(example_b)", "GET", "/api/addons", tok_b)
    cust1 = next((a for a in (addons_b1 or []) if a.get("id") == "customer"), None)
    ok = cust1 and sorted(m.get("path") for m in cust1.get("menus") or []) == \
        ["/customer/chances", "/customer/leads"] \
        and any((m.get("capability") or "") == "customerprofile:view" for m in (cust1.get("mounts") or []))
    step("授权后可见菜单与挂载", ok, cust1)
    # 功能点已授权 → 其挂载所在插件条目合法出现,但未授权的菜单必须仍为空(不泄露)
    prof1 = next((a for a in (addons_b1 or []) if a.get("id") == "customerprofile"), None)
    step("未授权菜单不可见(capability 挂载随授权下发)", prof1 is not None and prof1.get("menus") == [], prof1)

    api_ok("删除角色", "DELETE", "/api/roles/%s" % role_id, tok_admin)
    addons_b2 = api_ok("删除后插件下发(example_b)", "GET", "/api/addons", tok_b)
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
