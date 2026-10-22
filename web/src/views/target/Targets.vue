<template>
  <div>
    <el-card shadow="never">
      <div class="toolbar">
        <el-select v-model="filter.node" placeholder="全部节点" clearable filterable style="width: 220px" @change="load">
          <el-option v-for="n in flatNodes" :key="n.id" :label="n.name" :value="n.id" />
        </el-select>
        <el-date-picker v-model="filter.ym" type="month" placeholder="目标月" value-format="YYYY-MM"
          style="width: 150px" @change="load" />
        <el-button @click="load">查询</el-button>
        <div class="spacer" />
        <el-button type="primary" @click="openCreate">新建目标</el-button>
      </div>

      <el-alert type="info" :closable="false" style="margin-bottom: 12px"
        title="V1 实绩口径:公司级装修单签约额(首签月=目标月);分区/部门实绩待单据归属模型演进" />

      <el-table :data="rows" border stripe>
        <el-table-column prop="node_name" label="目标节点" min-width="140" />
        <el-table-column prop="ym" label="目标月" width="100" />
        <el-table-column label="业绩线" width="100">
          <template #default="{ row }">{{ dicts.targetLine?.[row.line] || row.line }}</template>
        </el-table-column>
        <el-table-column label="目标(元)" width="120" align="right">
          <template #default="{ row }">{{ fmt(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="实绩(元)" width="120" align="right">
          <template #default="{ row }">{{ fmt(row.actual) }}</template>
        </el-table-column>
        <el-table-column label="达成率" width="200">
          <template #default="{ row }">
            <el-progress :percentage="row.rate === null ? 0 : Math.min(row.rate / 10, 100)"
              :format="() => (row.rate === null ? '-' : (row.rate / 10).toFixed(1) + '%')"
              :status="row.rate !== null && row.rate >= 1000 ? 'success' : ''" />
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 0 ? 'success' : 'info'" size="small">
              {{ dicts.targetStatus?.[String(row.status)] || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openAdjust(row)">调整</el-button>
            <el-button link @click="openRecords(row)">留痕</el-button>
            <el-button v-if="row.status === 0" link type="warning"
              @click="onToggle(row, 1)">停用</el-button>
            <el-button v-else link type="success" @click="onToggle(row, 0)">启用</el-button>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建目标 -->
    <el-dialog v-model="createDialog" title="新建经营目标" width="480px">
      <el-form label-width="90px">
        <el-form-item label="目标节点" required>
          <el-select v-model="createForm.orgNode" filterable style="width: 100%"
            placeholder="选择组织树节点(公司/分区/部门)">
            <el-option v-for="n in flatNodes" :key="n.id" :label="n.name" :value="n.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标月" required>
          <el-date-picker v-model="createForm.ym" type="month" value-format="YYYY-MM" />
        </el-form-item>
        <el-form-item label="目标额(元)" required>
          <el-input-number v-model="createForm.amountYuan" :min="0.01" :precision="2" :step="10000"
            style="width: 100%" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="createForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <div class="tip">V1 实绩口径为公司级签约额;分区/部门目标的实绩待单据归属模型演进</div>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 调整 -->
    <el-dialog v-model="adjustDialog" :title="`调整目标 - ${adjustTarget?.node_name || ''} ${adjustTarget?.ym || ''}`" width="440px">
      <el-form label-width="90px">
        <el-form-item label="原目标">
          <span>{{ fmt(adjustTarget?.amount) }} 元</span>
        </el-form-item>
        <el-form-item label="新目标(元)" required>
          <el-input-number v-model="adjustAmountYuan" :min="0.01" :precision="2" :step="10000"
            style="width: 100%" />
        </el-form-item>
        <el-form-item label="调整说明">
          <el-input v-model="adjustRemark" type="textarea" :rows="2" placeholder="月中调整建议留痕说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjustDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onAdjust">确认调整</el-button>
      </template>
    </el-dialog>

    <!-- 留痕 -->
    <el-drawer v-model="recordsDrawer" :title="`调整留痕 - ${recordsTarget?.node_name || ''} ${recordsTarget?.ym || ''}`" size="480px">
      <el-table :data="recordRows" border size="small">
        <el-table-column prop="old_amount" label="原(元)" width="110" align="right">
          <template #default="{ row }">{{ fmt(row.old_amount) }}</template>
        </el-table-column>
        <el-table-column prop="new_amount" label="新(元)" width="110" align="right">
          <template #default="{ row }">{{ fmt(row.new_amount) }}</template>
        </el-table-column>
        <el-table-column prop="remark" label="说明" min-width="120" />
        <el-table-column prop="created_at" label="时间" width="160" />
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  pageTargetPlans, createTargetPlan, adjustTargetPlan, changeTargetPlanStatus,
  deleteTargetPlan, listTargetRecords, orgTree, fetchAddons,
} from '../../api'

const dicts = ref({})
const rows = ref([])
const filter = reactive({ node: null, ym: null })
const saving = ref(false)
const tree = ref([])

const createDialog = ref(false)
const createForm = reactive({ orgNode: null, ym: null, amountYuan: 10000, remark: '' })
const adjustDialog = ref(false)
const adjustTarget = ref(null)
const adjustAmountYuan = ref(0)
const adjustRemark = ref('')
const recordsDrawer = ref(false)
const recordsTarget = ref(null)
const recordRows = ref([])

const flatNodes = computed(() => {
  const out = []
  const walk = (nodes, depth) => {
    for (const n of nodes || []) {
      out.push({ id: n.id, name: '　'.repeat(depth) + n.name })
      walk(n.children, depth + 1)
    }
  }
  walk(tree.value, 0)
  return out
})

const fmt = (li) => (Number(li || 0) / 1000).toLocaleString('zh-CN', { minimumFractionDigits: 2 })

async function loadDicts() {
  const res = await fetchAddons()
  const addon = (res.data || []).find((a) => a.id === 'target')
  if (addon?.dicts) dicts.value = addon.dicts
}

async function loadTree() {
  const res = await orgTree()
  tree.value = res.data || []
}

async function load() {
  const params = {}
  if (filter.node) params.node = filter.node
  if (filter.ym) params.ym = filter.ym
  const res = await pageTargetPlans(params)
  rows.value = res.data || []
}

function openCreate() {
  if (tree.value.length === 0) loadTree()
  Object.assign(createForm, { orgNode: null, ym: null, amountYuan: 10000, remark: '' })
  createDialog.value = true
}

async function onCreate() {
  if (!createForm.orgNode) return ElMessage.warning('请选目标节点')
  if (!createForm.ym) return ElMessage.warning('请选目标月')
  saving.value = true
  try {
    await createTargetPlan({ orgNode: createForm.orgNode, yearMonth: createForm.ym,
      amount: Math.round(createForm.amountYuan * 1000), remark: createForm.remark || null })
    ElMessage.success('目标已创建')
    createDialog.value = false
    load()
  } finally {
    saving.value = false
  }
}

function openAdjust(row) {
  adjustTarget.value = row
  adjustAmountYuan.value = row.amount / 1000
  adjustRemark.value = ''
  adjustDialog.value = true
}

async function onAdjust() {
  saving.value = true
  try {
    await adjustTargetPlan(adjustTarget.value.id, {
      amount: Math.round(adjustAmountYuan.value * 1000), remark: adjustRemark.value || null })
    ElMessage.success('已调整(留痕)')
    adjustDialog.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function onToggle(row, status) {
  await changeTargetPlanStatus(row.id, { status })
  ElMessage.success(status === 1 ? '已停用' : '已启用')
  load()
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm('删除目标(错录场景;调整留痕一并删除)。确认删除?', '删除目标',
      { type: 'warning' })
  } catch { return }
  await deleteTargetPlan(row.id)
  ElMessage.success('已删除')
  load()
}

async function openRecords(row) {
  recordsTarget.value = row
  const res = await listTargetRecords(row.id)
  recordRows.value = res.data || []
  recordsDrawer.value = true
}

onMounted(async () => {
  await loadDicts()
  await loadTree()
  await load()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 12px; }
.spacer { flex: 1; }
.tip { color: var(--yz-ink-50); font-size: 12px; margin-bottom: 8px; }
</style>
