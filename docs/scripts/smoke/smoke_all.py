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
import threading
import time
import urllib.error
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
    uid_b = user_b["userId"]
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
    # 调度器 10s 一轮+指数退避(1s 基数):坏载荷走完 max_retries=2 需跨两轮轮询,等 25s 保证转死信
    time.sleep(25)
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
                where event_type like 'Decoration%%' and status != 1
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
    step("总线对账:decoration 事件零滞留(全消费确认)", undelivered == 0, undelivered)
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
