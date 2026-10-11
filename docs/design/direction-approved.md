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
