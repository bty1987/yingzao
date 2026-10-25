<template>
  <div>
    <div class="toolbar">
      <el-select v-model="filters.type" placeholder="类型" style="width: 130px" clearable @change="load">
        <el-option v-for="d in dicts.manifestType" :key="d.key" :label="d.label" :value="Number(d.key)" />
      </el-select>
      <el-select v-model="filters.status" placeholder="状态" style="width: 120px" clearable @change="load">
        <el-option v-for="d in dicts.manifestStatus" :key="d.key" :label="d.label" :value="Number(d.key)" />
      </el-select>
      <el-input v-model="filters.code" placeholder="单号" style="width: 150px" clearable @keyup.enter="load" />
      <el-button type="primary" @click="load">查询</el-button>
      <el-button @click="openManual(1)">进货</el-button>
      <el-button @click="openManual(3)">出货</el-button>
      <el-button @click="reversalDialog = true">冲销(进退/出返)</el-button>
      <el-button @click="transferDialog = true">调拨申请</el-button>
      <el-button @click="adjustDialog = true">盘盈/盘亏</el-button>
      <el-button @click="openPackage">套拆/套合</el-button>
      <span class="tip">单据即库存:建单即时过账;手工建单仅进货/出货,冲销 = 反向类型新单</span>
    </div>

    <el-table :data="rows" border stripe v-loading="loading">
      <el-table-column prop="code" label="单号" width="150">
        <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
      </el-table-column>
      <el-table-column label="类型" width="100">
        <template #default="{ row }">{{ label('manifestType', row.type) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 1 ? 'success' : row.status === 3 ? 'danger' : 'info'">
            {{ label('manifestStatus', row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="source_type" label="源单" width="110">
        <template #default="{ row }">{{ row.source_type || '—' }}</template>
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

    <!-- 建单(进货/出货) -->
    <el-dialog v-model="manualDialog" :title="manualType === 1 ? '进货单' : '出货单'" width="860px">
      <el-form label-width="80px">
        <el-form-item label="装修单">
          <el-input v-model="manualForm.orderId" placeholder="装修单id(可空,纯库存出入)" style="width: 220px" />
        </el-form-item>
      </el-form>
      <el-table :data="manualForm.items" border size="small">
        <el-table-column label="商品" min-width="180">
          <template #default="{ row }">
            <el-select v-model="row.goodsId" filterable remote :remote-method="searchGoods"
              :loading="goodsLoading" placeholder="搜索商品" style="width: 100%" @change="onGoodsPick(row)">
              <el-option v-for="g in goodsOptions" :key="g.id" :label="g.name" :value="g.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="颜色" width="120">
          <template #default="{ row }">
            <el-select v-model="row.colorId" clearable placeholder="无" style="width: 100%">
              <el-option v-for="a in row.attrs.filter((x) => x.attr_type === 0)" :key="a.id"
                :label="a.value" :value="a.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="规格" width="120">
          <template #default="{ row }">
            <el-select v-model="row.formatId" clearable placeholder="无" style="width: 100%">
              <el-option v-for="a in row.attrs.filter((x) => x.attr_type === 1)" :key="a.id"
                :label="a.value" :value="a.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column :label="manualType === 1 ? '入库仓' : '出库仓'" width="140">
          <template #default="{ row }">
            <el-select v-model="row.warehouseId" style="width: 100%">
              <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="数量" width="120">
          <template #default="{ row }">
            <el-input-number v-model="row.units" :min="0.001" :precision="3" :step="1" style="width: 100%" />
          </template>
        </el-table-column>
        <el-table-column label="单价(元)" width="130">
          <template #default="{ row }">
            <el-input-number v-model="row.yuan" :min="0" :precision="3" :step="10" style="width: 100%" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="60">
          <template #default="{ $index }">
            <el-button link type="danger" @click="manualForm.items.splice($index, 1)">删</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button style="margin-top: 8px" @click="addManualRow">加一行</el-button>
      <el-form label-width="80px" style="margin-top: 8px">
        <el-form-item label="备注"><el-input v-model="manualForm.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="manualDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onManual">建单过账</el-button>
      </template>
    </el-dialog>

    <!-- 冲销 -->
    <el-dialog v-model="reversalDialog" title="冲销(进退/出返)" width="640px">
      <el-form label-width="100px">
        <el-form-item label="冲销类型">
          <el-radio-group v-model="reversalForm.type">
            <el-radio :value="2">进退(退进货)</el-radio>
            <el-radio :value="4">出返(退出货)</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="源单id">
          <el-input v-model="reversalForm.sourceManifestId" style="width: 200px" placeholder="已确认的进货/出货单id" />
        </el-form-item>
      </el-form>
      <el-table :data="reversalForm.lines" border size="small">
        <el-table-column label="源明细id" min-width="140">
          <template #default="{ row }"><el-input v-model="row.sourceItemId" /></template>
        </el-table-column>
        <el-table-column label="数量" width="160">
          <template #default="{ row }">
            <el-input-number v-model="row.units" :min="0.001" :precision="3" style="width: 100%" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="60">
          <template #default="{ $index }">
            <el-button link type="danger" @click="reversalForm.lines.splice($index, 1)">删</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button style="margin-top: 8px" @click="reversalForm.lines.push({ sourceItemId: '', units: 1 })">加一行</el-button>
      <el-form label-width="100px" style="margin-top: 8px">
        <el-form-item label="备注"><el-input v-model="reversalForm.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reversalDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onReversal">生成反向单</el-button>
      </template>
    </el-dialog>

    <!-- 调拨 -->
    <el-dialog v-model="transferDialog" title="调拨申请(走内核审批,通过后过账)" width="760px">
      <el-table :data="transferForm.lines" border size="small">
        <el-table-column label="商品" min-width="170">
          <template #default="{ row }">
            <el-select v-model="row.goodsId" filterable remote :remote-method="searchGoods"
              :loading="goodsLoading" placeholder="搜索商品" style="width: 100%" @change="onGoodsPick(row)">
              <el-option v-for="g in goodsOptions" :key="g.id" :label="g.name" :value="g.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="出库仓" width="130">
          <template #default="{ row }">
            <el-select v-model="row.warehouseOut" style="width: 100%">
              <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="入库仓" width="130">
          <template #default="{ row }">
            <el-select v-model="row.warehouseIn" style="width: 100%">
              <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="数量" width="130">
          <template #default="{ row }">
            <el-input-number v-model="row.units" :min="0.001" :precision="3" style="width: 100%" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="60">
          <template #default="{ $index }">
            <el-button link type="danger" @click="transferForm.lines.splice($index, 1)">删</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button style="margin-top: 8px" @click="addTransferRow">加一行</el-button>
      <el-form label-width="100px" style="margin-top: 8px">
        <el-form-item label="审批人">
          <el-select v-model="transferForm.assigneeId" style="width: 220px">
            <el-option v-for="u in users" :key="u.id" :label="u.display_name" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="transferForm.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="transferDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onTransfer">提交审批</el-button>
      </template>
    </el-dialog>

    <!-- 盘盈亏 -->
    <el-dialog v-model="adjustDialog" title="盘盈 / 盘亏(事件闭环)" width="720px">
      <el-form label-width="80px">
        <el-form-item label="类型">
          <el-radio-group v-model="adjustForm.type">
            <el-radio :value="11">盘盈</el-radio>
            <el-radio :value="12">盘亏</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <el-table :data="adjustForm.items" border size="small">
        <el-table-column label="商品" min-width="170">
          <template #default="{ row }">
            <el-select v-model="row.goodsId" filterable remote :remote-method="searchGoods"
              :loading="goodsLoading" placeholder="搜索商品" style="width: 100%" @change="onGoodsPick(row)">
              <el-option v-for="g in goodsOptions" :key="g.id" :label="g.name" :value="g.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="仓库" width="140">
          <template #default="{ row }">
            <el-select v-model="row.warehouseId" style="width: 100%">
              <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="数量" width="140">
          <template #default="{ row }">
            <el-input-number v-model="row.units" :min="0.001" :precision="3" style="width: 100%" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="60">
          <template #default="{ $index }">
            <el-button link type="danger" @click="adjustForm.items.splice($index, 1)">删</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button style="margin-top: 8px" @click="adjustForm.items.push(newRow())">加一行</el-button>
      <el-form label-width="80px" style="margin-top: 8px">
        <el-form-item label="备注"><el-input v-model="adjustForm.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjustDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onAdjust">登记过账</el-button>
      </template>
    </el-dialog>

    <!-- 套拆/套合 -->
    <el-dialog v-model="packageDialog" :title="packageForm.type === 10 ? '套拆(出套餐+入成员)' : '套合(出成员+入套餐)'"
      width="520px">
      <el-form label-width="90px">
        <el-form-item label="动作">
          <el-radio-group v-model="packageForm.type">
            <el-radio :value="10">套拆</el-radio>
            <el-radio :value="9">套合</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="套餐商品" required>
          <el-select v-model="packageForm.packageGoodsId" filterable remote :remote-method="searchPackageGoods"
            :loading="pkgLoading" placeholder="搜索套餐商品" style="width: 100%">
            <el-option v-for="g in pkgOptions" :key="g.id" :label="g.name" :value="g.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="仓库" required>
          <el-select v-model="packageForm.warehouseId" style="width: 100%">
            <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="套餐数量" required>
          <el-input-number v-model="packageForm.units" :min="0.001" :precision="3" :step="1" />
          <span class="tip" style="margin-left: 8px">套;成员按配置量×套餐数自动生成行</span>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="packageForm.remark" /></el-form-item>
      </el-form>
      <div class="tip">成员色规继承套餐所选色规(本页未选色规=无色规行);拆合守恒,库存随单即时增减。</div>
      <template #footer>
        <el-button @click="packageDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onPackage">执行</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailDrawer" :title="'单据 ' + (detail && detail.code || '')" size="640px">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small" style="margin-bottom: 12px">
          <el-descriptions-item label="类型">{{ label('manifestType', detail.type) }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ label('manifestStatus', detail.status) }}</el-descriptions-item>
          <el-descriptions-item label="制单">{{ detail.created_by_name }}</el-descriptions-item>
          <el-descriptions-item label="建单时间">{{ detail.created_at }}</el-descriptions-item>
          <el-descriptions-item label="源单">
            {{ detail.source_type ? detail.source_type + '#' + detail.source_id : '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="备注">{{ detail.remark || '—' }}</el-descriptions-item>
        </el-descriptions>
        <div v-if="detail.status === 0 && (detail.type === 1 || detail.type === 3)" style="margin-bottom: 8px">
          <el-button type="primary" size="small" @click="onConfirm">确认(账面锁定)</el-button>
        </div>
        <el-table :data="detail.items" border size="small">
          <el-table-column prop="goods_name" label="商品" min-width="120" />
          <el-table-column prop="color_name" label="色" width="70" />
          <el-table-column prop="format_name" label="规" width="70" />
          <el-table-column label="入库仓" width="80">
            <template #default="{ row }">{{ whName(row.warehouse_in) }}</template>
          </el-table-column>
          <el-table-column label="出库仓" width="80">
            <template #default="{ row }">{{ whName(row.warehouse_out) }}</template>
          </el-table-column>
          <el-table-column label="数量" width="90" align="right">
            <template #default="{ row }">{{ units(row.num) }}</template>
          </el-table-column>
          <el-table-column label="剩余可冲" width="90" align="right">
            <template #default="{ row }">{{ row.surplus == null ? '—' : units(row.surplus) }}</template>
          </el-table-column>
          <el-table-column v-if="detail.status === 0 && (detail.type === 1 || detail.type === 3)"
            label="操作" width="70">
            <template #default="{ row }">
              <el-button link type="danger" @click="onRemoveItem(row.id)">删行</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import {
  pageManifests, fetchManifest, createManifest, confirmManifest, removeManifestItem,
  createReversal, submitTransfer, createAdjustment,
  packageSplit, packageMerge,
  listWarehouses, pageGoods, listGoodsSkuAttrs, listOrgUsers
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
const filters = reactive({ type: null, status: null, code: '' })
const warehouses = ref([])
const users = ref([])
const goodsOptions = ref([])
const goodsLoading = ref(false)

const manualDialog = ref(false)
const manualType = ref(1)
const manualForm = reactive({ orderId: '', items: [], remark: '' })
const reversalDialog = ref(false)
const reversalForm = reactive({ type: 2, sourceManifestId: '', lines: [], remark: '' })
const transferDialog = ref(false)
const transferForm = reactive({ lines: [], assigneeId: null, remark: '' })
const adjustDialog = ref(false)
const adjustForm = reactive({ type: 11, items: [], remark: '' })
const packageDialog = ref(false)
const packageForm = reactive({ type: 10, packageGoodsId: null, warehouseId: null, units: 1, remark: '' })
const pkgOptions = ref([])
const pkgLoading = ref(false)
const detailDrawer = ref(false)
const detail = ref(null)

const label = (dict, v) => {
  const hit = (dicts.value[dict] || []).find((d) => d.key === String(v))
  return hit ? hit.label : v
}
const units = (num) => (Number(num || 0) / 1000).toLocaleString('zh-CN', { maximumFractionDigits: 3 })
const whName = (id) => (warehouses.value.find((w) => w.id === id) || {}).name || (id ? '#' + id : '—')

function newRow() {
  return { goodsId: null, colorId: null, formatId: null, warehouseId: null, warehouseIn: null, warehouseOut: null, units: 1, yuan: 0, attrs: [] }
}

async function load() {
  loading.value = true
  try {
    const res = await pageManifests({ page: page.value, size,
      type: filters.type ?? undefined, status: filters.status ?? undefined,
      code: filters.code || undefined })
    rows.value = (res.data && res.data.rows) || []
    total.value = (res.data && res.data.total) || 0
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
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

function openManual(type) {
  manualType.value = type
  manualForm.orderId = ''
  manualForm.remark = ''
  manualForm.items = [newRow()]
  manualDialog.value = true
}

function addManualRow() {
  manualForm.items.push(newRow())
}

function addTransferRow() {
  transferForm.lines.push(newRow())
}

async function onManual() {
  const items = manualForm.items.map((r) => ({
    goodsId: r.goodsId, colorId: r.colorId || null, formatId: r.formatId || null,
    num: Math.round(r.units * 1000), price: Math.round(r.yuan * 1000),
    warehouseIn: manualType.value === 1 ? r.warehouseId : null,
    warehouseOut: manualType.value === 3 ? r.warehouseId : null
  }))
  if (items.some((i) => !i.goodsId || !i.num
      || (manualType.value === 1 && !i.warehouseIn)
      || (manualType.value === 3 && !i.warehouseOut))) {
    return ElMessage.warning('请补全商品/数量/仓库')
  }
  saving.value = true
  try {
    await createManifest({ type: manualType.value, items,
      orderId: manualForm.orderId || null, remark: manualForm.remark || null })
    ElMessage.success('已建单过账')
    manualDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '建单失败')
  } finally {
    saving.value = false
  }
}

async function onReversal() {
  const lines = reversalForm.lines
    .filter((l) => l.sourceItemId)
    .map((l) => ({ sourceItemId: Number(l.sourceItemId), num: Math.round(l.units * 1000) }))
  if (!reversalForm.sourceManifestId || !lines.length) return ElMessage.warning('请填源单与冲销行')
  saving.value = true
  try {
    await createReversal({ type: reversalForm.type, sourceManifestId: Number(reversalForm.sourceManifestId),
      lines, remark: reversalForm.remark || null })
    ElMessage.success('已生成反向单')
    reversalDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '冲销失败')
  } finally {
    saving.value = false
  }
}

async function onTransfer() {
  const lines = transferForm.lines.map((r) => ({
    goodsId: r.goodsId, colorId: r.colorId || null, formatId: r.formatId || null,
    num: Math.round(r.units * 1000), warehouseOut: r.warehouseOut, warehouseIn: r.warehouseIn
  }))
  if (lines.some((l) => !l.goodsId || !l.warehouseOut || !l.warehouseIn)) {
    return ElMessage.warning('请补全商品与出入库仓')
  }
  if (!transferForm.assigneeId) return ElMessage.warning('请选择审批人')
  saving.value = true
  try {
    await submitTransfer({ lines, assigneeId: transferForm.assigneeId, remark: transferForm.remark || null })
    ElMessage.success('调拨申请已提交审批')
    transferDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '提交失败')
  } finally {
    saving.value = false
  }
}

async function onAdjust() {
  const items = adjustForm.items.map((r) => ({
    goodsId: r.goodsId, colorId: r.colorId || null, formatId: r.formatId || null,
    warehouseId: r.warehouseId, num: Math.round(r.units * 1000)
  }))
  if (items.some((i) => !i.goodsId || !i.warehouseId)) return ElMessage.warning('请补全商品与仓库')
  saving.value = true
  try {
    await createAdjustment({ type: adjustForm.type, items, remark: adjustForm.remark || null })
    ElMessage.success('已登记过账')
    adjustDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '登记失败')
  } finally {
    saving.value = false
  }
}

function openPackage() {
  packageForm.type = 10
  packageForm.packageGoodsId = null
  packageForm.warehouseId = warehouses.value.length === 1 ? warehouses.value[0].id : null
  packageForm.units = 1
  packageForm.remark = ''
  pkgOptions.value = []
  packageDialog.value = true
}

async function searchPackageGoods(keyword) {
  pkgLoading.value = true
  try {
    const res = await pageGoods({ keyword: keyword || '', type: 1, status: 0, page: 1, size: 20 })
    pkgOptions.value = (res.data && res.data.rows) || []
  } finally {
    pkgLoading.value = false
  }
}

async function onPackage() {
  if (!packageForm.packageGoodsId || !packageForm.warehouseId) {
    return ElMessage.warning('请补全套餐商品与仓库')
  }
  saving.value = true
  try {
    const body = { packageGoodsId: packageForm.packageGoodsId, warehouseId: packageForm.warehouseId,
      num: Math.round(packageForm.units * 1000), colorId: null, formatId: null }
    if (packageForm.type === 10) {
      await packageSplit(body)
      ElMessage.success('套拆完成(出套餐+入成员)')
    } else {
      await packageMerge(body)
      ElMessage.success('套合完成(出成员+入套餐)')
    }
    packageDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '执行失败')
  } finally {
    saving.value = false
  }
}

async function openDetail(id) {
  const res = await fetchManifest(id)
  detail.value = res.data
  detailDrawer.value = true
}

async function onConfirm() {
  try {
    await confirmManifest(detail.value.id)
    ElMessage.success('已确认')
    openDetail(detail.value.id)
    load()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function onRemoveItem(itemId) {
  try {
    await removeManifestItem(detail.value.id, itemId)
    ElMessage.success('已删行并回冲库存')
    openDetail(detail.value.id)
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

onMounted(async () => {
  const [wh, us] = await Promise.all([listWarehouses(), listOrgUsers()])
  warehouses.value = wh.data || []
  users.value = us.data || []
  load()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; align-items: center; flex-wrap: wrap; }
.tip { color: var(--yz-ink-50); font-size: 12px; }
.mono { font-family: Consolas, monospace; font-size: 12px; }
</style>
