<template>
  <view v-if="detail">
    <view class="card">
      <view class="head">
        <text class="cell-title">{{ detail.title }}</text>
        <text class="tag" :class="statusTag(detail.status)">{{ statusLabel(detail.status) }}</text>
      </view>
      <view class="cell-sub">单号 #{{ detail.id }}</view>
      <view class="cell-sub">发起人:{{ detail.applicant_name }}</view>
      <view class="cell-sub">当前处理人:{{ detail.assignee_name }}</view>
      <view class="cell-sub">业务溯源:{{ detail.biz_addon }} / {{ detail.biz_type }} #{{ detail.biz_id }}</view>
      <view class="cell-sub">发起时间:{{ detail.created_at || '—' }}</view>
      <view class="cell-sub">办结时间:{{ detail.finished_at || '—' }}</view>
    </view>

    <view v-if="formRows.length || formImages.length" class="card">
      <text class="cell-title">申请内容</text>
      <view v-for="row in formRows" :key="row.label" class="form-row">
        <text class="form-label">{{ row.label }}</text>
        <text class="form-value">{{ row.prefix || '' }}{{ row.value }}</text>
      </view>
      <yz-image-grid v-if="formImages.length" :fileIds="formImages" class="form-images" />
    </view>

    <view v-else-if="attachIds.length" class="card">
      <text class="cell-title">附件</text>
      <yz-image-grid :fileIds="attachIds" class="form-images" />
    </view>

    <view class="card">
      <text class="cell-title">流水</text>
      <view v-if="(detail.records || []).length === 0" class="cell-sub">暂无流水</view>
      <view v-for="r in detail.records" :key="r.id" class="record">
        <view class="record-head">
          <text class="record-action">{{ actionLabel(r.action) }}</text>
          <text class="cell-sub">{{ r.operator_name }}</text>
          <text v-if="r.action === 4 && r.assignee_name" class="cell-sub">→ {{ r.assignee_name }}</text>
        </view>
        <view v-if="r.comment" class="record-comment">{{ r.comment }}</view>
        <view class="cell-sub">{{ r.created_at }}</view>
      </view>
    </view>

    <view v-if="canHandle" class="card">
      <text class="cell-title">审批操作</text>
      <textarea v-model="comment" class="comment" placeholder="审批意见(可空)" placeholder-class="ph" />
      <view class="btn-row">
        <button class="btn-half success" :disabled="acting" @click="onAct(1)">通 过</button>
        <button class="btn-half danger" :disabled="acting" @click="onAct(2)">驳 回</button>
      </view>
      <picker mode="selector" :range="userNames" @change="onPickTransfer">
        <view class="transfer-line">
          <text class="btn-mini">转交给…</text>
        </view>
      </picker>
    </view>

    <view v-if="canCancel" class="card">
      <button class="btn-primary warn" :disabled="acting" @click="onCancel">撤 回</button>
    </view>
  </view>
</template>

<script>
import { fetchApprovalDetail, approveApproval, rejectApproval, transferApproval, cancelApproval, listOrgUsers, listAddons } from '../../api'
import yzImageGrid from '../../components/yz-image-grid.vue'

// 插件清单模块级缓存:详情页按 biz_type 找回表单 schema 渲染申请内容(找不到就回落附件卡)
let addonsCache = null
function loadAddonsCached() {
  if (!addonsCache) addonsCache = listAddons().then((r) => r.data || [])
  return addonsCache
}

// 流水动作:1 提交 / 2 通过 / 3 驳回 / 4 转交 / 5 撤回(与内核一致)
const ACTION = { 1: '提交', 2: '通过', 3: '驳回', 4: '转交', 5: '撤回' }
const STATUS = { 0: '审批中', 1: '已通过', 2: '已驳回', 3: '已撤回' }
const STATUS_TAG = { 0: 'tag-info', 1: 'tag-success', 2: 'tag-danger', 3: 'tag-warning' }

export default {
  components: { yzImageGrid },
  data() {
    return { id: 0, detail: null, comment: '', acting: false, users: [], schema: null, formData: null, attachIds: [] }
  },
  computed: {
    me() {
      return Number(uni.getStorageSync('yz_user_id') || 0)
    },
    canHandle() {
      const d = this.detail
      return d && d.status === 0 && Number(d.assignee_id) === this.me
    },
    canCancel() {
      const d = this.detail
      return d && d.status === 0 && Number(d.applicant_id) === this.me
    },
    userNames() {
      return this.users.map((u) => u.display_name)
    },
    // 申请内容行:schema 中非 query/images 且值非空的字段(原始快照诚实渲染,filling 承载展示语义)
    formRows() {
      if (!this.schema || !this.formData) return []
      return (this.schema.fields || [])
        .filter((f) => f.type !== 'query' && f.type !== 'images')
        .map((f) => ({ label: f.label, value: this.formData[f.id], prefix: f.prefix || '' }))
        .filter((r) => r.value !== null && r.value !== undefined && r.value !== '')
    },
    // schema 中 images 字段(表单随附照片)的 fileId 并集
    formImages() {
      if (!this.schema || !this.formData) return []
      const ids = []
      ;(this.schema.fields || [])
        .filter((f) => f.type === 'images')
        .forEach((f) => {
          const v = this.formData[f.id]
          if (Array.isArray(v)) ids.push(...v.filter((x) => typeof x === 'number'))
        })
      return ids
    }
  },
  onLoad(query) {
    this.id = Number(query.id || 0)
  },
  onShow() {
    this.load()
  },
  methods: {
    statusLabel(s) {
      return STATUS[s] != null ? STATUS[s] : s
    },
    statusTag(s) {
      return STATUS_TAG[s] != null ? STATUS_TAG[s] : 'tag-info'
    },
    actionLabel(a) {
      return ACTION[a] != null ? ACTION[a] : a
    },
    async load() {
      try {
        const res = await fetchApprovalDetail(this.id)
        this.detail = res.data
        this.formData = this.parseJson(this.detail.form_data)
        this.attachIds = this.parseJson(this.detail.attachments) || []
        if (this.formData) {
          const addons = await loadAddonsCached()
          const hit = addons
            .flatMap((a) => a.approvalForms || [])
            .find((f) => f.key === this.detail.biz_type)
          this.schema = hit || null
        }
      } catch (e) {
        uni.showToast({ title: e.message || '加载失败', icon: 'none' })
      }
    },
    parseJson(text) {
      if (!text) return null
      try {
        return JSON.parse(text)
      } catch (e) {
        return null
      }
    },
    async onAct(result) {
      if (this.acting) return
      this.acting = true
      try {
        const body = { comment: this.comment || '' }
        if (result === 1) await approveApproval(this.id, body)
        else await rejectApproval(this.id, body)
        uni.showToast({ title: '已办结' })
        this.load()
      } catch (e) {
        uni.showToast({ title: e.message || '操作失败', icon: 'none' })
      } finally {
        this.acting = false
      }
    },
    async ensureUsers() {
      if (this.users.length > 0) return
      const res = await listOrgUsers()
      this.users = (res.data || []).filter((u) => Number(u.id) !== this.me)
    },
    async onPickTransfer(e) {
      const u = this.users[Number(e.detail.value)]
      if (!u) return
      if (this.acting) return
      this.acting = true
      try {
        await transferApproval(this.id, { toUserId: u.id, comment: this.comment || '' })
        uni.showToast({ title: '已转交给 ' + u.display_name })
        this.load()
      } catch (err) {
        uni.showToast({ title: err.message || '转交失败', icon: 'none' })
      } finally {
        this.acting = false
      }
    },
    onCancel() {
      uni.showModal({
        title: '撤回',
        content: '确定撤回该审批?',
        success: async (r) => {
          if (!r.confirm) return
          try {
            await cancelApproval(this.id)
            uni.showToast({ title: '已撤回' })
            this.load()
          } catch (e) {
            uni.showToast({ title: e.message || '撤回失败', icon: 'none' })
          }
        }
      })
    }
  }
}
</script>

<style scoped>
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8rpx;
}

.record {
  padding: 20rpx 0;
  border-bottom: 1rpx solid var(--yz-line);
}

.record:last-child { border-bottom: none; }

.record-head {
  display: flex;
  align-items: center;
  gap: 16rpx;
}

.record-action { font-weight: 600; }

.record-comment {
  margin: 8rpx 0;
  color: var(--yz-ink-70);
  font-size: 26rpx;
}

.comment {
  width: auto;
  min-height: 120rpx;
  background: var(--yz-paper-deep);
  border-radius: 4rpx;
  padding: 16rpx;
  margin-top: 16rpx;
  font-size: 28rpx;
}

.ph { color: var(--yz-ink-35); }

.btn-row {
  display: flex;
  gap: 20rpx;
  margin-top: 24rpx;
}

.btn-half {
  flex: 1;
  border-radius: 4rpx;
  font-size: 30rpx;
  color: var(--yz-paper);
}

.btn-half.success { background: var(--yz-moss); }
.btn-half.danger { background: var(--yz-seal); }

.transfer-line { margin-top: 24rpx; }

.btn-primary.warn { background: var(--yz-ochre); }

.form-row {
  display: flex;
  justify-content: space-between;
  gap: 24rpx;
  padding: 14rpx 0;
  border-bottom: 1rpx solid var(--yz-line);
}

.form-row:last-of-type { border-bottom: none; }

.form-label {
  color: var(--yz-ink-70);
  font-size: 26rpx;
  flex-shrink: 0;
}

.form-value {
  color: var(--yz-ink);
  font-size: 28rpx;
  text-align: right;
  word-break: break-all;
}

.form-images {
  margin-top: 16rpx;
}
</style>
