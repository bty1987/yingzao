<template>
  <div>
    <el-card shadow="never">
      <div class="toolbar">
        <el-select v-model="status" placeholder="状态" clearable style="width: 110px" @change="load(1)">
          <el-option v-for="(label, key) in dicts.exchangeStatus || {}" :key="key" :label="label" :value="key" />
        </el-select>
        <el-input v-model="exchangeNo" placeholder="商城单号" style="width: 200px" @keyup.enter="load(1)" />
        <el-button @click="load(1)">查询</el-button>
        <div class="spacer" />
      </div>
      <el-table :data="rows" border stripe>
        <el-table-column prop="id" label="#" width="64" />
        <el-table-column prop="exchange_no" label="商城单号" min-width="160" />
        <el-table-column prop="customer_name" label="客户" min-width="110" />
        <el-table-column label="积分分项" width="90">
          <template #default="{ row }">{{ dicts.pointType?.[String(row.point_type)] || row.point_type }}</template>
        </el-table-column>
        <el-table-column label="消耗积分" width="100">
          <template #default="{ row }">{{ Number(row.point_total).toLocaleString() }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.status === 0" size="small">已受理</el-tag>
            <el-tag v-else-if="row.status === 1" type="success" size="small">已核销</el-tag>
            <el-tag v-else-if="row.status === 2" type="info" size="small">已撤销</el-tag>
            <el-tag v-else size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="created_at" label="落单时间" width="160" />
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button v-if="row.status === 0" link type="danger" @click="onCancel(row)">撤销</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" layout="total, prev, pager, next" :total="total" :page-size="size"
        :current-page="page" @current-change="load" />
    </el-card>

    <el-drawer v-model="detailVisible" :title="`兑换单 ${detail?.exchange_no || ''}`" size="680px">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="客户">{{ detail.customer_name }}</el-descriptions-item>
          <el-descriptions-item label="积分分项">{{ dicts.pointType?.[String(detail.point_type)] }}</el-descriptions-item>
          <el-descriptions-item label="消耗积分">{{ Number(detail.point_total).toLocaleString() }}</el-descriptions-item>
          <el-descriptions-item label="关联装修单">{{ detail.order_id || '无' }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ dicts.exchangeStatus?.[String(detail.status)] }}</el-descriptions-item>
          <el-descriptions-item label="落单时间">{{ detail.created_at }}</el-descriptions-item>
        </el-descriptions>
        <div class="section">兑换明细</div>
        <el-table :data="detail.items || []" border size="small">
          <el-table-column prop="gift_no" label="编号" width="120" />
          <el-table-column prop="gift_name" label="礼品" min-width="120" />
          <el-table-column prop="integral" label="积分" width="90" />
          <el-table-column label="状态" width="86">
            <template #default="{ row }">
              {{ dicts.exchangeItemStatus?.[String(row.status)] || row.status }}
            </template>
          </el-table-column>
          <el-table-column prop="expire_time" label="批次过期" width="160" />
          <el-table-column label="操作" width="140" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.status === 0" link type="primary" @click="onConsume(row)">核销</el-button>
              <el-button v-if="row.status === 0" link type="danger" @click="onCancelItem(row)">撤销</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchAddons } from '../../api'
import {
  pageMemberExchanges, fetchMemberExchange, cancelMemberExchange,
  consumeMemberExchangeItem, cancelMemberExchangeItem,
} from '../../api'

const dicts = ref({})
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = 20
const status = ref(null)
const exchangeNo = ref('')
const detailVisible = ref(false)
const detail = ref(null)

async function loadDicts() {
  const res = await fetchAddons()
  const addon = (res.data || []).find((a) => a.id === 'member')
  if (addon?.dicts) dicts.value = addon.dicts
}

async function load(p) {
  const target = p || page.value
  const params = { page: target, size, exchangeNo: exchangeNo.value || undefined }
  if (status.value !== null && status.value !== '') params.status = status.value
  const res = await pageMemberExchanges(params)
  rows.value = res.data.rows || []
  total.value = res.data.total || 0
  page.value = target
}

async function openDetail(row) {
  const res = await fetchMemberExchange(row.id)
  detail.value = res.data
  detailVisible.value = true
}

async function onCancel(row) {
  await ElMessageBox.confirm('整单撤销将对称回补积分与库存,已核销明细不可撤。确认?', '提示', { type: 'warning' })
  await cancelMemberExchange(row.id)
  ElMessage.success('已撤销')
  await load()
}

async function onConsume(item) {
  const { value } = await ElMessageBox.prompt('核销说明(可空,默认「人工核销」)', '核销确认', { inputValue: '' })
  await consumeMemberExchangeItem(item.id, { content: value || null })
  ElMessage.success('已核销')
  detail.value = (await fetchMemberExchange(detail.value.id)).data
  await load()
}

async function onCancelItem(item) {
  await ElMessageBox.confirm('撤销该明细并回补积分/库存?', '提示', { type: 'warning' })
  await cancelMemberExchangeItem(item.id)
  ElMessage.success('已撤销')
  detail.value = (await fetchMemberExchange(detail.value.id)).data
  await load()
}

onMounted(async () => {
  await loadDicts()
  await load()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 12px; }
.spacer { flex: 1; }
.pager { margin-top: 12px; justify-content: flex-end; }
.section { margin: 16px 0 8px; font-family: var(--yz-font-display); font-weight: 600; }
</style>
