<template>
  <el-card v-if="isAdmin">
    <div style="margin-bottom: 12px; display: flex; align-items: center; gap: 12px; flex-wrap: wrap">
      <span style="color: var(--yz-ink-50); font-size: 12px">
        操作日志(yz_audit_log):@AuditLog 注解切面统一记录(REQUIRES_NEW 独立事务,敏感参数脱敏;
        业务失败/系统异常均原样留痕)。保留策略=audit-log-purge 计划,可在「计划任务」页查看。
      </span>
      <span style="flex: 1" />
      <el-date-picker
        v-model="range"
        type="daterange"
        value-format="YYYY-MM-DD"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        style="width: 260px"
        @change="load"
      />
      <el-input
        v-model="keyword"
        placeholder="操作人/动作/模块/对象/摘要"
        clearable
        style="width: 220px"
        @clear="load"
        @keyup.enter="load"
      />
      <el-button @click="load">查询</el-button>
    </div>

    <el-table :data="rows" border>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="created_at" label="时间" width="160" />
      <el-table-column prop="user_name" label="操作人" width="110" show-overflow-tooltip />
      <el-table-column prop="module" label="模块" width="100" />
      <el-table-column prop="action" label="动作" width="130" show-overflow-tooltip />
      <el-table-column label="对象" min-width="120" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.object_type ? `${row.object_type}#${row.object_id || '-'}` : '-' }}
        </template>
      </el-table-column>
      <el-table-column prop="summary" label="摘要" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">{{ row.summary || '-' }}</template>
      </el-table-column>
      <el-table-column label="结果" width="90">
        <template #default="{ row }">
          <el-tag :type="RESULT_TAG[row.result]" size="small">{{ RESULT_LABELS[row.result] || row.result }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="error_code" label="错误码" width="84">
        <template #default="{ row }">{{ row.error_code ?? '-' }}</template>
      </el-table-column>
      <el-table-column prop="ip" label="IP" width="120" show-overflow-tooltip />
      <el-table-column label="耗时" width="80">
        <template #default="{ row }">{{ row.run_time ? `${row.run_time}ms` : '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="80" fixed="right">
        <template #default="{ row }">
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

    <el-drawer v-model="detailDrawer" size="520px" :title="`操作日志 #${detail?.id || ''}`">
      <template v-if="detail">
        <div class="axis-row">
          <el-tag :type="RESULT_TAG[detail.result]" effect="plain">
            {{ RESULT_LABELS[detail.result] || detail.result }}
          </el-tag>
          <el-tag v-if="detail.error_code" type="warning" effect="plain" size="small">
            {{ detail.error_code }}
          </el-tag>
        </div>
        <div class="kv"><span>时间</span>{{ detail.created_at }}</div>
        <div class="kv"><span>操作人</span>{{ detail.user_name }}(#{{ detail.user_id }})</div>
        <div class="kv"><span>模块/动作</span>{{ detail.module }} / {{ detail.action }}</div>
        <div class="kv"><span>对象</span>{{ detail.object_type ? `${detail.object_type}#${detail.object_id}` : '-' }}</div>
        <div class="kv"><span>摘要</span><span class="mono">{{ detail.summary || '-' }}</span></div>
        <div class="kv"><span>参数</span><span class="mono">{{ detail.detail || '-' }}</span></div>
        <div class="kv"><span>错误</span>{{ detail.error_msg || '-' }}</div>
        <div class="kv"><span>IP</span>{{ detail.ip || '-' }}</div>
        <div class="kv"><span>耗时</span>{{ detail.run_time ? `${detail.run_time}ms` : '-' }}</div>
      </template>
    </el-drawer>
  </el-card>
  <el-empty v-else description="仅管理员可访问(122003)" />
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { pageAuditOperations } from '../api'

const isAdmin = JSON.parse(localStorage.getItem('yz_roles') || '[]').includes('admin')

const RESULT_LABELS = { 0: '成功', 1: '业务失败', 2: '系统异常' }
const RESULT_TAG = { 0: 'success', 1: 'warning', 2: 'danger' }

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = 20
const keyword = ref('')
const range = ref(null)
const detail = ref(null)
const detailDrawer = ref(false)

const load = async () => {
  const params = { page: page.value, size }
  if (keyword.value) params.keyword = keyword.value
  if (range.value && range.value[0]) params.begin = range.value[0]
  if (range.value && range.value[1]) params.end = range.value[1]
  const res = await pageAuditOperations(params)
  rows.value = res.rows || []
  total.value = res.total || 0
}

const openDetail = (row) => {
  detail.value = row
  detailDrawer.value = true
}

onMounted(() => {
  if (!isAdmin) return
  load()
})
</script>

<style scoped>
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
