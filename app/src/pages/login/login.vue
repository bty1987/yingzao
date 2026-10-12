<template>
  <view class="login-wrap">
    <view class="brand">
      <view class="yz-seal">营造</view>
      <text class="brand-name yz-display">营造</text>
      <text class="yz-eyebrow">YINGZAO</text>
      <text class="brand-slogan">现代装修企业的营造法式</text>
    </view>
    <view class="frame">
      <view class="form">
        <input v-model="username" class="ipt" placeholder="用户名" placeholder-class="ph" />
        <input v-model="password" class="ipt" password placeholder="密码" placeholder-class="ph" />
        <button class="btn-primary" :disabled="loading" @click="onLogin">{{ loading ? '登录中…' : '登 录' }}</button>
      </view>
    </view>
    <text class="colophon yz-display">一梁一柱 · 皆有法式</text>
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
/* 古籍扉页母题:朱印方章 + 宋体品牌 + 双线版框登录卡(对齐 web Login) */
.login-wrap {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-top: 140rpx;
}

.brand {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-bottom: 64rpx;
}

.brand-name {
  margin-top: 32rpx;
  font-size: 72rpx;
  letter-spacing: 24rpx;
  text-indent: 24rpx;
  font-weight: 600;
  color: var(--yz-ink);
}

.brand .yz-eyebrow { margin-top: 16rpx; }

.brand-slogan {
  margin-top: 24rpx;
  color: var(--yz-ink-50);
  font-size: 24rpx;
  letter-spacing: 4rpx;
}

/* 双线版框:外粗墨线 + 内淡墨线(古籍书框) */
.frame {
  width: 620rpx;
  border: 2rpx solid var(--yz-line-strong);
  padding: 8rpx;
}

.form {
  width: auto;
  border: 1rpx solid var(--yz-line);
  padding: 48rpx 40rpx 40rpx;
}

.ipt {
  border-bottom: 1rpx solid var(--yz-line);
  padding: 24rpx 8rpx;
  margin-bottom: 8rpx;
  font-size: 30rpx;
  color: var(--yz-ink);
}

.ph { color: var(--yz-ink-35); }

.colophon {
  margin-top: 72rpx;
  font-size: 24rpx;
  letter-spacing: 8rpx;
  color: var(--yz-ink-35);
}
</style>
