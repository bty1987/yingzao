# -*- coding: utf-8 -*-
"""营造 Yingzao 一键冒烟(2026-10-11 第 7 次运行沉淀为资产,第 8 次扩展 RBAC+多级审批,第 9 次扩展转交×多级串联)。

覆盖:认证 → RBAC 菜单/功能点过滤 → 客源中文登记 → 手动分配 → 机会状态机 → 站内通知 → 轻量审批闭环 → 多级审批串联 → 转交×多级串联。
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

    print("== 客户与楼盘档案(addon-customerprofile V1)==")
    stamp2 = time.strftime("%Y%m%d%H%M%S")
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
