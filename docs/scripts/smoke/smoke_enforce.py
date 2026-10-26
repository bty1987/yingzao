# -*- coding: utf-8 -*-
"""RBAC v2 enforce 终态专项冒烟(2026-10-12 第 37 次运行沉淀为资产;第 36 次 enforce 终态 5 步为手跑,本轮落盘并扩展九插件声明验证)。

覆盖:
  0. 静态完备性(不依赖服务):12 插件 addon.yml api 段 vs 全量端点样例表,
     模拟内核 RbacService.match(exact 优先 / 最长前缀 / pattern 命中但 methods 不兼容跳过继续找),
     断言零未声明端点 + methods 互补分层语义(basedata·decoration·customerprofile 读放开/写收权);
  1. admin 旁路:12 插件代表端点 + 内核豁免全通;
  2. 无角色 example_d:12 插件代表端点全拒 122006;内核 /api/roles 业务守卫 122003(非 122006,证明豁免);
  3. 全授权(emp_all 动态全菜单+全功能点):12 插件代表端点全通(逐出即时);
  4. 部分授权(仅 /sales/orders):authenticated 读放开 3 条通 / 写操作拒 122006 / 有菜单通 / 无菜单拒;
  5. 角色清理(幂等,可重复执行)。

前置:服务必须以 YINGZAO_RBAC_MODE=enforce 启动(observe 实例上第 2 组语义不同会误报),
python ensure_users.py 已跑。用法:python smoke_enforce.py → 逐步 ✓/✗,汇总 N/N PASS,任何 ✗ 退出码 1。
"""
import glob
import json
import os
import time
import urllib.error
import urllib.request

import yaml

BASE = os.environ.get("YINGZAO_BASE", "http://127.0.0.1:8080").rstrip("/")
PASSWORD = "yingzao123"

passed, failed = [], []


def step(name, ok, detail=""):
    (passed if ok else failed).append(name)
    print("%s %s%s" % ("✓" if ok else "✗", name, ("  | " + str(detail)) if detail and not ok else ""))


def call(method, path, token=None, body=None):
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
    status, res = call(method, path, token, body)
    ok = status == 200 and isinstance(res, dict) and res.get("code") == 0
    step(name, ok, res)
    return res.get("data") if ok else None


def api_err(name, method, path, token, body, want_code):
    status, res = call(method, path, token, body)
    ok = status == 200 and isinstance(res, dict) and res.get("code") == want_code
    step(name, ok, "http=%s body=%s" % (status, res))


def login(username):
    status, res = call("POST", "/api/auth/login", None, {"username": username, "password": PASSWORD})
    ok = status == 200 and isinstance(res, dict) and res.get("code") == 0
    if not ok:
        step("登录 " + username, False, res)
        return None
    step("登录 " + username, True)
    return res.get("data")


def load_rules():
    """从仓库 addon.yml 载入全部 api 规则,复刻内核构建:pattern 长度降序(同长保持文件序)。"""
    rules = []
    for f in sorted(glob.glob(os.path.join(os.path.dirname(__file__), "..", "..", "..",
                                           "addons", "*", "src", "main", "resources", "addon.yml"))):
        desc = yaml.safe_load(open(f, encoding="utf-8"))["addon"]
        for r in desc.get("api") or []:
            pattern = r.get("exact") or r.get("prefix")
            rules.append({"pattern": pattern, "exact": bool(r.get("exact")),
                          "require": r.get("require"), "methods": r.get("methods") or [],
                          "addon": desc["id"]})
    rules.sort(key=lambda x: len(x["pattern"]), reverse=True)
    return rules


def mock_match(rules, uri, method):
    """复刻内核 match:pattern 命中但 methods 不兼容 → 跳过继续找;全不兼容 = 未声明。"""
    for r in rules:
        hit = (r["pattern"] == uri) if r["exact"] else uri.startswith(r["pattern"])
        if not hit:
            continue
        if r["methods"] and method not in r["methods"]:
            continue
        return r
    return None


# ---- 静态完备性端点样例表:12 插件 Controller 全量方法级路由(动态段展开为 1) ----
ENDPOINTS = [
    # customer
    ("customer", "POST", "/api/customer/leads"), ("customer", "GET", "/api/customer/leads"),
    ("customer", "POST", "/api/customer/leads/1/convert"), ("customer", "POST", "/api/customer/leads/1/assign"),
    ("customer", "GET", "/api/customer/chances"), ("customer", "POST", "/api/customer/chances/1/confirm"),
    ("customer", "POST", "/api/customer/chances/1/complete"),
    # customerprofile
    ("customerprofile", "GET", "/api/customerprofile/cities"), ("customerprofile", "POST", "/api/customerprofile/cities"),
    ("customerprofile", "POST", "/api/customerprofile/cities/1/propers"),
    ("customerprofile", "GET", "/api/customerprofile/villages"), ("customerprofile", "GET", "/api/customerprofile/villages/1"),
    ("customerprofile", "POST", "/api/customerprofile/villages"), ("customerprofile", "PUT", "/api/customerprofile/villages/1"),
    ("customerprofile", "POST", "/api/customerprofile/villages/1/status"),
    ("customerprofile", "GET", "/api/customerprofile/customers"), ("customerprofile", "GET", "/api/customerprofile/customers/1"),
    ("customerprofile", "POST", "/api/customerprofile/customers"), ("customerprofile", "POST", "/api/customerprofile/customers/exist"),
    ("customerprofile", "PUT", "/api/customerprofile/customers/1"), ("customerprofile", "GET", "/api/customerprofile/customers/1/phones"),
    ("customerprofile", "POST", "/api/customerprofile/customers/1/phones"),
    ("customerprofile", "POST", "/api/customerprofile/customers/1/phones/1/default"),
    ("customerprofile", "POST", "/api/customerprofile/customers/1/phones/1/status"),
    ("customerprofile", "POST", "/api/customerprofile/customers/1/phones/1/reveal"),
    ("customerprofile", "GET", "/api/customerprofile/customers/1/phone-access-logs"),
    ("customerprofile", "GET", "/api/customerprofile/customers/1/accounts"), ("customerprofile", "POST", "/api/customerprofile/customers/1/accounts"),
    ("customerprofile", "POST", "/api/customerprofile/customers/1/accounts/1/default"),
    ("customerprofile", "POST", "/api/customerprofile/customers/1/accounts/1/status"),
    ("customerprofile", "GET", "/api/customerprofile/houses"), ("customerprofile", "GET", "/api/customerprofile/houses/1"),
    ("customerprofile", "POST", "/api/customerprofile/houses"), ("customerprofile", "POST", "/api/customerprofile/houses/exist"),
    ("customerprofile", "PUT", "/api/customerprofile/houses/1"),
    # example
    ("example", "GET", "/api/example/leaves"), ("example", "POST", "/api/example/leaves"),
    ("example", "POST", "/api/example/leaves/1/cancel"),
    # basedata
    ("basedata", "GET", "/api/basedata/brands"), ("basedata", "POST", "/api/basedata/brands"),
    ("basedata", "PUT", "/api/basedata/brands/1"), ("basedata", "POST", "/api/basedata/brands/1/status"),
    ("basedata", "GET", "/api/basedata/categories"), ("basedata", "POST", "/api/basedata/categories"),
    ("basedata", "PUT", "/api/basedata/categories/1"), ("basedata", "POST", "/api/basedata/categories/1/status"),
    ("basedata", "GET", "/api/basedata/goods"), ("basedata", "GET", "/api/basedata/goods/1"),
    ("basedata", "POST", "/api/basedata/goods"), ("basedata", "PUT", "/api/basedata/goods/1"),
    ("basedata", "POST", "/api/basedata/goods/1/status"),
    ("basedata", "GET", "/api/basedata/goods/1/sku-attrs"), ("basedata", "POST", "/api/basedata/goods/1/sku-attrs"),
    ("basedata", "POST", "/api/basedata/goods/1/sku-attrs/1/status"),
    ("basedata", "GET", "/api/basedata/goods/1/prices"), ("basedata", "POST", "/api/basedata/goods/1/prices"),
    ("basedata", "PUT", "/api/basedata/goods/1/prices/1"), ("basedata", "POST", "/api/basedata/goods/1/prices/1/status"),
    ("basedata", "GET", "/api/basedata/goods/1/packages"), ("basedata", "POST", "/api/basedata/goods/1/packages"),
    ("basedata", "POST", "/api/basedata/goods/1/packages/1/status"),
    ("basedata", "GET", "/api/basedata/models"), ("basedata", "POST", "/api/basedata/models"),
    ("basedata", "PUT", "/api/basedata/models/1"), ("basedata", "POST", "/api/basedata/models/1/status"),
    ("basedata", "GET", "/api/basedata/packages"), ("basedata", "POST", "/api/basedata/packages"),
    ("basedata", "POST", "/api/basedata/packages/1/reference"), ("basedata", "PUT", "/api/basedata/packages/1"),
    ("basedata", "POST", "/api/basedata/packages/1/status"),
    # payment
    ("payment", "GET", "/api/payment/bills"), ("payment", "GET", "/api/payment/bills/1"),
    ("payment", "POST", "/api/payment/bills"), ("payment", "POST", "/api/payment/bills/1/collect"),
    ("payment", "POST", "/api/payment/bills/1/confirm"), ("payment", "POST", "/api/payment/bills/1/refund"),
    ("payment", "POST", "/api/payment/bills/1/cancel"),
    ("payment", "GET", "/api/payment/bill-types"), ("payment", "POST", "/api/payment/bill-types"),
    ("payment", "PUT", "/api/payment/bill-types/1"), ("payment", "POST", "/api/payment/bill-types/1/status"),
    ("payment", "GET", "/api/payment/advance/subjects"), ("payment", "POST", "/api/payment/advance/subjects"),
    ("payment", "POST", "/api/payment/advance/subjects/1/status"),
    ("payment", "GET", "/api/payment/advance/accounts"), ("payment", "GET", "/api/payment/advance/accounts/1/flows"),
    ("payment", "POST", "/api/payment/advance/charge"), ("payment", "POST", "/api/payment/advance/deduct"),
    ("payment", "POST", "/api/payment/advance/withdraw"),
    # decoration
    ("decoration", "GET", "/api/decoration/orders"), ("decoration", "GET", "/api/decoration/orders/1"),
    ("decoration", "POST", "/api/decoration/orders"), ("decoration", "POST", "/api/decoration/orders/1/profile"),
    ("decoration", "POST", "/api/decoration/orders/1/presign"), ("decoration", "POST", "/api/decoration/orders/1/sign"),
    ("decoration", "POST", "/api/decoration/orders/1/resign"), ("decoration", "POST", "/api/decoration/orders/1/obsolete"),
    ("decoration", "POST", "/api/decoration/orders/1/terminate"), ("decoration", "POST", "/api/decoration/orders/1/account"),
    ("decoration", "POST", "/api/decoration/orders/1/finish"), ("decoration", "POST", "/api/decoration/orders/1/design-confirm"),
    ("decoration", "POST", "/api/decoration/orders/1/drawline-confirm"), ("decoration", "POST", "/api/decoration/orders/1/measures"),
    ("decoration", "POST", "/api/decoration/orders/1/belong/transfer"),
    ("portal", "POST", "/api/c/auth/code"), ("portal", "POST", "/api/c/auth/login"),
    ("portal", "GET", "/api/c/cases"), ("portal", "GET", "/api/c/progress"), ("portal", "GET", "/api/c/points"),
    ("portal", "GET", "/api/c/orders"),
    ("basedata", "GET", "/api/basedata/goods/import-template"),
    ("basedata", "POST", "/api/basedata/goods/import"),
    ("member", "POST", "/api/member/exchanges/1/ship"), ("member", "POST", "/api/member/points/expire-clear"),
    ("customer", "GET", "/api/customer/lead-config"), ("customer", "POST", "/api/customer/lead-config"),
    ("decoration", "GET", "/api/decoration/orders/1/account-check"), ("decoration", "GET", "/api/decoration/config"),
    ("decoration", "PUT", "/api/decoration/config"),
    ("decoration", "POST", "/api/decoration/measures/1/success"), ("decoration", "POST", "/api/decoration/measures/1/fail"),
    # quotation
    ("quotation", "GET", "/api/quotation/versions"), ("quotation", "POST", "/api/quotation/versions"),
    ("quotation", "PUT", "/api/quotation/versions/1"), ("quotation", "POST", "/api/quotation/versions/1/status"),
    ("quotation", "GET", "/api/quotation/quotations"), ("quotation", "GET", "/api/quotation/quotations/1"),
    ("quotation", "POST", "/api/quotation/quotations"), ("quotation", "POST", "/api/quotation/quotations/1/pricing"),
    ("quotation", "POST", "/api/quotation/quotations/1/lock"), ("quotation", "POST", "/api/quotation/quotations/1/unlock"),
    ("quotation", "POST", "/api/quotation/quotations/1/submit"), ("quotation", "POST", "/api/quotation/quotations/1/audit"),
    ("quotation", "POST", "/api/quotation/quotations/1/finish"), ("quotation", "POST", "/api/quotation/quotations/1/default"),
    ("quotation", "POST", "/api/quotation/quotations/1/areas"), ("quotation", "PUT", "/api/quotation/quotations/1/areas/1"),
    ("quotation", "DELETE", "/api/quotation/quotations/1/areas/1"),
    ("quotation", "POST", "/api/quotation/quotations/1/areas/1/items"),
    ("quotation", "PUT", "/api/quotation/quotations/1/items/1"), ("quotation", "DELETE", "/api/quotation/quotations/1/items/1"),
    # construction
    ("construction", "GET", "/api/construction/config"), ("construction", "PUT", "/api/construction/config"),
    ("construction", "GET", "/api/construction/nodes"), ("construction", "POST", "/api/construction/nodes"),
    ("construction", "PUT", "/api/construction/nodes/1"), ("construction", "POST", "/api/construction/nodes/1/status"),
    ("construction", "POST", "/api/construction/drawlines"), ("construction", "POST", "/api/construction/drawlines/1/schedule"),
    ("construction", "POST", "/api/construction/drawlines/1/success"), ("construction", "POST", "/api/construction/drawlines/1/fail"),
    ("construction", "POST", "/api/construction/drawlines/retroactive"),
    ("construction", "GET", "/api/construction/orders"), ("construction", "GET", "/api/construction/orders/1"),
    ("construction", "PUT", "/api/construction/orders/1/supervisor"), ("construction", "POST", "/api/construction/orders/1/accessory"),
    ("construction", "GET", "/api/construction/orders/1/schedules"), ("construction", "POST", "/api/construction/orders/1/schedules/reset"),
    ("construction", "POST", "/api/construction/schedules/1/begin"), ("construction", "POST", "/api/construction/schedules/1/finish"),
    ("construction", "POST", "/api/construction/schedules/1/open-salary"), ("construction", "POST", "/api/construction/schedules/1/checkout"),
    ("construction", "DELETE", "/api/construction/schedules/1"),
    ("construction", "POST", "/api/construction/schedules/1/workers"), ("construction", "GET", "/api/construction/schedules/1/workers"),
    ("construction", "POST", "/api/construction/workers/1/disable"), ("construction", "POST", "/api/construction/workers/1/rate-link"),
    ("construction", "POST", "/api/construction/workers/1/rate"), ("construction", "POST", "/api/construction/workers/1/enter"),
    ("construction", "POST", "/api/construction/workers/1/leave"), ("construction", "GET", "/api/construction/workers/1/records"),
    ("construction", "POST", "/api/construction/schedules/1/diaries"), ("construction", "GET", "/api/construction/schedules/1/diaries"),
    ("construction", "GET", "/api/construction/orders/1/diaries"),
    ("construction", "POST", "/api/construction/orders/1/acceptance"), ("construction", "GET", "/api/construction/orders/1/acceptance"),
    ("construction", "GET", "/api/construction/orders/1/accessory"), ("construction", "PUT", "/api/construction/orders/1/accessory-limit"),
    ("construction", "POST", "/api/construction/orders/1/accessory-quotas"),
    ("construction", "DELETE", "/api/construction/orders/1/accessory-quotas/1"),
    ("construction", "GET", "/api/construction/orders/1/accessory-ledger"),
    ("construction", "GET", "/api/construction/profile-templates"), ("construction", "POST", "/api/construction/profile-templates"),
    ("construction", "DELETE", "/api/construction/profile-templates/1"),
    ("construction", "POST", "/api/construction/profile-templates/1/items"),
    ("construction", "DELETE", "/api/construction/profile-templates/1/items/1"),
    ("construction", "POST", "/api/construction/orders/1/profiles"), ("construction", "GET", "/api/construction/orders/1/profiles"),
    ("construction", "PUT", "/api/construction/profiles/1/items/1"), ("construction", "POST", "/api/construction/profiles/1/complete"),
    # inventory
    ("inventory", "GET", "/api/inventory/manifests"), ("inventory", "POST", "/api/inventory/manifests"),
    ("inventory", "GET", "/api/inventory/manifests/1"), ("inventory", "POST", "/api/inventory/manifests/1/confirm"),
    ("inventory", "DELETE", "/api/inventory/manifests/1/items/1"),
    ("inventory", "POST", "/api/inventory/manifests/reversals"), ("inventory", "POST", "/api/inventory/manifests/transfers"),
    ("inventory", "POST", "/api/inventory/manifests/adjustments"),
    ("inventory", "GET", "/api/inventory/outsourcings"), ("inventory", "POST", "/api/inventory/outsourcings"),
    ("inventory", "GET", "/api/inventory/outsourcings/1"), ("inventory", "POST", "/api/inventory/outsourcings/1/assign"),
    ("inventory", "POST", "/api/inventory/outsourcings/1/deliver"), ("inventory", "POST", "/api/inventory/outsourcings/1/receive"),
    ("inventory", "POST", "/api/inventory/outsourcings/1/charges"), ("inventory", "POST", "/api/inventory/outsourcings/1/balance"),
    ("inventory", "POST", "/api/inventory/outsourcings/1/reject-balance"), ("inventory", "POST", "/api/inventory/outsourcings/1/finish"),
    ("inventory", "POST", "/api/inventory/outsourcings/1/cancel"),
    ("inventory", "GET", "/api/inventory/purchases"), ("inventory", "POST", "/api/inventory/purchases"),
    ("inventory", "GET", "/api/inventory/purchases/1"), ("inventory", "POST", "/api/inventory/purchases/1/inbound"),
    ("inventory", "DELETE", "/api/inventory/purchases/1/items/1"), ("inventory", "POST", "/api/inventory/purchases/1/cancel"),
    ("inventory", "GET", "/api/inventory/requisitions"), ("inventory", "POST", "/api/inventory/requisitions"),
    ("inventory", "GET", "/api/inventory/requisitions/1"), ("inventory", "POST", "/api/inventory/requisitions/1/preout"),
    ("inventory", "POST", "/api/inventory/requisitions/1/outbound"), ("inventory", "POST", "/api/inventory/requisitions/1/arrive"),
    ("inventory", "POST", "/api/inventory/requisitions/1/cancel"), ("inventory", "POST", "/api/inventory/requisitions/1/refuse"),
    ("inventory", "POST", "/api/inventory/requisitions/1/returns"),
    ("inventory", "GET", "/api/inventory/warehouses"), ("inventory", "POST", "/api/inventory/warehouses"),
    ("inventory", "PUT", "/api/inventory/warehouses/1"), ("inventory", "GET", "/api/inventory/warehouses/1/users"),
    ("inventory", "POST", "/api/inventory/warehouses/1/users"), ("inventory", "DELETE", "/api/inventory/warehouses/1/users/1"),
    ("inventory", "GET", "/api/inventory/stocks"),
    # sales
    ("sales", "POST", "/api/sales/orders"), ("sales", "GET", "/api/sales/orders"), ("sales", "GET", "/api/sales/orders/1"),
    ("sales", "PUT", "/api/sales/orders/1/items"), ("sales", "POST", "/api/sales/orders/1/bill"),
    ("sales", "POST", "/api/sales/orders/1/verify-code"), ("sales", "POST", "/api/sales/orders/1/confirm"),
    ("sales", "POST", "/api/sales/orders/1/finish"), ("sales", "POST", "/api/sales/orders/1/terminate"),
    ("sales", "POST", "/api/sales/orders/1/refunds"), ("sales", "POST", "/api/sales/orders/1/deliveries"),
    ("sales", "POST", "/api/sales/orders/1/settlement-reject"),
    ("sales", "GET", "/api/sales/refunds"), ("sales", "GET", "/api/sales/refunds/1"),
    ("sales", "POST", "/api/sales/refunds/1/verify-code"), ("sales", "POST", "/api/sales/refunds/1/confirm"),
    ("sales", "GET", "/api/sales/deliveries"), ("sales", "GET", "/api/sales/deliveries/1"),
    ("sales", "POST", "/api/sales/delivery-items/1/advance"),
    ("sales", "GET", "/api/sales/settlements"), ("sales", "PUT", "/api/sales/settlements/1/vbalance"),
    ("sales", "POST", "/api/sales/settlements/1/vpost"), ("sales", "PUT", "/api/sales/settlements/1/pbalance"),
    ("sales", "POST", "/api/sales/settlements/1/post"),
    # balance
    ("balance", "GET", "/api/balance/settlements"), ("balance", "GET", "/api/balance/settlements/1"),
    ("balance", "POST", "/api/balance/settlements/1/lock"), ("balance", "POST", "/api/balance/settlements/1/unlock"),
    ("balance", "POST", "/api/balance/settlements/1/audit"), ("balance", "POST", "/api/balance/settlements/1/unaudit"),
    ("balance", "POST", "/api/balance/settlements/1/settle"), ("balance", "PUT", "/api/balance/settlements/1/discount"),
    ("balance", "POST", "/api/balance/settlements/1/unbind-item"), ("balance", "DELETE", "/api/balance/settlements/1"),
    ("balance", "POST", "/api/balance/settlements/1/enter"), ("balance", "POST", "/api/balance/settlements/1/pay"),
    ("balance", "POST", "/api/balance/settlements/pay-mark"), ("balance", "POST", "/api/balance/settlements/1/finish"),
    ("balance", "GET", "/api/balance/accounts"), ("balance", "POST", "/api/balance/accounts"),
    ("balance", "POST", "/api/balance/accounts/1/status"), ("balance", "POST", "/api/balance/accounts/1/cash-in"),
    ("balance", "GET", "/api/balance/accounts/1/details"),
    ("balance", "GET", "/api/balance/cutpay-pools"), ("balance", "POST", "/api/balance/cutpay-pools"),
    ("balance", "POST", "/api/balance/cutpay-pools/1/status"),
    ("balance", "GET", "/api/balance/cutpays"), ("balance", "GET", "/api/balance/cutpays/1"),
    ("balance", "POST", "/api/balance/cutpays"), ("balance", "POST", "/api/balance/cutpays/1/bind"),
    ("balance", "POST", "/api/balance/cutpays/1/unbind"), ("balance", "POST", "/api/balance/cutpays/1/deduct"),
    # market
    ("market", "POST", "/api/market/campaigns"), ("market", "GET", "/api/market/campaigns"),
    ("market", "PUT", "/api/market/campaigns/1"), ("market", "POST", "/api/market/campaigns/1/publish"),
    ("market", "POST", "/api/market/campaigns/1/disable"), ("market", "POST", "/api/market/campaigns/1/archive"),
    ("market", "POST", "/api/market/channels"), ("market", "GET", "/api/market/channels"),
    ("market", "PUT", "/api/market/channels/1"), ("market", "POST", "/api/market/channels/1/disable"),
    ("market", "POST", "/api/market/channels/1/enable"),
    ("market", "POST", "/api/market/sources"), ("market", "GET", "/api/market/sources"),
    ("market", "GET", "/api/market/sources/1"), ("market", "POST", "/api/market/sources/1/disable"),
    ("market", "POST", "/api/market/sources/1/enable"),
    ("market", "POST", "/api/market/inbounds"), ("market", "GET", "/api/market/inbounds"),
    ("market", "GET", "/api/market/inbounds/1"), ("market", "POST", "/api/market/inbounds/1/retry"),
    ("market", "POST", "/api/market/inbounds/1/ignore"),
    ("market", "GET", "/api/market/funnel/campaigns"), ("market", "GET", "/api/market/funnel/channels"),
    ("market", "POST", "/api/market/showcase/cases/1/lead"), ("market", "GET", "/api/market/showcase/leads"),
    ("market", "POST", "/api/market/showcase/cities"), ("market", "GET", "/api/market/showcase/cities"),
    ("market", "PUT", "/api/market/showcase/cities/1"), ("market", "DELETE", "/api/market/showcase/cities/1"),
    ("market", "POST", "/api/market/showcase/persons"), ("market", "GET", "/api/market/showcase/persons"),
    ("market", "PUT", "/api/market/showcase/persons/1"), ("market", "DELETE", "/api/market/showcase/persons/1"),
    ("market", "POST", "/api/market/showcase/cases"), ("market", "GET", "/api/market/showcase/cases"),
    ("market", "GET", "/api/market/showcase/cases/1"), ("market", "PUT", "/api/market/showcase/cases/1"),
    ("market", "POST", "/api/market/showcase/cases/1/status"), ("market", "DELETE", "/api/market/showcase/cases/1"),
    ("market", "POST", "/api/market/showcase/cases/1/pictures"), ("market", "DELETE", "/api/market/showcase/pictures/1"),
    # supplier(第 41 次)
    ("supplier", "POST", "/api/supplier/suppliers"), ("supplier", "GET", "/api/supplier/suppliers"),
    ("supplier", "GET", "/api/supplier/suppliers/1"), ("supplier", "PUT", "/api/supplier/suppliers/1"),
    ("supplier", "POST", "/api/supplier/suppliers/1/status"), ("supplier", "POST", "/api/supplier/upsert"),
    ("supplier", "GET", "/api/supplier/suppliers/1/brands"), ("supplier", "POST", "/api/supplier/suppliers/1/brands"),
    ("supplier", "POST", "/api/supplier/suppliers/1/brands/1/unbind"),
    ("supplier", "POST", "/api/supplier/suppliers/1/brands/1/primary"),
    ("supplier", "GET", "/api/supplier/suppliers/1/terms"),
    ("supplier", "PUT", "/api/supplier/suppliers/1/terms/brands/1"),
    ("supplier", "POST", "/api/supplier/suppliers/1/terms/brands/1/categories"),
    ("supplier", "DELETE", "/api/supplier/suppliers/1/terms/brands/1/categories/1"),
    ("supplier", "POST", "/api/supplier/suppliers/1/terms/brands/1/goods"),
    ("supplier", "DELETE", "/api/supplier/suppliers/1/terms/brands/1/goods/1"),
    # member(第 43 次)
    ("member", "GET", "/api/member/activities"), ("member", "POST", "/api/member/activities"),
    ("member", "PUT", "/api/member/activities/1"), ("member", "POST", "/api/member/activities/1/status"),
    ("member", "POST", "/api/member/activities/1/default"),
    ("member", "GET", "/api/member/config"), ("member", "POST", "/api/member/config"),
    ("member", "GET", "/api/member/gifts"), ("member", "POST", "/api/member/gifts"),
    ("member", "PUT", "/api/member/gifts/1"), ("member", "POST", "/api/member/gifts/upsert"),
    ("member", "POST", "/api/member/gifts/1/status"), ("member", "POST", "/api/member/gifts/1/stock"),
    ("member", "GET", "/api/member/gifts/1/records"),
    ("member", "GET", "/api/member/exchanges"), ("member", "POST", "/api/member/exchanges"),
    ("member", "GET", "/api/member/exchanges/1"), ("member", "POST", "/api/member/exchanges/1/cancel"),
    ("member", "POST", "/api/member/exchanges/items/1/consume"),
    ("member", "POST", "/api/member/exchanges/items/1/cancel"),
    ("member", "GET", "/api/member/points/accounts"),
    ("member", "GET", "/api/member/points/accounts/1/records"),
    ("member", "POST", "/api/member/points/adjust"),
    # service(第 89 次 B1:投诉/回访/保修)
    ("service", "GET", "/api/service/complaints"), ("service", "POST", "/api/service/complaints"),
    ("service", "POST", "/api/service/complaints/1/start"), ("service", "POST", "/api/service/complaints/1/handle"),
    ("service", "GET", "/api/service/visits"), ("service", "POST", "/api/service/visits"),
    ("service", "POST", "/api/service/visits/1/draft"), ("service", "POST", "/api/service/visits/1/finish"),
    ("service", "POST", "/api/service/visits/1/fail"),
    ("service", "GET", "/api/service/guarantees"), ("service", "POST", "/api/service/guarantees"),
    ("service", "POST", "/api/service/guarantees/1/cancel"),
    ("service", "GET", "/api/service/config"), ("service", "PUT", "/api/service/config"),
]

# 12 插件代表 GET 端点(admin/全授权组用;三条读放开端点返回 200 属预期)
REP_GETS = [
    ("/api/customer/leads", "客源"), ("/api/customer/chances", "机会"),
    ("/api/customerprofile/customers", "客户档案"), ("/api/example/leaves", "请假"),
    ("/api/basedata/goods", "商品"), ("/api/payment/bills", "款项"),
    ("/api/decoration/orders", "装修单"), ("/api/quotation/quotations", "报价"),
    ("/api/construction/orders", "施工"), ("/api/inventory/manifests", "库存"),
    ("/api/sales/orders", "销售"), ("/api/balance/settlements", "结算"),
    ("/api/market/inbounds", "进线"),
    ("/api/supplier/suppliers", "厂商"),
    ("/api/member/exchanges", "会员兑换单"),
    ("/api/service/complaints", "售后投诉"),
]

# 无角色拒绝面:详情/子资源/未放开列表(读放开的三条列表端点不含在内,放开行为在第 4 组断言)
REP_DENIES = [
    ("/api/customer/leads", "客源"), ("/api/customer/chances", "机会"),
    ("/api/customerprofile/customers/1", "客户档案详情"), ("/api/example/leaves", "请假"),
    ("/api/basedata/packages", "套餐档案"), ("/api/payment/bills", "款项"),
    ("/api/decoration/orders/1", "装修单详情"), ("/api/quotation/quotations", "报价"),
    ("/api/construction/orders", "施工"), ("/api/inventory/manifests", "库存"),
    ("/api/sales/orders", "销售"), ("/api/balance/settlements", "结算"),
    ("/api/market/inbounds", "进线"),
    ("/api/supplier/suppliers/1", "厂商详情"),
    ("/api/member/gifts/1", "礼品详情"),
    ("/api/service/visits/1", "回访任务详情"),
]


def main():
    rules = load_rules()
    step("载入 api 规则(17 插件 58 条)", len({r["addon"] for r in rules}) == 17 and len(rules) == 58,
         "addons=%s rules=%d" % (sorted({r["addon"] for r in rules}), len(rules)))

    # ===== 0. 静态完备性:全量端点样例 vs 模拟 match,零未声明 =====
    print("\n== 静态完备性(method 感知模拟 match)==")
    miss = [(a, m, u) for (a, m, u) in ENDPOINTS if mock_match(rules, u, m) is None]
    step("全量端点样例 %d 条零未声明" % len(ENDPOINTS), not miss, miss[:5])

    # methods 互补分层语义抽验
    r = mock_match(rules, "/api/basedata/goods", "GET")
    step("basedata GET 列表 → authenticated(读放开)", r and r["require"] == "authenticated", r)
    r = mock_match(rules, "/api/basedata/goods", "POST")
    step("basedata POST → 商品菜单(写收权)", r and r["require"] == "/basedata/goods", r)
    r = mock_match(rules, "/api/basedata/goods/1/prices", "GET")
    step("basedata GET 价格子资源 → authenticated(前缀读放开覆盖子树,V1 接受档案读开放)",
         r and r["require"] == "authenticated", r)
    r = mock_match(rules, "/api/basedata/brands", "GET")
    step("basedata GET 品牌字典 → authenticated(exact 读放开,第 41 次)", r and r["require"] == "authenticated", r)
    r = mock_match(rules, "/api/basedata/brands", "POST")
    step("basedata POST 品牌 → 商品菜单(写收权)", r and r["require"] == "/basedata/goods", r)
    r = mock_match(rules, "/api/decoration/orders", "GET")
    step("decoration GET 列表 → authenticated(exact)", r and r["require"] == "authenticated", r)
    r = mock_match(rules, "/api/decoration/orders", "POST")
    step("decoration POST → 装修单菜单", r and r["require"] == "/decoration/orders", r)
    r = mock_match(rules, "/api/decoration/orders/1", "GET")
    step("decoration GET 详情 → 装修单菜单(exact 不放行详情)", r and r["require"] == "/decoration/orders", r)
    r = mock_match(rules, "/api/customerprofile/customers", "GET")
    step("customerprofile GET 列表 → authenticated(exact)", r and r["require"] == "authenticated", r)
    r = mock_match(rules, "/api/customerprofile/customers/exist", "POST")
    step("customerprofile exist → capability(exact 优先)", r and r["require"] == "customerprofile:view", r)
    r = mock_match(rules, "/api/supplier/suppliers", "GET")
    step("supplier GET 列表 → authenticated(exact 读放开,第 45 次跨页选择器第 6 处)",
         r and r["require"] == "authenticated", r)
    r = mock_match(rules, "/api/supplier/suppliers", "POST")
    step("supplier POST 建厂商 → 厂商菜单(写收权)", r and r["require"] == "/supplier/suppliers", r)

    # ===== 1. admin 旁路 =====
    print("\n== admin 旁路 + 内核豁免 ==")
    admin = login("admin")
    if not admin:
        return finish()
    for path, label in REP_GETS:
        api_ok("admin 旁路 %s" % label, "GET", path, admin["token"])
    api_ok("admin 内核 /api/roles", "GET", "/api/roles", admin["token"])

    # ===== 2. 无角色全拒 =====
    print("\n== 无角色 example_d 全拒 122006 ==")
    d = login("example_d")
    if not d:
        return finish()
    for path, label in REP_DENIES:
        api_err("无角色拒 %s 122006" % label, "GET", path, d["token"], None, 122006)
    # 内核豁免对照:/api/roles 未命中 RBAC → 业务守卫 122003(非 122006)
    status, res = call("POST", "/api/roles", d["token"], {"code": "enfprobe", "name": "探针"})
    step("内核端点豁免(业务守卫 122003 而非 122006)",
         status == 200 and isinstance(res, dict) and res.get("code") == 122003, res)

    # ===== 3. 全授权全通 =====
    print("\n== 全授权(emp_all)全通 ==")
    addons_admin = api_ok("拉全量插件清单", "GET", "/api/addons", admin["token"])
    all_menus, all_caps = [], []
    for a in addons_admin or []:
        all_menus += [m.get("path") for m in a.get("menus") or []]
        all_caps += [m.get("capability") for m in a.get("mounts") or [] if m.get("capability")]
    all_menus, all_caps = [x for x in all_menus if x], [x for x in all_caps if x]
    # 幂等清理旧角色
    roles_old = api_ok("查旧 enforce 角色", "GET", "/api/roles", admin["token"])
    for r in roles_old or []:
        if str(r.get("code", "")).startswith("enf_"):
            api_ok("清理旧角色 %s" % r["code"], "DELETE", "/api/roles/%s" % r["id"], admin["token"])
    all_id = api_ok("建全授权角色 enf_all", "POST", "/api/roles", admin["token"],
                    {"code": "enf_all", "name": "enforce 全授权"})
    api_ok("全菜单授权", "PUT", "/api/roles/%s/menus" % all_id, admin["token"], {"menus": all_menus})
    api_ok("全功能点授权", "PUT", "/api/roles/%s/capabilities" % all_id, admin["token"], {"capabilities": all_caps})
    api_ok("绑定 example_d", "PUT", "/api/roles/%s/users" % all_id, admin["token"], {"userIds": [d["userId"]]})
    d2 = login("example_d")
    for path, label in REP_GETS:
        api_ok("全授权通 %s" % label, "GET", path, d2["token"])

    # ===== 4. 部分授权(仅 /sales/orders):authenticated 放开 + 菜单闸 =====
    print("\n== 部分授权(仅销售单菜单)==")
    api_ok("全授权角色摘除 example_d", "PUT", "/api/roles/%s/users" % all_id, admin["token"], {"userIds": []})
    sales_id = api_ok("建部分授权角色 enf_sales", "POST", "/api/roles", admin["token"],
                      {"code": "enf_sales", "name": "enforce 仅销售"})
    api_ok("仅授销售单菜单", "PUT", "/api/roles/%s/menus" % sales_id, admin["token"],
           {"menus": ["/sales/orders"]})
    api_ok("绑定 example_d", "PUT", "/api/roles/%s/users" % sales_id, admin["token"], {"userIds": [d["userId"]]})
    d3 = login("example_d")
    api_ok("有菜单:GET 销售单", "GET", "/api/sales/orders", d3["token"])
    api_ok("放开:GET 商品列表(authenticated)", "GET", "/api/basedata/goods", d3["token"])
    api_ok("放开:GET 装修单列表(exact authenticated)", "GET", "/api/decoration/orders", d3["token"])
    api_ok("放开:GET 客户列表(exact authenticated)", "GET", "/api/customerprofile/customers", d3["token"])
    api_err("收权:GET 客户详情 122006", "GET", "/api/customerprofile/customers/1", d3["token"], None, 122006)
    api_ok("放开:GET 厂商列表(exact authenticated,外辅选择器)", "GET", "/api/supplier/suppliers", d3["token"])
    api_err("收权:GET 厂商详情 122006", "GET", "/api/supplier/suppliers/1", d3["token"], None, 122006)
    api_err("收权:POST 商品 122006", "POST", "/api/basedata/goods", d3["token"], {"no": "x"}, 122006)
    api_err("收权:POST 装修单 122006", "POST", "/api/decoration/orders", d3["token"], {}, 122006)
    api_err("收权:POST exist 122006(capability)", "POST", "/api/customerprofile/customers/exist", d3["token"], {}, 122006)
    api_err("无菜单:GET 款项 122006", "GET", "/api/payment/bills", d3["token"], None, 122006)
    api_err("无菜单:GET 库存 122006", "GET", "/api/inventory/manifests", d3["token"], None, 122006)
    api_err("无菜单:GET 结算 122006", "GET", "/api/balance/settlements", d3["token"], None, 122006)

    # ===== 5. 清理 =====
    print("\n== 清理 ==")
    api_ok("删除 enf_all", "DELETE", "/api/roles/%s" % all_id, admin["token"])
    api_ok("删除 enf_sales", "DELETE", "/api/roles/%s" % sales_id, admin["token"])
    d4 = login("example_d")
    api_err("清理后回落拒 122006", "GET", "/api/sales/orders", d4["token"], None, 122006)

    finish()


def finish():
    print("\n== 汇总 ==")
    print("%d/%d PASS" % (len(passed), len(passed) + len(failed)))
    if failed:
        print("FAILED:")
        for f in failed:
            print("  ✗ %s" % f)
        sys_exit(1)
    sys_exit(0)


def sys_exit(code):
    import sys
    sys.exit(code)


if __name__ == "__main__":
    main()
