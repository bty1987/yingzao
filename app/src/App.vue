<script>
export default {
  onLaunch() {
    // 未登录一律回到登录页(tabBar 页面不可 navigateTo 跳离,用 reLaunch)
    if (!uni.getStorageSync('yz_token')) {
      uni.reLaunch({ url: '/pages/login/login' })
    }
  }
}
</script>

<style>
/* ============================================================
   营造 Yingzao · 全局视觉 token(方向:东方纸墨极简)
   选定记录:docs/design/direction-approved.md(2026-10-11)
   与 web/src/styles/yingzao.css 同源:纸白暖底/墨黑/朱印红唯一点缀/
   宋体 display/8px 模数(材分制)/方角/细墨线分隔
   ============================================================ */
page {
  /* 色板 */
  --yz-paper: #FAF9F5;
  --yz-paper-deep: #F1EFE8;
  --yz-ink: #1A1A18;
  --yz-ink-70: rgba(26, 26, 24, 0.70);
  --yz-ink-50: rgba(26, 26, 24, 0.50);
  --yz-ink-35: rgba(26, 26, 24, 0.35);
  --yz-line: rgba(26, 26, 24, 0.12);
  --yz-line-strong: rgba(26, 26, 24, 0.55);
  --yz-seal: #B03A2E;
  --yz-seal-deep: #9E2B25;
  --yz-moss: #5A7247;
  --yz-ochre: #A6864B;

  background-color: var(--yz-paper);
  color: var(--yz-ink);
  font-size: 28rpx;
}

/* 宋体 display(标题/品牌/数字) */
.yz-display {
  font-family: 'Songti SC', 'SimSun', 'Noto Serif SC', serif;
}

/* 英文小标(YINGZAO 宽字距) */
.yz-eyebrow {
  font-size: 20rpx;
  letter-spacing: 8rpx;
  color: var(--yz-ink-50);
  text-transform: uppercase;
}

/* 品牌朱印(方章):朱底纸字阴文竖排 */
.yz-seal {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 88rpx;
  height: 88rpx;
  background: var(--yz-seal);
  color: var(--yz-paper);
  font-family: 'Songti SC', 'SimSun', 'Noto Serif SC', serif;
  font-size: 34rpx;
  line-height: 1.15;
  letter-spacing: 6rpx;
  border-radius: 4rpx;
  writing-mode: vertical-rl;
  text-orientation: upright;
}

/* 卡片:白纸面+细墨线+方角(材分制:4rpx=2px 微圆角) */
.card {
  background: #FFFFFF;
  border: 1rpx solid var(--yz-line);
  border-radius: 4rpx;
  padding: 24rpx;
  margin: 20rpx;
}

.cell-title {
  font-size: 30rpx;
  font-weight: 600;
  color: var(--yz-ink);
}

.cell-sub {
  color: var(--yz-ink-50);
  font-size: 24rpx;
  margin-top: 8rpx;
}

.tag {
  display: inline-block;
  font-size: 22rpx;
  padding: 4rpx 14rpx;
  border-radius: 4rpx;
  line-height: 1.5;
}

.tag-info { background: var(--yz-paper-deep); color: var(--yz-ink-70); }
.tag-success { background: rgba(90, 114, 71, 0.12); color: var(--yz-moss); }
.tag-danger { background: rgba(176, 58, 46, 0.10); color: var(--yz-seal); }
.tag-warning { background: rgba(166, 134, 75, 0.14); color: var(--yz-ochre); }

/* 主按钮:墨黑底纸白字,方角(对齐 web 墨黑主按钮) */
.btn-primary {
  margin-top: 24rpx;
  background: var(--yz-ink);
  color: var(--yz-paper);
  border-radius: 4rpx;
  font-size: 30rpx;
}

.btn-primary[disabled] { opacity: 0.5; }

.btn-mini {
  display: inline-block;
  font-size: 24rpx;
  padding: 8rpx 24rpx;
  margin-right: 16rpx;
  border-radius: 4rpx;
  border: 1rpx solid var(--yz-line);
  color: var(--yz-ink-70);
  background: #FFFFFF;
}

.btn-mini.success { color: var(--yz-moss); border-color: rgba(90, 114, 71, 0.4); }
.btn-mini.danger { color: var(--yz-seal); border-color: rgba(176, 58, 46, 0.4); }
.btn-mini.warn { color: var(--yz-ochre); border-color: rgba(166, 134, 75, 0.4); }

.empty {
  text-align: center;
  color: var(--yz-ink-35);
  padding: 120rpx 0;
}
</style>
