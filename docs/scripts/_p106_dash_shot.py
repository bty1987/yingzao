# -*- coding: utf-8 -*-
"""第 106 次:D4 看板三方向重出——playwright 截图 1600x900 入仓 docs/screenshots/design/"""
import asyncio, pathlib
from playwright.async_api import async_playwright

ROOT = pathlib.Path(r"D:\ERP\yingzao")
PAGES = [
    ("dashboard-dir-r2-1", ROOT / "docs/design/dashboard-dir-r2-1.html"),
    ("dashboard-dir-r2-2", ROOT / "docs/design/dashboard-dir-r2-2.html"),
    ("dashboard-dir-r2-3", ROOT / "docs/design/dashboard-dir-r2-3.html"),
]
OUT = ROOT / "docs/screenshots/design"
OUT.mkdir(parents=True, exist_ok=True)

async def main():
    async with async_playwright() as p:
        browser = await p.chromium.launch()
        page = await browser.new_page(viewport={"width": 1600, "height": 900})
        for name, path in PAGES:
            await page.goto(path.as_uri())
            await page.wait_for_timeout(400)
            await page.screenshot(path=str(OUT / f"{name}.png"))
            print("shot:", name)
        await browser.close()

asyncio.run(main())
