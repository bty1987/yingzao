<template>
  <div>
    <div class="toolbar">
      <el-select v-model="filters.phase" placeholder="保修状态" style="width: 120px" clearable>
        <el-option label="在保" :value="0" />
        <el-option label="即将到期" :value="1" />
        <el-option label="已过保" :value="2" />
      </el-select>
      <el-input v-model="filters.orderNo" placeholder="装修单号" style="width: 150px" clearable @keyup.enter="load" />
      <el-button type="primary" @click="load">查询</el-button>
      <el-button @click="openCreate">保修登记</el-button>
      <span class="tip">保修期=起点+月数;起点默认竣工验收日,可补录修改</span>
    </div>

    <el-table :data="rows" border stripe v-loading="loading">
      <el-table-column prop="order_no" label="装修单号" width="140" />
      <el-table-column prop="customer_name" label="客户" width="90" />
      <el-table-column prop="start_time" label="保修起点" width="110" />
      <el-table-column label="时长" width="80" align="right">
        <template #default="{ row }">{{ row.months }} 月</template>
      </el-table-column>
      <el-table-column prop="expire_time" label="到期日" width="110" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="phaseTag(row.phase)" effect="plain">{{ phaseLabel(row.phase) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
      <el-table-column prop="created_at" label="登记时间" width="150" />
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 0" link size="small" type="danger" @click="onCancel(row)">作废</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination layout="prev, pager, next, total" :total="total" :page-size="filters.size"
      :current-page="filters.page" @current-change="(p) => { filters.page = p; load() }" style="margin-top: 12px" />

    <!-- 保修登记 -->
    <el-dialog v-model="createDialog" title="保修登记" width="480px">
      <el-form label-width="90px">
        <el-form-item label="装修单号" required>
          <el-input v-model="createForm.orderNo" placeholder="输入装修单号" />
        </el-form-item>
        <el-form-item label="保修起点" required>
          <el-date-picker v-model="createForm.startTime" type="date" format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="时长(月)">
          <el-input-number v-model="createForm.months" :min="1" :max="120" />
          <span class="tip" style="margin-left: 8px">缺省=组织配置</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="createForm.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onCreate">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  pageServiceGuarantees, createServiceGuarantee, cancelServiceGuarantee, pageDecorations
} from '../../api'
import { addons } from '../../store/addons'

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const filters = reactive({ phase: null, orderNo: '', page: 1, size: 20 })
const dicts = ref({})
const createDialog = ref(false)
const createForm = ref({ orderId: null, orderNo: '', startTime: null, months: null, remark: '' })

const phaseTag = (p) => ({ 0: 'success', 1: 'warning', 2: 'info', 3: 'danger' })[p] ?? 'info'
const phaseLabel = (p) => ({ 0: '在保', 1: '即将到期', 2: '已过保', 3: '已作废' })[p] ?? p

async function load() {
  loading.value = true
  try {
    const res = await pageServiceGuarantees(filters)
    rows.value = (res.data && res.data.rows) || []
    total.value = (res.data && res.data.total) || 0
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  createForm.value = { orderId: null, orderNo: '', startTime: new Date(), months: null, remark: '' }
  createDialog.value = true
}

async function onCreate() {
  if (!createForm.value.orderNo || !createForm.value.startTime) {
    return ElMessage.warning('请补全装修单号与保修起点')
  }
  saving.value = true
  try {
    const res = await pageDecorations({ orderNo: createForm.value.orderNo, page: 1, size: 1 })
    const hit = (res.data && res.data.rows || [])[0]
    if (!hit) {
      ElMessage.error('装修单不存在')
      return
    }
    const d = createForm.value.startTime
    const p = (n) => String(n).padStart(2, '0')
    await createServiceGuarantee({
      orderId: hit.id,
      startTime: d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()),
      months: createForm.value.months, remark: createForm.value.remark || null
    })
    ElMessage.success('保修已登记')
    createDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '登记失败')
  } finally {
    saving.value = false
  }
}

async function onCancel(row) {
  try {
    await ElMessageBox.confirm('作废该保修登记?作废后可重新登记。', '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await cancelServiceGuarantee(row.id)
    ElMessage.success('已作废')
    load()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

onMounted(() => {
  const addon = addons.list.find((a) => a.id === 'service')
  dicts.value = (addon && addon.dicts) || {}
  load()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; align-items: center; }
.tip { color: var(--yz-ink-50, #8a887f); font-size: 12px; }
</style>
