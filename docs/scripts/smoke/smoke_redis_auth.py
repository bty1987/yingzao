# -*- coding: utf-8 -*-
"""F2 认证 Redis 化定向冒烟(docs/15 R22~R23,第 112 次):仅对 YINGZAO_AUTH_STORE=redis 起的服务运行。

覆盖:
- 键形态:yz:auth:token:{token} 存在 / PTTL≈会话期 / 载荷 JSON(principal/userId/orgId/expiresAt);
- 踢会话索引:yz:auth:user:{id} 含成员与同 TTL;同账号双会话并存;
- evictUser 语义:停用→令牌键与索引同删/重启用后旧令牌仍死/新登录恢复;
- C 端令牌:principal=customer 落键;**不入踢会话索引**(无 yz:auth:user:0——Lua 条件 SADD 回归锚);
- 收尾:恢复被停用账号 + DEL yz:auth:* 全部键(前缀隔离,不碰宿主机其他使用者)。

注意:对内存模式服务跑本脚本会在首个结构断言失败(EXISTS=0),属预期——结构断言只对 redis 模式有意义。
环境变量:YINGZAO_BASE(默认 http://127.0.0.1:8080)/ YINGZAO_REDIS_HOST / YINGZAO_REDIS_PORT;
DB 直连仅取 C 端验证码(与 smoke_all 同口径)。
"""
import json
import os
import socket
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get("YINGZAO_BASE", "http://127.0.0.1:8080")
RHOST = os.environ.get("YINGZAO_REDIS_HOST", "127.0.0.1")
RPORT = int(os.environ.get("YINGZAO_REDIS_PORT", "6379"))
PASSWORD = "yingzao123"
TOKEN_PREFIX = "yz:auth:token:"
USER_INDEX_PREFIX = "yz:auth:user:"
TTL_MS = 12 * 3600 * 1000

passed, failed = [], []


def step(name, ok, detail=""):
    (passed if ok else failed).append(name)
    print("%s %s%s" % ("✓" if ok else "✗", name, ("  | " + str(detail)) if detail and not ok else ""))


class Resp:
    """最小 RESP2 客户端(只覆盖冒烟所需命令;不引 redis 依赖,与冒烟资产纯 urllib 风格一致)。"""

    def __init__(self, host, port):
        self.sock = socket.create_connection((host, port), timeout=5)
        self.f = self.sock.makefile("rb")

    def cmd(self, *args):
        buf = ("*%d\r\n" % len(args)).encode()
        for a in args:
            b = a if isinstance(a, bytes) else str(a).encode()
            buf += ("$%d\r\n%s\r\n" % (len(b), b.decode("latin1"))).encode("latin1")
        self.sock.sendall(buf)
        return self._read()

    def _read(self):
        line = self.f.readline()
        t, rest = line[:1], line[1:-2]
        if t == b"+":
            return rest.decode()
        if t == b"-":
            raise RuntimeError("redis error: " + rest.decode())
        if t == b":":
            return int(rest)
        if t == b"$":
            n = int(rest)
            if n == -1:
                return None
            return self.f.read(n + 2)[:-2]
        if t == b"*":
            n = int(rest)
            if n == -1:
                return None
            return [self._read() for _ in range(n)]
        raise RuntimeError("bad reply %r" % line)

    def close(self):
        try:
            self.f.close()
            self.sock.close()
        except Exception:
            pass


def call(method, path, token=None, body=None):
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


def login(username):
    status, res = call("POST", "/api/auth/login", body={"username": username, "password": PASSWORD})
    ok = status == 200 and isinstance(res, dict) and res.get("code") == 0
    step("登录 %s" % username, ok, res)
    return res.get("data") if ok else None


def db_conn():
    import pymysql
    return pymysql.connect(
        host=os.environ.get("YINGZAO_DB_HOST", "192.168.5.21"),
        port=int(os.environ.get("YINGZAO_DB_PORT", "3306")),
        database=os.environ.get("YINGZAO_DB_NAME", "yingzao"),
        user=os.environ.get("YINGZAO_DB_USER", "root"),
        password=os.environ.get("YINGZAO_DB_PASSWORD", "Neusoft!"),
        charset="utf8mb4",
    )


def cleanup(r):
    """DEL yz:auth:* 全部键(前缀隔离);被停用账号已在用例内恢复。"""
    keys = r.cmd("KEYS", "yz:auth:*")
    if keys:
        r.cmd("DEL", *keys)
    print("清理 yz:auth:* 键 %d 个" % (len(keys) or 0))


def main():
    r = Resp(RHOST, RPORT)
    try:
        print("== F2 redis 会话定向冒烟(store=redis,redis=%s:%d)==" % (RHOST, RPORT))

        admin = login("admin")
        if not admin:
            return finish(r)
        tok_admin, uid_admin = admin["token"], admin["userId"]

        st, res = call("GET", "/api/addons", tok_admin)
        step("登录令牌过鉴权链(/api/addons)", st == 200 and res.get("code") == 0, res)

        # ---- 键形态 ----
        tok_key = TOKEN_PREFIX + tok_admin
        step("会话键落 redis(EXISTS)", r.cmd("EXISTS", tok_key) == 1, tok_key)
        pttl = r.cmd("PTTL", tok_key)
        step("PTTL≈会话期(12h,不滑动)", 0 < pttl <= TTL_MS and pttl > TTL_MS - 5 * 60 * 1000, pttl)
        payload = r.cmd("GET", tok_key)
        try:
            sess = json.loads(payload.decode())
        except Exception:
            sess = None
        step("载荷 JSON 可解析", isinstance(sess, dict), payload)
        step("载荷 principal=user/userId/orgId 归属正确",
             isinstance(sess, dict) and sess.get("principal") == "user"
             and sess.get("userId") == uid_admin and sess.get("orgId") == admin.get("orgId"), sess)

        # ---- 踢会话索引 ----
        idx_key = USER_INDEX_PREFIX + str(uid_admin)
        step("员工入踢会话索引(SISMEMBER)", r.cmd("SISMEMBER", idx_key, tok_admin) == 1, idx_key)
        idx_ttl = r.cmd("PTTL", idx_key)
        step("索引 TTL≈会话期", 0 < idx_ttl <= TTL_MS and idx_ttl > TTL_MS - 5 * 60 * 1000, idx_ttl)

        # ---- 双会话并存 ----
        admin2 = login("admin")
        tok2 = (admin2 or {}).get("token")
        st, _res = call("GET", "/api/addons", tok2)
        step("同账号第二会话可用", st == 200 and bool(tok2) and tok2 != tok_admin, tok2)
        step("索引含双成员", r.cmd("SCARD", idx_key) >= 2, r.cmd("SMEMBERS", idx_key))

        # ---- evictUser 语义(停用→踢会话)----
        c = login("example_c")
        if not c:
            return finish(r)
        tok_c, uid_c = c["token"], c["userId"]
        st, _res = call("POST", "/api/users/%d/status" % uid_c, tok_admin, {"status": 1})
        step("admin 停用 example_c", st == 200, _res)
        st, res = call("GET", "/api/addons", tok_c)
        step("停用后旧令牌 401", st == 401, (st, res))
        step("停用踢会话:令牌键同删", r.cmd("EXISTS", TOKEN_PREFIX + tok_c) == 0)
        step("停用踢会话:索引键同删", r.cmd("EXISTS", USER_INDEX_PREFIX + str(uid_c)) == 0)
        st, _res = call("POST", "/api/users/%d/status" % uid_c, tok_admin, {"status": 0})
        step("admin 重新启用 example_c(恢复现场)", st == 200, _res)
        c4 = login("example_c")
        st, _res = call("GET", "/api/addons", (c4 or {}).get("token"))
        step("重启用后新登录恢复", c4 and st == 200, c4)
        st, _res = call("GET", "/api/addons", tok_c)
        step("已 evict 旧令牌不复活", st == 401, st)

        # ---- C 端令牌(principal=customer,不入索引)----
        c_mobile = "137" + time.strftime("%d%H%M%S")
        st, _res = call("POST", "/api/c/auth/code", None, {"mobile": c_mobile})
        step("C端发验证码", st == 200, _res)
        conn = db_conn()
        try:
            with conn.cursor() as cur:
                cur.execute("select code from portal_sms_code where mobile = %s order by id desc limit 1",
                            (c_mobile,))
                prow = cur.fetchone()
        finally:
            conn.close()
        step("验证码落表可取", bool(prow and prow[0]), prow)
        c_login = call("POST", "/api/c/auth/login", None, {"mobile": c_mobile, "code": prow[0]})[1]
        c_data = (c_login or {}).get("data") or {} if isinstance(c_login, dict) else {}
        c_token = c_data.get("token")
        step("C端登录拿令牌", bool(c_token), c_login)
        st, res = call("GET", "/api/c/points", c_token)
        step("C端令牌过鉴权链(/api/c/points)", st == 200 and res.get("code") == 0, res)
        payload = r.cmd("GET", TOKEN_PREFIX + c_token)
        try:
            csess = json.loads(payload.decode())
        except Exception:
            csess = None
        step("C端载荷 principal=customer", isinstance(csess, dict) and csess.get("principal") == "customer", csess)
        step("C端令牌不入踢会话索引(无 user:0 键)", r.cmd("EXISTS", USER_INDEX_PREFIX + "0") == 0)

        return finish(r)
    except Exception as e:
        step("脚本异常中断", False, repr(e))
        return finish(r)


def finish(r):
    cleanup(r)
    r.close()
    print("== 汇总:%d/%d PASS ==" % (len(passed), len(passed) + len(failed)))
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
