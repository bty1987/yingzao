<template>
  <view class="page">
    <view class="head">
      <view class="yz-seal yz-display">家</view>
      <view class="head-t">
        <view class="yz-display title">营造·家</view>
        <view class="yz-eyebrow">YINGZAO · CLIENT</view>
      </view>
    </view>

    <scroll-view scroll-x class="chips" :show-scrollbar="false">
      <view class="chip" :class="{ on: !city }" @click="pick('')">全部</view>
      <view v-for="c in cityOptions" :key="c" class="chip" :class="{ on: city === c }"
        @click="pick(c)">{{ c }}</view>
    </scroll-view>

    <view v-if="!loading && rows.length === 0" class="empty">暂无案例,敬请期待</view>
    <view v-for="it in rows" :key="it.caseId" class="card case">
      <image v-if="it.coverImage && !broken[it.caseId]" :src="it.coverImage" mode="aspectFill"
        class="cover" @error="broken[it.caseId] = true" />
      <view v-else class="cover ph">
        <view class="yz-seal ph-seal yz-display">案</view>
        <view class="ph-name yz-display">{{ it.title }}</view>
      </view>
      <view class="c-body">
        <view class="yz-display c-title">{{ it.title }}</view>
        <view class="c-meta">
          <text v-if="it.cityName">{{ it.cityName }}</text>
          <text v-if="it.style"> · {{ it.style }}</text>
        </view>
        <view v-if="it.figureName" class="c-designer">设计 · {{ it.figureName }}</view>
      </view>
    </view>
    <view v-if="rows.length && rows.length >= total" class="tip end">— 已全部加载 —</view>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onShow, onReachBottom } from '@dcloudio/uni-app'
import { listCases } from '../../api'

const rows = ref([])
const total = ref(0)
const city = ref('')
const cityOptions = ref([])
const loading = ref(false)
const page = ref(1)
const broken = ref({})

async function load(reset) {
  if (loading.value) return
  loading.value = true
  try {
    if (reset) { page.value = 1; rows.value = [] }
    const res = await listCases({ city: city.value || undefined, page: page.value, size: 10 })
    const data = res.data || {}
    total.value = data.total || 0
    rows.value = rows.value.concat(data.rows || [])
    // 城市面从已返回案例归纳(匿名轻量口径,不另设端点)
    for (const r of (data.rows || [])) {
      if (r.cityName && !cityOptions.value.includes(r.cityName)) cityOptions.value.push(r.cityName)
    }
    page.value += 1
  } finally {
    loading.value = false
  }
}

const pick = (c) => { city.value = c; load(true) }
onShow(() => load(true))
onReachBottom(() => { if (rows.value.length < total.value) load(false) })
</script>

<style scoped>
.page { padding: 24rpx 0 40rpx; overflow-x: hidden; }
.head { display: flex; align-items: center; gap: 20rpx; padding: 12rpx 24rpx 24rpx; }
.head-t { display: flex; flex-direction: column; gap: 6rpx; }
.title { font-size: 40rpx; }
.chips { white-space: nowrap; width: 100%; box-sizing: border-box; padding: 0 24rpx 20rpx; }
.chip {
  display: inline-block; padding: 10rpx 28rpx; margin-right: 14rpx;
  border: 1rpx solid var(--yz-line); border-radius: 4rpx;
  font-size: 24rpx; color: var(--yz-ink-70);
}
.chip.on { background: var(--yz-seal); border-color: var(--yz-seal); color: var(--yz-paper); }
.case { padding: 0; overflow: hidden; }
.cover { width: 100%; height: 340rpx; display: block; }
.cover.ph {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 20rpx; background: var(--yz-paper-deep);
  border-bottom: 1rpx solid var(--yz-line);
}
.ph-seal { width: 64rpx; height: 64rpx; font-size: 30rpx; }
.ph-name {
  font-size: 30rpx; color: var(--yz-ink-50);
  max-width: 80%; overflow: hidden; white-space: nowrap; text-overflow: ellipsis;
}
.c-body { padding: 20rpx 24rpx 24rpx; }
.c-title { font-size: 32rpx; }
.c-meta { margin-top: 8rpx; font-size: 24rpx; color: var(--yz-ink-50); }
.c-designer { margin-top: 4rpx; font-size: 22rpx; color: var(--yz-ink-35); }
.end { text-align: center; margin-top: 8rpx; }
</style>
