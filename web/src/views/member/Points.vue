<template>
  <div>
    <el-card shadow="never">
      <div class="toolbar">
        <el-input v-model="customerId" placeholder="客户 ID" style="width: 140px" @keyup.enter="load(1)" />
        <el-button @click="load(1)">查询</el-button>
        <div class="spacer" />
        <el-button type="primary" @click="openAdjust()">运营积分调整</el-button>
        <el-button type="warning" @click="onExpireClear">过期清算</el-button>
      </div>
      <el-table :data="rows" border stripe>
        <el-table-column prop="id" label="#" width="64" />
        <el-table-column prop="customer_id" label="客户 ID" width="90" />
        <el-table-column prop="customer_name" label="客户" min-width="120" />
        <el-table-column label="分项" width="90">
          <template #default="{ row }">{{ dicts.pointType?.[String(row.point_type)] || row.point_type }}</template>
        </el-table-column>
        <el-table-column label="余额(积分)" width="130">
          <template #default="{ row }">{{ Number(row.balance).toLocaleString() }}</template>
        </el-table-column>
        <el-table-column prop="updated_at" label="最近变动" width="160" />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openRecords(row)">流水</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" layout="total, prev, pager, next" :total="total" :page-size="size"
        :current-page="page" @current-change="load" />
    </el-card>

    <el-dialog v-model="adjustVisible" title="运营积分调整" width="460px">
      <el-form label-width="100px">
        <el-form-item label="客户 ID" required>
          <el-input-number v-model="adjustForm.customerId" :min="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="分项" required>
          <el-select v-model="adjustForm.pointType" style="width: 100%">
            <el-option v-for="(label, key) in dicts.pointType || {}" :key="key" :label="label" :value="Number(key)" />
          </el-select>
        </el-form-item>
        <el-form-item label="变动(±)" required>
          <el-input-number v-model="adjustForm.delta" :step="100" style="width: 100%" />
          <div class="hint">正数增加 / 负数扣减(下限 0,禁负余额);调整下限不足将拒绝</div>
        </el-form-item>
        <el-form-item label="原因" required>
          <el-input v-model="adjustForm.reason" type="textarea" :rows="2" placeholder="必填,与外部商城对账留痕" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjustVisible = false">取消</el-button>
        <el-button type="primary" @click="saveAdjust">确认调整</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="recordsVisible" :title="`积分流水 - ${recordsAccount?.customer_name || ''}(${'分项 ' + recordsAccount?.point_type})`" size="760px">
      <el-table :data="recordRows" border size="small">
        <el-table-column prop="id" label="#" width="64" />
        <el-table-column label="类型" width="90">
          <template #default="{ row }">{{ dicts.pointRecordType?.[String(row.type)] || row.type }}</template>
        </el-table-column>
        <el-table-column label="变动" width="90">
          <template #default="{ row }">{{ row.delta > 0 ? '+' : '' }}{{ row.delta }}</template>
        </el-table-column>
        <el-table-column prop="balance_after" label="结存" width="90" />
        <el-table-column prop="expire_time" label="批次过期" width="160" />
        <el-table-column label="来源" min-width="140">
          <template #default="{ row }">
            {{ row.src_addon ? `${row.src_addon}/${row.src_type}#${row.src_id}` : (row.reason || '-') }}
          </template>
        </el-table-column>
        <el-table-column prop="created_at" label="时间" width="160" />
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchAddons } from '../../api'
import {
  pageMemberPointAccounts, listMemberPointRecords, adjustMemberPoint,
} from '../../api'

const dicts = ref({})
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = 20
const customerId = ref('')
const adjustVisible = ref(false)
const adjustForm = reactive({ customerId: undefined, pointType: 1, delta: 100, reason: '' })
const recordsVisible = ref(false)
const recordsAccount = ref(null)
const recordRows = ref([])

async function loadDicts() {
  const res = await fetchAddons()
  const addon = (res.data || []).find((a) => a.id === 'member')
  if (addon?.dicts) dicts.value = addon.dicts
}

async function load(p) {
  const target = p || page.value
  const params = { page: target, size }
  if (customerId.value) params.customerId = customerId.value
  const res = await pageMemberPointAccounts(params)
  rows.value = res.data.rows || []
  total.value = res.data.total || 0
  page.value = target
}

async function onExpireClear() {
  try {
    await ElMessageBox.confirm(
      '将对本组织全部已过期积分批次生成清算负行(余额向可用收敛,流水可溯)。确认执行?', '过期清算',
      { type: 'warning' })
  } catch { return }
  try {
    const res = await expireClearPoints()
    const d = res.data || {}
    ElMessage.success('清算完成:' + (d.cleared || 0) + ' 行 / ' + (d.customers || 0) + ' 客户')
    load()
  } catch (e) {
    ElMessage.error(e.message || '清算失败')
  }
}

function openAdjust() {
  Object.assign(adjustForm, { customerId: undefined, pointType: 1, delta: 100, reason: '' })
  adjustVisible.value = true
}

async function saveAdjust() {
  if (!adjustForm.customerId) return ElMessage.warning('请填写客户 ID')
  if (!adjustForm.delta) return ElMessage.warning('变动不能为 0')
  if (!adjustForm.reason) return ElMessage.warning('调整原因必填(留痕)')
  await adjustMemberPoint({ ...adjustForm })
  ElMessage.success('已调整')
  adjustVisible.value = false
  await load()
}

async function openRecords(row) {
  recordsAccount.value = row
  const res = await listMemberPointRecords(row.customer_id, { pointType: row.point_type, page: 1, size: 100 })
  recordRows.value = res.data.rows || []
  recordsVisible.value = true
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
.hint { color: var(--yz-ink-50); font-size: 12px; }
</style>
