<template>
  <div>
    <div class="toolbar">
      <el-button type="primary" @click="load">刷新</el-button>
    </div>

    <el-table :data="rows" border stripe v-loading="loading" @row-click="openDetail">
      <el-table-column prop="id" label="批次号" width="90" />
      <el-table-column prop="sale_code" label="销售单号" width="150" />
      <el-table-column prop="order_id" label="装修单" width="110" />
      <el-table-column prop="item_count" label="明细行" width="80" align="right" />
      <el-table-column prop="open_count" label="未完结行" width="90" align="right" />
      <el-table-column prop="remark" label="备注" min-width="150" show-overflow-tooltip />
      <el-table-column prop="created_by_name" label="创建人" width="110" />
      <el-table-column prop="created_at" label="创建时间" width="170" />
    </el-table>
    <el-pagination layout="prev, pager, next, total" :total="total" :page-size="filters.size"
      :current-page="filters.page" @current-change="(p) => { filters.page = p; load() }" style="margin-top: 12px" />

    <el-drawer v-model="detailDrawer" size="640px" :title="'配送批次 #' + (detail ? detail.id : '')">
      <el-table v-if="detail" :data="detail.items" border size="small">
        <el-table-column prop="goods_name" label="商品" min-width="130" />
        <el-table-column label="数量" width="80" align="right">
          <template #default="{ row }">{{ row.num / 1000 }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag effect="plain" size="small" :type="['info', '', '', 'warning', 'success'][row.status]">
              {{ label(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="下一动作" width="110">
          <template #default="{ row }">
            <el-button v-if="row.status < 4" size="small" link type="primary" @click="onAdvance(row)">
              {{ nextAction(row.status) }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { addons } from '../../store/addons'
import { pageSalesDeliveries, fetchSalesDelivery, advanceSalesDeliveryItem } from '../../api'

const dicts = reactive({ deliveryStatus: [] })
const filters = reactive({ page: 1, size: 20 })
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const detailDrawer = ref(false)
const detail = ref(null)

const label = (s) => {
  const hit = (dicts.deliveryStatus || []).find((d) => d.key === String(s))
  return hit ? hit.label : s
}
const nextAction = (s) => ({ 0: '配送', 1: '审核', 2: '客户确认', 3: '完结' }[s] || '')

async function load() {
  loading.value = true
  try {
    const res = await pageSalesDeliveries({ ...filters })
    rows.value = (res.data && res.data.rows) || []
    total.value = (res.data && res.data.total) || 0
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

async function openDetail(row) {
  const res = await fetchSalesDelivery(row.id)
  detail.value = res.data
  detailDrawer.value = true
}

async function onAdvance(row) {
  const action = { 0: 'tran', 1: 'check', 2: 'confirm', 3: 'finish' }[row.status]
  try {
    await advanceSalesDeliveryItem(row.id, { action })
    ElMessage.success('已推进:' + nextAction(row.status))
    openDetail({ id: detail.value.id })
  } catch (e) {
    ElMessage.error(e.message || '推进失败')
  }
}

onMounted(() => {
  const sales = addons.list.find((a) => a.id === 'sales')
  const d = (sales && sales.dicts) || {}
  dicts.deliveryStatus = d.deliveryStatus || []
  load()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; }
</style>
