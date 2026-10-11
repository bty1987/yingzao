<template>
  <div>
    <div class="toolbar">
      <el-select v-model="filters.subjectId" placeholder="科目" style="width: 180px" clearable @change="load">
        <el-option v-for="s in subjects" :key="s.id" :label="s.name" :value="s.id" />
      </el-select>
      <el-input v-model="filters.refId" placeholder="业务归属单号" style="width: 160px" clearable @keyup.enter="load" />
      <el-button type="primary" @click="load">查询</el-button>
      <el-button @click="openMove('charge')">收入</el-button>
      <el-button @click="openMove('deduct')">划扣</el-button>
      <el-button @click="openMove('withdraw')">提现</el-button>
      <el-button @click="subjectDialog = true">科目管理</el-button>
      <span class="tip">账户 = 科目 × 业务归属一户一行;余额 ≡ Σ流水(对账红线)</span>
    </div>

    <el-table :data="accounts" border stripe v-loading="loading">
      <el-table-column prop="id" label="账户" width="80" />
      <el-table-column prop="subject_name" label="科目" width="140" />
      <el-table-column prop="ref_type" label="归属类型" width="120" />
      <el-table-column prop="ref_id" label="归属单号" min-width="140">
        <template #default="{ row }"><span class="mono">{{ row.ref_id }}</span></template>
      </el-table-column>
      <el-table-column label="余额(元)" width="140" align="right">
        <template #default="{ row }">{{ yuan(row.balance) }}</template>
      </el-table-column>
      <el-table-column prop="updated_at" label="最近变动" width="170" />
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button link type="primary" @click="openFlows(row)">流水</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 收入/划扣/提现 -->
    <el-dialog v-model="moveDialog" :title="moveTitle" width="520px">
      <el-form :model="moveForm" label-width="90px">
        <el-form-item label="科目">
          <el-select v-model="moveForm.subjectCode" style="width: 100%">
            <el-option v-for="s in enabledSubjects" :key="s.code" :label="s.name" :value="s.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="归属类型">
          <el-input v-model="moveForm.refType" placeholder="如 decoration" />
        </el-form-item>
        <el-form-item label="归属单号">
          <el-input v-model="moveForm.refId" />
        </el-form-item>
        <el-form-item label="金额(元)">
          <el-input-number v-model="moveForm.yuan" :min="0.001" :precision="3" :step="100" />
        </el-form-item>
        <el-form-item v-if="moveMode === 'deduct'" label="溯源业务">
          <div style="display: flex; gap: 8px; width: 100%">
            <el-input v-model="moveForm.bizAddon" placeholder="插件id" />
            <el-input v-model="moveForm.bizType" placeholder="业务类型" />
            <el-input v-model="moveForm.bizId" placeholder="业务单号" />
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="moveForm.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="moveDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onMove">提交</el-button>
      </template>
    </el-dialog>

    <!-- 流水抽屉 -->
    <el-drawer v-model="flowDrawer" :title="'流水 — ' + (flowAccount && flowAccount.subject_name || '')"
      size="600px">
      <el-descriptions v-if="flowAccount" :column="2" border size="small" style="margin-bottom: 12px">
        <el-descriptions-item label="账户">{{ flowAccount.id }}</el-descriptions-item>
        <el-descriptions-item label="余额">{{ yuan(flowAccount.balance) }} 元</el-descriptions-item>
      </el-descriptions>
      <el-table :data="flows" border size="small">
        <el-table-column label="类型" width="70">
          <template #default="{ row }">{{ flowLabel(row.flow_type) }}</template>
        </el-table-column>
        <el-table-column label="发生额" width="110" align="right">
          <template #default="{ row }">{{ yuan(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="余额快照" width="110" align="right">
          <template #default="{ row }">{{ yuan(row.balance_after) }}</template>
        </el-table-column>
        <el-table-column label="业务溯源" min-width="150">
          <template #default="{ row }">
            <span class="mono">{{ row.biz_addon ? row.biz_addon + '/' + row.biz_type + '/' + row.biz_id : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="operator_name" label="经办" width="90" />
        <el-table-column prop="created_at" label="时间" width="160" />
      </el-table>
    </el-drawer>

    <!-- 科目管理 -->
    <el-dialog v-model="subjectDialog" title="预付款科目字典(组织自定义)" width="560px">
      <el-table :data="subjects" border size="small">
        <el-table-column prop="code" label="编码" width="140" />
        <el-table-column prop="name" label="名称" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 0 ? 'success' : 'info'" size="small">
              {{ row.status === 0 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button link @click="toggleSubject(row)">{{ row.status === 0 ? '停用' : '启用' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="new-type">
        <el-input v-model="newSubject.code" placeholder="编码(小写字母开头)" style="width: 180px" />
        <el-input v-model="newSubject.name" placeholder="名称" style="width: 160px" />
        <el-button type="primary" @click="onCreateSubject">新增科目</el-button>
      </div>
      <div class="tip" style="margin-top: 8px">合同/工程/主材 三科目为系统预置(首次访问自动创建)</div>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref, reactive, computed } from 'vue'
import { ElMessage } from 'element-plus'
import {
  listAdvanceSubjects, createAdvanceSubject, updateAdvanceSubjectStatus,
  listAdvanceAccounts, listAdvanceFlows, chargeAdvance, deductAdvance, withdrawAdvance
} from '../../api'
import { addons } from '../../store/addons'

const subjects = ref([])
const accounts = ref([])
const flows = ref([])
const flowAccount = ref(null)
const loading = ref(false)
const saving = ref(false)

const filters = reactive({ subjectId: null, refId: '' })
const moveDialog = ref(false)
const moveMode = ref('charge')
const subjectDialog = ref(false)
const flowDrawer = ref(false)
const moveForm = ref({})
const newSubject = reactive({ code: '', name: '' })

const flowTypeDict = computed(() => {
  const payment = addons.list.find((a) => a.id === 'payment')
  return ((payment && payment.dicts) || {}).flowType || []
})
const enabledSubjects = computed(() => subjects.value.filter((s) => s.status === 0))
const moveTitle = computed(() => ({ charge: '预付款收入', deduct: '预付款划扣', withdraw: '预付款提现' })[moveMode.value])

const yuan = (li) => (Number(li || 0) / 1000).toLocaleString('zh-CN', { minimumFractionDigits: 3 })
const flowLabel = (t) => {
  const hit = flowTypeDict.value.find((d) => d.key === String(t))
  return hit ? hit.label : t
}

async function loadSubjects() {
  const res = await listAdvanceSubjects({ includeDisabled: true })
  subjects.value = res.data || []
}

async function load() {
  loading.value = true
  try {
    const res = await listAdvanceAccounts({
      subjectId: filters.subjectId || undefined,
      refId: filters.refId || undefined
    })
    accounts.value = res.data || []
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

function openMove(mode) {
  moveMode.value = mode
  moveForm.value = { subjectCode: null, refType: '', refId: '', yuan: 0, bizAddon: '', bizType: '', bizId: '', remark: '' }
  moveDialog.value = true
}

async function onMove() {
  const f = moveForm.value
  if (!f.subjectCode || !f.refType || !f.refId || !(f.yuan > 0)) {
    return ElMessage.warning('请补全科目/归属/金额(金额须大于零)')
  }
  if (moveMode.value === 'deduct' && (!f.bizAddon || !f.bizType || !f.bizId)) {
    return ElMessage.warning('划扣必须携带业务溯源三元组')
  }
  saving.value = true
  try {
    const payload = { ...f, amount: Math.round(f.yuan * 1000) }
    if (moveMode.value === 'charge') await chargeAdvance(payload)
    else if (moveMode.value === 'deduct') await deductAdvance(payload)
    else await withdrawAdvance(payload)
    ElMessage.success('已入账')
    moveDialog.value = false
    load()
    if (flowDrawer.value) openFlows(flowAccount.value)
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  } finally {
    saving.value = false
  }
}

async function openFlows(row) {
  flowAccount.value = row
  const res = await listAdvanceFlows(row.id, { page: 1, size: 50 })
  flows.value = (res.data && res.data.rows) || []
  flowDrawer.value = true
}

async function toggleSubject(row) {
  try {
    await updateAdvanceSubjectStatus(row.id, { status: row.status === 0 ? 1 : 0 })
    loadSubjects()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function onCreateSubject() {
  if (!newSubject.code || !newSubject.name) return ElMessage.warning('请填编码与名称')
  try {
    await createAdvanceSubject({ ...newSubject })
    newSubject.code = ''
    newSubject.name = ''
    loadSubjects()
  } catch (e) {
    ElMessage.error(e.message || '新增失败')
  }
}

onMounted(() => {
  loadSubjects()
  load()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; align-items: center; }
.tip { color: var(--yz-ink-50); font-size: 12px; }
.mono { font-family: Consolas, monospace; font-size: 12px; }
.new-type { display: flex; gap: 8px; margin-top: 12px; }
</style>
