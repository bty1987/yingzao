<template>
  <view>
    <view v-if="!schema" class="empty">{{ loading ? '加载中…' : '表单模板不存在或无权限' }}</view>
    <view v-else class="card">
      <view class="head">
        <text class="cell-title">{{ schema.title }}</text>
      </view>
      <text v-if="schema.sub" class="cell-sub">{{ schema.sub }}</text>

      <view v-for="f in schema.fields" :key="f.id" class="field">
        <text class="label">{{ f.label }}<text v-if="f.required" class="req">*</text></text>

        <!-- query:远程联想选单,选中后按 fill 用选中对象的键回填表单字段 -->
        <yz-search-select
          v-if="f.type === 'query'"
          :modelValue="form[f.id]"
          :fetch="makeFetcher(f)"
          :labelKey="f.labelKey || 'name'"
          :subKey="f.subKey || ''"
          placeholder="输入关键字检索"
          @update:modelValue="onQueryPick(f, $event)"
          @change="onQueryPick(f, $event)"
        />

        <!-- filling:只读展示,由 query 选中回填 -->
        <text v-else-if="f.type === 'filling'" class="filling" :class="{ placeholder: !form[f.id] }">
          {{ form[f.id] || '选单后自动带出' }}
        </text>

        <!-- images:附件上传(F1 fileId 数组) -->
        <yz-upload-image v-else-if="f.type === 'images'" v-model="form[f.id]" />

        <textarea
          v-else-if="f.type === 'textarea'"
          v-model="form[f.id]"
          class="note"
          :placeholder="f.placeholder || '请输入'"
          placeholder-class="ph"
        />
        <input
          v-else
          v-model="form[f.id]"
          class="ipt"
          :type="f.type === 'number' ? 'digit' : 'text'"
          :placeholder="f.placeholder || '请输入'"
          placeholder-class="ph"
        />
      </view>

      <view class="field">
        <text class="label">审批人 *</text>
        <picker mode="selector" :range="userNames" @change="onPickAssignee">
          <view class="picker-value">
            <text :class="{ placeholder: !assigneeLabel }">{{ assigneeLabel || '请选择审批人' }}</text>
            <text class="arrow">›</text>
          </view>
        </picker>
      </view>

      <button class="btn-primary" :disabled="submitting" @click="onSubmit">
        {{ submitting ? '提交中…' : '提交审批' }}
      </button>
    </view>
  </view>
</template>

<script>
import yzSearchSelect from '../../components/yz-search-select.vue'
import yzUploadImage from '../../components/yz-upload-image.vue'
import { listAddons, listOrgUsers } from '../../api'
import { get, post } from '../../api/http'

// schema 驱动动态表单:模板由插件在 addon.yml approval-forms 段声明(经 GET /api/addons 下发,
// 内核不认业务字段),本页只做通用渲染解释——字段类型 input/textarea/number/query/filling/images。
// 提交 = POST schema.submit 端点(插件业务端点,自行 RBAC),成功返回 {approvalId} 直跳审批详情。
export default {
  components: { yzSearchSelect, yzUploadImage },
  data() {
    return { key: '', addon: '', schema: null, loading: true, form: {}, assigneeId: null, users: [], submitting: false }
  },
  computed: {
    userNames() {
      return this.users.map((u) => u.display_name)
    },
    assigneeLabel() {
      const hit = this.users.find((u) => u.id === this.assigneeId)
      return hit ? hit.display_name : ''
    }
  },
  async onLoad(query) {
    this.key = decodeURIComponent(query.key || '')
    this.addon = query.addon || ''
    try {
      const [addonsRes, usersRes] = await Promise.all([listAddons(), listOrgUsers()])
      const addon = (addonsRes.data || []).find((a) => a.id === this.addon)
      const form = (addon ? addon.approvalForms || [] : []).find((f) => f.key === this.key)
      if (form) {
        this.schema = form
        const model = {}
        ;(form.fields || []).forEach((f) => {
          model[f.id] = f.type === 'images' ? [] : ''
        })
        this.form = model
      }
      const me = Number(uni.getStorageSync('yz_user_id') || 0)
      this.users = (usersRes.data || []).filter((u) => u.id !== me)
    } catch (e) {
      uni.showToast({ title: e.message || '加载失败', icon: 'none' })
    } finally {
      this.loading = false
    }
  },
  methods: {
    makeFetcher(field) {
      return (kw) => get(field.source, { keyword: kw }).then((r) => r.data || [])
    },
    // 选中联动:v-model=选中对象;form[field.id] 存对象(回显用 displayKey),fill 声明回填只读字段
    onQueryPick(field, row) {
      this.form[field.id] = row || ''
      const fill = field.fill || {}
      Object.keys(fill).forEach((k) => {
        this.form[k] = row ? row[fill[k]] : ''
      })
    },
    onPickAssignee(e) {
      const hit = this.users[Number(e.detail.value)]
      if (hit) this.assigneeId = hit.id
    },
    validate() {
      for (const f of this.schema.fields) {
        const v = this.form[f.id]
        if (!f.required) continue
        if (f.type === 'query' && !(v && v.id)) return f.label
        if (f.type === 'images') continue
        if (v === '' || v === null || v === undefined) return f.label
      }
      if (!this.assigneeId) return '审批人'
      return null
    },
    async onSubmit() {
      if (this.submitting) return
      const miss = this.validate()
      if (miss) return uni.showToast({ title: '请填写:' + miss, icon: 'none' })
      const body = {}
      ;(this.schema.fields || []).forEach((f) => {
        let v = this.form[f.id]
        if (f.type === 'query') v = v && v.id ? v.id : null
        else if (f.type === 'number') v = v === '' || v === null || v === undefined ? null : Number(v)
        else if (f.type === 'images') v = Array.isArray(v) ? v : []
        else v = v === '' || v === undefined ? null : v
        if (v !== null && !(f.type === 'images' && (!v || v.length === 0))) body[f.id] = v
      })
      body.assigneeId = this.assigneeId
      this.submitting = true
      try {
        const [method, url] = String(this.schema.submit).split(' ')
        const res = method === 'GET' ? await get(url) : await post(url, body)
        const approvalId = res.data && res.data.approvalId
        uni.showToast({ title: '已提交审批' })
        setTimeout(() => {
          if (approvalId) {
            uni.redirectTo({ url: '/pages/approvals/detail?id=' + approvalId })
          } else {
            uni.navigateBack()
          }
        }, 600)
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
.empty {
  padding: 120rpx 40rpx;
  text-align: center;
  color: var(--yz-ink-35);
  font-size: 26rpx;
}

.head {
  margin-bottom: 8rpx;
}

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

.req {
  color: var(--yz-seal);
  margin-left: 4rpx;
}

.ipt {
  font-size: 30rpx;
}

.note {
  min-height: 140rpx;
  font-size: 30rpx;
}

.filling {
  font-size: 30rpx;
  color: var(--yz-ink);
}

.placeholder {
  color: var(--yz-ink-35);
}

.ph {
  color: var(--yz-ink-35);
}

.picker-value {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 30rpx;
  padding: 8rpx 0;
}

.arrow {
  color: var(--yz-ink-35);
  font-size: 36rpx;
}
</style>
