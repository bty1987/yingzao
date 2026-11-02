<template>
  <view class="page">
    <view v-if="!logged" class="empty">
      <view class="yz-display" style="font-size: 36rpx; margin-bottom: 16rpx">工地相册</view>
      <view class="tip" style="margin-bottom: 40rpx">登录后可查看施工日记里的工地照片</view>
      <view class="btn-primary go" @click="goLogin">手机验证码登录</view>
    </view>
    <template v-else>
      <!-- 单据选择(多单时) -->
      <view v-if="orders.length > 1" class="order-bar">
        <view
          v-for="o in orders"
          :key="o.orderId"
          class="order-chip"
          :class="{ active: o.orderId === orderId }"
          @click="switchOrder(o)"
        >{{ o.orderNo }}</view>
      </view>

      <view v-if="!loading && groups.length === 0" class="empty">工地还没有照片,等施工师傅记日记</view>
      <view v-for="g in groups" :key="g.node" class="group">
        <view class="g-head">
          <text class="g-node yz-display">{{ g.node }}</text>
          <text class="tip">{{ g.time }}</text>
        </view>
        <view class="grid9">
          <view v-for="(p, i) in g.photos" :key="p.fileId" class="cell" @click="preview(g, i)">
            <image v-if="p.src" class="pic" :src="p.src" mode="aspectFill" />
            <view v-else class="ph"><text>图</text></view>
          </view>
        </view>
      </view>
    </template>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onLoad, onShow, onPullDownRefresh } from '@dcloudio/uni-app'
import { fetchAlbum, listMyOrders } from '../../api'
import { getToken } from '../../api/http'
import { downloadAlbumImage } from '../../utils/c-file'

// 工地相册(R20,拍板=方向三九宫格):construction 日记图片投影,时间倒序,
// 节点名做组头;缩略图/全屏预览都走带票据 downloadFile 换本地路径;
// 单图失败落「图」字占位(品牌兜底红线:永不白屏)。
const orders = ref([])
const orderId = ref(null)
const logged = ref(false)
let presetOrderId = null

onLoad((q) => {
  presetOrderId = q && q.orderId ? Number(q.orderId) : null
})
const groups = ref([])
const loading = ref(false)

function switchOrder(o) {
  if (orderId.value === o.orderId) return
  orderId.value = o.orderId
  loadAlbum()
}

async function load() {
  if (!getToken()) {
    logged.value = false
    return
  }
  logged.value = true
  loading.value = true
  try {
    const res = await listMyOrders()
    orders.value = (res.data || {}).rows || []
    if (!orders.value.length) {
      groups.value = []
      return
    }
    if (presetOrderId && orders.value.some((o) => o.orderId === presetOrderId)) {
      orderId.value = presetOrderId
    } else if (!orderId.value || !orders.value.some((o) => o.orderId === orderId.value)) {
      orderId.value = orders.value[0].orderId
    }
    await loadAlbum()
  } catch (e) {
    uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

async function loadAlbum() {
  try {
    const res = await fetchAlbum(orderId.value)
    const photos = res.data || []
    groups.value = groupBy(photos)
    // 逐格换本地路径(失败留占位)
    for (const g of groups.value) {
      for (const p of g.photos) {
        downloadAlbumImage(p.fileId)
          .then((src) => { p.src = src })
          .catch(() => { p.src = '' })
      }
    }
  } catch (e) {
    uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  }
}

/** 按节点+日期分组,组内保序(接口已时间倒序)。 */
function groupBy(photos) {
  const out = []
  const index = new Map()
  for (const p of photos) {
    const key = p.nodeName + '|' + String(p.takenAt || '').slice(0, 10)
    if (!index.has(key)) {
      const g = { node: p.nodeName, time: String(p.takenAt || '').slice(0, 10), photos: [] }
      index.set(key, g)
      out.push(g)
    }
    index.get(key).photos.push({ fileId: p.fileId, src: '' })
  }
  return out
}

function preview(g, i) {
  const urls = g.photos.filter((p) => p.src).map((p) => p.src)
  if (!urls.length) return
  const current = Math.max(0, g.photos.slice(0, i + 1).filter((p) => p.src).length - 1)
  uni.previewImage({ urls, current })
}

function goLogin() {
  uni.navigateTo({ url: '/pages/login/index?redirect=/pages/album/index' })
}
onShow(load)
onPullDownRefresh(async () => {
  await load()
  uni.stopPullDownRefresh()
})
</script>

<style scoped>
.page { padding: 24rpx 0 60rpx; }
.order-bar { display: flex; flex-wrap: wrap; gap: 12rpx; padding: 0 24rpx 16rpx; }
.order-chip {
  padding: 8rpx 20rpx;
  border: 1rpx solid var(--yz-line);
  font-size: 24rpx;
  color: var(--yz-ink-50);
  background: var(--yz-paper);
}
.order-chip.active { color: var(--yz-paper); background: var(--yz-ink); border-color: var(--yz-ink); }

.group { margin-bottom: 24rpx; }
.g-head { display: flex; align-items: baseline; gap: 16rpx; padding: 8rpx 24rpx; }
.g-node { font-size: 30rpx; }

/* 九宫格(方向三):三列方格 */
.grid9 { display: flex; flex-wrap: wrap; gap: 8rpx; padding: 4rpx 24rpx; }
.cell {
  width: 222rpx;
  height: 222rpx;
  border: 1rpx solid var(--yz-line);
  background: var(--yz-paper-deep);
  box-sizing: border-box;
  overflow: hidden;
}
.pic { width: 100%; height: 100%; display: block; }
.ph {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}
.ph text { color: var(--yz-ink-35); font-size: 28rpx; font-family: 'Songti SC', 'SimSun', serif; }

.empty { text-align: center; padding: 120rpx 40rpx; color: var(--yz-ink-50); }
.tip { color: var(--yz-ink-50); font-size: 24rpx; }
</style>
