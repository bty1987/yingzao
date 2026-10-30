# 设计方向档案(Gate 文件)

## 三方向展示(huashu-design 三套逻辑,2026-10-10 夜间产出)

| 方向 | 文件 | 逻辑 |
|---|---|---|
| 一·巨字大字报 | `direction-1-big-type.html` | 秒数轮盘 6 号 · Bold Big-Type Editorial(参照 Jacquemus) |
| 二·石墨工具暗色 | `direction-2-linear-dark.html` | 现实参照 · Linear |
| 三·东方纸墨极简 | `direction-3-oriental-minimal.html` | 最佳设计师 · 原研哉式东方极简 |

## 用户选择

- **选择原话:「方向三」**(2026-10-11)
- **选定:东方纸墨极简**(`direction-3-oriental-minimal.html`)

## 选定方向的设计 DNA(落地为 `web/src/styles/yingzao.css` 视觉 token)

- 色板:纸白暖底 `#FAF9F5` / 墨黑 `#1A1A18` / 朱印红 `#B03A2E`(唯一点缀)/ 淡墨线 `rgba(26,26,24,.15)`;
- 字型:display 宋体栈(`'Songti SC','SimSun','Noto Serif SC',serif`)× 正文系统黑体;英文小标 YINGZAO 宽字距;
- 模数:全部间距/尺寸 8px 模数(材分制);
- 形状:方角或 2px 微圆角;分隔用细墨线;朱印方章为品牌签名元素;
- 母题:《营造法式》古籍扉页/版框/朱印——**form 来自品牌典出本身**。

## 状态

- [x] 三方向初稿展示(2026-10-10,提交 abb2699)
- [x] 用户选定方向三(2026-10-11)
- [x] 视觉 token 化 + web/ 页面落地(2026-10-11)
- [ ] huashu-design 视觉验收(用户浏览器确认后闭环)

---

## 附:D4 驾驶舱看板页方向轮(子页面,沿用纸墨体系)

### 第一轮(2026-10-30,第 104 次)——三方向全否

- 初稿:`dashboard-dir-1/2/3.html`(经营蓝调/分区工作台/夜航指挥舱);
- **用户原话:「不符合现在整体的风格样式」**——根因=三方向脱离 web 既定纸墨体系(蓝渐变/淡蓝分区/深色大屏均跨体系);
- 裁决:重出初稿必须锚定 `web/src/styles/yingzao.css` token 源,在体系内做三诠释(见 2026-10-30 拍板补充)。

### 第二轮(2026-10-31,第 106 次)——体系内三诠释,🔴待用户拍板

| 方向 | 文件 | 截图 | 一句话 |
|---|---|---|---|
| 一·素版·界格 | `dashboard-dir-r2-1.html` | `docs/screenshots/design/dashboard-dir-r2-1.png` | 整页即一页素纸:无卡无框,宋版书界行分栏,朱最少 |
| 二·账册·版框 | `dashboard-dir-r2-2.html` | `docs/screenshots/design/dashboard-dir-r2-2.png` | 淡墨单线框卡+头部朱印方章,与现有管理页同族延伸 |
| 三·朱批·卷页 | `dashboard-dir-r2-3.html` | `docs/screenshots/design/dashboard-dir-r2-3.png` | 名帖双框装裱+纸深分区+朱批异常带,朱用量最重但受约束 |

- 三方向同守:纸白暖底/墨黑/朱印红唯一点缀/宋体 display/8px 模数/2px 方角 + docs/06 §4.2 五区块布局与 §5 图表硬规则(数据三方向完全一致,纯风格对比);
- 可读性校准:初稿内 ink-50 文本统一提到 .62(3.3:1→4.85:1),脚注 14px——正式实现同步此口径(见方向卡);
