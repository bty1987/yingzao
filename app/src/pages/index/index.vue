<template>
  <view>
    <view class="card hello">
      <text class="cell-title yz-display" style="font-size: 34rpx">你好,{{ displayName }}</text>
      <text class="cell-sub">今天也要把家交好</text>
    </view>

    <!-- 工作台宫格(R14 配置化):条目 = GET /api/addons 各插件的 mobile 槽位段,
         后端已按当前用户授权过滤(无权限格子不出现),前端按 sort 跨插件合并排序 -->
    <view v-if="gridItems.length" class="card">
      <yz-grid :items="gridItems" :columns="4" @tap="goGrid" />
    </view>
    <view v-else class="card">
      <text class="cell-sub">暂无应用,由已启用插件按权限下发</text>
    </view>

    <!-- 内核固定入口:通知(带未读徽章,内核设施不上 addon.yml,与 tabBar 同属 app 壳) -->
    <view class="card">
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
import { fetchUnreadCount, listAddons } from '../../api'
import yzGrid from '../../components/yz-grid.vue'

export default {
  components: { yzGrid },
  data() {
    return { displayName: '', unread: 0, gridItems: [] }
  },
  onShow() {
    this.displayName = uni.getStorageSync('yz_display_name') || ''
    this.refreshUnread()
    this.refreshGrid()
  },
  methods: {
    go(url) {
      uni.navigateTo({ url })
    },
    goGrid(item) {
      this.go(item.page)
    },
    async refreshGrid() {
      try {
        const res = await listAddons()
        const items = []
        for (const a of (res.data || [])) {
          for (const m of (a.mobile || [])) {
            items.push({
              key: a.id + m.page,
              icon: m.icon,
              title: m.title,
              sub: m.sub || '',
              page: m.page,
              sort: m.sort || 0,
              addon: a.id
            })
          }
        }
        // 跨插件排序:sort 升序,同 sort 按插件 id 稳定
        items.sort((x, y) => x.sort - y.sort || (x.addon < y.addon ? -1 : x.addon > y.addon ? 1 : 0))
        this.gridItems = items
      } catch (e) {
        // 拉取失败静默,下次 onShow 重试(保留现有格子避免闪空)
      }
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
