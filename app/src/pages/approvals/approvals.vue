<template>
  <view>
    <view class="launchbar">
      <button class="launch-btn" @click="goLaunch">＋ 发起审批</button>
    </view>
    <view class="tabs">
      <text
        v-for="t in tabs"
        :key="t.key"
        class="tab"
        :class="{ active: role === t.key }"
        @click="switchRole(t.key)"
      >{{ t.label }}</text>
    </view>

    <view v-if="rows.length === 0" class="empty">暂无审批单</view>
    <view v-for="a in rows" :key="a.id" class="card" @click="goDetail(a)">
      <view class="head">
        <text class="cell-title">{{ a.title }}</text>
        <text class="tag" :class="statusTag(a.status)">{{ statusLabel(a.status) }}</text>
      </view>
      <view class="cell-sub">单号 #{{ a.id }} · {{ a.applicant_name }} 发起</view>
      <view class="cell-sub">溯源:{{ a.biz_addon }} / {{ a.biz_type }} #{{ a.biz_id }}</view>
      <view class="cell-sub">{{ role === 'done' ? ('办结时间 ' + (a.finished_at || '—')) : ('发起时间 ' + (a.created_at || '—')) }}</view>
    </view>
  </view>
</template>

<script>
import { listApprovals } from '../../api'

// 审批单状态机:0 审批中 → 1 通过 / 2 驳回 / 3 撤回(与内核 ApprovalService 一致)
const STATUS = { 0: '审批中', 1: '已通过', 2: '已驳回', 3: '已撤回' }
const STATUS_TAG = { 0: 'tag-info', 1: 'tag-success', 2: 'tag-danger', 3: 'tag-warning' }

export default {
  data() {
    return {
      role: 'todo',
      rows: [],
      tabs: [
        { key: 'todo', label: '待我处理' },
        { key: 'done', label: '我已办结' },
        { key: 'created', label: '我发起的' }
      ]
    }
  },
  onShow() {
    this.load()
  },
  onPullDownRefresh() {
    this.load().finally(() => uni.stopPullDownRefresh())
  },
  methods: {
    goLaunch() {
      uni.navigateTo({ url: '/pages/approvals/launch' })
    },
    statusLabel(s) {
      return STATUS[s] != null ? STATUS[s] : s
    },
    statusTag(s) {
      return STATUS_TAG[s] != null ? STATUS_TAG[s] : 'tag-info'
    },
    switchRole(key) {
      if (this.role === key) return
      this.role = key
      this.load()
    },
    async load() {
      try {
        const res = await listApprovals({ role: this.role, page: 1, size: 50 })
        this.rows = res.data || []
      } catch (e) {
        uni.showToast({ title: e.message || '加载失败', icon: 'none' })
      }
    },
    goDetail(a) {
      uni.navigateTo({ url: '/pages/approvals/detail?id=' + a.id })
    }
  }
}
</script>

<style scoped>
.launchbar {
  padding: 20rpx;
  background: #ffffff;
  border-bottom: 1rpx solid var(--yz-line);
}

.launch-btn {
  font-size: 28rpx;
  color: var(--yz-paper);
  background: var(--yz-seal);
  border-radius: 4rpx;
  line-height: 2.4;
}

.tabs {
  display: flex;
  background: #ffffff;
  padding: 0 20rpx;
  border-bottom: 1rpx solid var(--yz-line);
}

.tab {
  flex: 1;
  text-align: center;
  padding: 24rpx 0;
  color: var(--yz-ink-70);
  font-size: 28rpx;
  border-bottom: 4rpx solid transparent;
}

.tab.active {
  color: var(--yz-seal);
  font-weight: 600;
  border-bottom-color: var(--yz-seal);
}

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8rpx;
}
</style>
