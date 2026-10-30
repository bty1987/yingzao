# -*- coding: utf-8 -*-
# 第105次 R7 冒烟:留资四态断言(正常/幂等回显/守卫拒/频次防刷)+通知链 DB 断言+段尾自清理。
# 前置:_p105_card_data.py 已建卡(SHARE_TOKEN 下方硬编码为当轮值)。
import json
import time
import urllib.request
import pymysql

BASE = "http://127.0.0.1:8080"
SHARE = "2d98cc8e3dc458e080de68784be24a43cca41105a748006d"
TOKEN = None  # 管理端 token(登录获取)
DB = dict(host="192.168.5.21", user="root", password="Neusoft!",
          database="yingzao", charset="utf8mb4")

def api(method, path, body=None, token=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode("utf-8"))

def db_exec(sql, *args):
    conn = pymysql.connect(**DB)
    try:
        with conn.cursor() as c:
            c.execute(sql, args)
            conn.commit()
            return c.rowcount
    finally:
        conn.close()

def db_one(sql, *args):
    conn = pymysql.connect(**DB)
    try:
        with conn.cursor() as c:
            c.execute(sql, args)
            return c.fetchone()
    finally:
        conn.close()

tk = api("POST", "/api/auth/login", {"username": "admin", "password": "yingzao123"})["data"]["token"]
print("admin logged in")

# 段首定向清理:上一轮残留的同 hash 留资/通知(幂等可重跑)
db_exec("delete from yz_notification where source_type='card.notice'")
db_exec("delete from market_card_notice where card_id=1")

# 1) 正常提交 → duplicate=false
r1 = api("POST", "/api/c/cards/%s/notices" % SHARE,
         {"name": "访客甲", "phone": "13900002222", "comment": "想了解 128 平米全包报价"}, token=tk)
assert r1.get("code") == 0 and r1["data"]["duplicate"] is False, r1
n1 = r1["data"]["noticeId"]
row = db_one("select name, comment, status from market_card_notice where id=%s", n1)
assert row and row[0] == "访客甲" and row[2] == 0, row
print("1. submit ok -> noticeId=%d name/comment/status 落库正确" % n1)

# 2) 同人同卡重复留资 → 幂等回显 duplicate=true(同 noticeId)
r2 = api("POST", "/api/c/cards/%s/notices" % SHARE,
         {"name": "访客甲改", "phone": "13900002222", "comment": "补充:周末方便吗"}, token=tk)
assert r2.get("code") == 0 and r2["data"]["duplicate"] is True and r2["data"]["noticeId"] == n1, r2
cnt = db_one("select count(*) from market_card_notice where id=%s", n1)[0]
assert cnt == 1, "幂等回显不应新增行"
print("2. duplicate=true 幂等回显零新增")

# 3) 守卫族:10 位手机 / name 超 64 → 280104
r3a = api("POST", "/api/c/cards/%s/notices" % SHARE,
          {"name": "访客乙", "phone": "1390000222", "comment": ""}, token=tk)
assert r3a.get("code") == 280104, r3a
r3b = api("POST", "/api/c/cards/%s/notices" % SHARE,
          {"name": "测" * 65, "phone": "13900003333", "comment": ""}, token=tk)
assert r3b.get("code") == 280104, r3b
print("3. 280104 守卫族(手机格式/称呼超长)全拒")

# 4) 频次防刷:行仍在但退出幂等窗(DB 置归档态 2,模拟业务演进;防刷查询不筛 status)
#    → 60s 内同 hash 再提交 → 280110(修并发 TOCTOU 补刀语义,串行构造)
db_exec("update market_card_notice set status=2 where id=%s", n1)
r4 = api("POST", "/api/c/cards/%s/notices" % SHARE,
         {"name": "访客甲", "phone": "13900002222", "comment": "再试一次"}, token=tk)
assert r4.get("code") == 280110, r4
print("4. 280110 同 hash 60s 防刷命中")

# 5) 不同手机号不受影响(防刷按 hash)→ 正常落库;等事件消费后断言通知链
time.sleep(2)
r5 = api("POST", "/api/c/cards/%s/notices" % SHARE,
         {"name": "访客丙", "phone": "13900004444", "comment": "阳台封窗多少钱"}, token=tk)
assert r5.get("code") == 0 and r5["data"]["duplicate"] is False, r5
time.sleep(3)
note = db_one("select title from yz_notification where source_type='card.notice' "
              "and source_id=%s order by id desc limit 1", r5["data"]["noticeId"])
assert note, "CardNoticeConsumer 站内信未生成"
print("5. 不同访客留资 ok + 站内信通知链生成(title=%s)" % note[0])

# 6) 已读守卫:名片主人已读后管理端 notices 待审数变化(pending 口径)
r6 = api("POST", "/api/market/cards/notices/%d/read" % r5["data"]["noticeId"], {}, token=tk)
assert r6.get("code") == 0, r6
mine = api("GET", "/api/market/cards/mine", token=tk)
assert mine["data"]["pending"] == 0, mine["data"]["pending"]
print("6. 已读回写 pending 归零")

# 段尾自清理:留资+通知(卡片与相册保留供浏览器实测)
db_exec("delete from yz_notification where source_type='card.notice'")
db_exec("delete from market_card_notice where card_id=1")
print("CLEANED. SMOKE R7 ALL GREEN")
