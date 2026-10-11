<template>
  <view>
    <view class="card profile">
      <view class="avatar">{{ initial }}</view>
      <view class="who">
        <text class="cell-title">{{ displayName }}</text>
        <text class="cell-sub">用户 ID {{ userId }}</text>
      </view>
    </view>

    <view class="card">
      <view class="cell" @click="go('/pages/notifications/notifications')">
        <view class="cell-main">
          <text class="cell-title">通知</text>
        </view>
        <view class="cell-side">
          <text v-if="unread > 0" class="badge">{{ unread > 99 ? '99+' : unread }}</text>
          <text class="arrow">›</text>
        </view>
      </view>
      <view class="cell" @click="onLogout">
        <view class="cell-main">
          <text class="cell-title logout">退出登录</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script>
import { fetchUnreadCount } from '../../api'

export default {
  data() {
    return { displayName: '', userId: '', unread: 0 }
  },
  computed: {
    initial() {
      return this.displayName ? this.displayName.charAt(0) : '客'
    }
  },
  onShow() {
    this.displayName = uni.getStorageSync('yz_display_name') || ''
    this.userId = uni.getStorageSync('yz_user_id') || ''
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
        // 静默
      }
    },
    onLogout() {
      uni.showModal({
        title: '退出登录',
        content: '确定退出当前账号?',
        success: (r) => {
          if (!r.confirm) return
          uni.removeStorageSync('yz_token')
          uni.removeStorageSync('yz_user_id')
          uni.removeStorageSync('yz_display_name')
          uni.reLaunch({ url: '/pages/login/login' })
        }
      })
    }
  }
}
</script>

<style scoped>
.profile {
  display: flex;
  align-items: center;
  gap: 24rpx;
  padding: 36rpx 24rpx;
}

.avatar {
  width: 96rpx;
  height: 96rpx;
  border-radius: 50%;
  background: #2f6fed;
  color: #ffffff;
  font-size: 40rpx;
  display: flex;
  align-items: center;
  justify-content: center;
}

.who { display: flex; flex-direction: column; }

.cell {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 28rpx 0;
  border-bottom: 1rpx solid #f0f2f5;
}

.cell:last-child { border-bottom: none; }

.cell-main { flex: 1; }

.cell-side { display: flex; align-items: center; }

.arrow { color: #c2c9d4; font-size: 36rpx; margin-left: 16rpx; }

.badge {
  background: #d84a45;
  color: #ffffff;
  font-size: 22rpx;
  border-radius: 999rpx;
  padding: 2rpx 14rpx;
}

.logout { color: #d84a45; }
</style>
