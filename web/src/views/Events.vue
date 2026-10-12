<template>
  <el-card v-if="isAdmin">
    <div style="margin-bottom: 12px; display: flex; align-items: center; gap: 12px; flex-wrap: wrap">
      <span style="color: var(--yz-ink-50); font-size: 12px">
        领域事件出站箱:发布方业务事务内落箱,提交后立即投递;失败退避重试,超限转死信。
        消费确认是「禁静默丢失」红线的凭证,死信 requeue 是人工兜底出口。
      </span>
      <span style="flex: 1" />
      <el-select v-model="filter.status" placeholder="全部状态" clearable style="width: 140px" @change="load">
        <el-option v-for="(label, val) in STATUS_LABELS" :key="val" :label="label" :value="Number(val)" />
      </el-select>
      <el-input
        v-model="filter.eventType"
        placeholder="事件类型,如 PaymentCollectedEvent"
        clearable
        style="width: 240px"
        @clear="load"
        @keyup.enter="load"
      />
      <el-button @click="load">查询</el-button>
    </div>

    <div class="stat-row">
      <div v-for="(label, val) in STATUS_LABELS" :key="val" class="stat-cell" :class="`s${val}`">
        <p class="stat-num">{{ stats['status' + val] ?? 0 }}</p>
        <p class="stat-label">{{ label }}</p>
      </div>
    </div>

    <el-table :data="rows" border>
      <el-table-column prop="id" label="ID" width="72" />
      <el-table-column prop="event_type" label="事件类型" min-width="200" show-overflow-tooltip />
      <el-table-column prop="event_key" label="幂等键" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ row.event_key || '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="96">
        <template #default="{ row }">
          <el-tag :type="STATUS_TAG[row.status]" size="small">{{ STATUS_LABELS[row.status] || row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="重试" width="88">
        <template #default="{ row }">{{ row.retry_count }}/{{ row.max_retries }}</template>
      </el-table-column>
      <el-table-column prop="next_retry_time" label="下次投递" width="160">
        <template #default="{ row }">{{ row.next_retry_time || '-' }}</template>
      </el-table-column>
      <el-table-column prop="created_at" label="创建时间" width="160" />
      <el-table-column prop="last_error" label="最近错误" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">{{ row.last_error || '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="104" fixed="right">
        <template #default="{ row }">
          <el-button size="small" :loading="requeuing === row.id" @click="requeue(row)">requeue</el-button>
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
  </el-card>
  <el-empty v-else description="仅管理员可访问(123001)" />
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { pageEvents, eventStats, requeueEvent } from '../api'

const isAdmin = JSON.parse(localStorage.getItem('yz_roles') || '[]').includes('admin')

const STATUS_LABELS = { 0: '待投递', 1: '已消费', 2: '重试中', 3: '死信' }
const STATUS_TAG = { 0: 'info', 1: 'success', 2: 'warning', 3: 'danger' }

const stats = ref({})
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = 20
const filter = reactive({ status: null, eventType: '' })
const requeuing = ref(null)

async function load() {
  const params = { page: page.value, size }
  if (filter.status !== null && filter.status !== '') params.status = filter.status
  if (filter.eventType && filter.eventType.trim()) params.eventType = filter.eventType.trim()
  const { data } = await pageEvents(params)
  rows.value = data.rows || []
  total.value = data.total || 0
}

async function loadStats() {
  stats.value = (await eventStats()).data || {}
}

async function requeue(row) {
  requeuing.value = row.id
  try {
    await requeueEvent(row.id)
    ElMessage.success(`已重置为待投递并触发一轮投递(#${row.id})`)
    await Promise.all([load(), loadStats()])
  } finally {
    requeuing.value = null
  }
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
</style>
