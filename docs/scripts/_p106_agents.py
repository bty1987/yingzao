# -*- coding: utf-8 -*-
"""第 106 次:AGENTS.md 进度节维护——插入 106 条目,104 压缩为指针。"""
import io

P = r"D:\ERP\yingzao\AGENTS.md"
with io.open(P, "r", encoding="utf-8") as f:
    text = f.read()

ENTRY_106 = """## 当前进度(2026-10-31 更新,第 106 次运行:D4 看板页三方向重出——纸墨体系内三诠释,待拍板)

- ✅ **D4 看板页三方向重出兑现(第 106 次,纯设计轮,零代码零库零冒烟;执行 2026-10-30 拍板「重出必须锚定 yingzao.css token 在体系内做三诠释」)**:①**三方向初稿落盘 docs/design/dashboard-dir-r2-1/2/3.html**(布局恒 docs/06 §4.2 五区块+§5 图表硬规则,数据三方向完全一致=与第一轮同数纯风格对比):方向一「素版·界格」(整页即一页素纸:无卡无框无投影,宋版书界行分栏,朱最少=结论朱批点+名次第一)/方向二「账册·版框」(淡墨单线框卡 2px 方角无投影+头部朱印方章+古籍表线,与 Users/Plans 现有管理页同族延伸,一体化最自然实现成本最低)/方向三「朱批·卷页」(名帖双框装裱整页=外强墨线内淡墨线与 web-mp 名帖页同语+纸深分区+异常走「朱批」带(纸深底+朱侧线+「批」印)+预警卡顶边赭金识别条+排名首位「首」微印,朱用量最重仍受「异常是唯一红焦点」约束);②**体系锚定**:token 逐字取自 yingzao.css(纸白暖底 #FAF9F5/墨黑 #1A1A18/朱印红 #B03A2E 唯一点缀/宋体 display/8px 模数/2px 方角),语义色仅功能性(正常=苔绿 #5A7247=token --el-color-success/预警=深赭 #856011 体系内衍生暖色/告警红=朱印红本体);🔴**可读性校准回写候选:--yz-ink-50 文本场景 3.3:1 不达 4.5:1,初稿内统一 rgba(26,26,24,.62)=4.85:1,正式实现建议 yingzao.css 增 --yz-ink-62 或用途分层**(方向卡已记);③huashu-design design_lint 三稿红灯全清(标签 11→12px/脚注 14px/装饰性中点改顿号,余 2 处黄灯=方向名内字间间隔号汉语正字保留)+playwright 1600×900 截图三张入仓 docs/screenshots/design/(亲验三屏);④文档回写:方向卡 docs/design/dashboard-design-r2.md+direction-approved.md 增附录(D4 方向轮:第一轮全否记录+第二轮三诠释)/PRD 状态行 R2 标注/docs/15 R38 行更新/AGENTS 本条(第 104 次压缩为指针);**🔴待用户拍板=三方向任选或混搭(唯一闸),拍板后下轮 Home.vue 正式实现(ECharts+视觉验收)**;下一轮候选=看板页拍板→Home.vue 正式实现>B2 品牌跳级 PRD(按 10-30 拍板「做」,先出行业中性 PRD)>兜底=R12~R13 移动端组件基建或 D4 二轮;老系统零触碰,fl-yy 零触碰,生产库零访问;

"""

PTR_104 = """- 第 104 次:D4 驾驶舱写码首轮——内核 report 最小面(F8:MetricProvider SPI/MetricRegistry/ReportController 三端点/空态四分支/V109 埋点表)+第 18 插件 addon-dashboard(390000 段/V1901 落地页表/DashboardService.home 六维健康卡+结论条+双线+大区排名,全数据经 MetricRegistry 零跨域耦合)+北极星五 provider(签约额契约包装/达成率/回款/主材签单权责/工程维唯一新表 V605 ads yz_plan 日刷链),五跑冒烟 2520/2520+enforce 101/101(18 插件 64 条)全绿,三真产品 bug 修(yearMonth=2026-13 月份范围正则/orgScope instanceof Set 判空越权放行→Collection/Connector/J datetime 强转 Timestamp);完整记录见 git 16d1175 与 docs/reports/2026-10-30-第104次运行总结.md;
"""

# 1) 压缩第 104 次整条(标题+正文)为指针
old_head = "## 当前进度(2026-10-30 更新,第 104 次运行:D4 写码首轮——内核 report 最小面+addon-dashboard 骨架)"
i = text.index(old_head)
j = text.index("## 拍板补充(2026-10-30 交互轮,第 105 次起生效)")
text = text[:i] + text[j:]

# 2) 第 103 次及以前 → 第 104 次及以前,补 104 指针
old_sec = "## 第 103 次及以前(2026-10-30)\n\n- 第 103 次:"
new_sec = "## 第 104 次及以前(2026-10-30)\n\n" + PTR_104 + "- 第 103 次:"
assert old_sec in text
text = text.replace(old_sec, new_sec)

# 3) 插入第 106 次条目于第 105 次节前
anchor = "## 当前进度(2026-10-31 更新,第 105 次运行:R7 web-mp 匿名名片页——D6b 电子名片收官)"
assert anchor in text
text = text.replace(anchor, ENTRY_106 + anchor, 1)

with io.open(P, "w", encoding="utf-8", newline="\n") as f:
    f.write(text)
print("AGENTS.md updated, len =", len(text))
