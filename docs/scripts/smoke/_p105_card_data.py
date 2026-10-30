# 第105次 R7 冒烟数据:admin 建名片+两张相册(管理端 API 全链,-phone_cipher 走 CryptoBox AES 正确形态)
# 用法:python _p105_card_data.py   (等 8080 就绪后执行)
import json
import time
import urllib.request

BASE = "http://127.0.0.1:8080"

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

# 1. admin 登录(冒烟口令 yingzao123)
login = api("POST", "/api/auth/login", {"username": "admin", "password": "yingzao123"})
assert login.get("code") == 0, login
tk = login["data"]["token"]
print("admin logged in")

# 2. 建卡(一人一卡;库空,首建即成功;若已有则复用 mine)
mine = api("GET", "/api/market/cards/mine", token=tk)
if mine.get("code") == 0 and mine.get("data") and mine["data"].get("id"):
    card_id = mine["data"]["id"]
    print("card exists ->", card_id)
else:
    created = api("POST", "/api/market/cards", {
        "userName": "沈砚舟",
        "phone": "13800001234",
        "wechat": "shen_yz2026",
        "industry": "装修设计",
        "profession": "首席设计师",
        "address": "沈阳市铁西区北二路营造设计中心",
        "info": "十二年住宅设计经验,主张「先有生活,后有风格」。代表作:中海观江樾 8# 样板间、万科翡翠别墅全案。"
    }, token=tk)
    assert created.get("code") == 0, created
    card_id = created["data"]
    print("card created ->", card_id)

# 3. 相册两张(uni-app 静态约定 src/static/smoke,H5 dev 伺服 /static/smoke/)
for url, sort in [("http://localhost:5175/static/smoke/p1.svg", 1), ("http://localhost:5175/static/smoke/p2.svg", 2)]:
    r = api("POST", "/api/market/cards/%d/photos" % card_id, {"url": url, "sortNo": sort}, token=tk)
    print("photo:", r.get("code"), r.get("data") or r.get("message"))

# 4. 取 share_token
mine = api("GET", "/api/market/cards/mine", token=tk)
share = mine["data"]["share_token"]
print("SHARE_TOKEN=" + str(share))

# 5. C 端匿名面自检(白名单免登)
view = api("GET", "/api/c/cards/" + str(share))
assert view.get("code") == 0, view
v = view["data"]
print("C-view: name=%s phone=%s photos=%d" % (v.get("userName"), v.get("phone"), len(v.get("photos") or [])))
print("URL=http://localhost:5175/#/pages/card/detail?token=" + str(share))
