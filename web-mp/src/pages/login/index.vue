<template>
  <view class="page">
    <view class="hero">
      <view class="yz-seal yz-display">家</view>
      <view class="yz-display title">登录营造·家</view>
      <view class="tip">手机验证码登录;新号码自动建档</view>
    </view>

    <view class="form">
      <view class="field">
        <text class="label">手机号</text>
        <input v-model="mobile" type="number" maxlength="11" placeholder="请输入手机号" />
      </view>
      <view class="field">
        <text class="label">验证码</text>
        <input v-model="code" type="number" maxlength="6" placeholder="6 位验证码" />
        <view class="send" :class="{ off: counting }" @click="onSend">
          {{ counting ? countdown + 's 后重发' : '获取验证码' }}
        </view>
      </view>
      <view class="btn-primary go" @click="onLogin">登 录</view>
      <view class="tip center">未注册手机号验证通过后自动创建客户档案</view>
    </view>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { sendCode, login } from '../../api'
import { saveSession } from '../../api/http'

const mobile = ref('')
const code = ref('')
const counting = ref(false)
const countdown = ref(60)
const redirect = ref('')

onLoad((q) => { redirect.value = q.redirect ? decodeURIComponent(q.redirect) : '' })

async function onSend() {
  if (!/^1\d{10}$/.test(mobile.value)) return uni.showToast({ title: '请输入正确手机号', icon: 'none' })
  try {
    await sendCode(mobile.value)
    uni.showToast({ title: '验证码已发送', icon: 'none' })
    counting.value = true
    countdown.value = 60
    const timer = setInterval(() => {
      countdown.value -= 1
      if (countdown.value <= 0) { clearInterval(timer); counting.value = false }
    }, 1000)
  } catch (e) {
    uni.showToast({ title: e.message, icon: 'none' })
  }
}

async function onLogin() {
  if (!/^1\d{10}$/.test(mobile.value)) return uni.showToast({ title: '请输入正确手机号', icon: 'none' })
  if (!/^\d{6}$/.test(code.value)) return uni.showToast({ title: '请输入 6 位验证码', icon: 'none' })
  try {
    const res = await login(mobile.value, code.value)
    const d = res.data || {}
    saveSession(d.token, d.customerId)
    uni.setStorageSync('c_mobile', mobile.value)
    uni.showToast({ title: '已登录', icon: 'none' })
    setTimeout(() => {
      if (redirect.value) uni.reLaunch({ url: redirect.value })
      else uni.switchTab({ url: '/pages/cases/index' })
    }, 400)
  } catch (e) {
    uni.showToast({ title: e.message, icon: 'none' })
  }
}
</script>

<style scoped>
.page { padding: 80rpx 48rpx; }
.hero { display: flex; flex-direction: column; align-items: center; gap: 20rpx; margin-bottom: 60rpx; }
.title { font-size: 40rpx; }
.form { display: flex; flex-direction: column; gap: 28rpx; }
.field {
  display: flex; align-items: center; gap: 16rpx;
  border-bottom: 1rpx solid var(--yz-line-strong); padding: 16rpx 4rpx;
  box-sizing: border-box; width: 100%;
}
.label { width: 120rpx; font-size: 26rpx; color: var(--yz-ink-70); flex-shrink: 0; }
.field input { flex: 1; min-width: 0; font-size: 30rpx; }
.send { font-size: 24rpx; color: var(--yz-seal); flex-shrink: 0; width: 180rpx; text-align: right; }
.send.off { color: var(--yz-ink-35); }
.go { margin-top: 16rpx; }
.center { text-align: center; margin-top: 8rpx; }
</style>
