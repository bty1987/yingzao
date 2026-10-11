<template>
  <div>
    <div class="toolbar">
      <el-select v-model="filters.status" placeholder="状态" style="width: 120px" clearable @change="load">
        <el-option v-for="d in dicts.requisitionStatus" :key="d.key" :label="d.label" :value="Number(d.key)" />
      </el-select>
      <el-select v-model="filters.type" placeholder="类型" style="width: 110px" clearable @change="load">
        <el-option v-for="d in dicts.requisitionType" :key="d.key" :label="d.label" :value="Number(d.key)" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <el-button @click="openCreate">领料登记</el-button>
      <span class="tip">工程额度双闸(总额/per-gid)在施工域校验;售后单跳过额度联动</span>
    </div>

    <el-table :data="rows" border stripe v-loading="loading">
      <el-table-column prop="code" label="单号" width="150">
        <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
      </el-table-column>
      <el-table-column prop="order_no" label="装修单" width="140" />
      <el-table-column prop="house_label" label="房屋" min-width="150" show-overflow-tooltip />
      <el-table-column label="类型" width="80">
        <template #default="{ row }">{{ label('requisitionType', row.type) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="['3', '4'].includes(String(row.status)) ? 'danger'
            : row.status === 6 ? 'success' : 'info'">{{ label('requisitionStatus', row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="总额(元)" width="110" align="right">
        <template #default="{ row }">{{ yuan(row.total) }}</template>
      </el-table-column>
      <el-table-column prop="created_by_name" label="领用人" width="90" />
      <el-table-column prop="created_at" label="登记时间" width="170" />
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row.id)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination layout="total, prev, pager, next" :total="total" :page-size="size"
      v-model:current-page="page" @current-change="load" style="margin-top: 12px" />

    <!-- 登记 -->
    <el-dialog v-model="createDialog" title="领料登记" width="820px">
      <el-form label-width="80px">
        <el-form-item label="装修单">
          <el-input v-model="createForm.orderId" placeholder="装修单id(须已放线点火)" style="width: 220px" />
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="createForm.type">
            <el-radio :value="0">普通(占工程额度)</el-radio>
            <el-radio :value="1">售后(不占额度)</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <el-table :data="createForm.items" border size="small">
        <el-table-column label="商品" min-width="170">
          <template #default="{ row }">
            <el-select v-model="row.goodsId" filterable remote :remote-method="searchGoods"
              :loading="goodsLoading" placeholder="搜索商品" style="width: 100%" @change="onGoodsPick(row)">
              <el-option v-for="g in goodsOptions" :key="g.id" :label="g.name" :value="g.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="颜色" width="110">
          <template #default="{ row }">
            <el-select v-model="row.colorId" clearable placeholder="无" style="width: 100%">
              <el-option v-for="a in row.attrs.filter((x) => x.attr_type === 0)" :key="a.id"
                :label="a.value" :value="a.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="规格" width="110">
          <template #default="{ row }">
            <el-select v-model="row.formatId" clearable placeholder="无" style="width: 100%">
              <el-option v-for="a in row.attrs.filter((x) => x.attr_type === 1)" :key="a.id"
                :label="a.value" :value="a.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="领用仓" width="130">
          <template #default="{ row }">
            <el-select v-model="row.warehouseId" style="width: 100%">
              <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="数量" width="120">
          <template #default="{ row }">
            <el-input-number v-model="row.units" :min="0.001" :precision="3" style="width: 100%" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="60">
          <template #default="{ $index }">
            <el-button link type="danger" @click="createForm.items.splice($index, 1)">删</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button style="margin-top: 8px" @click="createForm.items.push(newRow())">加一行</el-button>
      <el-form label-width="80px" style="margin-top: 8px">
        <el-form-item label="备注"><el-input v-model="createForm.comment" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onCreate">登记</el-button>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <el-drawer v-model="detailDrawer" :title="'领料单 ' + (detail && detail.code || '')" size="680px">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small" style="margin-bottom: 12px">
          <el-descriptions-item label="装修单">{{ detail.order_no }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ label('requisitionStatus', detail.status) }}</el-descriptions-item>
          <el-descriptions-item label="总额">{{ yuan(detail.total) }} 元</el-descriptions-item>
          <el-descriptions-item label="领用人">{{ detail.created_by_name }}</el-descriptions-item>
          <el-descriptions-item label="出库时间">{{ detail.outbound_at || '—' }}</el-descriptions-item>
          <el-descriptions-item label="到货时间">{{ detail.arrive_at || '—' }}</el-descriptions-item>
        </el-descriptions>
        <div class="ops">
          <el-button v-if="detail.status === 0" size="small" @click="act(preoutRequisition)">预出</el-button>
          <el-button v-if="[0, 1].includes(detail.status)" size="small" type="primary"
            @click="act(outboundRequisition)">出库</el-button>
          <el-button v-if="detail.status === 2" size="small" type="primary"
            @click="act(arriveRequisition)">到货</el-button>
          <el-button v-if="detail.status === 0" size="small" @click="act(cancelRequisition)">撤销</el-button>
          <el-button v-if="detail.status === 0" size="small" type="danger" @click="act(refuseRequisition)">拒绝</el-button>
        </div>
        <el-table :data="detail.items" border size="small">
          <el-table-column prop="goods_name" label="商品" min-width="120" />
          <el-table-column prop="warehouse_name" label="仓库" width="100" />
          <el-table-column label="申请" width="80" align="right">
            <template #default="{ row }">{{ units(row.num) }}</template>
          </el-table-column>
          <el-table-column label="已出库" width="80" align="right">
            <template #default="{ row }">{{ units(row.outbound_num) }}</template>
          </el-table-column>
          <el-table-column label="已退回" width="80" align="right">
            <template #default="{ row }">{{ units(row.returned_num) }}</template>
          </el-table-column>
          <el-table-column label="退回" width="120" v-if="[2, 6].includes(detail.status)">
            <template #default="{ row }">
              <el-input-number v-model="row.returnUnits" :min="0" :precision="3" size="small"
                style="width: 100%" />
            </template>
          </el-table-column>
        </el-table>
        <div v-if="[2, 6].includes(detail.status)" style="margin-top: 8px">
          <el-button type="primary" size="small" @click="onReturn">生成退回单(AR)</el-button>
        </div>
        <h4 class="sec">联动货单</h4>
        <el-table :data="detail.manifests" border size="small">
          <el-table-column prop="code" label="单号" width="150" />
          <el-table-column label="类型" width="100">
            <template #default="{ row }">{{ manifestLabel(row.type) }}</template>
          </el-table-column>
          <el-table-column prop="created_at" label="时间" width="170" />
        </el-table>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import {
  pageRequisitions, fetchRequisition, createRequisition, preoutRequisition, outboundRequisition,
  arriveRequisition, cancelRequisition, refuseRequisition, returnRequisitionGoods,
  listWarehouses, pageGoods, listGoodsSkuAttrs, pageDecorations
} from '../../api'
import { addons } from '../../store/addons'

const addon = computed(() => addons.list.find((a) => a.id === 'inventory') || {})
const dicts = computed(() => addon.value.dicts || {})
const manifestDict = computed(() => (addon.value.dicts || {}).manifestType || [])

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)
const saving = ref(false)
const filters = reactive({ status: null, type: null })
const warehouses = ref([])
const goodsOptions = ref([])
const goodsLoading = ref(false)
const createDialog = ref(false)
const createForm = reactive({ orderId: '', type: 0, items: [], comment: '' })
const detailDrawer = ref(false)
const detail = ref(null)

const label = (dict, v) => {
  const hit = (dicts.value[dict] || []).find((d) => d.key === String(v))
  return hit ? hit.label : v
}
const manifestLabel = (t) => {
  const hit = manifestDict.value.find((d) => d.key === String(t))
  return hit ? hit.label : t
}
const units = (num) => (Number(num || 0) / 1000).toLocaleString('zh-CN', { maximumFractionDigits: 3 })
const yuan = (li) => (Number(li || 0) / 1000).toLocaleString('zh-CN', { minimumFractionDigits: 3 })

function newRow() {
  return { goodsId: null, colorId: null, formatId: null, warehouseId: null, units: 1, attrs: [] }
}

async function load() {
  loading.value = true
  try {
    const res = await pageRequisitions({ page: page.value, size,
      status: filters.status ?? undefined, type: filters.type ?? undefined })
    rows.value = (res.data && res.data.rows) || []
    total.value = (res.data && res.data.total) || 0
  } finally {
    loading.value = false
  }
}

async function searchGoods(keyword) {
  if (!keyword) return
  goodsLoading.value = true
  try {
    const res = await pageGoods({ keyword, page: 1, size: 20 })
    goodsOptions.value = (res.data && res.data.rows) || []
  } finally {
    goodsLoading.value = false
  }
}

async function onGoodsPick(row) {
  row.colorId = null
  row.formatId = null
  if (!row.goodsId) { row.attrs = []; return }
  const res = await listGoodsSkuAttrs(row.goodsId)
  row.attrs = (res.data || []).filter((a) => a.status === 0)
}

function openCreate() {
  createForm.orderId = ''
  createForm.type = 0
  createForm.comment = ''
  createForm.items = [newRow()]
  createDialog.value = true
}

async function onCreate() {
  const items = createForm.items.map((r) => ({
    goodsId: r.goodsId, colorId: r.colorId || null, formatId: r.formatId || null,
    warehouseId: r.warehouseId, num: Math.round(r.units * 1000)
  }))
  if (!createForm.orderId || items.some((i) => !i.goodsId || !i.warehouseId)) {
    return ElMessage.warning('请补全装修单/商品/仓库')
  }
  saving.value = true
  try {
    await createRequisition({ orderId: Number(createForm.orderId), type: createForm.type,
      items, comment: createForm.comment || null })
    ElMessage.success('已登记')
    createDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '登记失败')
  } finally {
    saving.value = false
  }
}

async function openDetail(id) {
  const res = await fetchRequisition(id)
  detail.value = { ...res.data, items: (res.data.items || []).map((i) => ({ ...i, returnUnits: 0 })) }
  detailDrawer.value = true
}

async function act(fn) {
  try {
    await fn(detail.value.id)
    ElMessage.success('已操作')
    openDetail(detail.value.id)
    load()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function onReturn() {
  const lines = detail.value.items
    .filter((i) => i.returnUnits > 0)
    .map((i) => ({ itemId: i.id, num: Math.round(i.returnUnits * 1000) }))
  if (!lines.length) return ElMessage.warning('请填写退回数量')
  try {
    await returnRequisitionGoods(detail.value.id, { lines })
    ElMessage.success('退回单已生成')
    openDetail(detail.value.id)
    load()
  } catch (e) {
    ElMessage.error(e.message || '退回失败')
  }
}

onMounted(async () => {
  const wh = await listWarehouses()
  warehouses.value = wh.data || []
  load()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; align-items: center; flex-wrap: wrap; }
.tip { color: var(--yz-ink-50); font-size: 12px; }
.mono { font-family: Consolas, monospace; font-size: 12px; }
.ops { display: flex; gap: 8px; margin-bottom: 8px; }
.sec { margin: 14px 0 8px; color: var(--yz-ink); }
</style>
