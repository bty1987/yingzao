<template>
  <div>
    <div class="toolbar">
      <el-input v-model="filters.code" placeholder="销售单号" style="width: 150px" clearable @keyup.enter="load" />
      <el-select v-model="filters.status" placeholder="主单状态" style="width: 120px" clearable>
        <el-option v-for="d in dicts.orderStatus" :key="d.key" :label="d.label" :value="Number(d.key)" />
      </el-select>
      <el-select v-model="filters.balanceStatus" placeholder="结算轴" style="width: 110px" clearable>
        <el-option v-for="d in dicts.balanceStatus" :key="d.key" :label="d.label" :value="Number(d.key)" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <el-button @click="openCreate">开单</el-button>
    </div>

    <el-table :data="rows" border stripe v-loading="loading" @row-click="openDetail">
      <el-table-column prop="code" label="销售单号" width="150" />
      <el-table-column prop="order_no" label="装修单" width="150" />
      <el-table-column prop="customer_name" label="客户" min-width="120" show-overflow-tooltip />
      <el-table-column label="总额(元)" width="110" align="right">
        <template #default="{ row }">{{ yuan(row.total) }}</template>
      </el-table-column>
      <el-table-column label="主单" width="90">
        <template #default="{ row }">
          <el-tag :type="orderTag(row.status)" effect="plain">{{ label('orderStatus', row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="结算" width="90">
        <template #default="{ row }">
          <el-tag :type="balanceTag(row.balance_status)" effect="plain">{{ label('balanceStatus', row.balance_status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="款项" width="90">
        <template #default="{ row }">
          <el-tag :type="payTag(row.fullpay_status)" effect="plain">{{ label('payStatus', row.fullpay_status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="客户确认" width="90">
        <template #default="{ row }">
          <el-tag :type="row.customer_confirmed === 1 ? 'success' : 'info'" effect="plain">
            {{ row.customer_confirmed === 1 ? '已确认' : '未确认' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="created_at" label="开单时间" width="170" />
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button v-if="row.status === 0" type="primary" link @click.stop="onFinish(row)">完结</el-button>
          <el-button v-if="row.status === 0" type="danger" link @click.stop="onTerminate(row)">废单</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination layout="prev, pager, next, total" :total="total" :page-size="filters.size"
      :current-page="filters.page" @current-change="(p) => { filters.page = p; load() }" style="margin-top: 12px" />

    <!-- 开单 -->
    <el-dialog v-model="createDialog" title="开单(挂装修单)" width="720px">
      <el-form label-width="90px">
        <el-form-item label="装修单">
          <el-select v-model="form.orderId" filterable remote :remote-method="searchOrders"
            :loading="searching" placeholder="输入装修单号搜索" style="width: 100%">
            <el-option v-for="o in orderOptions" :key="o.id" :label="o.order_no + ' · ' + o.customer_name" :value="o.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <div class="section">明细(套餐自动展开,金额服务端重算)</div>
      <div v-for="(line, i) in form.items" :key="i" class="item-line">
        <el-select v-model="line.goodsId" filterable remote :remote-method="searchGoods"
          :loading="searchingGoods" placeholder="商品/套餐" style="width: 260px">
          <el-option v-for="g in goodsOptions" :key="g.id" :label="g.name + '(' + g.no + ')'" :value="g.id" />
        </el-select>
        <el-input-number v-model="line.num" :min="0.001" :precision="3" :step="1" placeholder="数量(件)" />
        <el-input-number v-model="line.discount" :min="0.1" :max="100" :precision="1" :step="0.1"
          placeholder="折扣(100=无折)" />
        <el-button type="danger" link @click="form.items.splice(i, 1)">删</el-button>
      </div>
      <el-button link type="primary" @click="form.items.push({ goodsId: null, num: 1, discount: 100 })">+ 加一行</el-button>
      <el-form label-width="90px" style="margin-top: 12px">
        <el-form-item label="备注"><el-input v-model="form.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onCreate">开单</el-button>
      </template>
    </el-dialog>

    <!-- 客户确认 -->
    <el-dialog v-model="confirmDialog" title="客户确认(验证码+手机三重校验)" width="440px">
      <el-form label-width="80px">
        <el-form-item label="手机号"><el-input v-model="confirmForm.phone" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="onSendCode" :loading="saving">发验证码</el-button>
        <el-button @click="confirmDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onConfirm">确认</el-button>
      </template>
    </el-dialog>

    <!-- 发起退货 -->
    <el-dialog v-model="refundDialog" title="发起退货(走内核审批)" width="640px">
      <el-form label-width="90px">
        <el-form-item label="退货类型">
          <el-select v-model="refundForm.type" style="width: 200px">
            <el-option v-for="d in dicts.refundType" :key="d.key" :label="d.label" :value="Number(d.key)" />
          </el-select>
        </el-form-item>
        <el-form-item label="审批人">
          <el-select v-model="refundForm.assigneeId" filterable style="width: 260px">
            <el-option v-for="u in users" :key="u.id" :label="u.display_name" :value="u.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <div class="section">退货明细(数量不可超过可退余量)</div>
      <div v-for="(line, i) in refundForm.items" :key="i" class="item-line">
        <el-select v-model="line.saleItemId" style="width: 320px">
          <el-option v-for="it in refundableItems" :key="it.id"
            :label="it.goods_name + (it.color_name ? '/' + it.color_name : '') + ' 余' + it.num / 1000" :value="it.id" />
        </el-select>
        <el-input-number v-model="line.num" :min="0.001" :precision="3" :step="1" />
        <el-button type="danger" link @click="refundForm.items.splice(i, 1)">删</el-button>
      </div>
      <el-button link type="primary" @click="refundForm.items.push({ saleItemId: null, num: 1 })">+ 加一行</el-button>
      <template #footer>
        <el-button @click="refundDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onRefund">提交审批</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailDrawer" size="780px" :title="detail ? detail.code : '详情'">
      <template v-if="detail">
        <div class="axis-row">
          <el-tag :type="orderTag(detail.status)" effect="plain">{{ label('orderStatus', detail.status) }}</el-tag>
          <el-tag :type="balanceTag(detail.balance_status)" effect="plain">{{ label('balanceStatus', detail.balance_status) }}</el-tag>
          <el-tag :type="payTag(detail.fullpay_status)" effect="plain">{{ label('payStatus', detail.fullpay_status) }}</el-tag>
          <el-tag :type="detail.customer_confirmed === 1 ? 'success' : 'info'" effect="plain">
            {{ detail.customer_confirmed === 1 ? '客户已确认' : '客户未确认' }}
          </el-tag>
        </div>
        <div class="kv"><span>装修单</span>{{ detail.order_no }} · {{ detail.customer_name }} · {{ detail.house_label }}</div>
        <div class="kv"><span>总额</span>{{ yuan(detail.total) }} 元(服务端重算)</div>

        <div class="section">明细</div>
        <el-table :data="detail.items" border size="small">
          <el-table-column prop="goods_name" label="商品" min-width="130" />
          <el-table-column prop="brand_name" label="品牌" width="90" />
          <el-table-column label="数量" width="80" align="right">
            <template #default="{ row }">{{ row.num / 1000 }}</template>
          </el-table-column>
          <el-table-column label="已出/采购/已送" width="110" align="right">
            <template #default="{ row }">{{ row.xgnum / 1000 }}/{{ row.punum / 1000 }}/{{ row.trnum / 1000 }}</template>
          </el-table-column>
          <el-table-column label="折后单价" width="95" align="right">
            <template #default="{ row }">{{ yuan(row.dis_price) }}</template>
          </el-table-column>
          <el-table-column label="金额" width="100" align="right">
            <template #default="{ row }">{{ yuan(row.total) }}</template>
          </el-table-column>
          <el-table-column label="层级" width="70">
            <template #default="{ row }">
              <el-tag v-if="row.package_id > 0" type="info" effect="plain" size="small">子项</el-tag>
              <el-tag v-else effect="plain" size="small">主项</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <div style="margin-top: 8px; display: flex; gap: 8px">
          <el-button size="small" :disabled="detail.fullpay_status > 0" @click="openEditItems">调整明细</el-button>
          <el-button size="small" :disabled="detail.fullpay_status > 0" @click="onGenerateBill">生成全款款项单</el-button>
          <el-button size="small" :disabled="detail.customer_confirmed === 1" type="primary" @click="confirmDialog = true">客户确认</el-button>
          <el-button size="small" :disabled="!(detail.fullpay_status >= 1 && [0, 5].includes(detail.status))" @click="openDelivery">生成配送</el-button>
          <el-button size="small" :disabled="![0, 5].includes(detail.status)" type="warning" @click="openRefund">发起退货</el-button>
        </div>

        <div class="section">结算行(三段式)</div>
        <el-table :data="detail.settlements" border size="small">
          <el-table-column prop="brand_name" label="品牌" width="100" />
          <el-table-column label="类型" width="90">
            <template #default="{ row }">{{ label('settleType', row.type) }}</template>
          </el-table-column>
          <el-table-column label="应收" width="95" align="right">
            <template #default="{ row }">{{ yuan(row.total) }}</template>
          </el-table-column>
          <el-table-column label="厂结值" width="95" align="right">
            <template #default="{ row }">{{ yuan(row.vbalance) }}</template>
          </el-table-column>
          <el-table-column label="结算值" width="95" align="right">
            <template #default="{ row }">{{ yuan(row.pbalance) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag effect="plain" size="small" :type="['info', 'warning', 'success'][row.status]">
                {{ label('settleStatus', row.status) }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
        <div style="margin-top: 8px">
          <el-button size="small" type="danger" :disabled="detail.balance_status !== 1"
            @click="onRejectSettlement">驳回预结(整单)</el-button>
        </div>

        <div class="section">配送项</div>
        <el-table :data="detail.deliveryItems || []" border size="small">
          <el-table-column prop="goods_name" label="商品" min-width="120" />
          <el-table-column label="数量" width="80" align="right">
            <template #default="{ row }">{{ row.num / 1000 }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag effect="plain" size="small" :type="['info', '', '', 'warning', 'success'][row.status]">
                {{ label('deliveryStatus', row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="下一动作" width="130">
            <template #default="{ row }">
              <el-button v-if="row.status < 4" size="small" link type="primary"
                @click="onAdvance(row)">{{ nextAction(row.status) }}</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="section">退货单</div>
        <el-table :data="detail.refunds" border size="small">
          <el-table-column prop="id" label="单号" width="70" />
          <el-table-column label="类型" width="80">
            <template #default="{ row }">{{ label('refundType', row.type) }}</template>
          </el-table-column>
          <el-table-column label="金额" width="100" align="right">
            <template #default="{ row }">{{ yuan(row.amount) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">{{ label('refundStatus', row.status) }}</template>
          </el-table-column>
          <el-table-column label="客户确认" width="90">
            <template #default="{ row }">{{ row.is_affirm === 1 ? '已确认' : '未确认' }}</template>
          </el-table-column>
        </el-table>

        <div class="section">款项单</div>
        <el-table :data="detail.bills" border size="small">
          <el-table-column prop="billId" label="款项号" width="80" />
          <el-table-column prop="typeName" label="类型" width="110" />
          <el-table-column label="方向" width="70">
            <template #default="{ row }">{{ row.direction === 1 ? '退款' : '应收' }}</template>
          </el-table-column>
          <el-table-column label="金额" width="110" align="right">
            <template #default="{ row }">{{ yuan(row.amount) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag effect="plain" size="small" :type="{ 0: 'info', 1: 'warning', 2: 'success', 3: 'danger', 4: 'info' }[row.status]">
                {{ ['', '已收', '已确认', '已退款', '已作废'][row.status] || row.status }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>

        <div class="section">轨迹</div>
        <div v-for="(t, i) in detail.tracks" :key="i" class="track-line">
          <span class="track-time">{{ t.created_at }}</span>
          <b>{{ t.operator_name }}</b> {{ t.detail || t.action }}
        </div>
      </template>
    </el-drawer>

    <!-- 调整明细 -->
    <el-dialog v-model="editDialog" title="调整明细(全量替换,服务端重算)" width="720px">
      <div v-for="(line, i) in editItems" :key="i" class="item-line">
        <span class="mono" style="width: 200px">{{ line.goodsName }}</span>
        <el-input-number v-model="line.num" :min="0.001" :precision="3" :step="1" />
        <el-input-number v-model="line.discount" :min="0.1" :max="100" :precision="1" :step="0.1" />
        <span class="tip">折扣(100=无折)</span>
      </div>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSaveItems">保存</el-button>
      </template>
    </el-dialog>

    <!-- 生成配送 -->
    <el-dialog v-model="deliveryDialog" title="生成配送批次" width="640px">
      <div v-for="(line, i) in deliveryItems" :key="i" class="item-line">
        <span class="mono" style="width: 200px">{{ line.goodsName }}</span>
        <span class="tip">可送 {{ line.max / 1000 }}</span>
        <el-input-number v-model="line.num" :min="0.001" :max="line.max / 1000" :precision="3" :step="1" />
      </div>
      <template #footer>
        <el-button @click="deliveryDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onCreateDelivery">生成</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { addons } from '../../store/addons'
import {
  pageSalesOrders, fetchSalesOrder, createSalesOrder, updateSalesOrderItems, generateSalesBill,
  sendSalesVerifyCode, confirmSalesOrder, finishSalesOrder, terminateSalesOrder,
  initiateSalesRefund, sendRefundVerifyCode, confirmSalesRefund,
  createSalesDelivery, advanceSalesDeliveryItem, pageDecorations, pageGoods, listOrgUsers
} from '../../api'

const dicts = reactive({ orderStatus: [], balanceStatus: [], payStatus: [], settleStatus: [], settleType: [], refundStatus: [], refundType: [], deliveryStatus: [] })
const filters = reactive({ code: '', status: null, balanceStatus: null, page: 1, size: 20 })
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const users = ref([])

const createDialog = ref(false)
const form = ref({ orderId: null, items: [{ goodsId: null, num: 1, discount: 100 }], remark: '' })
const orderOptions = ref([])
const goodsOptions = ref([])
const searching = ref(false)
const searchingGoods = ref(false)

const detailDrawer = ref(false)
const detail = ref(null)
const confirmDialog = ref(false)
const confirmForm = ref({ phone: '' })
const refundDialog = ref(false)
const refundForm = ref({ type: 2, assigneeId: null, items: [{ saleItemId: null, num: 1 }] })
const refundableItems = ref([])
const editDialog = ref(false)
const editItems = ref([])
const deliveryDialog = ref(false)
const deliveryItems = ref([])

const yuan = (li) => (Number(li || 0) / 1000).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 3 })
const label = (dict, v) => {
  const hit = (dicts[dict] || []).find((d) => d.key === String(v))
  return hit ? hit.label : v
}
const orderTag = (s) => ({ 0: 'info', 2: 'danger', 3: 'warning', 4: 'danger', 5: 'success' }[s] ?? 'info')
const balanceTag = (s) => ({ 0: 'info', 1: 'warning', 2: '', 3: 'success' }[s] ?? 'info')
const payTag = (s) => ({ [-1]: 'info', 0: 'warning', 1: '', 2: 'success' }[s] ?? 'info')
const nextAction = (s) => ({ 0: '配送', 1: '审核', 2: '客户确认', 3: '完结' }[s] || '')

async function load() {
  loading.value = true
  try {
    const res = await pageSalesOrders({ ...filters, code: filters.code || undefined })
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

async function searchGoods(keyword) {
  searchingGoods.value = true
  try {
    const res = await pageGoods({ keyword: keyword || '', page: 1, size: 20 })
    goodsOptions.value = (res.data && res.data.rows) || []
  } finally {
    searchingGoods.value = false
  }
}

function openCreate() {
  form.value = { orderId: null, items: [{ goodsId: null, num: 1, discount: 100 }], remark: '' }
  orderOptions.value = []
  goodsOptions.value = []
  createDialog.value = true
}

async function onCreate() {
  const f = form.value
  if (!f.orderId) return ElMessage.warning('请选择装修单')
  const items = f.items.filter((l) => l.goodsId && l.num > 0)
    .map((l) => ({ goodsId: l.goodsId, num: Math.round(l.num * 1000), discount: Math.round(l.discount * 10) }))
  if (items.length === 0) return ElMessage.warning('请补全明细')
  saving.value = true
  try {
    await createSalesOrder({ orderId: f.orderId, items, remark: f.remark || null })
    ElMessage.success('已开单')
    createDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '开单失败')
  } finally {
    saving.value = false
  }
}

async function openDetail(row) {
  const res = await fetchSalesOrder(row.id)
  detail.value = res.data
  detailDrawer.value = true
}

async function onSendCode() {
  if (!confirmForm.value.phone) return ElMessage.warning('请填客户手机号')
  saving.value = true
  try {
    await sendSalesVerifyCode(detail.value.id, { phone: confirmForm.value.phone })
    ElMessage.success('验证码已发(站内通知,请转告客户)')
  } catch (e) {
    ElMessage.error(e.message || '发送失败')
  } finally {
    saving.value = false
  }
}

async function onConfirm() {
  saving.value = true
  try {
    await confirmSalesOrder(detail.value.id, confirmForm.value)
    ElMessage.success('客户确认完成')
    confirmDialog.value = false
    openDetail(detail.value)
  } catch (e) {
    ElMessage.error(e.message || '确认失败')
  } finally {
    saving.value = false
  }
}

function openEditItems() {
  editItems.value = (detail.value.items || []).filter((it) => it.package_id === 0)
    .map((it) => ({ goodsId: it.goods_id, goodsName: it.goods_name, num: it.num / 1000, discount: it.discount / 10 }))
  editDialog.value = true
}

async function onSaveItems() {
  saving.value = true
  try {
    const items = editItems.value.map((l) => ({ goodsId: l.goodsId, num: Math.round(l.num * 1000), discount: Math.round(l.discount * 10) }))
    await updateSalesOrderItems(detail.value.id, { items })
    ElMessage.success('明细已更新(服务端重算)')
    editDialog.value = false
    openDetail(detail.value)
    load()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function onGenerateBill() {
  try {
    await generateSalesBill(detail.value.id)
    ElMessage.success('全款款项单已生成')
    openDetail(detail.value)
  } catch (e) {
    ElMessage.error(e.message || '生成失败')
  }
}

function openRefund() {
  refundForm.value = { type: 2, assigneeId: null, items: [{ saleItemId: null, num: 1 }] }
  refundableItems.value = (detail.value.items || []).filter((it) => it.package_id === 0 && it.num > 0)
    .map((it) => ({ ...it, num: it.num }))
  if (users.value.length === 0) listOrgUsers().then((res) => { users.value = res.data || [] })
  refundDialog.value = true
}

async function onRefund() {
  const f = refundForm.value
  const items = f.items.filter((l) => l.saleItemId && l.num > 0)
    .map((l) => ({ saleItemId: l.saleItemId, num: Math.round(l.num * 1000) }))
  if (!f.assigneeId || items.length === 0) return ElMessage.warning('请补全审批人与退货明细')
  saving.value = true
  try {
    await initiateSalesRefund(detail.value.id, { type: f.type, items, assigneeId: f.assigneeId })
    ElMessage.success('退货审批已提交')
    refundDialog.value = false
    openDetail(detail.value)
  } catch (e) {
    ElMessage.error(e.message || '提交失败')
  } finally {
    saving.value = false
  }
}

function openDelivery() {
  deliveryItems.value = (detail.value.items || []).filter((it) => it.package_id === 0)
    .map((it) => ({ saleItemId: it.id, goodsName: it.goods_name, num: 0, max: it.num - it.trnum }))
    .filter((l) => l.max > 0)
  if (deliveryItems.value.length === 0) return ElMessage.warning('无可送明细')
  deliveryDialog.value = true
}

async function onCreateDelivery() {
  saving.value = true
  try {
    const items = deliveryItems.value.filter((l) => l.num > 0)
      .map((l) => ({ saleItemId: l.saleItemId, num: Math.round(l.num * 1000) }))
    if (items.length === 0) { saving.value = false; return ElMessage.warning('请填配送数量') }
    await createSalesDelivery(detail.value.id, { items })
    ElMessage.success('配送批次已生成')
    deliveryDialog.value = false
    openDetail(detail.value)
  } catch (e) {
    ElMessage.error(e.message || '生成失败')
  } finally {
    saving.value = false
  }
}

async function onAdvance(row) {
  const action = { 0: 'tran', 1: 'check', 2: 'confirm', 3: 'finish' }[row.status]
  try {
    await advanceSalesDeliveryItem(row.id, { action })
    ElMessage.success('已推进:' + nextAction(row.status))
    openDetail(detail.value)
  } catch (e) {
    ElMessage.error(e.message || '推进失败')
  }
}

async function onFinish(row) {
  try {
    await ElMessageBox.confirm('完结守卫:全款已确认且无未完配送项。确定完结?', '完结', { type: 'warning' })
  } catch (e) { return }
  try {
    await finishSalesOrder(row.id)
    ElMessage.success('已完结')
    load()
  } catch (e) {
    ElMessage.error(e.message || '完结失败')
  }
}

async function onTerminate(row) {
  try {
    await ElMessageBox.confirm('废单仅限四轴全初始。确定作废?', '废单', { type: 'warning' })
  } catch (e) { return }
  try {
    await terminateSalesOrder(row.id)
    ElMessage.success('已废单')
    load()
  } catch (e) {
    ElMessage.error(e.message || '废单失败')
  }
}

async function onRejectSettlement() {
  try {
    await ElMessageBox.prompt('驳回原因', '驳回预结(整单,原子)', { type: 'warning' })
      .then(({ value }) => rejectSalesSettlement(detail.value.id, { reason: value || null }))
    ElMessage.success('预结已驳回,主单回无结')
    openDetail(detail.value)
  } catch (e) {
    if (e !== 'cancel' && e?.message) ElMessage.error(e.message || '驳回失败')
  }
}

onMounted(() => {
  const sales = addons.list.find((a) => a.id === 'sales')
  const d = (sales && sales.dicts) || {}
  for (const k of Object.keys(dicts)) dicts[k] = d[k] || []
  load()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; }
.tip { color: var(--yz-ink-50); font-size: 12px; }
.mono { font-family: Consolas, monospace; font-size: 12px; }
.item-line { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; }
.section { margin: 16px 0 8px; font-weight: 600; }
.axis-row { display: flex; gap: 8px; margin-bottom: 10px; }
.kv { margin-bottom: 6px; font-size: 13px; }
.kv span { color: var(--yz-ink-50); display: inline-block; width: 60px; }
.track-line { font-size: 12px; margin-bottom: 4px; }
.track-time { color: var(--yz-ink-50); margin-right: 8px; }
</style>
