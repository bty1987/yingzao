<template>
  <el-card v-if="isAdmin">
    <div style="margin-bottom: 12px; display: flex; align-items: center; gap: 12px; flex-wrap: wrap">
      <span style="color: var(--yz-ink-50); font-size: 12px">
        计划任务(yz_plan):待办定点执行(一任务一处理器),失败指数退避,超限转死信。
        与事件总线的分工:总线管广播与回程,计划管待办(导出生成/批量重算/清算跑批等)。
      </span>
      <span style="flex: 1" />
      <el-select v-model="filter.status" placeholder="全部状态" clearable style="width: 140px" @change="load">
        <el-option v-for="(label, val) in STATUS_LABELS" :key="val" :label="label" :value="Number(val)" />
      </el-select>
      <el-input
        v-model="filter.handler"
        placeholder="处理器,如 member-expire-clear"
        clearable
        style="width: 240px"
        @clear="load"
        @keyup.enter="load"
      />
      <el-button @click="load">查询</el-button>
      <el-button type="primary" plain @click="openSubmit">提交计划</el-button>
    </div>

    <div class="stat-row">
      <div v-for="(label, val) in STATUS_LABELS" :key="val" class="stat-cell" :class="`s${val}`">
        <p class="stat-num">{{ stats['status' + val] ?? 0 }}</p>
        <p class="stat-label">{{ label }}</p>
      </div>
    </div>

    <el-table :data="rows" border>
      <el-table-column prop="id" label="ID" width="72" />
      <el-table-column prop="handler" label="处理器" min-width="170" show-overflow-tooltip />
      <el-table-column prop="biz_key" label="业务键" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.biz_key || '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="STATUS_TAG[row.status]" size="small">{{ STATUS_LABELS[row.status] || row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="重试" width="80">
        <template #default="{ row }">{{ row.retry_count }}/{{ row.max_retries }}</template>
      </el-table-column>
      <el-table-column prop="next_run_time" label="到期时间" width="160">
        <template #default="{ row }">{{ row.next_run_time || '立即' }}</template>
      </el-table-column>
      <el-table-column prop="result" label="结果" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.result || '-' }}</template>
      </el-table-column>
      <el-table-column prop="last_error" label="最近错误" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.last_error || '-' }}</template>
      </el-table-column>
      <el-table-column prop="created_at" label="创建时间" width="160" />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button v-if="[0, 2].includes(row.status)" size="small" type="warning"
            :loading="acting === row.id" @click="onCancel(row)">取消</el-button>
          <el-button v-if="[2, 3].includes(row.status)" size="small" type="primary"
            :loading="acting === row.id" @click="onRetry(row)">重跑</el-button>
          <el-button size="small" link @click="openDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div style="margin-top: 12px; display: flex; justify-content: flex-end">
      <el-pagination
        layout="total, prev, pager, next"
        :total="total"
        :page-size="size"
        :current-page="page"
        @current-change="(p) => { page = p; load() }"
      />
    </div>

    <!-- 提交计划(管理/演示面;业务插件姿势=注入 PlanService 直调) -->
    <el-dialog v-model="submitDialog" title="提交计划(管理面)" width="520px">
      <el-form label-width="90px">
        <el-form-item label="处理器" required>
          <el-input v-model="submitForm.handler" placeholder="如 member-expire-clear / echo" />
        </el-form-item>
        <el-form-item label="业务键">
          <el-input v-model="submitForm.bizKey" placeholder="幂等/对账键,可空" />
        </el-form-item>
        <el-form-item label="参数 JSON">
          <el-input v-model="submitForm.params" type="textarea" :rows="2" placeholder='{"message":"你好"}' />
        </el-form-item>
        <el-form-item label="重试上限">
          <el-input-number v-model="submitForm.maxRetries" :min="1" :max="10" />
        </el-form-item>
        <el-form-item label="定时">
          <el-date-picker v-model="submitForm.runAt" type="datetime" placeholder="空=立即可执行" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="submitDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSubmit">提交</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailDrawer" size="520px" :title="`计划 #${detail?.id || ''}`">
      <template v-if="detail">
        <div class="axis-row">
          <el-tag :type="STATUS_TAG[detail.status]" effect="plain">{{ STATUS_LABELS[detail.status] || detail.status }}</el-tag>
          <el-tag effect="plain" size="small">重试 {{ detail.retry_count }}/{{ detail.max_retries }}</el-tag>
        </div>
        <div class="kv"><span>处理器</span>{{ detail.handler }}</div>
        <div class="kv"><span>业务键</span>{{ detail.biz_key || '-' }}</div>
        <div class="kv"><span>组织</span>{{ detail.org_id }}</div>
        <div class="kv"><span>参数</span><span class="mono">{{ detail.params || '-' }}</span></div>
        <div class="kv"><span>到期</span>{{ detail.next_run_time || '立即' }}</div>
        <div class="kv"><span>结果</span>{{ detail.result || '-' }}</div>
        <div class="kv"><span>最近错误</span>{{ detail.last_error || '-' }}</div>
        <div class="kv"><span>创建</span>{{ detail.created_at }}</div>
        <div class="kv"><span>更新</span>{{ detail.updated_at }}</div>
      </template>
    </el-drawer>
  </el-card>
  <el-empty v-else description="仅管理员可访问(123013)" />
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { pagePlans, planStats, fetchPlan, cancelPlan, retryPlan, submitPlan } from '../api'

const isAdmin = JSON.parse(localStorage.getItem('yz_roles') || '[]').includes('admin')

const STATUS_LABELS = { 0: '待执行', 1: '成功', 2: '重试中', 3: '死信', 4: '已取消' }
const STATUS_TAG = { 0: 'info', 1: 'success', 2: 'warning', 3: 'danger', 4: 'info' }

const stats = ref({})
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = 20
const filter = reactive({ status: null, handler: '' })
const acting = ref(null)
const saving = ref(false)
const submitDialog = ref(false)
const submitForm = reactive({ handler: '', bizKey: '', params: '', maxRetries: 3, runAt: null })
const detailDrawer = ref(false)
const detail = ref(null)

async function load() {
  const params = { page: page.value, size }
  if (filter.status !== null && filter.status !== '') params.status = filter.status
  if (filter.handler && filter.handler.trim()) params.handler = filter.handler.trim()
  const { data } = await pagePlans(params)
  rows.value = data.rows || []
  total.value = data.total || 0
}

async function loadStats() {
  stats.value = (await planStats()).data || {}
}

function openSubmit() {
  Object.assign(submitForm, { handler: '', bizKey: '', params: '', maxRetries: 3, runAt: null })
  submitDialog.value = true
}

function fmtRunAt(d) {
  if (!d) return null
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

async function onSubmit() {
  if (!submitForm.handler.trim()) return ElMessage.warning('请填处理器注册名')
  let params = null
  if (submitForm.params && submitForm.params.trim()) {
    try {
      params = JSON.parse(submitForm.params)
    } catch {
      return ElMessage.warning('参数须为合法 JSON')
    }
  }
  saving.value = true
  try {
    const { data } = await submitPlan({
      handler: submitForm.handler.trim(),
      bizKey: submitForm.bizKey || null,
      params,
      maxRetries: submitForm.maxRetries,
      runAt: fmtRunAt(submitForm.runAt),
    })
    ElMessage.success(`已提交计划 #${data}`)
    submitDialog.value = false
    await Promise.all([load(), loadStats()])
  } finally {
    saving.value = false
  }
}

async function onCancel(row) {
  acting.value = row.id
  try {
    await cancelPlan(row.id)
    ElMessage.success(`已取消计划 #${row.id}`)
    await Promise.all([load(), loadStats()])
  } finally {
    acting.value = null
  }
}

async function onRetry(row) {
  acting.value = row.id
  try {
    await retryPlan(row.id)
    ElMessage.success(`已重置为待执行并立即执行一轮(#${row.id})`)
    await Promise.all([load(), loadStats()])
  } finally {
    acting.value = null
  }
}

async function openDetail(row) {
  const { data } = await fetchPlan(row.id)
  detail.value = data
  detailDrawer.value = true
}

onMounted(async () => {
  if (!isAdmin) return
  await Promise.all([load(), loadStats()])
})
</script>

<style scoped>
.stat-row {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.stat-cell {
  flex: 1;
  border: 1px solid var(--yz-line);
  padding: 10px 14px;
  background: var(--yz-paper-deep);
}

.stat-cell.s3 { border-left: 3px solid var(--yz-seal); }
.stat-cell.s2 { border-left: 3px solid var(--yz-gold, #A6864B); }
.stat-cell.s1 { border-left: 3px solid var(--yz-moss, #5B7065); }
.stat-cell.s4 { border-left: 3px solid var(--yz-ink-50, #999); }

.stat-num {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
  color: var(--yz-ink);
  font-family: var(--yz-display, inherit);
}

.stat-label {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--yz-ink-50);
  letter-spacing: 2px;
}

.axis-row {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.kv {
  display: flex;
  gap: 10px;
  padding: 6px 0;
  border-bottom: 1px dashed var(--yz-line);
  font-size: 13px;
  word-break: break-all;
}

.kv span:first-child {
  flex: 0 0 70px;
  color: var(--yz-ink-50);
}

.mono {
  font-family: monospace;
}
</style>
