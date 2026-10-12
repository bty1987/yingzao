<template>
  <view class="card">
    <view class="field">
      <text class="label">客户姓名</text>
      <input v-model="form.name" class="ipt" placeholder="请输入姓名" placeholder-class="ph" />
    </view>
    <view class="field">
      <text class="label">联系电话</text>
      <input v-model="form.phone" class="ipt" type="number" placeholder="请输入手机号" placeholder-class="ph" />
    </view>
    <view class="field">
      <text class="label">来源</text>
      <picker mode="selector" :range="sourceNames" @change="onPickSource">
        <view class="picker-value">
          <text :class="{ placeholder: !form.source }">{{ sourceLabel || '请选择来源' }}</text>
          <text class="arrow">›</text>
        </view>
      </picker>
    </view>
    <view class="field">
      <text class="label">备注</text>
      <textarea v-model="form.note" class="note" placeholder="户型/小区/需求等(可空)" placeholder-class="ph" />
    </view>
    <button class="btn-primary" :disabled="submitting" @click="onSubmit">{{ submitting ? '提交中…' : '登 记' }}</button>
  </view>
</template>

<script>
import { createLead } from '../../api'

// 来源字典与 web 端 Leads.vue 一致(值 = 后端 CreateLeadRequest.source)
const SOURCES = [
  { value: 'advertisement', label: '广告' },
  { value: 'referral', label: '转介绍' },
  { value: 'walkin', label: '自然进店' }
]

export default {
  data() {
    return {
      form: { name: '', phone: '', source: '', note: '' },
      submitting: false
    }
  },
  computed: {
    sourceNames() {
      return SOURCES.map((s) => s.label)
    },
    sourceLabel() {
      const hit = SOURCES.find((s) => s.value === this.form.source)
      return hit ? hit.label : ''
    }
  },
  methods: {
    onPickSource(e) {
      this.form.source = SOURCES[Number(e.detail.value)].value
    },
    async onSubmit() {
      if (!this.form.name) return uni.showToast({ title: '请输入客户姓名', icon: 'none' })
      if (!this.form.phone) return uni.showToast({ title: '请输入联系电话', icon: 'none' })
      if (!this.form.source) return uni.showToast({ title: '请选择来源', icon: 'none' })
      this.submitting = true
      try {
        await createLead(this.form)
        uni.showToast({ title: '登记成功' })
        setTimeout(() => uni.navigateBack(), 600)
      } catch (e) {
        uni.showToast({ title: e.message || '登记失败', icon: 'none' })
      } finally {
        this.submitting = false
      }
    }
  }
}
</script>

<style scoped>
.field {
  display: flex;
  flex-direction: column;
  padding: 20rpx 0;
  border-bottom: 1rpx solid var(--yz-line);
}

.label {
  color: var(--yz-ink-70);
  font-size: 26rpx;
  margin-bottom: 12rpx;
}

.ipt { font-size: 30rpx; }

.picker-value {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 30rpx;
  padding: 8rpx 0;
}

.placeholder { color: var(--yz-ink-35); }

.arrow { color: var(--yz-ink-35); font-size: 36rpx; }

.note {
  min-height: 140rpx;
  font-size: 30rpx;
}

.ph { color: var(--yz-ink-35); }
</style>
