<template>
  <view>
    <view class="toolbar">
      <text class="btn-mini" :class="{ disabled: unread === 0 }" @click="onReadAll">全部已读</text>
    </view>

    <view v-if="rows.length === 0" class="empty">暂无通知</view>
    <view
      v-for="n in rows"
      :key="n.id"
      class="card"
      :class="{ unread: n.read_flag === 0 }"
      @click="onRead(n)"
    >
      <view class="head">
        <text class="cell-title">{{ n.title }}</text>
        <text v-if="n.read_flag === 0" class="dot" />
      </view>
      <view class="cell-sub content">{{ n.content }}</view>
      <view class="cell-sub">{{ n.created_at }}</view>
    </view>
  </view>
</template>

<script>
import { listNotifications, markNotificationRead, markAllNotificationsRead, fetchUnreadCount } from '../../api'

export default {
  data() {
    return { rows: [], unread: 0 }
  },
  onShow() {
    this.load()
  },
  onPullDownRefresh() {
    this.load().finally(() => uni.stopPullDownRefresh())
  },
  methods: {
    async load() {
      try {
        const res = await listNotifications({ page: 1, size: 50 })
        this.rows = res.data || []
        this.refreshUnread()
      } catch (e) {
        uni.showToast({ title: e.message || '加载失败', icon: 'none' })
      }
    },
    async refreshUnread() {
      try {
        const res = await fetchUnreadCount()
        this.unread = res.data || 0
      } catch (e) {
        // 静默
      }
    },
    async onRead(n) {
      if (n.read_flag !== 0) return
      try {
        await markNotificationRead(n.id)
        n.read_flag = 1
        this.refreshUnread()
      } catch (e) {
        uni.showToast({ title: e.message || '操作失败', icon: 'none' })
      }
    },
    async onReadAll() {
      if (this.unread === 0) return
      try {
        await markAllNotificationsRead()
        this.rows.forEach((n) => { n.read_flag = 1 })
        this.unread = 0
      } catch (e) {
        uni.showToast({ title: e.message || '操作失败', icon: 'none' })
      }
    }
  }
}
</script>

<style scoped>
.toolbar {
  display: flex;
  justify-content: flex-end;
  padding: 20rpx 20rpx 0;
}

.toolbar .disabled { color: #c2c9d4; }

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8rpx;
}

.dot {
  width: 14rpx;
  height: 14rpx;
  border-radius: 50%;
  background: #d84a45;
}

.content { margin-bottom: 8rpx; }
</style>
