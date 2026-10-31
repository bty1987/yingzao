<template>
  <el-card v-if="isAdmin">
    <div style="margin-bottom: 12px; display: flex; align-items: center; gap: 12px; flex-wrap: wrap">
      <span style="color: var(--yz-ink-50); font-size: 12px">
        登录日志(yz_audit_log):登录成功/失败(110001 密码误/120106 组织停用/120102 账号停用)+
        踢出(evict:停用/重置密码触发,老系统不留痕,营造补上)。user_id=0 行=用户不存在的尝试。
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
      <el-select v-model="result" placeholder="全部结果" clearable style="width: 120px" @change="load">
        <el-option label="成功" :value="0" />
        <el-option label="失败" :value="1" />
      </el-select>
      <el-input
        v-model="keyword"
        placeholder="登录名/动作(evict)"
        clearable
        style="width: 200px"
        @clear="load"
        @keyup.enter="load"
      />
      <el-button @click="load">查询</el-button>
    </div>

    <el-table :data="rows" border>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="created_at" label="时间" width="160" />
      <el-table-column prop="user_name" label="登录名" width="130" show-overflow-tooltip />
      <el-table-column prop="user_id" label="用户ID" width="84" />
      <el-table-column prop="action" label="动作" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.action === 'evict'" type="warning" size="small" effect="plain">踢出</el-tag>
          <span v-else>登录</span>
        </template>
      </el-table-column>
      <el-table-column label="结果" width="90">
        <template #default="{ row }">
          <el-tag :type="RESULT_TAG[row.result]" size="small">{{ RESULT_LABELS[row.result] || row.result }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="error_code" label="错误码" width="90">
        <template #default="{ row }">{{ row.error_code ?? '-' }}</template>
      </el-table-column>
      <el-table-column prop="error_msg" label="说明" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ row.error_msg || row.summary || '-' }}</template>
      </el-table-column>
      <el-table-column prop="ip" label="IP" width="130" show-overflow-tooltip />
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
  <el-empty v-else description="仅管理员可访问(122003)" />
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { pageAuditLogins } from '../api'

const isAdmin = JSON.parse(localStorage.getItem('yz_roles') || '[]').includes('admin')

const RESULT_LABELS = { 0: '成功', 1: '失败' }
const RESULT_TAG = { 0: 'success', 1: 'danger' }

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = 20
const keyword = ref('')
const result = ref(null)
const range = ref(null)

const load = async () => {
  const params = { page: page.value, size }
  if (keyword.value) params.keyword = keyword.value
  if (result.value !== null && result.value !== undefined && result.value !== '') params.result = result.value
  if (range.value && range.value[0]) params.begin = range.value[0]
  if (range.value && range.value[1]) params.end = range.value[1]
  const res = await pageAuditLogins(params)
  rows.value = res.rows || []
  total.value = res.total || 0
}

onMounted(() => {
  if (!isAdmin) return
  load()
})
</script>
