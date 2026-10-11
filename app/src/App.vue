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
page {
  background-color: #f5f6f8;
  color: #2b2f36;
  font-size: 28rpx;
}

.card {
  background: #ffffff;
  border-radius: 16rpx;
  padding: 24rpx;
  margin: 20rpx;
}

.cell-title {
  font-size: 30rpx;
  font-weight: 600;
}

.cell-sub {
  color: #8a94a6;
  font-size: 24rpx;
  margin-top: 8rpx;
}

.tag {
  display: inline-block;
  font-size: 22rpx;
  padding: 4rpx 14rpx;
  border-radius: 8rpx;
  line-height: 1.5;
}

.tag-info { background: #f0f2f5; color: #5a6474; }
.tag-success { background: #e8f7ee; color: #1f9d61; }
.tag-danger { background: #fdecec; color: #d84a45; }
.tag-warning { background: #fdf3e6; color: #c47b1d; }

.btn-primary {
  margin-top: 24rpx;
  background: #2f6fed;
  color: #ffffff;
  border-radius: 12rpx;
  font-size: 30rpx;
}

.btn-primary[disabled] { opacity: 0.6; }

.btn-mini {
  display: inline-block;
  font-size: 24rpx;
  padding: 8rpx 24rpx;
  margin-right: 16rpx;
  border-radius: 8rpx;
  border: 1rpx solid #d5dae2;
  color: #40485a;
  background: #ffffff;
}

.btn-mini.success { color: #1f9d61; border-color: #b5e3ca; }
.btn-mini.danger { color: #d84a45; border-color: #f3c1bf; }

.empty {
  text-align: center;
  color: #8a94a6;
  padding: 120rpx 0;
}
</style>
