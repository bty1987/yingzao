<template>
  <view>
    <view v-if="!loading && forms.length === 0" class="empty">暂无可发起的审批,由已启用插件按权限下发</view>
    <view v-for="f in forms" :key="f.addonId + '/' + f.key" class="card form-card" @click="go(f)">
      <view class="seal"><text class="seal-char">{{ f.icon || '审' }}</text></view>
      <view class="form-main">
        <text class="cell-title">{{ f.title }}</text>
        <text v-if="f.sub" class="cell-sub">{{ f.sub }}</text>
      </view>
      <text class="arrow">›</text>
    </view>
  </view>
</template>

<script>
import { listAddons } from '../../api'

// 发起入口:各插件 addon.yml approval-forms 段经 GET /api/addons 下发(后端已按当前用户授权过滤),
// 跨插件 sort 升序合并(同工作台宫格合并姿势,同值按插件 id 稳定)。
export default {
  data() {
    return { forms: [], loading: true }
  },
  onShow() {
    this.load()
  },
  onPullDownRefresh() {
    this.load().finally(() => uni.stopPullDownRefresh())
  },
  methods: {
    async load() {
      this.loading = true
      try {
        const res = await listAddons()
        const forms = []
        ;(res.data || []).forEach((a) => {
          ;(a.approvalForms || []).forEach((f) => forms.push({ ...f, addonId: a.id }))
        })
        forms.sort((x, y) => (x.sort || 0) - (y.sort || 0) || (x.addonId < y.addonId ? -1 : 1))
        this.forms = forms
      } catch (e) {
        uni.showToast({ title: e.message || '加载失败', icon: 'none' })
      } finally {
        this.loading = false
      }
    },
    go(f) {
      uni.navigateTo({
        url: '/pages/approvals/create?key=' + encodeURIComponent(f.key) + '&addon=' + f.addonId
      })
    }
  }
}
</script>

<style scoped>
.empty {
  padding: 120rpx 40rpx;
  text-align: center;
  color: var(--yz-ink-35);
  font-size: 26rpx;
}

.form-card {
  display: flex;
  align-items: center;
  gap: 24rpx;
}

.seal {
  width: 88rpx;
  height: 88rpx;
  border: 2rpx solid var(--yz-line-strong);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.seal-char {
  font-family: 'Songti SC', 'SimSun', 'Noto Serif SC', serif;
  font-size: 44rpx;
  color: var(--yz-ink);
}

.form-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}

.arrow {
  color: var(--yz-ink-35);
  font-size: 40rpx;
}
</style>
