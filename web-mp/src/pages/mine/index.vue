<template>
  <view class="page">
    <view class="card me">
      <view class="yz-seal yz-display">客</view>
      <view class="me-t">
        <view class="yz-display name">{{ logged ? mobile : '未登录' }}</view>
        <view v-if="logged" class="tip">客户编号 {{ customerId }}</view>
        <view v-else class="tip">登录后可查看装修进度与积分</view>
      </view>
    </view>
    <view class="card act" @click="logged ? doLogout() : goLogin()">
      <view>{{ logged ? '退出登录' : '手机验证码登录' }}</view>
      <view class="arrow">→</view>
    </view>
    <view class="about">
      <view class="yz-eyebrow">YINGZAO · OPEN SOURCE ERP</view>
      <view class="tip">营造·家 — 客户服务门户</view>
    </view>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { clearSession, getToken, getCustomerId } from '../../api/http'

const logged = ref(false)
const customerId = ref('')
const mobile = ref('')

function load() {
  logged.value = !!getToken()
  customerId.value = getCustomerId()
  mobile.value = uni.getStorageSync('c_mobile') || '已登录客户'
}
function doLogout() {
  clearSession()
  uni.removeStorageSync('c_mobile')
  load()
  uni.showToast({ title: '已退出', icon: 'none' })
}
function goLogin() {
  uni.navigateTo({ url: '/pages/login/index?redirect=/pages/mine/index' })
}
onShow(load)
</script>

<style scoped>
.page { padding: 24rpx 0 40rpx; }
.me { display: flex; align-items: center; gap: 24rpx; }
.me-t { display: flex; flex-direction: column; gap: 8rpx; }
.name { font-size: 34rpx; }
.act { display: flex; justify-content: space-between; align-items: center; }
.arrow { color: var(--yz-ink-35); }
.about { text-align: center; margin-top: 60rpx; display: flex; flex-direction: column; gap: 10rpx; }
</style>
