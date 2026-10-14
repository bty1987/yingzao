<template>
  <view class="page">
    <view v-if="!logged" class="empty">
      <view class="yz-display" style="font-size: 36rpx; margin-bottom: 16rpx">我的积分</view>
      <view class="tip" style="margin-bottom: 40rpx">登录后可查看会员积分与流水</view>
      <view class="btn-primary go" @click="goLogin">手机验证码登录</view>
    </view>
    <template v-else>
      <view v-if="!loading && items.length === 0" class="empty">暂无积分</view>
      <view v-for="it in items" :key="it.pointType" class="card pt">
        <view class="pt-name">{{ typeLabel(it.pointType) }}积分</view>
        <view class="yz-display pt-num">{{ it.available }}</view>
        <view class="tip">可用 {{ it.available }} / 累计 {{ it.balance }}</view>
      </view>
      <view v-if="records.length" class="yz-eyebrow sec">最近流水</view>
      <view v-for="r in records" :key="r.id" class="card rec">
        <view class="rec-row">
          <view>{{ typeLabel(r.pointType) }}积分 {{ r.num > 0 ? '+' : '' }}{{ r.num }}</view>
          <view class="tip">{{ short(r.createdAt) }}</view>
        </view>
        <view class="tip">{{ srcLabel(r.sourceType) }}</view>
      </view>
    </template>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app'
import { fetchPoints } from '../../api'
import { getToken } from '../../api/http'

const items = ref([])
const records = ref([])
const loading = ref(false)
const logged = ref(false)

const typeLabel = (t) => ({ decoration: '装修', sales: '主材', manual: '运营' }[t] || t)
const srcLabel = (s) => ({
  payment_confirm: '收款记分', exchange_consume: '兑换扣减', exchange_revoke: '撤销回补',
  payment_refund: '退款冲减', manual_adjust: '运营调整'
}[s] || s)
const short = (t) => (t ? String(t).replace('T', ' ').slice(0, 16) : '')

async function load() {
  logged.value = !!getToken()
  if (!logged.value) return
  loading.value = true
  try {
    const res = await fetchPoints()
    items.value = (res.data && res.data.items) || []
    records.value = (res.data && res.data.records) || []
  } finally {
    loading.value = false
  }
}
function goLogin() {
  uni.navigateTo({ url: '/pages/login/index?redirect=/pages/points/index' })
}
onShow(load)
onPullDownRefresh(async () => { await load(); uni.stopPullDownRefresh() })
</script>

<style scoped>
.page { padding: 24rpx 0 40rpx; }
.pt { display: flex; flex-direction: column; align-items: center; padding: 36rpx 24rpx; }
.pt-name { font-size: 24rpx; color: var(--yz-ink-50); }
.pt-num { font-size: 64rpx; margin: 12rpx 0; }
.sec { display: block; margin: 8rpx 24rpx 16rpx; }
.rec-row { display: flex; justify-content: space-between; margin-bottom: 6rpx; }
.go { margin: 0 60rpx; }
</style>
