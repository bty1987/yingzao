# 第105次 R7 冒烟素材:两张纸墨风 SVG 相册占位图(web-mp/src/static/smoke/,不入库)
# uni-app 静态资源约定 src/static(H5 dev 伺服 /static/,publicDir 被 uni 插件接管不伺服)。
import os
os.makedirs(r"D:\ERP\yingzao\web-mp\src\static\smoke", exist_ok=True)

TPL = """<svg xmlns="http://www.w3.org/2000/svg" width="640" height="480" viewBox="0 0 640 480">
<rect width="640" height="480" fill="#F1EFE8"/>
<rect x="24" y="24" width="592" height="432" fill="none" stroke="#1A1A18" stroke-opacity="0.35" stroke-width="2"/>
<rect x="34" y="34" width="572" height="412" fill="none" stroke="#1A1A18" stroke-opacity="0.14" stroke-width="1"/>
<text x="320" y="216" font-family="Songti SC,SimSun,serif" font-size="64" fill="#1A1A18" fill-opacity="0.78" text-anchor="middle">{label}</text>
<text x="320" y="272" font-family="sans-serif" font-size="18" letter-spacing="6" fill="#1A1A18" fill-opacity="0.4" text-anchor="middle">YINGZAO · {en}</text>
<text x="320" y="404" font-family="sans-serif" font-size="16" letter-spacing="3" fill="#B03A2E" fill-opacity="0.85" text-anchor="middle">实 景 作 品</text>
</svg>"""

for fname, label, en in [("p1.svg", "作品一", "WORK 01"), ("p2.svg", "作品二", "WORK 02")]:
    path = os.path.join(r"D:\ERP\yingzao\web-mp\src\static\smoke", fname)
    with open(path, "w", encoding="utf-8") as f:
        f.write(TPL.format(label=label, en=en))
    print("written", path)
