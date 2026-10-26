<template>
  <div>
    <div class="toolbar">
      <el-select v-model="filters.status" placeholder="状态" style="width: 110px" clearable>
        <el-option v-for="d in dicts.complaintStatus" :key="d.key" :label="d.label" :value="Number(d.key)" />
      </el-select>
      <el-input v-model="filters.orderNo" placeholder="装修单号" style="width: 150px" clearable @keyup.enter="load" />
      <el-button type="primary" @click="load">查询</el-button>
      <el-button @click="openCreate">登记投诉</el-button>
      <el-button @click="openConfig">售后配置</el-button>
    </div>

    <el-table :data="rows" border stripe v-loading="loading">
      <el-table-column prop="order_no" label="装修单号" width="140" />
      <el-table-column prop="customer_name" label="客户" width="90" />
      <el-table-column label="类型" width="80">
        <template #default="{ row }">{{ dictLabel('complaintType', row.type) }}</template>
      </el-table-column>
      <el-table-column label="级别" width="70">
        <template #default="{ row }">
          <el-tag :type="row.level === 'urgent' ? 'danger' : 'info'" size="small" effect="plain">
            {{ dictLabel('complaintLevel', row.level) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="content" label="投诉内容" min-width="180" show-overflow-tooltip />
      <el-table-column prop="handle_result" label="处理结果" min-width="150" show-overflow-tooltip />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" effect="plain">{{ dictLabel('complaintStatus', row.status) }}</el-tag>
          <el-tag v-if="row.overdue" type="danger" size="small" effect="plain" style="margin-left: 4px">超期</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="created_at" label="登记时间" width="150" />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 0" link size="small" type="primary" @click="onAct(row, 'start')">开始处理</el-button>
          <el-button v-if="row.status < 2" link size="small" type="success" @click="openHandle(row)">标记已处理</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination layout="prev, pager, next, total" :total="total" :page-size="filters.size"
      :current-page="filters.page" @current-change="(p) => { filters.page = p; load() }" style="margin-top: 12px" />

    <!-- 登记投诉 -->
    <el-dialog v-model="createDialog" title="登记投诉" width="520px">
      <el-form label-width="90px">
        <el-form-item label="装修单号" required>
          <el-input v-model="createForm.orderNo" placeholder="输入装修单号" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="createForm.type" style="width: 100%">
            <el-option v-for="d in dicts.complaintType" :key="d.key" :label="d.label" :value="d.key" />
          </el-select>
        </el-form-item>
        <el-form-item label="级别" required>
          <el-radio-group v-model="createForm.level">
            <el-radio v-for="d in dicts.complaintLevel" :key="d.key" :value="d.key">{{ d.label }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="承诺时限">
          <el-date-picker v-model="createForm.promiseTime" type="datetime" format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%" placeholder="可空" />
        </el-form-item>
        <el-form-item label="投诉内容" required>
          <el-input v-model="createForm.content" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onCreate">提交</el-button>
      </template>
    </el-dialog>

    <!-- 标记已处理 -->
    <el-dialog v-model="handleDialog" title="标记已处理" width="480px">
      <el-form label-width="90px">
        <el-form-item label="处理结果" required>
          <el-input v-model="handleForm.handleResult" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <div class="tip">标记已处理后,若组织开启「投诉回访联动」将自动生成回访任务。</div>
      <template #footer>
        <el-button @click="handleDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onHandle">确认</el-button>
      </template>
    </el-dialog>

    <!-- 售后配置 -->
    <el-dialog v-model="configDialog" title="售后配置(组织级)" width="480px">
      <el-form label-width="140px">
        <el-form-item label="投诉回访联动">
          <el-switch v-model="configForm.visitOnComplaint" />
        </el-form-item>
        <el-form-item label="验收回访联动">
          <el-switch v-model="configForm.visitOnAcceptance" />
        </el-form-item>
        <el-form-item label="回访计划日偏移(天)">
          <el-input-number v-model="configForm.acceptanceVisitDays" :min="1" :max="365" />
        </el-form-item>
        <el-form-item label="保修时长(月)">
          <el-input-number v-model="configForm.warrantyMonths" :min="1" :max="120" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="configDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSaveConfig">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import {
  pageServiceComplaints, createServiceComplaint, startServiceComplaint,
  handleServiceComplaint, fetchServiceConfig, updateServiceConfig,
  pageDecorations
} from '../../api'
import { addons } from '../../store/addons'

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const filters = reactive({ status: null, orderNo: '', page: 1, size: 20 })
const dicts = ref({ complaintType: [], complaintLevel: [], complaintStatus: [] })
const createDialog = ref(false)
const createForm = ref({ orderId: null, orderNo: '', type: 'quality', level: 'normal', content: '', promiseTime: null })
const handleDialog = ref(false)
const handleTarget = ref(null)
const handleForm = ref({ handleResult: '' })
const configDialog = ref(false)
const configForm = ref({ visitOnComplaint: true, visitOnAcceptance: false, acceptanceVisitDays: 7, warrantyMonths: 24 })

const statusTag = (s) => ({ 0: 'info', 1: 'warning', 2: 'success' })[s] ?? 'info'

function dictLabel(name, value) {
  const hit = (dicts.value[name] || []).find((d) => d.key === String(value))
  return hit ? hit.label : (value ?? '—')
}

async function load() {
  loading.value = true
  try {
    const res = await pageServiceComplaints(filters)
    rows.value = (res.data && res.data.rows) || []
    total.value = (res.data && res.data.total) || 0
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  createForm.value = { orderId: null, orderNo: '', type: 'quality', level: 'normal', content: '', promiseTime: null }
  createDialog.value = true
}

async function onCreate() {
  if (!createForm.value.orderNo || !createForm.value.content) {
    return ElMessage.warning('请补全装修单号与投诉内容')
  }
  saving.value = true
  try {
    // 单号→orderId:经装修单分页反查
    const res = await pageDecorations({ orderNo: createForm.value.orderNo, page: 1, size: 1 })
    const hit = (res.data && res.data.rows || [])[0]
    if (!hit) {
      ElMessage.error('装修单不存在')
      return
    }
    const pt = createForm.value.promiseTime
    await createServiceComplaint({
      orderId: hit.id, type: createForm.value.type, level: createForm.value.level,
      content: createForm.value.content,
      promiseTime: pt ? formatTime(pt) : null
    })
    ElMessage.success('投诉已登记')
    createDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '登记失败')
  } finally {
    saving.value = false
  }
}

function openHandle(row) {
  handleTarget.value = row
  handleForm.value = { handleResult: row.handle_result || '' }
  handleDialog.value = true
}

async function onHandle() {
  if (!handleForm.value.handleResult) return ElMessage.warning('处理结果必填')
  saving.value = true
  try {
    await handleServiceComplaint(handleTarget.value.id, { handleResult: handleForm.value.handleResult })
    ElMessage.success('已标记处理完成')
    handleDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  } finally {
    saving.value = false
  }
}

async function onAct(row, action) {
  try {
    await startServiceComplaint(row.id)
    ElMessage.success('已开始处理')
    load()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function openConfig() {
  const res = await fetchServiceConfig()
  const c = res.data || {}
  configForm.value = {
    visitOnComplaint: c.visit_on_complaint === 1, visitOnAcceptance: c.visit_on_acceptance === 1,
    acceptanceVisitDays: c.acceptance_visit_days || 7, warrantyMonths: c.warranty_months || 24
  }
  configDialog.value = true
}

async function onSaveConfig() {
  saving.value = true
  try {
    await updateServiceConfig({
      visitOnComplaint: configForm.value.visitOnComplaint ? 1 : 0,
      visitOnAcceptance: configForm.value.visitOnAcceptance ? 1 : 0,
      acceptanceVisitDays: configForm.value.acceptanceVisitDays,
      warrantyMonths: configForm.value.warrantyMonths
    })
    ElMessage.success('配置已保存')
    configDialog.value = false
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
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
