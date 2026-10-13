<template>
  <div>
    <div class="toolbar">
      <el-select v-model="filters.status" placeholder="状态" style="width: 130px" clearable @change="load">
        <el-option v-for="d in dicts.purchaseStatus" :key="d.key" :label="d.label" :value="Number(d.key)" />
      </el-select>
      <el-input v-model="filters.code" placeholder="单号" style="width: 150px" clearable @keyup.enter="load" />
      <el-button type="primary" @click="load">查询</el-button>
      <el-button @click="openCreate">采购建单</el-button>
      <span class="tip">分批入库自动判定部分/全部;取消/删项释放余量走事件(销售域回写);结算统一走款项域</span>
    </div>

    <el-table :data="rows" border stripe v-loading="loading">
      <el-table-column prop="code" label="单号" width="150">
        <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
      </el-table-column>
      <el-table-column prop="supplier_name" label="厂商" width="130" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 2 ? 'success' : row.status === 3 ? 'danger' : 'info'">
            {{ label('purchaseStatus', row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
      <el-table-column prop="created_by_name" label="制单" width="90" />
      <el-table-column prop="created_at" label="建单时间" width="170" />
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row.id)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination layout="total, prev, pager, next" :total="total" :page-size="size"
      v-model:current-page="page" @current-change="load" style="margin-top: 12px" />

    <!-- 建单 -->
    <el-dialog v-model="createDialog" title="采购建单" width="860px">
      <el-form label-width="90px">
        <el-form-item label="销售单锚点">
          <el-input v-model="createForm.salesId" placeholder="销售单id(可空)" style="width: 220px" />
        </el-form-item>
        <el-form-item label="厂商" required>
          <el-select v-model="createForm.supplierId" filterable remote :remote-method="searchSuppliers"
            :loading="supplierLoading" placeholder="搜索厂商(须已在厂商管理建档并启用)" style="width: 260px">
            <el-option v-for="c in supplierOptions" :key="c.id" :label="c.name + '(' + (c.no || '') + ')'"
              :value="c.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <el-table :data="createForm.items" border size="small">
        <el-table-column label="商品" min-width="180">
          <template #default="{ row }">
            <el-select v-model="row.goodsId" filterable remote :remote-method="searchGoods"
              :loading="goodsLoading" placeholder="搜索商品" style="width: 100%" @change="onGoodsPick(row)">
              <el-option v-for="g in goodsOptions" :key="g.id" :label="g.name" :value="g.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="数量" width="120">
          <template #default="{ row }">
            <el-input-number v-model="row.units" :min="0.001" :precision="3" style="width: 100%" />
          </template>
        </el-table-column>
        <el-table-column label="进价(元)" width="130">
          <template #default="{ row }">
            <el-input-number v-model="row.yuan" :min="0" :precision="3" style="width: 100%" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="60">
          <template #default="{ $index }">
            <el-button link type="danger" @click="createForm.items.splice($index, 1)">删</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button style="margin-top: 8px" @click="createForm.items.push(newRow())">加一行</el-button>
      <el-form label-width="90px" style="margin-top: 8px">
        <el-form-item label="备注"><el-input v-model="createForm.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onCreate">建单</el-button>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <el-drawer v-model="detailDrawer" :title="'采购单 ' + (detail && detail.code || '')" size="680px">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small" style="margin-bottom: 12px">
          <el-descriptions-item label="厂商">{{ detail.supplier_name || '—' }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ label('purchaseStatus', detail.status) }}</el-descriptions-item>
          <el-descriptions-item label="入库时间">{{ detail.inbound_at || '—' }}</el-descriptions-item>
          <el-descriptions-item label="取消时间">{{ detail.cancelled_at || '—' }}</el-descriptions-item>
        </el-descriptions>
        <div class="ops">
          <el-button v-if="[0, 1].includes(detail.status)" size="small" type="primary"
            @click="inboundDialog = true">分批入库</el-button>
          <el-button v-if="[0, 1].includes(detail.status)" size="small" type="danger"
            @click="act(cancelPurchase)">取消(释放余量)</el-button>
        </div>
        <el-table :data="detail.items" border size="small">
          <el-table-column prop="goods_name" label="商品" min-width="130" />
          <el-table-column label="采购数" width="90" align="right">
            <template #default="{ row }">{{ units(row.num) }}</template>
          </el-table-column>
          <el-table-column label="余量" width="90" align="right">
            <template #default="{ row }">{{ units(row.surplus) }}</template>
          </el-table-column>
          <el-table-column label="进价" width="90" align="right">
            <template #default="{ row }">{{ yuan(row.inprice) }}</template>
          </el-table-column>
          <el-table-column v-if="detail.status === 0" label="操作" width="70">
            <template #default="{ row }">
              <el-button link type="danger" @click="onRemoveItem(row.id)">删项</el-button>
            </template>
          </el-table-column>
        </el-table>
        <h4 class="sec">入库货单</h4>
        <el-table :data="detail.manifests" border size="small">
          <el-table-column prop="code" label="单号" width="150" />
          <el-table-column prop="created_at" label="时间" width="170" />
        </el-table>
      </template>
    </el-drawer>

    <!-- 入库 -->
    <el-dialog v-model="inboundDialog" title="分批入库" width="520px">
      <el-form label-width="80px">
        <el-form-item label="入库仓">
          <el-select v-model="inboundForm.warehouseId" style="width: 200px">
            <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <el-table :data="detail && detail.items" border size="small">
        <el-table-column prop="goods_name" label="商品" min-width="120" />
        <el-table-column label="本次入库" width="150">
          <template #default="{ row }">
            <el-input-number v-model="row.inUnits" :min="0" :precision="3" size="small" style="width: 100%" />
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="inboundDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onInbound">入库过账</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import {
  pagePurchases, fetchPurchase, createPurchase, inboundPurchase, removePurchaseItem,
  cancelPurchase, listWarehouses, pageGoods, listGoodsSkuAttrs, pageSuppliers
} from '../../api'
import { addons } from '../../store/addons'

const addon = computed(() => addons.list.find((a) => a.id === 'inventory') || {})
const dicts = computed(() => addon.value.dicts || {})

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)
const saving = ref(false)
const filters = reactive({ status: null, code: '' })
const warehouses = ref([])
const goodsOptions = ref([])
const goodsLoading = ref(false)

const createDialog = ref(false)
const createForm = reactive({ salesId: '', supplierId: null, items: [], remark: '' })
const supplierOptions = ref([])
const supplierLoading = ref(false)
const detailDrawer = ref(false)
const detail = ref(null)
const inboundDialog = ref(false)
const inboundForm = reactive({ warehouseId: null })

const label = (dict, v) => {
  const hit = (dicts.value[dict] || []).find((d) => d.key === String(v))
  return hit ? hit.label : v
}
const units = (num) => (Number(num || 0) / 1000).toLocaleString('zh-CN', { maximumFractionDigits: 3 })
const yuan = (li) => (Number(li || 0) / 1000).toLocaleString('zh-CN', { minimumFractionDigits: 3 })

function newRow() {
  return { goodsId: null, units: 1, yuan: 0, attrs: [] }
}

async function load() {
  loading.value = true
  try {
    const res = await pagePurchases({ page: page.value, size,
      status: filters.status ?? undefined, code: filters.code || undefined })
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
  if (!row.goodsId) { row.attrs = []; return }
  const res = await listGoodsSkuAttrs(row.goodsId)
  row.attrs = (res.data || []).filter((a) => a.status === 0)
}

function openCreate() {
  createForm.salesId = ''
  createForm.supplierId = null
  createForm.remark = ''
  createForm.items = [newRow()]
  supplierOptions.value = []
  createDialog.value = true
}

async function searchSuppliers(q) {
  supplierLoading.value = true
  try {
    const res = await pageSuppliers({ keyword: q || '', page: 1, size: 20 })
    supplierOptions.value = (res.data && res.data.rows) || []
  } finally {
    supplierLoading.value = false
  }
}

async function onCreate() {
  const items = createForm.items.map((r) => ({
    goodsId: r.goodsId, num: Math.round(r.units * 1000), inprice: Math.round(r.yuan * 1000)
  }))
  if (items.some((i) => !i.goodsId)) return ElMessage.warning('请补全商品')
  saving.value = true
  try {
    if (!createForm.supplierId) return ElMessage.warning('请选择厂商')
    await createPurchase({ salesId: createForm.salesId || null,
      supplierId: createForm.supplierId, items, remark: createForm.remark || null })
    ElMessage.success('已建单')
    createDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '建单失败')
  } finally {
    saving.value = false
  }
}

async function openDetail(id) {
  const res = await fetchPurchase(id)
  detail.value = { ...res.data, items: (res.data.items || []).map((i) => ({ ...i, inUnits: 0 })) }
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

async function onInbound() {
  const lines = detail.value.items
    .filter((i) => i.inUnits > 0)
    .map((i) => ({ itemId: i.id, num: Math.round(i.inUnits * 1000) }))
  if (!inboundForm.warehouseId || !lines.length) return ElMessage.warning('请选仓库并填入库数量')
  saving.value = true
  try {
    await inboundPurchase(detail.value.id, { warehouseId: inboundForm.warehouseId, lines })
    ElMessage.success('入库过账完成')
    inboundDialog.value = false
    openDetail(detail.value.id)
    load()
  } catch (e) {
    ElMessage.error(e.message || '入库失败')
  } finally {
    saving.value = false
  }
}

async function onRemoveItem(itemId) {
  try {
    await removePurchaseItem(detail.value.id, itemId)
    ElMessage.success('已删项并释放余量')
    openDetail(detail.value.id)
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
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
