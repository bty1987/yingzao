<template>
  <div>
    <div class="toolbar">
      <el-select v-model="filters.settlementType" placeholder="来源" style="width: 140px" clearable>
        <el-option v-for="d in dicts.settlementType" :key="d.key" :label="d.label" :value="d.key" />
      </el-select>
      <el-select v-model="filters.status" placeholder="状态" style="width: 110px" clearable>
        <el-option v-for="d in dicts.settlementStatus" :key="d.key" :label="d.label" :value="Number(d.key)" />
      </el-select>
      <el-input v-model="filters.no" placeholder="结算单号" style="width: 150px" clearable @keyup.enter="load" />
      <el-button type="primary" @click="load">查询</el-button>
    </div>

    <el-table :data="rows" border stripe v-loading="loading">
      <el-table-column prop="no" label="结算单号" width="140" />
      <el-table-column label="来源" width="110">
        <template #default="{ row }">{{ label('settlementType', row.settlement_type) }}</template>
      </el-table-column>
      <el-table-column prop="name" label="结算单" min-width="160" />
      <el-table-column label="应收(元)" width="100" align="right">
        <template #default="{ row }">{{ yuan(row.income) }}</template>
      </el-table-column>
      <el-table-column label="应付(元)" width="100" align="right">
        <template #default="{ row }">{{ yuan(row.payment) }}</template>
      </el-table-column>
      <el-table-column label="应付总额(元)" width="110" align="right">
        <template #default="{ row }">{{ yuan(row.amount) }}</template>
      </el-table-column>
      <el-table-column label="扣款(元)" width="95" align="right">
        <template #default="{ row }">{{ yuan(row.cutpay) }}</template>
      </el-table-column>
      <el-table-column label="余量(元)" width="95" align="right">
        <template #default="{ row }">{{ yuan(row.surplus) }}</template>
      </el-table-column>
      <el-table-column label="付款" width="70" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.is_pay === 1" type="success" effect="plain" size="small">已付</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag effect="plain" :type="['info', 'warning', 'warning', 'primary', 'success'][row.status]">
            {{ label('settlementStatus', row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="270" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 0" link type="primary" @click="onDiscount(row)">让减</el-button>
          <el-button v-if="row.status === 0 && row.item_count === 0" link type="danger" @click="onDelete(row)">删除</el-button>
          <el-button v-if="row.status === 0" link type="warning" @click="act(lockSettlement, row, '已锁定')">锁定</el-button>
          <el-button v-if="row.status === 1" link @click="act(unlockSettlement, row, '已解锁')">解锁</el-button>
          <el-button v-if="row.status === 1" link type="warning" @click="act(auditSettlement, row, '已送审')">送审</el-button>
          <el-button v-if="row.status === 2" link @click="act(unauditSettlement, row, '已驳回')">驳回</el-button>
          <el-button v-if="row.status === 1 || row.status === 2" link type="success" @click="act(settleBalanceSettlement, row, '已结算')">结算</el-button>
          <el-button v-if="row.status === 3 || row.status === 4" link type="primary" @click="openDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination layout="prev, pager, next, total" :total="total" :page-size="filters.size"
      :current-page="filters.page" @current-change="(p) => { filters.page = p; load() }" style="margin-top: 12px" />

    <el-drawer v-model="drawer" :title="current ? current.no + ' · 结算单详情' : ''" size="640px">
      <template v-if="current">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="状态">{{ label('settlementStatus', current.status) }}</el-descriptions-item>
          <el-descriptions-item label="来源">{{ label('settlementType', current.settlement_type) }}</el-descriptions-item>
          <el-descriptions-item label="品牌">{{ current.brand_name || '未分组' }}</el-descriptions-item>
          <el-descriptions-item label="归属人">{{ current.supplier_name || '未指定' }}</el-descriptions-item>
          <el-descriptions-item label="应收(元)">{{ yuan(current.income) }}</el-descriptions-item>
          <el-descriptions-item label="让利国补(元)">{{ yuan(current.cost) }}</el-descriptions-item>
          <el-descriptions-item label="应付(元)">{{ yuan(current.payment) }}</el-descriptions-item>
          <el-descriptions-item label="利润(元)">{{ yuan(current.profit) }}</el-descriptions-item>
          <el-descriptions-item label="让减(元)">{{ yuan(current.discount) }}</el-descriptions-item>
          <el-descriptions-item label="应付总额(元)">{{ yuan(current.amount) }}</el-descriptions-item>
          <el-descriptions-item label="扣款冲抵(元)">{{ yuan(current.cutpay) }}</el-descriptions-item>
          <el-descriptions-item label="余量(元)">{{ yuan(current.surplus) }}</el-descriptions-item>
          <el-descriptions-item label="实付累计(元)">{{ yuan(current.paid) }}</el-descriptions-item>
          <el-descriptions-item label="付款标">{{ current.is_pay === 1 ? '已付' : '未付' }}</el-descriptions-item>
        </el-descriptions>

        <div v-if="current.status === 3" class="money-actions">
          <el-select v-model="enterAccountId" placeholder="结算账户(台账腿)" style="width: 200px" size="small">
            <el-option v-for="a in settleAccounts" :key="a.id"
              :label="(a.belonger_name || a.belonger || '未指定') + '·结算'" :value="a.id" />
          </el-select>
          <el-button size="small" type="primary" @click="onEnter">台账入账</el-button>
          <el-select v-model="cashAccountId" placeholder="出纳账户(现金腿)" style="width: 200px" size="small">
            <el-option v-for="a in cashierAccounts" :key="a.id"
              :label="(a.belonger_name || a.belonger || '未指定') + '·出纳'" :value="a.id" />
          </el-select>
          <el-button size="small" type="warning" @click="onPay">出纳付款</el-button>
          <el-button size="small" @click="onPayMark">打付款标</el-button>
          <el-button size="small" type="success" @click="onFinish">完结</el-button>
        </div>

        <h4>明细快照({{ (current.items || []).length }})</h4>
        <el-table :data="current.items || []" border size="small">
          <el-table-column prop="source_addon" label="来源插件" width="90" />
          <el-table-column prop="source_type" label="明细类型" width="90" />
          <el-table-column prop="source_item_id" label="明细id" width="80" />
          <el-table-column label="应收(元)" width="90" align="right">
            <template #default="{ row }">{{ yuan(row.income) }}</template>
          </el-table-column>
          <el-table-column label="让利(元)" width="90" align="right">
            <template #default="{ row }">{{ yuan(row.cost) }}</template>
          </el-table-column>
          <el-table-column label="应付(元)" width="90" align="right">
            <template #default="{ row }">{{ yuan(row.payment) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="70" v-if="current.status === 0">
            <template #default="{ row }">
              <el-button link type="danger" size="small" @click="onUnbind(row)">解绑</el-button>
            </template>
          </el-table-column>
        </el-table>

        <h4>操作流水</h4>
        <el-timeline>
          <el-timeline-item v-for="(t, i) in current.tracks || []" :key="i"
            :timestamp="t.created_at + ' · ' + t.operator_name">
            {{ t.action }}<span v-if="t.detail"> — {{ t.detail }}</span>
          </el-timeline-item>
        </el-timeline>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { addons } from '../../store/addons'
import {
  pageBalanceSettlements, fetchBalanceSettlement, lockBalanceSettlement, unlockBalanceSettlement,
  auditBalanceSettlement, unauditBalanceSettlement, settleBalanceSettlement, updateBalanceDiscount,
  unbindBalanceItem, deleteBalanceSettlement, enterBalanceSettlement, payBalanceSettlement,
  payMarkBalanceSettlements, finishBalanceSettlement, pageBalanceAccounts
} from '../../api'

const dicts = reactive({ settlementStatus: [], settlementType: [] })
const filters = reactive({ settlementType: '', status: null, no: '', page: 1, size: 20 })
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const drawer = ref(false)
const current = ref(null)
const accounts = ref([])
const enterAccountId = ref(null)
const cashAccountId = ref(null)
const settleAccounts = computed(() => accounts.value.filter((a) => a.type === 0 && a.status === 0))
const cashierAccounts = computed(() => accounts.value.filter((a) => a.type === 1 && a.status === 0))

const yuan = (li) => (Number(li || 0) / 1000).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 3 })
const label = (dict, v) => {
  const hit = (dicts[dict] || []).find((d) => d.key === String(v))
  return hit ? hit.label : v
}

async function load() {
  loading.value = true
  try {
    const res = await pageBalanceSettlements({
      settlementType: filters.settlementType || undefined, status: filters.status,
      no: filters.no || undefined, page: filters.page, size: filters.size
    })
    rows.value = (res.data && res.data.rows) || []
    total.value = (res.data && res.data.total) || 0
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

async function loadAccounts() {
  try {
    const res = await pageBalanceAccounts({ page: 1, size: 100 })
    accounts.value = (res.data && res.data.rows) || []
  } catch (e) { /* 账户未建时静默 */ }
}

async function act(fn, row, okText) {
  try {
    await fn(row.id)
    ElMessage.success(okText)
    load()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function onDiscount(row) {
  try {
    const { value } = await ElMessageBox.prompt('让减金额(元,0~应付)', '整单让减 — ' + row.no, { type: 'warning' })
    await updateBalanceDiscount(row.id, { value: Math.round(Number(value) * 1000) })
    ElMessage.success('已让减')
    load()
  } catch (e) {
    if (e?.message) ElMessage.error(e.message)
  }
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm('删除空结算单 ' + row.no + '?', '删除', { type: 'warning' })
    await deleteBalanceSettlement(row.id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    if (e?.message) ElMessage.error(e.message)
  }
}

async function openDetail(row) {
  try {
    const res = await fetchBalanceSettlement(row.id)
    current.value = res.data
    drawer.value = true
    loadAccounts()
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  }
}

async function refreshDetail() {
  if (!current.value) return
  const res = await fetchBalanceSettlement(current.value.id)
  current.value = res.data
  load()
}

async function onEnter() {
  if (!enterAccountId.value) return ElMessage.warning('先选结算账户')
  try {
    const { value } = await ElMessageBox.prompt('入账金额(元)', '台账入账', { type: 'warning' })
    await enterBalanceSettlement(current.value.id, { accountId: enterAccountId.value, money: Math.round(Number(value) * 1000) })
    ElMessage.success('已入账')
    refreshDetail()
  } catch (e) {
    if (e?.message) ElMessage.error(e.message)
  }
}

async function onPay() {
  if (!cashAccountId.value) return ElMessage.warning('先选出纳账户')
  try {
    const { value } = await ElMessageBox.prompt('付款金额(元)', '出纳付款(允许透支=预付)', { type: 'warning' })
    await payBalanceSettlement(current.value.id, { accountId: cashAccountId.value, money: Math.round(Number(value) * 1000) })
    ElMessage.success('已付款')
    refreshDetail()
  } catch (e) {
    if (e?.message) ElMessage.error(e.message)
  }
}

async function onPayMark() {
  try {
    await payMarkBalanceSettlements([current.value.id])
    ElMessage.success('已打付款标')
    refreshDetail()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function onFinish() {
  try {
    await finishBalanceSettlement(current.value.id)
    ElMessage.success('已完结')
    refreshDetail()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function onUnbind(item) {
  try {
    await ElMessageBox.confirm('解绑明细 ' + item.source_item_id + ' 并退回上游?', '解绑', { type: 'warning' })
    await unbindBalanceItem(current.value.id, {
      sourceAddon: item.source_addon, sourceType: item.source_type, sourceItemId: item.source_item_id
    })
    ElMessage.success('已解绑')
    refreshDetail()
  } catch (e) {
    if (e?.message) ElMessage.error(e.message)
  }
}

onMounted(() => {
  const balance = addons.list.find((a) => a.id === 'balance')
  const d = (balance && balance.dicts) || {}
  dicts.settlementStatus = d.settlementStatus || []
  dicts.settlementType = d.settlementType || []
  load()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; }
.money-actions { display: flex; gap: 8px; margin: 12px 0; flex-wrap: wrap; }
h4 { margin: 14px 0 8px; }
</style>
