<template>
  <div>
    <div class="toolbar">
      <el-select v-model="filters.status" placeholder="状态" style="width: 120px" clearable @change="load">
        <el-option v-for="d in dicts.outsourcingStatus" :key="d.key" :label="d.label" :value="Number(d.key)" />
      </el-select>
      <el-input v-model="filters.supplierName" placeholder="厂商" style="width: 150px" clearable @keyup.enter="load" />
      <el-button type="primary" @click="load">查询</el-button>
      <el-button @click="openCreate">外辅登记</el-button>
      <span class="tip">工程验收后锁定不可登记;金额单据内自闭环(线下钱货);作废仅到货前</span>
    </div>

    <el-table :data="rows" border stripe v-loading="loading">
      <el-table-column prop="code" label="单号" width="150">
        <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
      </el-table-column>
      <el-table-column prop="order_no" label="装修单" width="140" />
      <el-table-column prop="supplier_name" label="厂商" width="120" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 6 ? 'danger' : row.status === 5 ? 'success' : 'info'">
            {{ label('outsourcingStatus', row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="商品总额" width="110" align="right">
        <template #default="{ row }">{{ yuan(row.total) }}</template>
      </el-table-column>
      <el-table-column label="费用" width="100" align="right">
        <template #default="{ row }">{{ yuan(row.charge) }}</template>
      </el-table-column>
      <el-table-column label="结算额" width="100" align="right">
        <template #default="{ row }">{{ yuan(row.vbalance) }}</template>
      </el-table-column>
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
    <el-dialog v-model="createDialog" title="外辅登记" width="860px">
      <el-form label-width="110px">
        <el-form-item label="装修单">
          <el-input v-model="createForm.orderId" placeholder="装修单id" style="width: 220px" />
        </el-form-item>
        <el-form-item label="物流属性">
          <el-checkbox v-model="createForm.isEnter" label="可进园区" />
          <el-checkbox v-model="createForm.isLift" label="有电梯" />
          <el-checkbox v-model="createForm.isExceed" label="超阶(浮动额计价)" />
          <el-checkbox v-model="createForm.isAttic" label="阁楼" />
          <el-select v-model="createForm.loadFloor" style="width: 110px">
            <el-option v-for="d in dicts.loadFloor" :key="d.key" :label="d.label" :value="Number(d.key)" />
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
        <el-table-column label="原结算价(元)" width="140">
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
      <el-form label-width="110px" style="margin-top: 8px">
        <el-form-item label="备注"><el-input v-model="createForm.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onCreate">登记</el-button>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <el-drawer v-model="detailDrawer" :title="'外辅单 ' + (detail && detail.code || '')" size="720px">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small" style="margin-bottom: 12px">
          <el-descriptions-item label="装修单">{{ detail.order_no }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ label('outsourcingStatus', detail.status) }}</el-descriptions-item>
          <el-descriptions-item label="厂商">{{ detail.supplier_name || '未分配' }}</el-descriptions-item>
          <el-descriptions-item label="电话">{{ detail.supplier_phone || '—' }}</el-descriptions-item>
          <el-descriptions-item label="商品总额">{{ yuan(detail.total) }} 元</el-descriptions-item>
          <el-descriptions-item label="费用">{{ yuan(detail.charge) }} 元</el-descriptions-item>
          <el-descriptions-item label="结算额">{{ yuan(detail.vbalance) }} 元</el-descriptions-item>
          <el-descriptions-item label="应收费用">{{ yuan(detail.rcharge) }} 元</el-descriptions-item>
        </el-descriptions>

        <div class="ops">
          <el-button v-if="[0, 1].includes(detail.status)" size="small" type="primary"
            @click="assignDialog = true">分配厂商</el-button>
          <el-button v-if="detail.status === 1" size="small" type="primary"
            @click="qtyDialog = { mode: 'deliver' }">送货</el-button>
          <el-button v-if="detail.status === 2" size="small" type="primary"
            @click="qtyDialog = { mode: 'receive' }">到货</el-button>
          <el-button v-if="detail.status < 4 && detail.status !== 6" size="small"
            @click="chargeDialog = true">收费明细</el-button>
          <el-button v-if="detail.status === 3" size="small" type="primary"
            @click="act(balanceOutsourcing)">结算</el-button>
          <el-button v-if="detail.status === 4" size="small" @click="act(rejectOutsourcingBalance)">反结算</el-button>
          <el-button v-if="detail.status === 4" size="small" type="primary" @click="act(finishOutsourcing)">完成</el-button>
          <el-button v-if="[0, 1, 2].includes(detail.status)" size="small" type="danger"
            @click="cancelDialog = true">作废</el-button>
        </div>

        <el-table :data="detail.items" border size="small">
          <el-table-column prop="goods_name" label="商品" min-width="130" />
          <el-table-column label="申请" width="80" align="right">
            <template #default="{ row }">{{ units(row.num) }}</template>
          </el-table-column>
          <el-table-column label="送货" width="80" align="right">
            <template #default="{ row }">{{ units(row.sennum) }}</template>
          </el-table-column>
          <el-table-column label="到货" width="80" align="right">
            <template #default="{ row }">{{ units(row.recnum) }}</template>
          </el-table-column>
          <el-table-column label="原结算价" width="90" align="right">
            <template #default="{ row }">{{ yuan(row.rbalance) }}</template>
          </el-table-column>
          <el-table-column label="费用小计" width="90" align="right">
            <template #default="{ row }">{{ yuan(row.charge) }}</template>
          </el-table-column>
        </el-table>

        <h4 class="sec">收费明细</h4>
        <el-table :data="detail.charges" border size="small">
          <el-table-column prop="name" label="费用名" width="110" />
          <el-table-column label="单价" width="90" align="right">
            <template #default="{ row }">{{ yuan(row.price) }}</template>
          </el-table-column>
          <el-table-column label="浮动" width="90" align="right">
            <template #default="{ row }">{{ yuan(row.surplus) }}</template>
          </el-table-column>
          <el-table-column prop="num" label="数量" width="70" align="right" />
          <el-table-column label="小计" width="100" align="right">
            <template #default="{ row }">{{ yuan(row.charge) }}</template>
          </el-table-column>
        </el-table>

        <h4 class="sec">轨迹</h4>
        <el-table :data="detail.tracks" border size="small">
          <el-table-column label="动作" width="100">
            <template #default="{ row }">
              {{ ((dicts.outsourcingTrackAction || []).find((d) => d.key === row.action) || {}).label || row.action }}
            </template>
          </el-table-column>
          <el-table-column prop="detail" label="详情" min-width="120" />
          <el-table-column prop="operator_name" label="经办" width="90" />
          <el-table-column prop="created_at" label="时间" width="170" />
        </el-table>
      </template>
    </el-drawer>

    <!-- 分配厂商 -->
    <el-dialog v-model="assignDialog" title="分配厂商" width="420px">
      <el-form label-width="80px">
        <el-form-item label="厂商名称"><el-input v-model="assignForm.supplierName" /></el-form-item>
        <el-form-item label="电话"><el-input v-model="assignForm.supplierPhone" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="assignDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onAssign">分配</el-button>
      </template>
    </el-dialog>

    <!-- 送货/到货 -->
    <el-dialog v-model="qtyDialog" :title="qtyDialog && qtyDialog.mode === 'deliver' ? '送货登记' : '到货登记'"
      width="460px">
      <el-table :data="detail && detail.items" border size="small">
        <el-table-column prop="goods_name" label="商品" min-width="120" />
        <el-table-column :label="qtyDialog && qtyDialog.mode === 'deliver' ? '本次送货' : '本次到货'" width="150">
          <template #default="{ row }">
            <el-input-number v-model="row.opUnits" :min="0" :precision="3" size="small" style="width: 100%" />
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="qtyDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onQty">提交</el-button>
      </template>
    </el-dialog>

    <!-- 收费明细 -->
    <el-dialog v-model="chargeDialog" title="收费明细(全量替换,服务端公式重算)" width="640px">
      <el-table :data="chargeForm" border size="small">
        <el-table-column label="费用名" min-width="110">
          <template #default="{ row }">
            <el-select v-model="row.name" style="width: 100%">
              <el-option v-for="d in dicts.chargeType" :key="d.key" :label="d.label" :value="d.label" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="单价(元)" width="130">
          <template #default="{ row }">
            <el-input-number v-model="row.yuan" :min="0" :precision="3" size="small" style="width: 100%" />
          </template>
        </el-table-column>
        <el-table-column label="浮动(元)" width="130">
          <template #default="{ row }">
            <el-input-number v-model="row.surplusYuan" :min="0" :precision="3" size="small" style="width: 100%" />
          </template>
        </el-table-column>
        <el-table-column label="数量" width="110">
          <template #default="{ row }">
            <el-input-number v-model="row.num" :min="0" size="small" style="width: 100%" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="60">
          <template #default="{ $index }">
            <el-button link type="danger" @click="chargeForm.splice($index, 1)">删</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button style="margin-top: 8px"
        @click="chargeForm.push({ itemId: null, name: '', yuan: 0, surplusYuan: 0, num: 1 })">加一行</el-button>
      <template #footer>
        <el-button @click="chargeDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onCharges">保存</el-button>
      </template>
    </el-dialog>

    <!-- 作废 -->
    <el-dialog v-model="cancelDialog" title="作废(仅到货前,单据保留)" width="420px">
      <el-input v-model="cancelRemark" placeholder="作废原因" />
      <template #footer>
        <el-button @click="cancelDialog = false">取消</el-button>
        <el-button type="danger" :loading="saving" @click="onCancel">作废</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import {
  pageOutsourcings, fetchOutsourcing, createOutsourcing, assignOutsourcing, deliverOutsourcing,
  receiveOutsourcing, saveOutsourcingCharges, balanceOutsourcing, rejectOutsourcingBalance,
  finishOutsourcing, cancelOutsourcing, pageGoods, listGoodsSkuAttrs, pageDecorations
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
const filters = reactive({ status: null, supplierName: '' })
const goodsOptions = ref([])
const goodsLoading = ref(false)

const createDialog = ref(false)
const createForm = reactive({ orderId: '', items: [], remark: '', isEnter: false, isLift: false,
  isExceed: false, isAttic: false, loadFloor: 0 })
const detailDrawer = ref(false)
const detail = ref(null)
const assignDialog = ref(false)
const assignForm = reactive({ supplierName: '', supplierPhone: '' })
const qtyDialog = ref(false)
const chargeDialog = ref(false)
const chargeForm = ref([])
const cancelDialog = ref(false)
const cancelRemark = ref('')

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
    const res = await pageOutsourcings({ page: page.value, size,
      status: filters.status ?? undefined, supplierName: filters.supplierName || undefined })
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
  createForm.orderId = ''
  createForm.items = [newRow()]
  createForm.remark = ''
  createDialog.value = true
}

async function onCreate() {
  const items = createForm.items.map((r) => ({
    goodsId: r.goodsId, num: Math.round(r.units * 1000), rbalance: Math.round(r.yuan * 1000)
  }))
  if (!createForm.orderId || items.some((i) => !i.goodsId)) return ElMessage.warning('请补全装修单与商品')
  saving.value = true
  try {
    await createOutsourcing({ orderId: Number(createForm.orderId), items,
      isEnter: createForm.isEnter ? 1 : 0, isLift: createForm.isLift ? 1 : 0,
      isExceed: createForm.isExceed ? 1 : 0, isAttic: createForm.isAttic ? 1 : 0,
      loadFloor: createForm.loadFloor, remark: createForm.remark || null })
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
  const res = await fetchOutsourcing(id)
  detail.value = { ...res.data, items: (res.data.items || []).map((i) => ({ ...i, opUnits: 0 })) }
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

async function onAssign() {
  if (!assignForm.supplierName) return ElMessage.warning('请填厂商名称')
  saving.value = true
  try {
    await assignOutsourcing(detail.value.id, { ...assignForm })
    ElMessage.success('已分配')
    assignDialog.value = false
    openDetail(detail.value.id)
    load()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  } finally {
    saving.value = false
  }
}

async function onQty() {
  const lines = detail.value.items
    .filter((i) => i.opUnits > 0)
    .map((i) => ({ itemId: i.id, num: Math.round(i.opUnits * 1000) }))
  if (!lines.length) return ElMessage.warning('请填写数量')
  saving.value = true
  try {
    if (qtyDialog.value.mode === 'deliver') await deliverOutsourcing(detail.value.id, { lines })
    else await receiveOutsourcing(detail.value.id, { lines })
    ElMessage.success('已登记')
    qtyDialog.value = false
    openDetail(detail.value.id)
    load()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  } finally {
    saving.value = false
  }
}

async function onCharges() {
  const charges = chargeForm.value
    .filter((c) => c.name && c.num > 0)
    .map((c) => ({ itemId: c.itemId, name: c.name, price: Math.round(c.yuan * 1000),
      surplus: Math.round(c.surplusYuan * 1000), num: c.num }))
  saving.value = true
  try {
    await saveOutsourcingCharges(detail.value.id, { charges })
    ElMessage.success('收费明细已保存')
    chargeDialog.value = false
    openDetail(detail.value.id)
    load()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function onCancel() {
  saving.value = true
  try {
    await cancelOutsourcing(detail.value.id, { remark: cancelRemark.value || null })
    ElMessage.success('已作废')
    cancelDialog.value = false
    openDetail(detail.value.id)
    load()
  } catch (e) {
    ElMessage.error(e.message || '作废失败')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; align-items: center; flex-wrap: wrap; }
.tip { color: var(--yz-ink-50); font-size: 12px; }
.mono { font-family: Consolas, monospace; font-size: 12px; }
.ops { display: flex; gap: 8px; margin-bottom: 8px; flex-wrap: wrap; }
.sec { margin: 14px 0 8px; color: var(--yz-ink); }
</style>
