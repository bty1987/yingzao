<template>
  <div>
    <div class="toolbar">
      <el-input v-model="filters.keyword" placeholder="项目名" style="width: 180px" clearable @keyup.enter="load" />
      <el-select v-model="filters.status" placeholder="状态" style="width: 100px" clearable>
        <el-option v-for="d in dicts.catalogStatus" :key="d.key" :label="d.label" :value="Number(d.key)" />
      </el-select>
      <el-select v-model="filters.subtype" placeholder="分类" style="width: 100px" clearable>
        <el-option v-for="d in dicts.itemSubtype" :key="d.key" :label="d.label" :value="Number(d.key)" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <el-button @click="openForm(null)">新建报价项</el-button>
    </div>

    <el-table :data="rows" border stripe v-loading="loading">
      <el-table-column prop="name" label="项目名" min-width="160" show-overflow-tooltip />
      <el-table-column label="分类" width="80">
        <template #default="{ row }">{{ dictLabel('itemSubtype', row.subtype) }}</template>
      </el-table-column>
      <el-table-column prop="unit" label="单位" width="70" />
      <el-table-column label="当前单价" width="120" align="right">
        <template #default="{ row }">¥{{ yuan(row.price) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 0 ? 'success' : 'info'" effect="plain">
            {{ dictLabel('catalogStatus', row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip />
      <el-table-column prop="created_at" label="建档时间" width="150" />
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openPrice(row)">调价</el-button>
          <el-button size="small" @click="openPrices(row)">价格历史</el-button>
          <el-button size="small" @click="openForm(row)">编辑</el-button>
          <el-button size="small" :type="row.status === 0 ? 'warning' : 'success'" @click="onToggle(row)">
            {{ row.status === 0 ? '停用' : '启用' }}</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination layout="prev, pager, next, total" :total="total" :page-size="filters.size"
      :current-page="filters.page" @current-change="(p) => { filters.page = p; load() }" style="margin-top: 12px" />

    <!-- 新建/编辑 -->
    <el-dialog v-model="formDialog" :title="form.id ? '编辑报价项' : '新建报价项'" width="480px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="项目名">
          <el-input v-model="form.name" placeholder="如 墙面乳胶漆" />
        </el-form-item>
        <el-form-item label="分类">
          <el-radio-group v-model="form.subtype">
            <el-radio v-for="d in dicts.itemSubtype" :key="d.key" :value="Number(d.key)">{{ d.label }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="单位">
          <el-input v-model="form.unit" placeholder="m²/项/套" style="width: 120px" />
        </el-form-item>
        <el-form-item v-if="!form.id" label="初始单价">
          <el-input-number v-model="form.priceYuan" :min="0" :precision="3" :step="10" />
          <span class="tip" style="margin-left: 8px">建档即写入首条价格快照;调价走「调价」动作</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 调价 -->
    <el-dialog v-model="priceDialog" title="调价(写入新价格快照)" width="420px">
      <el-form label-width="90px">
        <el-form-item label="项目">{{ priceTarget && priceTarget.name }}</el-form-item>
        <el-form-item label="当前单价">¥{{ priceTarget && yuan(priceTarget.price) }}</el-form-item>
        <el-form-item label="新单价">
          <el-input-number v-model="priceForm.priceYuan" :min="0" :precision="3" :step="10" />
        </el-form-item>
      </el-form>
      <div class="tip" style="margin-bottom: 12px">历史快照 append-only:调价写入新快照并同步当前价;与当前同价不写入。</div>
      <template #footer>
        <el-button @click="priceDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onPrice">确认调价</el-button>
      </template>
    </el-dialog>

    <!-- 价格历史 -->
    <el-drawer v-model="pricesDrawer" size="480px" :title="priceTarget ? `价格历史 · ${priceTarget.name}` : '价格历史'">
      <el-table :data="prices" size="small" v-loading="pricesLoading">
        <el-table-column label="生效时点" width="160">
          <template #default="{ row }">{{ row.created_at }}</template>
        </el-table-column>
        <el-table-column label="单价" width="110" align="right">
          <template #default="{ row }">¥{{ yuan(row.price) }}</template>
        </el-table-column>
        <el-table-column prop="operator_name" label="操作人" width="90" />
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, ref, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  pageQuotationCatalog, createQuotationCatalog, updateQuotationCatalog,
  priceQuotationCatalog, statusQuotationCatalog, listQuotationCatalogPrices
} from '../../api'
import { addons } from '../../store/addons'

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const filters = reactive({ keyword: '', status: null, subtype: null, page: 1, size: 20 })
const dicts = reactive({ catalogStatus: [], itemSubtype: [] })

const formDialog = ref(false)
const form = ref({ id: null, name: '', subtype: 0, unit: '', priceYuan: 0, remark: '' })
const priceDialog = ref(false)
const priceTarget = ref(null)
const priceForm = ref({ priceYuan: 0 })
const pricesDrawer = ref(false)
const prices = ref([])
const pricesLoading = ref(false)

const yuan = (li) => (Number(li || 0) / 1000).toLocaleString('zh-CN', { minimumFractionDigits: 3 })

function dictLabel(name, value) {
  const hit = (dicts[name] || []).find((d) => d.key === String(value))
  return hit ? hit.label : (value ?? '—')
}

async function load() {
  loading.value = true
  try {
    const res = await pageQuotationCatalog(filters)
    rows.value = (res.data && res.data.rows) || []
    total.value = (res.data && res.data.total) || 0
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

function openForm(row) {
  form.value = row
    ? { id: row.id, name: row.name, subtype: row.subtype, unit: row.unit || '', priceYuan: 0, remark: row.remark || '' }
    : { id: null, name: '', subtype: 0, unit: '', priceYuan: 0, remark: '' }
  formDialog.value = true
}

async function onSave() {
  if (!form.value.name) return ElMessage.warning('项目名必填')
  saving.value = true
  try {
    const body = {
      name: form.value.name, subtype: form.value.subtype, unit: form.value.unit || null,
      remark: form.value.remark || null,
      price: form.value.id ? null : Math.round(Number(form.value.priceYuan) * 1000)
    }
    if (form.value.id) {
      await updateQuotationCatalog(form.value.id, body)
      ElMessage.success('已保存(单价走「调价」写入快照)')
    } else {
      await createQuotationCatalog(body)
      ElMessage.success('报价项已建档')
    }
    formDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

function openPrice(row) {
  priceTarget.value = row
  priceForm.value = { priceYuan: Number(row.price || 0) / 1000 }
  priceDialog.value = true
}

async function onPrice() {
  saving.value = true
  try {
    await priceQuotationCatalog(priceTarget.value.id,
      { price: Math.round(Number(priceForm.value.priceYuan) * 1000) })
    ElMessage.success('已调价并写入快照')
    priceDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '调价失败')
  } finally {
    saving.value = false
  }
}

async function openPrices(row) {
  priceTarget.value = row
  pricesDrawer.value = true
  pricesLoading.value = true
  try {
    const res = await listQuotationCatalogPrices(row.id)
    prices.value = res.data || []
  } finally {
    pricesLoading.value = false
  }
}

async function onToggle(row) {
  const to = row.status === 0 ? 1 : 0
  const label = to === 1 ? '停用' : '启用'
  try {
    await ElMessageBox.confirm(
      to === 1 ? `停用「${row.name}」后不可再挂接到报价明细(已有挂接行的重算不受影响)。`
        : `启用「${row.name}」?`,
      `${label}报价项`, { type: 'warning' })
  } catch {
    return
  }
  try {
    await statusQuotationCatalog(row.id, { status: to })
    ElMessage.success(`已${label}`)
    load()
  } catch (e) {
    ElMessage.error(e.message || `${label}失败`)
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
</style>
