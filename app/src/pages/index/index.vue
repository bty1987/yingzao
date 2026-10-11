<template>
  <view>
    <view class="card hello">
      <text class="cell-title">你好,{{ displayName }}</text>
      <text class="cell-sub">今天也要把家交好</text>
    </view>

    <view class="card">
      <view class="cell" @click="go('/pages/leads/create')">
        <view class="cell-main">
          <text class="cell-title">登记客源</text>
          <text class="cell-sub">店头/渠道线索现场登记</text>
        </view>
        <text class="arrow">›</text>
      </view>
      <view class="cell" @click="go('/pages/chances/chances')">
        <view class="cell-main">
          <text class="cell-title">我的机会</text>
          <text class="cell-sub">分配给我的客源机会跟进</text>
        </view>
        <text class="arrow">›</text>
      </view>
      <view class="cell" @click="go('/pages/notifications/notifications')">
        <view class="cell-main">
          <text class="cell-title">通知</text>
          <text class="cell-sub">审批与分配动态</text>
        </view>
        <view class="cell-side">
          <text v-if="unread > 0" class="badge">{{ unread > 99 ? '99+' : unread }}</text>
          <text class="arrow">›</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script>
import { fetchUnreadCount } from '../../api'

export default {
  data() {
    return { displayName: '', unread: 0 }
  },
  onShow() {
    this.displayName = uni.getStorageSync('yz_display_name') || ''
    this.refreshUnread()
  },
  methods: {
    go(url) {
      uni.navigateTo({ url })
    },
    async refreshUnread() {
      try {
        const res = await fetchUnreadCount()
        this.unread = res.data || 0
      } catch (e) {
        // 轮询失败静默,下次 onShow 重试
      }
    }
  }
}
</script>

<style scoped>
.hello { padding: 32rpx 24rpx; }
.cell {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 28rpx 0;
  border-bottom: 1rpx solid #f0f2f5;
}
.cell:last-child { border-bottom: none; }
.cell-main { display: flex; flex-direction: column; flex: 1; }
.cell-side { display: flex; align-items: center; }
.arrow { color: #c2c9d4; font-size: 36rpx; margin-left: 16rpx; }
.badge {
  background: #d84a45;
  color: #ffffff;
  font-size: 22rpx;
  border-radius: 999rpx;
  padding: 2rpx 14rpx;
}
</style>
