<template>
  <div>
    <div class="toolbar">
      <el-input v-model="filters.belonger" placeholder="归属人" style="width: 140px" clearable @keyup.enter="load" />
      <el-select v-model="filters.type" placeholder="类型" style="width: 130px" clearable>
        <el-option v-for="d in dicts.accountType" :key="d.key" :label="d.label" :value="Number(d.key)" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <el-button @click="createVisible = true">新建账户</el-button>
    </div>

    <el-table :data="rows" border stripe v-loading="loading">
      <el-table-column prop="id" label="id" width="70" />
      <el-table-column label="归属人" min-width="140">
        <template #default="{ row }">{{ row.belonger_name || row.belonger || '未指定' }}</template>
      </el-table-column>
      <el-table-column label="类型" width="110">
        <template #default="{ row }">{{ label('accountType', row.type) }}</template>
      </el-table-column>
      <el-table-column label="应付登记(元)" width="120" align="right">
        <template #default="{ row }">{{ yuan(row.refer_money) }}</template>
      </el-table-column>
      <el-table-column label="实付累计(元)" width="120" align="right">
        <template #default="{ row }">{{ yuan(row.real_money) }}</template>
      </el-table-column>
      <el-table-column label="净头寸(元)" width="120" align="right">
        <template #default="{ row }">{{ yuan(row.surplus) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag effect="plain" :type="row.status === 0 ? 'success' : 'info'">
            {{ label('accountStatus', row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetails(row)">流水</el-button>
          <el-button v-if="row.type === 1 && row.status === 0" link type="warning" @click="onCashIn(row)">入金</el-button>
          <el-button link :type="row.status === 0 ? 'danger' : 'success'" @click="onToggle(row)">
            {{ row.status === 0 ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination layout="prev, pager, next, total" :total="total" :page-size="filters.size"
      :current-page="filters.page" @current-change="(p) => { filters.page = p; load() }" style="margin-top: 12px" />

    <el-dialog v-model="createVisible" title="新建结算账户" width="460px">
      <el-form label-width="90px">
        <el-form-item label="归属人"><el-input v-model="form.belonger" placeholder="身份字符串,空=未指定" /></el-form-item>
        <el-form-item label="显示名"><el-input v-model="form.belongerName" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.type" style="width: 100%">
            <el-option v-for="d in dicts.accountType" :key="d.key" :label="d.label" :value="Number(d.key)" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="onCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailDrawer" :title="current ? '账户流水 · ' + (current.belonger_name || '未指定') : ''" size="560px">
      <el-table :data="detailRows" border size="small">
        <el-table-column prop="created_at" label="时间" width="165" />
        <el-table-column prop="action" label="动作" width="100" />
        <el-table-column label="发生额(元)" width="110" align="right">
          <template #default="{ row }">{{ yuan(row.money) }}</template>
        </el-table-column>
        <el-table-column label="余额(元)" width="110" align="right">
          <template #default="{ row }">{{ yuan(row.balance_after) }}</template>
        </el-table-column>
        <el-table-column prop="memo" label="备注" min-width="100" />
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { addons } from '../../store/addons'
import {
  pageBalanceAccounts, createBalanceAccount, updateBalanceAccountStatus,
  cashInBalanceAccount, listBalanceAccountDetails
} from '../../api'

const dicts = reactive({ accountType: [], accountStatus: [] })
const filters = reactive({ belonger: '', type: null, page: 1, size: 20 })
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const createVisible = ref(false)
const form = reactive({ belonger: '', belongerName: '', type: 0 })
const detailDrawer = ref(false)
const current = ref(null)
const detailRows = ref([])

const yuan = (li) => (Number(li || 0) / 1000).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 3 })
const label = (dict, v) => {
  const hit = (dicts[dict] || []).find((d) => d.key === String(v))
  return hit ? hit.label : v
}

async function load() {
  loading.value = true
  try {
    const res = await pageBalanceAccounts({
      belonger: filters.belonger || undefined, type: filters.type, page: filters.page, size: filters.size
    })
    rows.value = (res.data && res.data.rows) || []
    total.value = (res.data && res.data.total) || 0
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

async function onCreate() {
  try {
    await createBalanceAccount({ ...form, belonger: form.belonger || undefined })
    ElMessage.success('已创建')
    createVisible.value = false
    form.belonger = ''; form.belongerName = ''
    load()
  } catch (e) {
    ElMessage.error(e.message || '创建失败')
  }
}

async function onToggle(row) {
  try {
    await updateBalanceAccountStatus(row.id, { status: row.status === 0 ? 1 : 0 })
    ElMessage.success(row.status === 0 ? '已停用' : '已启用')
    load()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function onCashIn(row) {
  try {
    const { value } = await ElMessageBox.prompt('入金金额(元)', '出纳入金(补足现金腿)', { type: 'warning' })
    await cashInBalanceAccount(row.id, { money: Math.round(Number(value) * 1000) })
    ElMessage.success('已入金')
    load()
  } catch (e) {
    if (e?.message) ElMessage.error(e.message)
  }
}

async function openDetails(row) {
  try {
    const res = await listBalanceAccountDetails(row.id)
    current.value = row
    detailRows.value = (res.data && res.data.rows) || []
    detailDrawer.value = true
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  }
}

onMounted(() => {
  const balance = addons.list.find((a) => a.id === 'balance')
  const d = (balance && balance.dicts) || {}
  dicts.accountType = d.accountType || []
  dicts.accountStatus = d.accountStatus || []
  load()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; }
</style>
