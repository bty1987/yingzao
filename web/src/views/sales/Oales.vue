<template>
  <div>
    <div class="toolbar">
      <el-select v-model="filters.type" placeholder="类型" style="width: 100px" clearable>
        <el-option v-for="d in dicts.oalesType" :key="d.key" :label="d.label" :value="Number(d.key)" />
      </el-select>
      <el-select v-model="filters.status" placeholder="状态" style="width: 110px" clearable>
        <el-option v-for="d in dicts.oalesStatus" :key="d.key" :label="d.label" :value="Number(d.key)" />
      </el-select>
      <el-select v-model="filters.brandId" placeholder="品牌" style="width: 140px" clearable filterable>
        <el-option v-for="b in brands" :key="b.id" :label="b.name" :value="b.id" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <el-button type="success" @click="openCreate(0)">购货开单</el-button>
    </div>

    <el-table :data="rows" border stripe v-loading="loading" @row-click="openDetail">
      <el-table-column prop="no" label="外销单号" width="140" />
      <el-table-column prop="order_no" label="装修单" width="140" />
      <el-table-column prop="customer_name" label="客户" min-width="100" show-overflow-tooltip />
      <el-table-column prop="brand_name" label="品牌" width="110" />
      <el-table-column prop="supplier_name" label="厂商" width="110" show-overflow-tooltip />
      <el-table-column label="类型" width="70">
        <template #default="{ row }">
          <el-tag :type="row.type === 1 ? 'danger' : 'primary'" effect="plain">{{ label('oalesType', row.type) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="货款(元)" width="100" align="right">
        <template #default="{ row }">{{ yuan(row.total) }}</template>
      </el-table-column>
      <el-table-column label="利润(元)" width="100" align="right">
        <template #default="{ row }"><span :class="{ 'profit-neg': row.type === 1 }">{{ yuan(row.type === 1 ? -row.profit : row.profit) }}</span></template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" effect="plain">{{ label('oalesStatus', row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="created_at" label="登记时间" width="160" />
      <el-table-column label="操作" width="210">
        <template #default="{ row }">
          <el-button v-if="row.status === 0" type="primary" link @click.stop="onSubmit(row)">提交</el-button>
          <el-button v-if="row.status === 1" type="success" link @click.stop="onConfirm(row)">确认</el-button>
          <el-button v-if="row.status === 1" type="warning" link @click.stop="openReject(row)">驳回</el-button>
          <el-button v-if="row.status === 3 || (row.status === 2 && row.type === 1)" type="success" link
            @click.stop="onFinish(row)">完结</el-button>
          <el-button v-if="row.status === 2 && row.type === 0" type="warning" link
            @click.stop="openCreate(1, row)">退货</el-button>
          <el-button v-if="[0, 1, 2].includes(row.status)" type="danger" link @click.stop="openAbate(row)">废弃</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination layout="prev, pager, next, total" :total="total" :page-size="filters.size"
      :current-page="filters.page" @current-change="(p) => { filters.page = p; load() }" style="margin-top: 12px" />

    <!-- 开单(购货/退货共用;退货跳态出生) -->
    <el-dialog v-model="createDialog" :title="createForm.type === 1 ? '退货单(自原单复制,跳态出生)' : '外销开单(挂装修单)'"
      width="780px">
      <el-form label-width="80px">
        <el-form-item label="装修单">
          <el-select v-model="createForm.orderId" filterable remote :remote-method="searchOrders" :loading="searching"
            :disabled="createForm.type === 1" placeholder="输入装修单号搜索" style="width: 100%">
            <el-option v-for="o in orderOptions" :key="o.id" :value="o.id"
              :label="`${o.order_no} · ${o.customer_name}`" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="createForm.type === 0" label="品牌">
          <el-select v-model="createForm.brandId" filterable placeholder="选择品牌(默认厂商自动带出)" style="width: 100%">
            <el-option v-for="b in brands" :key="b.id" :value="b.id" :label="b.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="利润(元)">
          <el-input-number v-model="createForm.profitYuan" :min="0" :precision="2" :step="100" style="width: 200px" />
          <span class="tip" style="margin-left: 8px">0 ≤ 利润 ≤ 货款合计;退货单利润负计进结算</span>
        </el-form-item>
      </el-form>
      <div class="section">明细(货款 = Σ单价×数量,服务端算)</div>
      <div v-for="(it, i) in createForm.items" :key="i" class="item-line">
        <el-input v-model="it.name" placeholder="品名" style="width: 180px" />
        <el-input v-model="it.format" placeholder="规格" style="width: 100px" />
        <el-input v-model="it.color" placeholder="颜色" style="width: 90px" />
        <el-input v-model="it.model" placeholder="型号" style="width: 100px" />
        <el-input-number v-model="it.priceYuan" :min="0.01" :precision="3" placeholder="单价" style="width: 130px" />
        <el-input-number v-model="it.num" :min="0.001" :precision="3" placeholder="数量" style="width: 130px" />
        <el-button link type="danger" @click="createForm.items.splice(i, 1)">删</el-button>
      </div>
      <el-button size="small" @click="createForm.items.push({ name: '', format: '', color: '', model: '', priceYuan: null, num: 1 })">加明细</el-button>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onCreate">保存</el-button>
      </template>
    </el-dialog>

    <!-- 草稿编辑 -->
    <el-dialog v-model="editDialog" title="编辑草稿" width="780px">
      <el-form label-width="80px">
        <el-form-item label="利润(元)">
          <el-input-number v-model="editProfitYuan" :min="0" :precision="2" :step="100" style="width: 200px" />
        </el-form-item>
      </el-form>
      <div class="section">明细(全量替换重算)</div>
      <div v-for="(it, i) in editItems" :key="i" class="item-line">
        <el-input v-model="it.name" placeholder="品名" style="width: 180px" />
        <el-input v-model="it.format" placeholder="规格" style="width: 100px" />
        <el-input v-model="it.color" placeholder="颜色" style="width: 90px" />
        <el-input v-model="it.model" placeholder="型号" style="width: 100px" />
        <el-input-number v-model="it.priceYuan" :min="0.01" :precision="3" style="width: 130px" />
        <el-input-number v-model="it.num" :min="0.001" :precision="3" style="width: 130px" />
        <el-button link type="danger" @click="editItems.splice(i, 1)">删</el-button>
      </div>
      <el-button size="small" @click="editItems.push({ name: '', format: '', color: '', model: '', priceYuan: null, num: 1 })">加明细</el-button>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onUpdate">保存</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailDrawer" size="620px" :title="detail ? detail.no : '外销单详情'">
      <template v-if="detail">
        <div class="kv">装修单:{{ detail.order_no }} · {{ detail.customer_name }} · {{ detail.house_label }}</div>
        <div class="kv">品牌/厂商:{{ detail.brand_name }} / {{ detail.supplier_name }}</div>
        <div class="kv">
          状态:<el-tag :type="statusTag(detail.status)" effect="plain" size="small">{{ label('oalesStatus', detail.status) }}</el-tag>
          <el-tag :type="detail.type === 1 ? 'danger' : 'primary'" effect="plain" size="small" style="margin-left: 6px">
            {{ label('oalesType', detail.type) }}</el-tag>
          <span v-if="detail.foid" class="tip" style="margin-left: 6px">退货自原单 #{{ detail.foid }}</span>
        </div>
        <div class="kv">货款 ¥{{ yuan(detail.total) }} / 利润
          <span :class="{ 'profit-neg': detail.type === 1 }">¥{{ yuan(detail.type === 1 ? -detail.profit : detail.profit) }}</span>
        </div>
        <div v-if="detail.abate_reason" class="kv">废弃原因:{{ detail.abate_reason }}</div>

        <div class="section">明细</div>
        <el-table :data="detail.items" border size="small">
          <el-table-column prop="name" label="品名" min-width="120" />
          <el-table-column prop="format" label="规格" width="80" />
          <el-table-column prop="color" label="颜色" width="70" />
          <el-table-column prop="model" label="型号" width="80" />
          <el-table-column label="单价" width="90" align="right">
            <template #default="{ row }">{{ yuan(row.price) }}</template>
          </el-table-column>
          <el-table-column prop="num" label="数量" width="80" align="right" />
          <el-table-column label="小计" width="100" align="right">
            <template #default="{ row }">{{ yuan(row.total) }}</template>
          </el-table-column>
        </el-table>

        <div class="section">款项单</div>
        <div v-for="b in detail.bills" :key="b.billId" class="kv">
          {{ b.typeName }} ¥{{ yuan(b.amount) }}
          <el-tag size="small" effect="plain" style="margin-left: 6px">{{ billStatus(b.status) }}</el-tag>
        </div>
        <div v-if="!(detail.bills || []).length" class="tip">未生成(确认后建款)</div>

        <div class="section">操作留痕</div>
        <div v-for="(t, i) in detail.tracks" :key="i" class="kv track">
          <span class="track-time">{{ t.created_at }}</span>
          {{ trackLabel(t.action) }}
          <span v-if="t.detail"> | {{ t.detail }}</span>
          <span v-if="t.operator_name"> | {{ t.operator_name }}</span>
        </div>

        <div v-if="detail.status === 0" style="margin-top: 12px">
          <el-button type="primary" @click="openEdit(detail)">编辑草稿</el-button>
          <el-button type="danger" @click="onDeleteDraft(detail)">删除草稿</el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { addons } from '../../store/addons'
import {
  pageOales, fetchOales, createOales, updateOales, deleteOales, submitOales, cancelOales,
  confirmOales, rejectOales, finishOales, abateOales, pageDecorations, listBrands
} from '../../api'

const dicts = reactive({ oalesStatus: [], oalesType: [] })
const filters = reactive({ type: null, status: null, brandId: null, page: 1, size: 20 })
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const brands = ref([])
const orderOptions = ref([])
const searching = ref(false)

const createDialog = ref(false)
const createForm = ref({ type: 0, orderId: null, brandId: null, profitYuan: 0, items: [] })
const editDialog = ref(false)
const editId = ref(null)
const editProfitYuan = ref(0)
const editItems = ref([])
const detailDrawer = ref(false)
const detail = ref(null)

const yuan = (li) => (Number(li || 0) / 1000).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 3 })
const label = (dict, v) => {
  const hit = (dicts[dict] || []).find((d) => d.key === String(v))
  return hit ? hit.label : v
}
const statusTag = (s) => ({ 0: 'info', 1: 'warning', 2: '', 3: 'warning', 4: 'success', 8: 'danger' }[s] ?? 'info')
const trackLabel = (a) => ({ create: '登记', refund: '退货出生', update: '改单', delete: '删除草稿', submit: '提交', cancel: '撤回', confirm: '确认建款', reject: '驳回', collect: '收款', finish: '完结', abate: '废弃' }[a] || a)
const billStatus = (s) => ({ 0: '待收', 1: '已收', 2: '已确认', 3: '已退款', 4: '已作废' }[s] ?? s)

async function load() {
  loading.value = true
  try {
    const res = await pageOales({ ...filters })
    rows.value = (res.data && res.data.rows) || []
    total.value = (res.data && res.data.total) || 0
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

async function searchOrders(keyword) {
  searching.value = true
  try {
    const res = await pageDecorations({ orderNo: keyword || '', page: 1, size: 20 })
    orderOptions.value = (res.data && res.data.rows) || []
  } finally {
    searching.value = false
  }
}

function openCreate(type, source) {
  createForm.value = {
    type, orderId: source ? source.order_id : null, brandId: source ? source.brand_id : null,
    profitYuan: 0, foid: source ? source.id : null,
    items: [{ name: '', format: '', color: '', model: '', priceYuan: null, num: 1 }]
  }
  createDialog.value = true
}

async function onCreate() {
  const f = createForm.value
  const items = f.items.filter((it) => it.name && it.priceYuan && it.num)
    .map((it) => ({ name: it.name, format: it.format || null, color: it.color || null, model: it.model || null,
      price: Math.round(Number(it.priceYuan) * 1000), num: Math.round(Number(it.num) * 1000) }))
  if (!items.length) { ElMessage.warning('至少一条完整明细'); return }
  saving.value = true
  try {
    await createOales({
      orderId: f.orderId, brandId: f.type === 0 ? f.brandId : null, type: f.type,
      foid: f.type === 1 ? f.foid : null, profit: Math.round(Number(f.profitYuan) * 1000), items
    })
    ElMessage.success('已保存')
    createDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

function openEdit(row) {
  editId.value = row.id
  editProfitYuan.value = Number(row.profit || 0) / 1000
  editItems.value = (row.items || []).length ? row.items.map((it) => ({
    name: it.name, format: it.format || '', color: it.color || '', model: it.model || '',
    priceYuan: Number(it.price) / 1000, num: Number(it.num) / 1000
  })) : [{ name: '', format: '', color: '', model: '', priceYuan: null, num: 1 }]
  editDialog.value = true
}

async function onUpdate() {
  const items = editItems.value.filter((it) => it.name && it.priceYuan && it.num)
    .map((it) => ({ name: it.name, format: it.format || null, color: it.color || null, model: it.model || null,
      price: Math.round(Number(it.priceYuan) * 1000), num: Math.round(Number(it.num) * 1000) }))
  if (!items.length) { ElMessage.warning('至少一条完整明细'); return }
  saving.value = true
  try {
    await updateOales(editId.value, { profit: Math.round(Number(editProfitYuan) * 1000), items })
    ElMessage.success('已保存')
    editDialog.value = false
    if (detailDrawer.value) openDetail({ id: editId.value })
    load()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function onDeleteDraft(row) {
  try {
    await ElMessageBox.confirm('物理删除该草稿(含明细与留痕)?', '删除草稿', { type: 'warning' })
  } catch { return }
  try {
    await deleteOales(row.id)
    ElMessage.success('已删除')
    detailDrawer.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

async function openDetail(row) {
  try {
    const res = await fetchOales(row.id)
    detail.value = res.data
    detailDrawer.value = true
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  }
}

async function onSubmit(row) {
  try { await submitOales(row.id); ElMessage.success('已提交待确认'); load() }
  catch (e) { ElMessage.error(e.message || '提交失败') }
}

async function onConfirm(row) {
  try { await confirmOales(row.id); ElMessage.success('已确认并生成款项单'); load() }
  catch (e) { ElMessage.error(e.message || '确认失败') }
}

function openReject(row) {
  ElMessageBox.prompt('驳回原因(必填,留痕)', '驳回回草稿', { inputPattern: /\S+/, inputErrorMessage: '原因必填' })
    .then(async ({ value }) => {
      await rejectOales(row.id, { reason: value })
      ElMessage.success('已驳回回草稿')
      load()
    }).catch(() => {})
}

async function onFinish(row) {
  try { await finishOales(row.id); ElMessage.success('已完结'); load() }
  catch (e) { ElMessage.error(e.message || '完结失败') }
}

function openAbate(row) {
  ElMessageBox.prompt('废弃原因(必填;已确认单将撤回结算与未收款项)', '废弃', { inputPattern: /\S+/, inputErrorMessage: '原因必填' })
    .then(async ({ value }) => {
      await abateOales(row.id, { reason: value })
      ElMessage.success('已废弃')
      load()
    }).catch(() => {})
}

onMounted(() => {
  const sales = addons.list.find((a) => a.id === 'sales')
  const d = (sales && sales.dicts) || {}
  for (const k of Object.keys(dicts)) dicts[k] = d[k] || []
  listBrands({ page: 1, size: 100 }).then((res) => {
    brands.value = ((res.data && res.data.rows) || res.data || [])
  }).catch(() => {})
  load()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; }
.tip { color: var(--yz-ink-50); font-size: 12px; }
.item-line { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; }
.section { margin: 16px 0 8px; font-weight: 600; }
.kv { margin-bottom: 6px; font-size: 13px; }
.track { border-bottom: 1px dashed var(--yz-line, #eee); padding-bottom: 4px; }
.track-time { color: var(--yz-ink-50); font-size: 12px; margin-right: 8px; }
.profit-neg { color: var(--el-color-danger); }
</style>
