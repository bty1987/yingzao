<script>
export default {
  onLaunch() {
    // C 端首页(案例库)匿名可逛,不强制登录;进度/积分页自行做登录墙
  }
}
</script>

<style>
/* ============================================================
   营造 Yingzao · C 端全局视觉 token(方向:东方纸墨极简)
   与员工端 app/src/App.vue / web yingzao.css 同源:纸白暖底/墨黑/
   朱印红唯一点缀/宋体 display/方角/细墨线
   ============================================================ */
page {
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

  background-color: var(--yz-paper);
  color: var(--yz-ink);
  font-size: 28rpx;
}

.yz-display { font-family: 'Songti SC', 'SimSun', 'Noto Serif SC', serif; }

.yz-eyebrow {
  font-size: 20rpx;
  letter-spacing: 8rpx;
  color: var(--yz-ink-50);
  text-transform: uppercase;
}

.yz-seal {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 88rpx;
  height: 88rpx;
  background: var(--yz-seal);
  color: var(--yz-paper);
  font-family: 'Songti SC', 'SimSun', serif;
  font-size: 40rpx;
  flex-shrink: 0;
}

.card {
  background: var(--yz-paper);
  border: 1rpx solid var(--yz-line);
  border-radius: 4rpx;
  padding: 24rpx;
  margin: 0 24rpx 20rpx;
}

.btn-primary {
  background: var(--yz-ink);
  color: var(--yz-paper);
  border-radius: 4rpx;
  font-size: 30rpx;
  padding: 20rpx 0;
  text-align: center;
}
.btn-ghost {
  border: 1rpx solid var(--yz-line-strong);
  color: var(--yz-ink);
  border-radius: 4rpx;
  font-size: 30rpx;
  padding: 20rpx 0;
  text-align: center;
}
.tip { font-size: 22rpx; color: var(--yz-ink-50); }
.empty { text-align: center; color: var(--yz-ink-35); padding: 120rpx 0; font-size: 26rpx; }
</style>
