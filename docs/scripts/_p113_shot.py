# -*- coding: utf-8 -*-
# 第113次:施工域三方向初稿截图(每屏元素截图 + 整板图)
from playwright.sync_api import sync_playwright
import os

BASE = r"D:/ERP/yingzao"
OUT = os.path.join(BASE, "docs", "screenshots", "design")
os.makedirs(OUT, exist_ok=True)

# 每方向屏幕命名(DOM 顺序)
NAMES = {
    1: ["list", "detail", "timeline", "confirm", "diary", "plans"],
    2: ["list", "detail", "timeline", "confirm", "diary", "plans"],
    3: ["diary", "list", "detail", "timeline", "confirm", "plans"],  # dir-3 主视觉日记在前
}

with sync_playwright() as pw:
    browser = pw.chromium.launch()
    for n in (1, 2, 3):
        page = browser.new_page(viewport={"width": 2200, "height": 1400}, device_scale_factor=2)
        page.goto("file:///" + (BASE + "/docs/design/construction-dir-%d.html" % n).replace("\\", "/"))
        page.wait_for_timeout(900)
        phones = page.locator(".phone")
        cnt = phones.count()
        names = NAMES[n]
        for i in range(cnt):
            nm = names[i] if i < len(names) else ("s%d" % (i + 1))
            phones.nth(i).screenshot(path=os.path.join(OUT, "construction-dir%d-%s.png" % (n, nm)))
        page.screenshot(path=os.path.join(OUT, "construction-dir%d-board.png" % n), full_page=True)
        print("dir%d: %d phones + board" % (n, cnt))
        page.close()
    browser.close()
print("done")
