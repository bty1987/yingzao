<template>
  <div>
    <div class="toolbar">
      <el-button type="primary" @click="load">刷新</el-button>
      <el-button @click="openFlows">品牌配送流程</el-button>
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

    <!-- B2 品牌配送流程模板:免人工位 + 里程碑分成(正交配置;完结级恒人工防自答) -->
    <el-dialog v-model="flowDialog" title="品牌配送流程" width="640px">
      <el-form label-width="120px">
        <el-form-item label="品牌">
          <el-select v-model="flowForm.brandId" filterable placeholder="选择品牌" style="width: 100%" @change="prefillFlow">
            <el-option v-for="b in brands" :key="b.id" :value="b.id" :label="b.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="免人工推进">
          <el-checkbox v-model="flowForm.autoTran">配送级自动通过</el-checkbox>
          <el-checkbox v-model="flowForm.autoCheck">审核级自动通过</el-checkbox>
          <el-checkbox v-model="flowForm.autoConfirm">确认级自动通过</el-checkbox>
        </el-form-item>
        <el-form-item label=" ">
          <span class="flow-hint">完结级恒人工(防自答);人工逐级纪律不变,系统仅按配置代打并留痕</span>
        </el-form-item>
        <el-form-item label="里程碑分成">
          <div class="rate-grid">
            <div v-for="m in 4" :key="m" class="rate-cell">
              <span>{{ milestoneLabel(m) }}</span>
              <el-input-number v-model="flowForm.rates[m - 1].rate" :min="0" :max="10000" :step="500"
                :controls="false" style="width: 90px" />
              <span class="rate-pct">{{ pct(flowForm.rates[m - 1].rate) }}</span>
            </div>
          </div>
        </el-form-item>
        <el-form-item label=" ">
          <span class="flow-hint">万分比(4000=40%);全部为 0 = 不启用分成;启用时四级之和须等于 10000</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="flowDialog = false">取消</el-button>
        <el-button type="primary" :disabled="!flowForm.brandId" @click="saveFlow">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { addons } from '../../store/addons'
import {
  pageSalesDeliveries, fetchSalesDelivery, advanceSalesDeliveryItem,
  listSalesDeliveryFlows, saveSalesDeliveryFlow, listBrands
} from '../../api'

const dicts = reactive({ deliveryStatus: [], deliveryMilestone: [] })
const filters = reactive({ page: 1, size: 20 })
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const detailDrawer = ref(false)
const detail = ref(null)

// B2 品牌配送流程
const flowDialog = ref(false)
const brands = ref([])
const flowRows = ref([])
const flowForm = reactive({ brandId: null, autoTran: false, autoCheck: false, autoConfirm: false, rates: [] })

const label = (s) => {
  const hit = (dicts.deliveryStatus || []).find((d) => d.key === String(s))
  return hit ? hit.label : s
}
const milestoneLabel = (m) => {
  const hit = (dicts.deliveryMilestone || []).find((d) => d.key === String(m))
  return hit ? hit.label : m
}
const nextAction = (s) => ({ 0: '配送', 1: '审核', 2: '客户确认', 3: '完结' }[s] || '')
const pct = (v) => ((Number(v) || 0) / 100).toFixed(2).replace(/\.?0+$/, '') + '%'

function emptyRates() {
  return [1, 2, 3, 4].map((m) => ({ milestone: m, rate: 0 }))
}

async function openFlows() {
  flowDialog.value = true
  try {
    const [b, f] = await Promise.all([listBrands({ page: 1, size: 100 }), listSalesDeliveryFlows()])
    brands.value = ((b.data && b.data.rows) || b.data || [])
    flowRows.value = f.data || []
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  }
}

function prefillFlow(brandId) {
  const hit = (flowRows.value || []).find((r) => Number(r.brand_id) === Number(brandId))
  flowForm.autoTran = !!(hit && hit.auto_tran === 1)
  flowForm.autoCheck = !!(hit && hit.auto_check === 1)
  flowForm.autoConfirm = !!(hit && hit.auto_confirm === 1)
  flowForm.rates = emptyRates()
  if (hit) {
    for (const r of flowForm.rates) {
      r.rate = hit['rate_' + ['tran', 'check', 'confirm', 'finish'][r.milestone - 1]] || 0
    }
  }
}

async function saveFlow() {
  const rates = (flowForm.rates || []).filter((r) => Number(r.rate) > 0)
    .map((r) => ({ milestone: r.milestone, rate: Number(r.rate) }))
  const sum = rates.reduce((a, r) => a + r.rate, 0)
  if (sum > 0 && sum !== 10000) return ElMessage.warning('启用分成时四级比例之和须为 10000(100%)')
  try {
    await saveSalesDeliveryFlow(flowForm.brandId, {
      autoTran: flowForm.autoTran ? 1 : 0,
      autoCheck: flowForm.autoCheck ? 1 : 0,
      autoConfirm: flowForm.autoConfirm ? 1 : 0,
      rates
    })
    ElMessage.success('流程模板已保存')
    flowDialog.value = false
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  }
}

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
  dicts.deliveryMilestone = d.deliveryMilestone || []
  flowForm.rates = emptyRates()
  load()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; }
.flow-hint { color: rgba(26, 26, 24, 0.62); font-size: 12px; }
.rate-grid { display: flex; gap: 16px; flex-wrap: wrap; }
.rate-cell { display: flex; align-items: center; gap: 6px; }
.rate-pct { color: rgba(26, 26, 24, 0.62); font-size: 12px; width: 44px; }
</style>
