<template>
  <view>
    <view v-if="rows.length === 0" class="empty">暂无分配给我的机会</view>
    <view v-for="c in rows" :key="c.id" class="card">
      <view class="head">
        <text class="cell-title">{{ c.lead_name }}</text>
        <text class="tag" :class="statusTag(c.status)">{{ statusLabel(c.status) }}</text>
      </view>
      <view class="cell-sub">客户电话:{{ c.phone || '—' }}</view>
      <view class="cell-sub">分配时间:{{ c.assigned_at || '—' }}</view>
      <view v-if="c.status === 0" class="actions">
        <text class="btn-mini success" @click="onConfirm(c)">确认接单</text>
      </view>
      <view v-else-if="c.status === 3" class="actions">
        <text class="btn-mini" @click="onComplete(c)">标记完成</text>
      </view>
    </view>
  </view>
</template>

<script>
import { listChances, confirmChance, completeChance } from '../../api'

// 机会状态机:0 已分配 → 3 已确认 → 4 已完成(与 ChanceService 常量一致)
const STATUS = { 0: '待确认', 3: '已确认', 4: '已完成' }
const STATUS_TAG = { 0: 'tag-warning', 3: 'tag-info', 4: 'tag-success' }

export default {
  data() {
    return { rows: [] }
  },
  onShow() {
    this.load()
  },
  onPullDownRefresh() {
    this.load().finally(() => uni.stopPullDownRefresh())
  },
  methods: {
    statusLabel(s) {
      return STATUS[s] != null ? STATUS[s] : s
    },
    statusTag(s) {
      return STATUS_TAG[s] != null ? STATUS_TAG[s] : 'tag-info'
    },
    async load() {
      try {
        const me = Number(uni.getStorageSync('yz_user_id') || 0)
        const res = await listChances()
        // 列表为组织全量,移动端只看"我的机会";服务端按人过滤待 V2 补参数
        this.rows = (res.data || []).filter((c) => Number(c.assignee_id) === me)
      } catch (e) {
        uni.showToast({ title: e.message || '加载失败', icon: 'none' })
      }
    },
    async onConfirm(c) {
      try {
        await confirmChance(c.id)
        uni.showToast({ title: '已确认' })
        this.load()
      } catch (e) {
        uni.showToast({ title: e.message || '操作失败', icon: 'none' })
      }
    },
    async onComplete(c) {
      try {
        await completeChance(c.id)
        uni.showToast({ title: '已完成' })
        this.load()
      } catch (e) {
        uni.showToast({ title: e.message || '操作失败', icon: 'none' })
      }
    }
  }
}
</script>

<style scoped>
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8rpx;
}
.actions { margin-top: 16rpx; }
</style>
