<template>
  <view>
    <view v-if="rows.length === 0" class="card">
      <text class="cell-title">暂无在管工地</text>
      <text class="cell-sub">参与(监理/施工班组)的工地会出现在这里</text>
    </view>
    <view v-for="s in rows" :key="s.id" class="card site" @click="goDetail(s)">
      <view class="head">
        <text class="cell-title">{{ s.customer_name }}</text>
        <text class="tag" :class="s.process === 1 ? 'tag-success' : 'tag-info'">
          {{ s.process === 1 ? '已竣工' : '施工中' }}
        </text>
      </view>
      <view class="cell-sub">{{ s.order_no }} · {{ s.house_label }}</view>
      <view class="node-line">
        <text class="node-now yz-display">{{ s.current_node_name || '未排期' }}</text>
        <text class="node-meta">节点 {{ s.node_done }}/{{ s.node_total }}</text>
      </view>
      <view class="foot">
        <text class="cell-sub">{{ s.supervisor_name ? '监理 ' + s.supervisor_name : '暂未派监理' }}</text>
        <text class="arrow">›</text>
      </view>
    </view>
  </view>
</template>

<script>
import { listMySites } from '../../api'

// 我的工地(R17,docs/15 app P0-3 首项):参与即所见(监理 or 在册工人),后端一个接口
// 替代老系统工程/监理/质检三角色列表。竣工单保留(历史工地),排序 process asc + id desc。
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
    async load() {
      try {
        const res = await listMySites()
        this.rows = res.data || []
      } catch (e) {
        uni.showToast({ title: e.message || '加载失败', icon: 'none' })
      }
    },
    goDetail(s) {
      uni.navigateTo({ url: '/pkg-site/site/detail?orderId=' + s.order_id })
    }
  }
}
</script>

<style scoped>
.site { padding: 24rpx 28rpx; }
.head { display: flex; align-items: center; justify-content: space-between; }
.node-line {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-top: 16rpx;
  padding-top: 16rpx;
  border-top: 1rpx solid var(--yz-line);
}
.node-now { font-size: 34rpx; color: var(--yz-ink); font-weight: 700; }
.node-meta { font-size: 24rpx; color: var(--yz-ink-62); }
.foot { display: flex; align-items: center; justify-content: space-between; margin-top: 12rpx; }
.arrow { color: var(--yz-ink-35); font-size: 32rpx; }
</style>
