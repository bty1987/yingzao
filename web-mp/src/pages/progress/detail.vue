<template>
  <view class="page">
    <view class="yz-eyebrow head">CONSTRUCTION · {{ orderNo }}</view>
    <view v-if="!loading && nodes.length === 0" class="empty">施工排期尚未生成</view>
    <view v-for="(n, i) in nodes" :key="i" class="node">
      <view class="rail">
        <view class="dot" :class="dotClass(n)" />
        <view v-if="i < nodes.length - 1" class="line" :class="{ done: n.status === 2 }" />
      </view>
      <view class="body">
        <view class="yz-display name">{{ n.nodeName }}</view>
        <view class="st" :class="stClass(n)">{{ stLabel(n) }}</view>
        <view v-if="n.planStart || n.planEnd" class="tip">
          计划 {{ short(n.planStart) }} ~ {{ short(n.planEnd) }}
        </view>
        <view v-if="n.finishTime" class="ok">完工 {{ short(n.finishTime) }}</view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { fetchProgress } from '../../api'

const nodes = ref([])
const loading = ref(true)
const orderNo = ref('')

const stLabel = (n) => ({ 0: '待开工', 1: '施工中', 2: '已完工', 3: '工费确认' }[n.status] || '—')
const stClass = (n) => ({ 1: 'cur', 2: 'fin', 3: 'sal' }[n.status] || '')
const dotClass = (n) => ({ 1: 'cur', 2: 'fin' }[n.status] || '')
const short = (t) => (t ? String(t).replace('T', ' ').slice(0, 16) : '')

onLoad(async (q) => {
  try {
    const res = await fetchProgress(q.orderId)
    nodes.value = (res.data && res.data.nodes) || []
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.page { padding: 32rpx 24rpx 40rpx; }
.head { margin-bottom: 28rpx; }
.node { display: flex; }
.rail { width: 40rpx; display: flex; flex-direction: column; align-items: center; margin-right: 20rpx; }
.dot { width: 18rpx; height: 18rpx; border-radius: 50%; border: 2rpx solid var(--yz-line-strong); background: var(--yz-paper); margin-top: 8rpx; }
.dot.cur { border-color: var(--yz-seal); background: var(--yz-seal); }
.dot.fin { border-color: var(--yz-moss); background: var(--yz-moss); }
.line { flex: 1; width: 2rpx; background: var(--yz-line); margin: 4rpx 0; }
.line.done { background: var(--yz-moss); }
.body { flex: 1; padding-bottom: 32rpx; }
.name { font-size: 30rpx; }
.st { font-size: 24rpx; color: var(--yz-ink-50); margin: 6rpx 0; }
.st.cur { color: var(--yz-seal); }
.st.fin { color: var(--yz-moss); }
.ok { font-size: 22rpx; color: var(--yz-moss); }
</style>
