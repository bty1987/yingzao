<template>
  <div>
    <el-card shadow="never">
      <div class="toolbar">
        <el-select v-model="status" placeholder="状态" clearable style="width: 110px" @change="load(1)">
          <el-option v-for="(label, key) in dicts.giftStatus || {}" :key="key" :label="label" :value="key" />
        </el-select>
        <el-input v-model="keyword" placeholder="礼品名称/编号" style="width: 200px" @keyup.enter="load(1)" />
        <el-button @click="load(1)">查询</el-button>
        <div class="spacer" />
        <el-button type="primary" @click="openForm()">新建礼品</el-button>
      </div>
      <el-table :data="rows" border stripe>
        <el-table-column prop="no" label="编号" width="130" />
        <el-table-column prop="name" label="礼品名称" min-width="140" />
        <el-table-column label="积分价" width="100">
          <template #default="{ row }">{{ Number(row.integral_price).toLocaleString() }}</template>
        </el-table-column>
        <el-table-column label="金额单价(元)" width="120">
          <template #default="{ row }">{{ row.unit_price ? yuan(row.unit_price) : '-' }}</template>
        </el-table-column>
        <el-table-column label="算业绩" width="80">
          <template #default="{ row }">{{ row.is_prefor === 0 ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column label="算库存" width="80">
          <template #default="{ row }">{{ row.is_stock === 0 ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column label="计金额" width="80">
          <template #default="{ row }">{{ row.is_payment === 0 ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column label="库存" width="90">
          <template #default="{ row }">{{ row.is_stock === 0 ? row.stock_num : '不计' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">{{ dicts.giftStatus?.[String(row.status)] || row.status }}</template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openForm(row)">编辑</el-button>
            <el-button link @click="onToggle(row)">{{ row.status === 0 ? '禁用' : '启用' }}</el-button>
            <el-button link @click="openStock(row)">出入库</el-button>
            <el-button link @click="openRecords(row)">流水</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" layout="total, prev, pager, next" :total="total" :page-size="size"
        :current-page="page" @current-change="load" />
    </el-card>

    <el-dialog v-model="formVisible" :title="form.id ? '编辑礼品' : '新建礼品'" width="540px">
      <el-form label-width="110px">
        <el-form-item label="礼品编号">
          <el-input v-model="form.no" :disabled="!!form.id" placeholder="留空自动生成 MG 编号(商城同步用一等公民)" />
        </el-form-item>
        <el-form-item label="礼品名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="积分价" required>
          <el-input-number v-model="form.integralPrice" :min="1" :step="100" style="width: 100%" />
        </el-form-item>
        <el-form-item label="金额单价(元)">
          <el-input-number v-model="form.unitPriceYuan" :min="0" :precision="2" :step="10" style="width: 100%" />
        </el-form-item>
        <el-form-item label="算业绩">
          <el-switch v-model="form.isPrefor" active-text="算" inactive-text="不算" />
        </el-form-item>
        <el-form-item label="算库存">
          <el-switch v-model="form.isStock" active-text="算" inactive-text="不算" />
          <span class="hint" style="margin-left: 8px">算库存的礼品兑换时原子扣减</span>
        </el-form-item>
        <el-form-item label="计金额">
          <el-switch v-model="form.isPayment" active-text="计" inactive-text="不计" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="stockVisible" :title="`出入库 / 盘点 - ${stockGift?.name || ''}`" width="440px">
      <el-form label-width="90px">
        <el-form-item label="类型" required>
          <el-select v-model="stockForm.type" style="width: 100%">
            <el-option v-for="(label, key) in dicts.stockType || {}" :key="key" :label="label" :value="Number(key)" />
          </el-select>
        </el-form-item>
        <el-form-item label="数量" required>
          <el-input-number v-model="stockForm.num" :min="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="stockForm.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="stockVisible = false">取消</el-button>
        <el-button type="primary" @click="saveStock">确认</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="recordsVisible" :title="`库存流水 - ${recordsGift?.name || ''}`" size="640px">
      <el-table :data="recordRows" border size="small">
        <el-table-column prop="id" label="#" width="64" />
        <el-table-column label="类型" width="80">
          <template #default="{ row }">{{ dicts.stockType?.[String(row.type)] || row.type }}</template>
        </el-table-column>
        <el-table-column prop="subtype" label="动作" width="90" />
        <el-table-column prop="num" label="数量" width="70" />
        <el-table-column prop="after_num" label="结存" width="70" />
        <el-table-column prop="remark" label="备注" min-width="120" />
        <el-table-column prop="created_at" label="时间" width="160" />
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchAddons } from '../../api'
import {
  pageMemberGifts, createMemberGift, updateMemberGift, changeMemberGiftStatus,
  memberGiftStock, listMemberGiftRecords,
} from '../../api'

const dicts = ref({})
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = 20
const status = ref(null)
const keyword = ref('')
const formVisible = ref(false)
const form = reactive({})
const stockVisible = ref(false)
const stockGift = ref(null)
const stockForm = reactive({ type: 0, num: 1, remark: '' })
const recordsVisible = ref(false)
const recordsGift = ref(null)
const recordRows = ref([])

const yuan = (li) => (Number(li || 0) / 1000).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 3 })

async function loadDicts() {
  const res = await fetchAddons()
  const addon = (res.data || []).find((a) => a.id === 'member')
  if (addon?.dicts) dicts.value = addon.dicts
}

async function load(p) {
  const target = p || page.value
  const params = { page: target, size, keyword: keyword.value || undefined }
  if (status.value !== null && status.value !== '') params.status = status.value
  const res = await pageMemberGifts(params)
  rows.value = res.data.rows || []
  total.value = res.data.total || 0
  page.value = target
}

function openForm(row) {
  Object.assign(form, {
    id: row?.id, no: row?.no, name: row?.name,
    integralPrice: row?.integral_price ?? 1000,
    unitPriceYuan: row?.unit_price ? row.unit_price / 1000 : undefined,
    isPrefor: row ? row.is_prefor === 0 : true,
    isStock: row ? row.is_stock === 0 : true,
    isPayment: row ? row.is_payment === 0 : true,
    remark: row?.remark,
  })
  formVisible.value = true
}

function toPayload() {
  return {
    no: form.no, name: form.name, integralPrice: form.integralPrice,
    unitPrice: form.unitPriceYuan != null ? Math.round(form.unitPriceYuan * 1000) : null,
    isPrefor: form.isPrefor ? 0 : 1, isStock: form.isStock ? 0 : 1, isPayment: form.isPayment ? 0 : 1,
    remark: form.remark,
  }
}

async function save() {
  if (!form.name) return ElMessage.warning('请填写礼品名称')
  if (!form.integralPrice || form.integralPrice <= 0) return ElMessage.warning('积分价必须大于 0')
  if (form.id) await updateMemberGift(form.id, toPayload())
  else await createMemberGift(toPayload())
  ElMessage.success('已保存')
  formVisible.value = false
  await load()
}

async function onToggle(row) {
  await changeMemberGiftStatus(row.id, { status: row.status === 0 ? 1 : 0 })
  ElMessage.success('已变更')
  await load()
}

function openStock(row) {
  stockGift.value = row
  Object.assign(stockForm, { type: 0, num: 1, remark: '' })
  stockVisible.value = true
}

async function saveStock() {
  await memberGiftStock(stockGift.value.id, { type: stockForm.type, num: stockForm.num, remark: stockForm.remark })
  ElMessage.success('已入账')
  stockVisible.value = false
  await load()
}

async function openRecords(row) {
  recordsGift.value = row
  const res = await listMemberGiftRecords(row.id, { page: 1, size: 100 })
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
