<template>
  <div>
    <div style="margin-bottom: 12px">
      <el-button @click="load">刷新</el-button>
    </div>

    <el-table :data="chances" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="lead_name" label="客源" width="120" />
      <el-table-column prop="phone" label="电话" width="140" />
      <el-table-column prop="assignee_name" label="责任人" width="120" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTag[row.status] || 'info'">{{ statusLabel[row.status] || row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="assigned_at" label="分配时间" width="170" />
      <el-table-column prop="confirmed_at" label="确认时间" width="170" />
      <el-table-column prop="completed_at" label="完成时间" width="170" />
      <el-table-column label="操作" width="140">
        <template #default="{ row }">
          <el-button v-if="row.status === 0" type="primary" link @click="onConfirm(row)">确认接单</el-button>
          <el-button v-if="row.status === 3" type="success" link @click="onComplete(row)">标记完成</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listChances, confirmChance, completeChance } from '../../api'

const chances = ref([])

// 状态机 0 已分配 → 3 已确认 → 4 已完成(与 addon-customer ChanceService 对齐)
const statusLabel = { 0: '已分配', 3: '已确认', 4: '已完成' }
const statusTag = { 0: 'warning', 3: 'primary', 4: 'success' }

onMounted(load)

async function load() {
  const res = await listChances()
  chances.value = res.data || []
}

async function onConfirm(row) {
  try {
    await confirmChance(row.id)
    ElMessage.success('已确认接单')
    load()
  } catch (e) {
    ElMessage.error(e.message || '确认失败')
  }
}

async function onComplete(row) {
  try {
    await completeChance(row.id)
    ElMessage.success('已完成')
    load()
  } catch (e) {
    ElMessage.error(e.message || '完成失败')
  }
}
</script>
