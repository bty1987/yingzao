<template>
  <div>
    <div class="toolbar">
      <el-input v-model="filters.saleCode" placeholder="销售单号" style="width: 150px" clearable @keyup.enter="load" />
      <el-select v-model="filters.status" placeholder="行状态" style="width: 110px" clearable>
        <el-option v-for="d in dicts.settleStatus" :key="d.key" :label="d.label" :value="Number(d.key)" />
      </el-select>
      <el-select v-model="filters.type" placeholder="类型" style="width: 120px" clearable>
        <el-option v-for="d in dicts.settleType" :key="d.key" :label="d.label" :value="Number(d.key)" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
    </div>

    <el-table :data="rows" border stripe v-loading="loading">
      <el-table-column prop="sale_code" label="销售单号" width="150" />
      <el-table-column prop="brand_name" label="品牌" width="110" />
      <el-table-column label="类型" width="95">
        <template #default="{ row }">{{ label('settleType', row.type) }}</template>
      </el-table-column>
      <el-table-column label="应收(元)" width="110" align="right">
        <template #default="{ row }">{{ yuan(row.total) }}</template>
      </el-table-column>
      <el-table-column label="厂结值(元)" width="110" align="right">
        <template #default="{ row }">{{ yuan(row.vbalance) }}</template>
      </el-table-column>
      <el-table-column label="结算值(元)" width="110" align="right">
        <template #default="{ row }">{{ yuan(row.pbalance) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag effect="plain" :type="['info', 'warning', 'success'][row.status]">{{ label('settleStatus', row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="vposted_at" label="厂结时间" width="165" />
      <el-table-column prop="posted_at" label="结算时间" width="165" />
      <el-table-column label="操作" width="270">
        <template #default="{ row }">
          <el-button v-if="row.status === 0" link type="primary" @click="editValue(row, 'vbalance')">改厂结值</el-button>
          <el-button v-if="row.status === 0" link type="success" @click="onVpost(row)">厂结</el-button>
          <el-button v-if="row.status === 1" link type="primary" @click="editValue(row, 'pbalance')">改结算值</el-button>
          <el-button v-if="row.status === 1" link type="success" @click="onPost(row)">结算</el-button>
          <el-button v-if="row.type === 0" link type="info" @click="openBatches(row)">分批计划</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination layout="prev, pager, next, total" :total="total" :page-size="filters.size"
      :current-page="filters.page" @current-change="(p) => { filters.page = p; load() }" style="margin-top: 12px" />

    <!-- B2 里程碑分批计划(认领计划视图):该品牌结算总额按里程碑的拆分,Σ 分批 = 预结总额 -->
    <el-drawer v-model="batchDrawer" size="620px" :title="'分批计划 — ' + (batchRow ? batchRow.sale_code + ' / ' + batchRow.brand_name : '')">
      <div v-if="batchRow" class="batch-meta">
        <span>预结总额:{{ yuan(batchRow.total) }}</span>
        <span>分批合计:{{ yuan(batchSum) }}</span>
        <el-tag effect="plain" size="small" type="info">Σ 分批 = 预结总额(末批补尾差)</el-tag>
      </div>
      <el-table :data="batches" border size="small">
        <el-table-column label="里程碑" width="80">
          <template #default="{ row }">{{ label('deliveryMilestone', row.milestone) }}</template>
        </el-table-column>
        <el-table-column label="比例" width="80" align="right">
          <template #default="{ row }">{{ (row.rate / 100).toFixed(2).replace(/\.?0+$/, '') + '%' }}</template>
        </el-table-column>
        <el-table-column label="应收(元)" width="100" align="right">
          <template #default="{ row }">{{ yuan(row.total) }}</template>
        </el-table-column>
        <el-table-column label="厂结份额(元)" width="110" align="right">
          <template #default="{ row }">{{ yuan(row.vbalance) }}</template>
        </el-table-column>
        <el-table-column label="结算份额(元)" width="110" align="right">
          <template #default="{ row }">{{ yuan(row.pbalance) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag effect="plain" size="small" :type="row.status === 1 ? 'success' : 'info'">
              {{ label('batchStatus', row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="claimed_at" label="认领时间" width="165" />
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { addons } from '../../store/addons'
import {
  pageSalesSettlements, updateSettlementVbalance, vpostSettlement,
  updateSettlementPbalance, postSettlement, listSalesSettlementBatches
} from '../../api'

const dicts = reactive({ settleStatus: [], settleType: [], deliveryMilestone: [], batchStatus: [] })
const filters = reactive({ saleCode: '', status: null, type: null, page: 1, size: 20 })
const rows = ref([])
const total = ref(0)
const loading = ref(false)
// B2 分批计划抽屉
const batchDrawer = ref(false)
const batchRow = ref(null)
const batches = ref([])
const batchSum = ref(0)

const yuan = (li) => (Number(li || 0) / 1000).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 3 })
const label = (dict, v) => {
  const hit = (dicts[dict] || []).find((d) => d.key === String(v))
  return hit ? hit.label : v
}

async function load() {
  loading.value = true
  try {
    const res = await pageSalesSettlements({ ...filters, saleCode: filters.saleCode || undefined })
    rows.value = (res.data && res.data.rows) || []
    total.value = (res.data && res.data.total) || 0
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

async function editValue(row, field) {
  const title = field === 'vbalance' ? '改厂结值(非零且与金额同向)' : '改结算值(非零且与金额同向)'
  try {
    const { value } = await ElMessageBox.prompt('金额(元)', title + ' — ' + row.sale_code, { type: 'warning' })
    const li = Math.round(Number(value) * 1000)
    if (!li) return ElMessage.warning('值不可为 0')
    if (field === 'vbalance') await updateSettlementVbalance(row.id, { value: li })
    else await updateSettlementPbalance(row.id, { value: li })
    ElMessage.success('已调整')
    load()
  } catch (e) {
    if (e?.message) ElMessage.error(e.message || '操作失败')
  }
}

async function onVpost(row) {
  try {
    await vpostSettlement(row.id)
    ElMessage.success('已厂结')
    load()
  } catch (e) {
    ElMessage.error(e.message || '厂结失败')
  }
}

async function onPost(row) {
  try {
    await postSettlement(row.id)
    ElMessage.success('已结算(终态)')
    load()
  } catch (e) {
    ElMessage.error(e.message || '结算失败')
  }
}

async function openBatches(row) {
  batchRow.value = row
  batchDrawer.value = true
  try {
    const res = await listSalesSettlementBatches(row.id)
    batches.value = res.data || []
    batchSum.value = batches.value.reduce((a, b) => a + Number(b.total || 0), 0)
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  }
}

onMounted(() => {
  const sales = addons.list.find((a) => a.id === 'sales')
  const d = (sales && sales.dicts) || {}
  dicts.settleStatus = d.settleStatus || []
  dicts.settleType = d.settleType || []
  dicts.deliveryMilestone = d.deliveryMilestone || []
  dicts.batchStatus = d.batchStatus || []
  load()
})
</script>

<style scoped>
.batch-meta { display: flex; gap: 16px; align-items: center; margin-bottom: 12px; font-size: 13px; }
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; }
</style>
