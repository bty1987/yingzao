<template>
  <div>
    <div class="toolbar">
      <el-input v-model="filters.orderNo" placeholder="装修单号" style="width: 150px" clearable @keyup.enter="load" />
      <el-select v-model="filters.status" placeholder="定稿线" style="width: 110px" clearable>
        <el-option v-for="d in dicts.quotationStatus" :key="d.key" :label="d.label" :value="Number(d.key)" />
      </el-select>
      <el-select v-model="filters.attstatus" placeholder="审核线" style="width: 110px" clearable>
        <el-option v-for="d in dicts.attStatus" :key="d.key" :label="d.label" :value="Number(d.key)" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <el-button @click="openCreate">新建报价</el-button>
      <el-button @click="openVersions">报价版本</el-button>
    </div>

    <el-table :data="rows" border stripe v-loading="loading" @row-click="openDetail">
      <el-table-column prop="name" label="报价名" min-width="130" show-overflow-tooltip />
      <el-table-column prop="order_no" label="装修单号" width="140" />
      <el-table-column prop="customer_name" label="客户" width="90" />
      <el-table-column prop="house_label" label="房屋" min-width="180" show-overflow-tooltip />
      <el-table-column label="定稿线" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" effect="plain">
            {{ dictLabel('quotationStatus', row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="审核线" width="90">
        <template #default="{ row }">
          <el-tag :type="attTag(row.attstatus)" effect="plain">{{ dictLabel('attStatus', row.attstatus) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="默认" width="60" align="center">
        <template #default="{ row }"><span v-if="row.is_default === 1" class="default-mark">默认</span></template>
      </el-table-column>
      <el-table-column label="折后" width="110" align="right">
        <template #default="{ row }">¥{{ yuan(row.discount_price) }}</template>
      </el-table-column>
      <el-table-column label="总价" width="110" align="right">
        <template #default="{ row }">¥{{ yuan(row.total_price) }}</template>
      </el-table-column>
      <el-table-column prop="creator_name" label="创建人" width="90" />
      <el-table-column prop="created_at" label="创建时间" width="150" />
    </el-table>
    <el-pagination layout="prev, pager, next, total" :total="total" :page-size="filters.size"
      :current-page="filters.page" @current-change="(p) => { filters.page = p; load() }" style="margin-top: 12px" />

    <!-- 新建报价 -->
    <el-dialog v-model="createDialog" title="新建报价" width="560px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="装修单" required>
          <el-select v-model="form.orderId" filterable remote :remote-method="searchOrders"
            :loading="orderLoading" placeholder="输入装修单号搜索" style="width: 100%">
            <el-option v-for="o in orderOptions" :key="o.id" :value="o.id"
              :label="o.order_no + ' ' + o.customer_name + ' ' + (o.house_label || '')" />
          </el-select>
        </el-form-item>
        <el-form-item label="报价名" required>
          <el-input v-model="form.name" placeholder="如 全包方案A" />
        </el-form-item>
        <el-form-item label="报价版本">
          <el-select v-model="form.versionId" clearable placeholder="选择版本带出费率(可空)" style="width: 100%">
            <el-option v-for="v in versions" :key="v.id" :value="v.id"
              :label="v.name + '(管理' + permille(v.manage_rate) + '‰ 搬运' + permille(v.carry_rate) + '‰ 税' + permille(v.tax_rate) + '‰)'" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" />
        </el-form-item>
        <el-form-item label="上限提示">
          <span class="tip">同一装修单报价数量有上限(默认 3 份);首份报价自动设为默认</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 版本管理 -->
    <el-dialog v-model="versionDialog" title="报价版本(费率默认值配置域)" width="760px">
      <div class="toolbar">
        <el-button type="primary" size="small" @click="openVersionForm()">新建版本</el-button>
        <el-checkbox v-model="versionIncludeDisabled" @change="loadVersions">显示停用</el-checkbox>
      </div>
      <el-table :data="versionRows" border size="small">
        <el-table-column prop="name" label="版本名" min-width="120" />
        <el-table-column label="适用类型" width="90">
          <template #default="{ row }">{{ busitypeLabel(row.busitype) }}</template>
        </el-table-column>
        <el-table-column label="管理费" width="110" align="right">
          <template #default="{ row }">{{ permille(row.manage_rate) }}‰ × {{ basis(row.manage_discount) }}</template>
        </el-table-column>
        <el-table-column label="搬运费" width="110" align="right">
          <template #default="{ row }">{{ permille(row.carry_rate) }}‰ × {{ basis(row.carry_discount) }}</template>
        </el-table-column>
        <el-table-column label="税" width="110" align="right">
          <template #default="{ row }">{{ permille(row.tax_rate) }}‰ × {{ basis(row.tax_discount) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small" effect="plain">
              {{ row.status === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button link size="small" @click="openVersionForm(row)">编辑</el-button>
            <el-button link size="small" @click="onVersionStatus(row)">{{ row.status === 1 ? '停用' : '启用' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 版本新建/编辑 -->
    <el-dialog v-model="versionFormDialog" :title="versionForm.id ? '编辑版本' : '新建版本'" width="520px"
      append-to-body>
      <el-form :model="versionForm" label-width="110px">
        <el-form-item label="版本名" required>
          <el-input v-model="versionForm.name" />
        </el-form-item>
        <el-form-item label="适用类型">
          <el-select v-model="versionForm.busitype" style="width: 100%">
            <el-option label="半包" :value="0" /><el-option label="包干" :value="1" /><el-option label="整装" :value="2" />
          </el-select>
        </el-form-item>
        <el-form-item label="管理费率‰">
          <el-input-number v-model="versionForm.manageRate" :min="0" :max="1000" />
          <span class="tip">× 折扣(万分比,10000=不折)</span>
          <el-input-number v-model="versionForm.manageDiscount" :min="1" :max="10000" style="margin-left: 8px" />
        </el-form-item>
        <el-form-item label="搬运费率‰">
          <el-input-number v-model="versionForm.carryRate" :min="0" :max="1000" />
          <el-input-number v-model="versionForm.carryDiscount" :min="1" :max="10000" style="margin-left: 8px" />
        </el-form-item>
        <el-form-item label="税率‰">
          <el-input-number v-model="versionForm.taxRate" :min="0" :max="1000" />
          <span class="tip">0 = 不启税(建报价不生成税金项)</span>
          <el-input-number v-model="versionForm.taxDiscount" :min="1" :max="10000" style="margin-left: 8px" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="versionForm.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="versionFormDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSaveVersion">保存</el-button>
      </template>
    </el-dialog>

    <!-- 费率覆盖 -->
    <el-dialog v-model="pricingDialog" title="费率/折扣覆盖(仅编辑态)" width="520px">
      <el-form :model="pricingForm" label-width="130px">
        <el-form-item label="整单折扣(万分比)">
          <el-input-number v-model="pricingForm.discount" :min="1" :max="10000" />
          <span class="tip">10000 = 不打折</span>
        </el-form-item>
        <el-form-item label="管理费率‰">
          <el-input-number v-model="pricingForm.manageRate" :min="0" :max="1000" />
          <el-input-number v-model="pricingForm.manageDiscount" :min="1" :max="10000" style="margin-left: 8px" />
        </el-form-item>
        <el-form-item label="搬运费率‰">
          <el-input-number v-model="pricingForm.carryRate" :min="0" :max="1000" />
          <el-input-number v-model="pricingForm.carryDiscount" :min="1" :max="10000" style="margin-left: 8px" />
        </el-form-item>
        <el-form-item label="税率‰">
          <el-input-number v-model="pricingForm.taxRate" :min="0" :max="1000" />
          <el-input-number v-model="pricingForm.taxDiscount" :min="1" :max="10000" style="margin-left: 8px" />
        </el-form-item>
        <el-form-item label="说明">
          <span class="tip">管理费/搬运费 = 折后合计 × 费率 × 折上折;税 = (折后+管理+搬运) × 税率 × 折上折;总价服务端聚合</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pricingDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSavePricing">保存并重算</el-button>
      </template>
    </el-dialog>

    <!-- 加功能区 -->
    <el-dialog v-model="areaDialog" title="加功能区" width="420px" append-to-body>
      <el-form :model="areaForm" label-width="90px">
        <el-form-item label="区名" required>
          <el-input v-model="areaForm.name" placeholder="如 客厅/厨房" />
        </el-form-item>
        <el-form-item label="区类型">
          <el-select v-model="areaForm.type" style="width: 100%">
            <el-option v-for="d in dicts.areaType" :key="d.key" :label="d.label" :value="Number(d.key)" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="areaDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onAddArea">保存</el-button>
      </template>
    </el-dialog>

    <!-- 加/改功能项 -->
    <el-dialog v-model="itemDialog" :title="itemForm.id ? '编辑功能项' : '加功能项'" width="520px" append-to-body>
      <el-form :model="itemForm" label-width="90px">
        <el-form-item label="项目名" required>
          <el-input v-model="itemForm.name" placeholder="如 墙面乳胶漆" />
        </el-form-item>
        <el-form-item label="子类">
          <el-radio-group v-model="itemForm.subtype">
            <el-radio v-for="d in dicts.itemSubtype" :key="d.key" :value="Number(d.key)">{{ d.label }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="单位">
          <el-input v-model="itemForm.unit" placeholder="m²/项/套" style="width: 100px" />
        </el-form-item>
        <el-form-item label="数量" required>
          <el-input-number v-model="itemForm.numView" :min="0.001" :precision="3" :step="1" />
        </el-form-item>
        <el-form-item label="单价" required>
          <el-input-number v-model="itemForm.priceYuan" :min="0" :precision="3" :step="100" />
          <span class="tip">元</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="itemForm.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="itemDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSaveItem">保存</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailDrawer" size="720px" :title="detail ? detail.name : '报价详情'">
      <template v-if="detail">
        <div class="status-grid">
          <div class="status-cell">
            <div class="status-name">定稿线</div>
            <el-tag :type="detail.status === 1 ? 'success' : 'info'" effect="plain">
              {{ dictLabel('quotationStatus', detail.status) }}</el-tag>
          </div>
          <div class="status-cell">
            <div class="status-name">审核线</div>
            <el-tag :type="attTag(detail.attstatus)" effect="plain">{{ dictLabel('attStatus', detail.attstatus) }}</el-tag>
          </div>
          <div class="status-cell">
            <div class="status-name">折后合计</div>
            <div class="amount">¥{{ yuan(detail.discount_price) }}</div>
          </div>
          <div class="status-cell">
            <div class="status-name">总价</div>
            <div class="amount">¥{{ yuan(detail.total_price) }}</div>
          </div>
        </div>
        <el-descriptions :column="2" border size="small" style="margin-top: 12px">
          <el-descriptions-item label="装修单">{{ detail.order_no }}</el-descriptions-item>
          <el-descriptions-item label="客户">{{ detail.customer_name }}</el-descriptions-item>
          <el-descriptions-item label="房屋" :span="2">{{ detail.house_label }}</el-descriptions-item>
          <el-descriptions-item label="未折合计">¥{{ yuan(detail.undis_price) }}</el-descriptions-item>
          <el-descriptions-item label="折扣">{{ basis(detail.discount) }}</el-descriptions-item>
          <el-descriptions-item label="锁定时间">{{ detail.lock_time || '—' }}</el-descriptions-item>
          <el-descriptions-item label="完成时间">{{ detail.finish_time || '—' }}</el-descriptions-item>
        </el-descriptions>

        <div class="section">
          <div class="section-title">流转操作</div>
          <el-button v-if="detail.status === 0" size="small" type="primary" @click="onAct(lockQuotation, '已锁定定稿')">锁定定稿</el-button>
          <el-button v-if="detail.status === 1" size="small" @click="onAct(unlockQuotation, '已解锁')">解锁</el-button>
          <el-button v-if="detail.status === 1 && detail.attstatus === 0" size="small" type="primary"
            @click="onAct(submitQuotation, '已提交审核')">提交审核</el-button>
          <el-button v-if="detail.attstatus === 1" size="small" type="success"
            @click="onAct(auditQuotation, '审核通过')">审核</el-button>
          <el-button v-if="detail.attstatus === 2" size="small" type="success"
            @click="onAct(finishQuotation, '审核线完成')">完成</el-button>
          <el-button v-if="detail.status === 1" size="small" type="warning"
            @click="onAct(setDefaultQuotation, '已设为默认')">设为默认</el-button>
          <el-button v-if="detail.status === 0 && detail.attstatus < 2" size="small"
            @click="openPricing">费率/折扣</el-button>
          <el-button v-if="detail.status === 0 && detail.attstatus < 2" size="small" @click="openAreaForm">加功能区</el-button>
        </div>

        <div class="section" v-for="area in detail.areas" :key="area.id">
          <div class="section-title">
            {{ area.name }}
            <el-tag size="small" effect="plain" style="margin-left: 6px">{{ areaTypeLabel(area.type) }}</el-tag>
            <el-popconfirm v-if="editable" title="删除该功能区及其全部功能项?" @confirm="onDeleteArea(area)">
              <template #reference>
                <el-button link type="danger" size="small" style="float: right">删区</el-button>
              </template>
            </el-popconfirm>
            <el-button v-if="editable && area.type !== 0" link size="small" style="float: right"
              @click="openItemForm(area)">加项</el-button>
          </div>
          <el-table :data="area.items" border size="small">
            <el-table-column prop="name" label="项目" min-width="140" show-overflow-tooltip />
            <el-table-column label="子类" width="70">
              <template #default="{ row }">{{ dictLabel('itemSubtype', row.subtype) }}</template>
            </el-table-column>
            <el-table-column label="类型" width="80">
              <template #default="{ row }">{{ dictLabel('itemType', row.type) }}</template>
            </el-table-column>
            <el-table-column prop="unit" label="单位" width="60" />
            <el-table-column label="数量" width="90" align="right">
              <template #default="{ row }">{{ qty(row.num) }}</template>
            </el-table-column>
            <el-table-column label="单价" width="100" align="right">
              <template #default="{ row }">¥{{ yuan(row.price) }}</template>
            </el-table-column>
            <el-table-column label="小计" width="110" align="right">
              <template #default="{ row }">¥{{ yuan(itemSubtotal(row)) }}</template>
            </el-table-column>
            <el-table-column v-if="editable && area.type !== 0" label="操作" width="110">
              <template #default="{ row }">
                <el-button link size="small" @click="openItemForm(area, row)">改</el-button>
                <el-popconfirm title="删除该功能项?" @confirm="onDeleteItem(row)">
                  <template #reference>
                    <el-button link type="danger" size="small">删</el-button>
                  </template>
                </el-popconfirm>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div class="section">
          <div class="section-title">操作轨迹</div>
          <div v-for="(t, i) in detail.tracks" :key="i" class="kv track">
            <span class="track-time">{{ t.created_at }}</span>
            {{ trackLabel(t.action) }}
            <span v-if="t.from_value !== null && t.from_value !== undefined && t.to_value !== null">
              ({{ t.from_value }} → {{ t.to_value }})</span>
            <span v-if="t.from_att !== null && t.from_att !== undefined && t.to_att !== null">
              审核({{ t.from_att }} → {{ t.to_att }})</span>
            <span v-if="t.operator_name"> | {{ t.operator_name }}</span>
          </div>
          <div v-if="!detail.tracks.length" class="tip">暂无轨迹</div>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, ref, reactive, computed } from 'vue'
import { ElMessage } from 'element-plus'
import {
  pageQuotations, fetchQuotation, createQuotation, updateQuotationPricing,
  lockQuotation, unlockQuotation, submitQuotation, auditQuotation, finishQuotation, setDefaultQuotation,
  addQuotationArea, updateQuotationArea, deleteQuotationArea,
  addQuotationItem, updateQuotationItem, deleteQuotationItem,
  listQuotationVersions, createQuotationVersion, updateQuotationVersion, updateQuotationVersionStatus,
  pageDecorations
} from '../../api'
import { addons } from '../../store/addons'

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const filters = reactive({ orderNo: '', status: null, attstatus: null, page: 1, size: 20 })

const dicts = reactive({ quotationStatus: [], attStatus: [], areaType: [], itemType: [], itemSubtype: [],
  versionStatus: [], trackAction: [] })
const versions = ref([])

const createDialog = ref(false)
const versionDialog = ref(false)
const versionFormDialog = ref(false)
const pricingDialog = ref(false)
const areaDialog = ref(false)
const itemDialog = ref(false)
const detailDrawer = ref(false)
const detail = ref(null)
const orderOptions = ref([])
const orderLoading = ref(false)
const versionRows = ref([])
const versionIncludeDisabled = ref(false)

const form = ref({ orderId: null, name: '', versionId: null, remark: '' })
const versionForm = ref({})
const pricingForm = ref({})
const areaForm = ref({ name: '', type: 1 })
const itemForm = ref({ areaId: null, id: null, name: '', subtype: 0, unit: '', numView: 1, priceYuan: 0, remark: '' })

const editable = computed(() => detail.value && detail.value.status === 0 && detail.value.attstatus < 2)

const yuan = (li) => (Number(li || 0) / 1000).toLocaleString('zh-CN', { minimumFractionDigits: 3 })
const qty = (num) => (Number(num || 0) / 1000).toLocaleString('zh-CN', { maximumFractionDigits: 3 })
const permille = (rate) => Number(rate || 0)
const basis = (d) => (Number(d || 10000) / 100).toFixed(0) + '%'
const itemSubtotal = (row) => Math.round((Number(row.num || 0) * Number(row.price || 0)) / 1000)

function dictLabel(name, value) {
  const hit = (dicts[name] || []).find((d) => d.key === String(value))
  return hit ? hit.label : (value ?? '—')
}
function areaTypeLabel(t) { return dictLabel('areaType', t) }
function trackLabel(a) {
  const hit = (dicts.trackAction || []).find((d) => d.key === a)
  return hit ? hit.label : a
}
function busitypeLabel(t) { return ['半包', '包干', '整装'][t] ?? t }
const attTag = (s) => ({ 0: 'info', 1: 'warning', 2: 'success', 3: 'success' })[s] ?? 'info'

async function load() {
  loading.value = true
  try {
    const res = await pageQuotations({ ...filters, orderNo: filters.orderNo || undefined })
    rows.value = (res.data && res.data.rows) || []
    total.value = (res.data && res.data.total) || 0
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

async function loadVersions() {
  const res = await listQuotationVersions({ includeDisabled: versionIncludeDisabled.value })
  versions.value = res.data || []
  versionRows.value = versions.value
}

async function searchOrders(keyword) {
  if (!keyword) return
  orderLoading.value = true
  try {
    const res = await pageDecorations({ orderNo: keyword, page: 1, size: 20 })
    orderOptions.value = (res.data && res.data.rows) || []
  } finally {
    orderLoading.value = false
  }
}

function openCreate() {
  form.value = { orderId: null, name: '', versionId: null, remark: '' }
  orderOptions.value = []
  loadVersions()
  createDialog.value = true
}

async function onCreate() {
  if (!form.value.orderId || !form.value.name) return ElMessage.warning('请补全装修单与报价名')
  saving.value = true
  try {
    await createQuotation({ ...form.value, remark: form.value.remark || null })
    ElMessage.success('报价已创建')
    createDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '创建失败')
  } finally {
    saving.value = false
  }
}

function openVersions() {
  versionIncludeDisabled.value = false
  loadVersions()
  versionDialog.value = true
}

function openVersionForm(row) {
  versionForm.value = row ? {
    id: row.id, name: row.name, busitype: row.busitype,
    manageRate: row.manage_rate, manageDiscount: row.manage_discount,
    carryRate: row.carry_rate, carryDiscount: row.carry_discount,
    taxRate: row.tax_rate, taxDiscount: row.tax_discount, remark: row.remark || ''
  } : { id: null, name: '', busitype: 0, manageRate: 0, manageDiscount: 10000,
    carryRate: 0, carryDiscount: 10000, taxRate: 0, taxDiscount: 10000, remark: '' }
  versionFormDialog.value = true
}

async function onSaveVersion() {
  const f = versionForm.value
  if (!f.name) return ElMessage.warning('请填写版本名')
  saving.value = true
  try {
    if (f.id) {
      await updateQuotationVersion(f.id, { name: f.name, busitype: f.busitype,
        manageRate: f.manageRate, manageDiscount: f.manageDiscount, carryRate: f.carryRate,
        carryDiscount: f.carryDiscount, taxRate: f.taxRate, taxDiscount: f.taxDiscount,
        remark: f.remark || null })
    } else {
      await createQuotationVersion({ name: f.name, busitype: f.busitype,
        manageRate: f.manageRate, manageDiscount: f.manageDiscount, carryRate: f.carryRate,
        carryDiscount: f.carryDiscount, taxRate: f.taxRate, taxDiscount: f.taxDiscount,
        remark: f.remark || null })
    }
    ElMessage.success('版本已保存')
    versionFormDialog.value = false
    loadVersions()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function onVersionStatus(row) {
  try {
    await updateQuotationVersionStatus(row.id, { status: row.status === 1 ? 0 : 1 })
    ElMessage.success(row.status === 1 ? '已停用' : '已启用')
    loadVersions()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function openDetail(row) {
  const res = await fetchQuotation(row.id)
  detail.value = res.data
  detailDrawer.value = true
}

async function refreshDetail() {
  if (detail.value) {
    const res = await fetchQuotation(detail.value.id)
    detail.value = res.data
  }
  load()
}

async function onAct(promise, done) {
  try {
    await promise(detail.value.id)
    ElMessage.success(done || '操作成功')
    await refreshDetail()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

function openPricing() {
  const d = detail.value
  pricingForm.value = { discount: d.discount, manageRate: d.manage_rate, manageDiscount: d.manage_discount,
    carryRate: d.carry_rate, carryDiscount: d.carry_discount, taxRate: d.tax_rate, taxDiscount: d.tax_discount }
  pricingDialog.value = true
}

async function onSavePricing() {
  saving.value = true
  try {
    await updateQuotationPricing(detail.value.id, pricingForm.value)
    ElMessage.success('费率已保存并重算')
    pricingDialog.value = false
    await refreshDetail()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

function openAreaForm() {
  areaForm.value = { name: '', type: 1 }
  areaDialog.value = true
}

async function onAddArea() {
  if (!areaForm.value.name) return ElMessage.warning('请填写区名')
  saving.value = true
  try {
    await addQuotationArea(detail.value.id, areaForm.value)
    ElMessage.success('功能区已添加')
    areaDialog.value = false
    await refreshDetail()
  } catch (e) {
    ElMessage.error(e.message || '添加失败')
  } finally {
    saving.value = false
  }
}

async function onDeleteArea(area) {
  try {
    await deleteQuotationArea(detail.value.id, area.id)
    ElMessage.success('功能区已删除')
    await refreshDetail()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

function openItemForm(area, item) {
  itemForm.value = item ? {
    areaId: item.area_id, id: item.id, name: item.name, subtype: item.subtype, unit: item.unit || '',
    numView: Number(item.num || 1000) / 1000, priceYuan: Number(item.price || 0) / 1000, remark: item.remark || ''
  } : {
    areaId: area.id, id: null, name: '', subtype: 0, unit: '', numView: 1, priceYuan: 0, remark: ''
  }
  itemDialog.value = true
}

async function onSaveItem() {
  const f = itemForm.value
  if (!f.name) return ElMessage.warning('请填写项目名')
  saving.value = true
  const payload = { name: f.name, subtype: f.subtype, unit: f.unit || null,
    num: Math.round(f.numView * 1000), price: Math.round(f.priceYuan * 1000),
    remark: f.remark || null }
  try {
    if (f.id) {
      await updateQuotationItem(detail.value.id, f.id, payload)
    } else {
      await addQuotationItem(detail.value.id, f.areaId, payload)
    }
    ElMessage.success('功能项已保存')
    itemDialog.value = false
    await refreshDetail()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function onDeleteItem(item) {
  try {
    await deleteQuotationItem(detail.value.id, item.id)
    ElMessage.success('功能项已删除')
    await refreshDetail()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

onMounted(() => {
  const quotation = addons.list.find((a) => a.id === 'quotation')
  const d = (quotation && quotation.dicts) || {}
  for (const key of Object.keys(dicts)) dicts[key] = d[key] || []
  load()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; align-items: center; }
.tip { color: var(--yz-ink-50, #8a887f); font-size: 12px; }
.default-mark { color: var(--yz-cinnabar, #b03a2e); font-size: 12px; font-weight: 600; }
.status-grid { display: flex; gap: 12px; }
.status-cell { flex: 1; border: 1px solid var(--yz-paper-3, #e6e2d8); padding: 8px; text-align: center; }
.status-name { font-size: 12px; color: var(--yz-ink-50, #8a887f); margin-bottom: 6px; }
.amount { font-size: 15px; font-weight: 600; }
.section { margin-top: 14px; }
.section-title { font-weight: 600; margin-bottom: 6px; border-left: 3px solid var(--yz-cinnabar, #b03a2e);
  padding-left: 8px; overflow: hidden; }
.kv { line-height: 1.9; font-size: 13px; }
.track { font-size: 12px; }
.track-time { color: var(--yz-ink-50, #8a887f); margin-right: 8px; }
</style>
