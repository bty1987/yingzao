<template>
  <view>
    <view class="card hello">
      <text class="cell-title yz-display" style="font-size: 34rpx">你好,{{ displayName }}</text>
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
      <view class="cell" @click="go('/pages/leave/leave')">
        <view class="cell-main">
          <text class="cell-title">请假示例</text>
          <text class="cell-sub">插件接入轻量审批的示范(days≥3 两级审批)</text>
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
      <view class="cell" @click="go('/pkg-base/pages/playground')">
        <view class="cell-main">
          <text class="cell-title">组件示范</text>
          <text class="cell-sub">基础组件基建验收页(R12~R13,R14 配置化时由槽位下发取代)</text>
        </view>
        <text class="arrow">›</text>
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
  border-bottom: 1rpx solid var(--yz-line);
}
.cell:last-child { border-bottom: none; }
.cell-main { display: flex; flex-direction: column; flex: 1; }
.cell-side { display: flex; align-items: center; }
.arrow { color: var(--yz-ink-35); font-size: 36rpx; margin-left: 16rpx; }
.badge {
  background: var(--yz-seal);
  color: var(--yz-paper);
  font-size: 22rpx;
  border-radius: 4rpx;
  padding: 2rpx 14rpx;
}
</style>
