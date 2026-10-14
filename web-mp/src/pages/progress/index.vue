<template>
  <view class="page">
    <view v-if="!logged" class="empty">
      <view class="yz-display" style="font-size: 36rpx; margin-bottom: 16rpx">查看我的装修</view>
      <view class="tip" style="margin-bottom: 40rpx">登录后可查看施工进度与节点时间轴</view>
      <view class="btn-primary go" @click="goLogin">手机验证码登录</view>
    </view>
    <template v-else>
      <view v-if="!loading && rows.length === 0" class="empty">名下暂无在途装修单</view>
      <view v-for="o in rows" :key="o.orderId" class="card" @click="open(o)">
        <view class="row1">
          <view class="yz-display no">{{ o.orderNo }}</view>
          <view class="badge" :class="'ps-' + o.projectStatus">{{ projectLabel(o.projectStatus) }}</view>
        </view>
        <view class="house">{{ o.houseLabel }}</view>
        <view class="tip">点击查看施工进度 →</view>
      </view>
    </template>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app'
import { listMyOrders } from '../../api'
import { getToken } from '../../api/http'

const rows = ref([])
const loading = ref(false)
const logged = ref(false)

const projectLabel = (s) => ({ 0: '未开工', 1: '施工中', 2: '已竣工' }[s] || '—')

async function load() {
  logged.value = !!getToken()
  if (!logged.value) return
  loading.value = true
  try {
    const res = await listMyOrders()
    rows.value = (res.data && res.data.rows) || []
  } finally {
    loading.value = false
  }
}

function open(o) {
  uni.navigateTo({ url: '/pages/progress/detail?orderId=' + o.orderId })
}
function goLogin() {
  uni.navigateTo({ url: '/pages/login/index?redirect=/pages/progress/index' })
}
onShow(load)
onPullDownRefresh(async () => { await load(); uni.stopPullDownRefresh() })
</script>

<style scoped>
.page { padding: 24rpx 0 40rpx; }
.row1 { display: flex; justify-content: space-between; align-items: center; }
.no { font-size: 32rpx; }
.house { margin: 12rpx 0; font-size: 26rpx; color: var(--yz-ink-70); }
.badge { font-size: 22rpx; padding: 4rpx 16rpx; border: 1rpx solid var(--yz-line); border-radius: 4rpx; color: var(--yz-ink-50); }
.badge.ps-1 { border-color: var(--yz-seal); color: var(--yz-seal); }
.badge.ps-2 { border-color: var(--yz-moss); color: var(--yz-moss); }
.go { margin: 0 60rpx; }
</style>
