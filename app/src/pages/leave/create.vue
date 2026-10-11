<template>
  <view class="card">
    <view class="field">
      <text class="label">审批人(一级)</text>
      <picker mode="selector" :range="userNames" @change="onPickAssignee">
        <view class="picker-value">
          <text :class="{ placeholder: !assigneeLabel }">{{ assigneeLabel || '请选择审批人' }}</text>
          <text class="arrow">›</text>
        </view>
      </picker>
    </view>
    <view class="field">
      <text class="label">请假天数</text>
      <input v-model="form.days" class="ipt" type="number" placeholder="≥3 天自动升级二级审批" placeholder-class="ph" />
    </view>
    <view v-if="multiLevel" class="field">
      <text class="label">二级审批人</text>
      <picker mode="selector" :range="managerNames" @change="onPickManager">
        <view class="picker-value">
          <text :class="{ placeholder: !managerLabel }">{{ managerLabel || '长请假须二级审批人' }}</text>
          <text class="arrow">›</text>
        </view>
      </picker>
    </view>
    <view class="field">
      <text class="label">请假事由</text>
      <textarea v-model="form.reason" class="note" placeholder="请输入请假事由" placeholder-class="ph" />
    </view>
    <button class="btn-primary" :disabled="submitting" @click="onSubmit">{{ submitting ? '提交中…' : '提 交' }}</button>
  </view>
</template>

<script>
import { createLeave, listOrgUsers } from '../../api'

const MULTI_LEVEL_MIN_DAYS = 3

export default {
  data() {
    return {
      users: [],
      form: { assigneeId: null, days: '', reason: '', managerId: null },
      submitting: false
    }
  },
  computed: {
    multiLevel() {
      return Number(this.form.days) >= MULTI_LEVEL_MIN_DAYS
    },
    userNames() {
      return this.users.map((u) => u.display_name)
    },
    assigneeLabel() {
      const hit = this.users.find((u) => u.id === this.form.assigneeId)
      return hit ? hit.display_name : ''
    },
    // 二级审批人候选:排除自己与一级审批人(与后端 290006 守卫同口径)
    managerCandidates() {
      const me = Number(uni.getStorageSync('yz_user_id') || 0)
      return this.users.filter((u) => u.id !== me && u.id !== this.form.assigneeId)
    },
    managerNames() {
      return this.managerCandidates.map((u) => u.display_name)
    },
    managerLabel() {
      const hit = this.managerCandidates.find((u) => u.id === this.form.managerId)
      return hit ? hit.display_name : ''
    }
  },
  onShow() {
    this.loadUsers()
  },
  methods: {
    async loadUsers() {
      try {
        const me = Number(uni.getStorageSync('yz_user_id') || 0)
        const res = await listOrgUsers()
        this.users = (res.data || []).filter((u) => u.id !== me)
      } catch (e) {
        uni.showToast({ title: e.message || '加载选人失败', icon: 'none' })
      }
    },
    onPickAssignee(e) {
      const hit = this.users[Number(e.detail.value)]
      if (hit) {
        this.form.assigneeId = hit.id
        if (this.form.managerId === hit.id) this.form.managerId = null
      }
    },
    onPickManager(e) {
      const hit = this.managerCandidates[Number(e.detail.value)]
      if (hit) this.form.managerId = hit.id
    },
    async onSubmit() {
      const days = Number(this.form.days)
      if (!this.form.assigneeId) return uni.showToast({ title: '请选择审批人', icon: 'none' })
      if (!days || days <= 0) return uni.showToast({ title: '请填写请假天数', icon: 'none' })
      if (days >= MULTI_LEVEL_MIN_DAYS && !this.form.managerId) {
        return uni.showToast({ title: '≥3 天请选择二级审批人', icon: 'none' })
      }
      if (!this.form.reason || !this.form.reason.trim()) {
        return uni.showToast({ title: '请填写请假事由', icon: 'none' })
      }
      this.submitting = true
      try {
        await createLeave({
          reason: this.form.reason.trim(),
          days,
          assigneeId: this.form.assigneeId,
          managerId: days >= MULTI_LEVEL_MIN_DAYS ? this.form.managerId : null
        })
        uni.showToast({ title: '已提交审批' })
        setTimeout(() => uni.navigateBack(), 600)
      } catch (e) {
        uni.showToast({ title: e.message || '提交失败', icon: 'none' })
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
  border-bottom: 1rpx solid #f0f2f5;
}

.label {
  color: #5a6474;
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

.placeholder { color: #b8c0cc; }

.arrow { color: #c2c9d4; font-size: 36rpx; }

.note {
  min-height: 140rpx;
  font-size: 30rpx;
}

.ph { color: #b8c0cc; }
</style>
