<template>
  <div>
    <div class="toolbar">
      <el-select v-model="filters.status" placeholder="状态" style="width: 110px" clearable>
        <el-option v-for="d in dicts.visitStatus" :key="d.key" :label="d.label" :value="Number(d.key)" />
      </el-select>
      <el-select v-model="filters.bizType" placeholder="来源" style="width: 110px" clearable>
        <el-option v-for="d in dicts.visitBizType" :key="d.key" :label="d.label" :value="d.key" />
      </el-select>
      <el-input v-model="filters.orderNo" placeholder="装修单号" style="width: 150px" clearable @keyup.enter="load" />
      <el-button type="primary" @click="load">查询</el-button>
      <el-button @click="openCreate">手工登记</el-button>
    </div>

    <el-table :data="rows" border stripe v-loading="loading">
      <el-table-column prop="order_no" label="装修单号" width="140" />
      <el-table-column prop="customer_name" label="客户" width="90" />
      <el-table-column label="来源" width="90">
        <template #default="{ row }">{{ dictLabel('visitBizType', row.biz_type) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" effect="plain">{{ dictLabel('visitStatus', row.status) }}</el-tag>
          <el-tag v-if="row.overdue" type="danger" size="small" effect="plain" style="margin-left: 4px">逾期</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="满意度" width="80" align="center">
        <template #default="{ row }">{{ row.rating ? row.rating + '★' : '—' }}</template>
      </el-table-column>
      <el-table-column prop="content" label="回访内容" min-width="160" show-overflow-tooltip />
      <el-table-column label="无法完成原因" width="110">
        <template #default="{ row }">{{ row.reason ? dictLabel('visitReason', row.reason) : '—' }}</template>
      </el-table-column>
      <el-table-column prop="plan_time" label="计划回访日" width="150" />
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <template v-if="row.status === 0">
            <el-button link size="small" @click="openVisit(row)">回访</el-button>
            <el-button link size="small" @click="openFail(row)">无法完成</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination layout="prev, pager, next, total" :total="total" :page-size="filters.size"
      :current-page="filters.page" @current-change="(p) => { filters.page = p; load() }" style="margin-top: 12px" />

    <!-- 手工登记 -->
    <el-dialog v-model="createDialog" title="手工登记回访任务" width="480px">
      <el-form label-width="90px">
        <el-form-item label="装修单号" required>
          <el-input v-model="createForm.orderNo" placeholder="输入装修单号" />
        </el-form-item>
        <el-form-item label="计划回访日">
          <el-date-picker v-model="createForm.planTime" type="datetime" format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%" placeholder="可空" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onCreate">提交</el-button>
      </template>
    </el-dialog>

    <!-- 回访 -->
    <el-dialog v-model="visitDialog" title="回访登记" width="480px">
      <el-form label-width="90px">
        <el-form-item label="满意度" required>
          <el-rate v-model="visitForm.rating" />
        </el-form-item>
        <el-form-item label="草稿">
          <span class="tip">{{ visitTarget && visitTarget.draft_content || '无' }}</span>
        </el-form-item>
        <el-form-item label="回访内容" required>
          <el-input v-model="visitForm.content" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="onSaveDraft" :loading="saving">存草稿</el-button>
        <el-button @click="visitDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onFinish">提交完成</el-button>
      </template>
    </el-dialog>

    <!-- 无法完成 -->
    <el-dialog v-model="failDialog" title="标记无法完成" width="420px">
      <el-form label-width="90px">
        <el-form-item label="原因" required>
          <el-select v-model="failForm.reason" style="width: 100%">
            <el-option v-for="d in dicts.visitReason" :key="d.key" :label="d.label" :value="d.key" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="failDialog = false">取消</el-button>
        <el-button type="warning" :loading="saving" @click="onFail">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import {
  pageServiceVisits, createServiceVisit, draftServiceVisit,
  finishServiceVisit, failServiceVisit, pageDecorations
} from '../../api'
import { addons } from '../../store/addons'

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const filters = reactive({ status: null, bizType: null, orderNo: '', page: 1, size: 20 })
const dicts = ref({ visitStatus: [], visitBizType: [], visitReason: [] })
const createDialog = ref(false)
const createForm = ref({ orderId: null, orderNo: '', planTime: null })
const visitDialog = ref(false)
const visitTarget = ref(null)
const visitForm = ref({ rating: 5, content: '' })
const failDialog = ref(false)
const failTarget = ref(null)
const failForm = ref({ reason: null })

const statusTag = (s) => ({ 0: 'info', 1: 'success', 2: 'danger' })[s] ?? 'info'

function dictLabel(name, value) {
  const hit = (dicts.value[name] || []).find((d) => d.key === String(value))
  return hit ? hit.label : (value ?? '—')
}

async function load() {
  loading.value = true
  try {
    const res = await pageServiceVisits(filters)
    rows.value = (res.data && res.data.rows) || []
    total.value = (res.data && res.data.total) || 0
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  createForm.value = { orderId: null, orderNo: '', planTime: null }
  createDialog.value = true
}

async function onCreate() {
  if (!createForm.value.orderNo) return ElMessage.warning('请填写装修单号')
  saving.value = true
  try {
    const res = await pageDecorations({ orderNo: createForm.value.orderNo, page: 1, size: 1 })
    const hit = (res.data && res.data.rows || [])[0]
    if (!hit) {
      ElMessage.error('装修单不存在')
      return
    }
    await createServiceVisit({
      orderId: hit.id,
      planTime: createForm.value.planTime ? formatTime(createForm.value.planTime) : null
    })
    ElMessage.success('回访任务已登记')
    createDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '登记失败')
  } finally {
    saving.value = false
  }
}

function openVisit(row) {
  visitTarget.value = row
  visitForm.value = { rating: 5, content: row.draft_content || '' }
  visitDialog.value = true
}

async function onSaveDraft() {
  if (!visitForm.value.content) return ElMessage.warning('请填写内容')
  saving.value = true
  try {
    await draftServiceVisit(visitTarget.value.id, { draftContent: visitForm.value.content })
    ElMessage.success('草稿已保存')
    visitDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function onFinish() {
  if (!visitForm.value.content) return ElMessage.warning('请填写回访内容')
  saving.value = true
  try {
    await finishServiceVisit(visitTarget.value.id, {
      rating: visitForm.value.rating, content: visitForm.value.content
    })
    ElMessage.success('回访已完成')
    visitDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '提交失败')
  } finally {
    saving.value = false
  }
}

function openFail(row) {
  failTarget.value = row
  failForm.value = { reason: null }
  failDialog.value = true
}

async function onFail() {
  if (!failForm.value.reason) return ElMessage.warning('请选择原因')
  saving.value = true
  try {
    await failServiceVisit(failTarget.value.id, { reason: failForm.value.reason })
    ElMessage.success('已标记无法完成')
    failDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  } finally {
    saving.value = false
  }
}

function formatTime(d) {
  const p = (n) => String(n).padStart(2, '0')
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' '
    + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds())
}

onMounted(() => {
  const addon = addons.list.find((a) => a.id === 'service')
  const d = (addon && addon.dicts) || {}
  dicts.value = d
  load()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; align-items: center; }
.tip { color: var(--yz-ink-50, #8a887f); font-size: 12px; }
</style>
