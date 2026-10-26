<template>
  <div>
    <div class="toolbar">
      <el-select v-model="filters.status" placeholder="状态" style="width: 110px" clearable>
        <el-option v-for="d in dicts.purchaseStatus" :key="d.key" :label="d.label" :value="Number(d.key)" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <el-button @click="openCreate">采购申请</el-button>
      <span class="tip">父单链分批入库:每批一张入库子单,全部入库后父单自动完成</span>
    </div>

    <el-table :data="rows" border stripe v-loading="loading" @row-click="openDetail">
      <el-table-column label="礼品" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.gift_name }}
          <el-tag v-if="row.father_id" size="small" effect="plain">入库批</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="num" label="数量" width="90" align="right" />
      <el-table-column label="剩余待入库" width="100" align="right">
        <template #default="{ row }">{{ row.father_id ? '—' : row.surplus }}</template>
      </el-table-column>
      <el-table-column label="进价" width="100" align="right">
        <template #default="{ row }">{{ row.inprice != null ? '¥' + yuan(row.inprice) : '—' }}</template>
      </el-table-column>
      <el-table-column label="结算价" width="100" align="right">
        <template #default="{ row }">{{ row.baprice != null ? '¥' + yuan(row.baprice) : '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" effect="plain">{{ dictLabel('purchaseStatus', row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="purchase_time" label="采购时间" width="150" />
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 0 && !row.father_id" link size="small" type="primary"
            @click.stop="onAct(row, 'purchase', '已登记采购')">采购</el-button>
          <el-button v-if="row.status === 1 && !row.father_id" link size="small" type="success"
            @click.stop="openInstore(row)">入库</el-button>
          <el-button v-if="row.status === 2 && row.father_id" link size="small" type="warning"
            @click.stop="openBalance(row)">结算</el-button>
          <el-button v-if="row.status === 0 && !row.father_id" link size="small" type="danger"
            @click.stop="onAct(row, 'cancel', '已失效')">失效</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination layout="prev, pager, next, total" :total="total" :page-size="filters.size"
      :current-page="filters.page" @current-change="(p) => { filters.page = p; load() }" style="margin-top: 12px" />

    <!-- 采购申请 -->
    <el-dialog v-model="createDialog" title="采购申请" width="480px">
      <el-form label-width="90px">
        <el-form-item label="礼品" required>
          <el-select v-model="form.giftId" filterable remote :remote-method="searchGifts"
            :loading="giftLoading" placeholder="搜索礼品" style="width: 100%">
            <el-option v-for="g in giftOptions" :key="g.id"
              :label="g.name + '(库存 ' + g.stock_num + ')'" :value="g.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="数量" required>
          <el-input-number v-model="form.num" :min="1" :step="10" />
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onCreate">提交</el-button>
      </template>
    </el-dialog>

    <!-- 入库 -->
    <el-dialog v-model="instoreDialog" title="分批入库" width="440px">
      <el-form label-width="90px">
        <el-form-item label="礼品">{{ instoreTarget && instoreTarget.gift_name }}</el-form-item>
        <el-form-item label="剩余待入库">{{ instoreTarget && instoreTarget.surplus }}</el-form-item>
        <el-form-item label="本次入库" required>
          <el-input-number v-model="instoreForm.num" :min="1" :max="instoreTarget ? instoreTarget.surplus : 0" />
        </el-form-item>
        <el-form-item label="进价(元)">
          <el-input-number v-model="instoreForm.inpriceYuan" :min="0" :precision="3" />
        </el-form-item>
      </el-form>
      <div class="tip">入库即回加礼品库存(is_stock=0 算库存的礼品)并记采购流水;本批=剩余量时父单自动完成。</div>
      <template #footer>
        <el-button @click="instoreDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onInstore">入库</el-button>
      </template>
    </el-dialog>

    <!-- 结算 -->
    <el-dialog v-model="balanceDialog" title="采购结算" width="420px">
      <el-form label-width="90px">
        <el-form-item label="进价">¥{{ balanceTarget && yuan(balanceTarget.inprice) }}</el-form-item>
        <el-form-item label="结算价(元)" required>
          <el-input-number v-model="balanceForm.bapriceYuan" :min="0" :precision="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="balanceDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onBalance">确认结算</el-button>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <el-drawer v-model="detailDrawer" size="520px" :title="detail ? '采购单 #' + detail.id : '采购单'">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="礼品" :span="2">{{ detail.gift_name }}({{ detail.gift_no }})</el-descriptions-item>
          <el-descriptions-item label="状态">{{ dictLabel('purchaseStatus', detail.status) }}</el-descriptions-item>
          <el-descriptions-item label="数量">{{ detail.num }}</el-descriptions-item>
          <el-descriptions-item label="剩余待入库">{{ detail.surplus }}</el-descriptions-item>
          <el-descriptions-item label="备注">{{ detail.remark || '—' }}</el-descriptions-item>
        </el-descriptions>
        <div class="section-title" style="margin-top: 14px">入库批次</div>
        <el-table :data="detail.children || []" border size="small">
          <el-table-column prop="id" label="批号" width="70" />
          <el-table-column prop="num" label="数量" width="80" align="right" />
          <el-table-column label="进价" width="100" align="right">
            <template #default="{ row }">{{ row.inprice != null ? '¥' + yuan(row.inprice) : '—' }}</template>
          </el-table-column>
          <el-table-column label="结算" width="90">
            <template #default="{ row }">{{ dictLabel('purchaseStatus', row.status) }}</template>
          </el-table-column>
          <el-table-column prop="instore_time" label="入库时间" width="150" />
        </el-table>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, ref, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  pageGiftPurchases, fetchGiftPurchase, createGiftPurchase,
  purchaseGift, instoreGiftPurchase, balanceGiftPurchase, cancelGiftPurchase,
  pageMemberGifts
} from '../../api'
import { addons } from '../../store/addons'

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const filters = reactive({ status: null, page: 1, size: 20 })
const dicts = ref({ purchaseStatus: [] })

const createDialog = ref(false)
const form = ref({ giftId: null, num: 10, remark: '' })
const giftOptions = ref([])
const giftLoading = ref(false)
const instoreDialog = ref(false)
const instoreTarget = ref(null)
const instoreForm = ref({ num: 1, inpriceYuan: 0 })
const balanceDialog = ref(false)
const balanceTarget = ref(null)
const balanceForm = ref({ bapriceYuan: 0 })
const detailDrawer = ref(false)
const detail = ref(null)

const yuan = (li) => (Number(li || 0) / 1000).toLocaleString('zh-CN', { minimumFractionDigits: 3 })
const statusTag = (s) => ({ 0: 'info', 1: 'warning', 2: 'success', 3: 'success', 4: 'danger', 5: 'success' })[s] ?? 'info'

function dictLabel(name, value) {
  const hit = (dicts.value[name] || []).find((d) => d.key === String(value))
  return hit ? hit.label : (value ?? '—')
}

async function load() {
  loading.value = true
  try {
    const res = await pageGiftPurchases(filters)
    rows.value = (res.data && res.data.rows) || []
    total.value = (res.data && res.data.total) || 0
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.value = { giftId: null, num: 10, remark: '' }
  giftOptions.value = []
  createDialog.value = true
}

async function searchGifts(keyword) {
  giftLoading.value = true
  try {
    const res = await pageMemberGifts({ keyword: keyword || '', status: 0, page: 1, size: 20 })
    giftOptions.value = (res.data && res.data.rows) || []
  } finally {
    giftLoading.value = false
  }
}

async function onCreate() {
  if (!form.value.giftId || !form.value.num) return ElMessage.warning('请补全礼品与数量')
  saving.value = true
  try {
    await createGiftPurchase(form.value)
    ElMessage.success('采购申请已登记')
    createDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '登记失败')
  } finally {
    saving.value = false
  }
}

function openInstore(row) {
  instoreTarget.value = row
  instoreForm.value = { num: row.surplus, inpriceYuan: 0 }
  instoreDialog.value = true
}

async function onInstore() {
  saving.value = true
  try {
    await instoreGiftPurchase(instoreTarget.value.id, {
      num: instoreForm.value.num,
      inprice: Math.round(Number(instoreForm.value.inpriceYuan) * 1000)
    })
    ElMessage.success('入库完成')
    instoreDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '入库失败')
  } finally {
    saving.value = false
  }
}

function openBalance(row) {
  balanceTarget.value = row
  balanceForm.value = { bapriceYuan: Number(row.inprice || 0) / 1000 }
  balanceDialog.value = true
}

async function onBalance() {
  saving.value = true
  try {
    await balanceGiftPurchase(balanceTarget.value.id, {
      baprice: Math.round(Number(balanceForm.value.bapriceYuan) * 1000)
    })
    ElMessage.success('已结算')
    balanceDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '结算失败')
  } finally {
    saving.value = false
  }
}

async function onAct(row, action, done) {
  try {
    await ElMessageBox.confirm(`确认${done}?`, '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    if (action === 'purchase') await purchaseGift(row.id)
    else await cancelGiftPurchase(row.id)
    ElMessage.success(done)
    load()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function openDetail(row) {
  const res = await fetchGiftPurchase(row.id)
  detail.value = res.data
  detailDrawer.value = true
}

onMounted(() => {
  const member = addons.list.find((a) => a.id === 'member')
  const d = (member && member.dicts) || {}
  dicts.value = d
  load()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; align-items: center; }
.tip { color: var(--yz-ink-50, #8a887f); font-size: 12px; }
.section-title { font-weight: 600; margin-bottom: 6px; border-left: 3px solid var(--yz-cinnabar, #b03a2e); padding-left: 8px; }
</style>
