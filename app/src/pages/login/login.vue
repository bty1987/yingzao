<template>
  <view class="login-wrap">
    <view class="brand">
      <text class="brand-name">营造</text>
      <text class="brand-slogan">现代装修企业的营造法式</text>
    </view>
    <view class="card form">
      <input v-model="username" class="ipt" placeholder="用户名" placeholder-class="ph" />
      <input v-model="password" class="ipt" password placeholder="密码" placeholder-class="ph" />
      <button class="btn-primary" :disabled="loading" @click="onLogin">{{ loading ? '登录中…' : '登 录' }}</button>
    </view>
  </view>
</template>

<script>
import { login } from '../../api'

export default {
  data() {
    return { username: '', password: '', loading: false }
  },
  methods: {
    async onLogin() {
      if (!this.username || !this.password) {
        uni.showToast({ title: '请输入用户名与密码', icon: 'none' })
        return
      }
      this.loading = true
      try {
        const res = await login({ username: this.username, password: this.password })
        uni.setStorageSync('yz_token', res.data.token)
        uni.setStorageSync('yz_user_id', String(res.data.userId))
        uni.setStorageSync('yz_display_name', res.data.displayName)
        uni.reLaunch({ url: '/pages/index/index' })
      } catch (e) {
        uni.showToast({ title: e.message || '登录失败', icon: 'none' })
      } finally {
        this.loading = false
      }
    }
  }
}
</script>

<style scoped>
.login-wrap {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-top: 180rpx;
}

.brand {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-bottom: 60rpx;
}

.brand-name {
  font-size: 64rpx;
  letter-spacing: 16rpx;
  font-weight: 600;
}

.brand-slogan {
  margin-top: 16rpx;
  color: #8a94a6;
  font-size: 24rpx;
}

.form { width: 640rpx; }

.ipt {
  border-bottom: 1rpx solid #e8ebf0;
  padding: 24rpx 8rpx;
  margin-bottom: 8rpx;
  font-size: 30rpx;
}

.ph { color: #b8c0cc; }
</style>
