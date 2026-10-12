<template>
  <view>
    <view v-if="rows.length === 0" class="empty">暂无请假记录</view>
    <view v-for="l in rows" :key="l.id" class="card">
      <view class="head">
        <text class="cell-title">{{ l.reason }}</text>
        <text class="tag" :class="statusTag(l.status)">{{ statusLabel(l.status) }}</text>
      </view>
      <view class="cell-sub">
        {{ l.days }} 天 · 审批人:{{ l.assignee_name }}
        <text v-if="l.approval_level === 2" class="tag level-badge">二级审批</text>
      </view>
      <view class="cell-sub">申请时间:{{ l.created_at || '—' }}</view>
      <view class="cell-sub">办结时间:{{ l.finished_at || '—' }}</view>
      <view v-if="l.status === 0" class="actions">
        <text class="btn-mini warn" @click="onCancel(l)">撤回</text>
      </view>
    </view>

    <view class="fab" @click="goCreate">＋</view>
  </view>
</template>

<script>
import { listLeaves, cancelLeave } from '../../api'

// 请假状态字典镜像 addon.yml dicts.leaveStatus(0 审批中/1 已通过/2 已驳回/3 已撤回)
const STATUS = { 0: '审批中', 1: '已通过', 2: '已驳回', 3: '已撤回' }
const STATUS_TAG = { 0: 'tag-info', 1: 'tag-success', 2: 'tag-danger', 3: 'tag-warning' }

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
    goCreate() {
      uni.navigateTo({ url: '/pages/leave/create' })
    },
    async load() {
      try {
        const res = await listLeaves()
        this.rows = res.data || []
      } catch (e) {
        uni.showToast({ title: e.message || '加载失败', icon: 'none' })
      }
    },
    onCancel(l) {
      uni.showModal({
        title: '撤回请假',
        content: '确定撤回该请假申请?',
        success: async (res) => {
          if (!res.confirm) return
          try {
            await cancelLeave(l.id)
            uni.showToast({ title: '已撤回' })
            this.load()
          } catch (e) {
            uni.showToast({ title: e.message || '撤回失败', icon: 'none' })
          }
        }
      })
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
.level-badge {
  margin-left: 8rpx;
  background: rgba(166, 134, 75, 0.14);
  color: var(--yz-ochre);
}
.fab {
  position: fixed;
  right: 40rpx;
  bottom: 80rpx;
  width: 96rpx;
  height: 96rpx;
  border-radius: 4rpx;
  background: var(--yz-ink);
  color: var(--yz-paper);
  font-size: 48rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 8rpx 24rpx rgba(26, 26, 24, 0.25);
}
</style>
