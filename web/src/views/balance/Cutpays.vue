<template>
  <div>
    <div class="toolbar">
      <el-button type="primary" @click="poolVisible = true">新建扣款池</el-button>
      <el-button @click="cutpayVisible = true">新建扣款单</el-button>
    </div>

    <h4>扣款池</h4>
    <el-table :data="pools" border stripe v-loading="loading" size="small">
      <el-table-column prop="id" label="id" width="70" />
      <el-table-column prop="name" label="名称" min-width="140" />
      <el-table-column label="类型" width="100">
        <template #default="{ row }">{{ label('poolType', row.type) }}</template>
      </el-table-column>
      <el-table-column label="归属" width="110">
        <template #default="{ row }">{{ row.supplier || '通用' }}</template>
      </el-table-column>
      <el-table-column label="总额(元)" width="110" align="right">
        <template #default="{ row }">{{ yuan(row.total) }}</template>
      </el-table-column>
      <el-table-column label="余量(元)" width="110" align="right">
        <template #default="{ row }">{{ yuan(row.surplus) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag effect="plain" :type="row.status === 0 ? 'success' : 'info'">
            {{ label('poolStatus', row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button link :type="row.status === 0 ? 'danger' : 'success'" @click="onTogglePool(row)">
            {{ row.status === 0 ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <h4>扣款单</h4>
    <el-table :data="cutpays" border stripe v-loading="loading" size="small">
      <el-table-column prop="id" label="id" width="70" />
      <el-table-column prop="pool_name" label="扣款池" min-width="130" />
      <el-table-column label="金额(元)" width="110" align="right">
        <template #default="{ row }">{{ yuan(row.money) }}</template>
      </el-table-column>
      <el-table-column label="类型" width="90">
        <template #default="{ row }">{{ label('cutpayType', row.type) }}</template>
      </el-table-column>
      <el-table-column label="结算单" width="90">
        <template #default="{ row }">{{ row.settlement_id || '-' }}</template>
      </el-table-column>
      <el-table-column label="子流水(元)" width="110" align="right">
        <template #default="{ row }">{{ yuan(row.deduct_total) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag effect="plain" :type="row.status === 1 ? 'success' : 'info'">
            {{ label('cutpayStatus', row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 0" link type="primary" @click="onBind(row)">绑定结算单</el-button>
          <el-button v-if="row.status === 1" link type="warning" @click="onDeduct(row)">拆子流水</el-button>
          <el-button v-if="row.status === 1" link type="danger" @click="onUnbind(row)">解绑</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination layout="prev, pager, next, total" :total="cutpayTotal" :page-size="cutpayFilters.size"
      :current-page="cutpayFilters.page" @current-change="(p) => { cutpayFilters.page = p; load() }"
      style="margin-top: 12px" />

    <el-dialog v-model="poolVisible" title="新建扣款池" width="460px">
      <el-form label-width="90px">
        <el-form-item label="名称"><el-input v-model="poolForm.name" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="poolForm.type" style="width: 100%">
            <el-option v-for="d in dicts.poolType" :key="d.key" :label="d.label" :value="Number(d.key)" />
          </el-select>
        </el-form-item>
        <el-form-item label="归属厂商"><el-input v-model="poolForm.supplier" placeholder="空=通用池" /></el-form-item>
        <el-form-item label="初始额度(元)"><el-input v-model="poolForm.total" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="poolVisible = false">取消</el-button>
        <el-button type="primary" @click="onCreatePool">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="cutpayVisible" title="新建扣款单(待绑定)" width="460px">
      <el-form label-width="90px">
        <el-form-item label="扣款池">
          <el-select v-model="cutpayForm.poolId" style="width: 100%">
            <el-option v-for="p in activePools" :key="p.id" :label="p.name + '(余' + yuan(p.surplus) + ')'" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="金额(元)"><el-input v-model="cutpayForm.money" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="cutpayForm.memo" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cutpayVisible = false">取消</el-button>
        <el-button type="primary" @click="onCreateCutpay">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { addons } from '../../store/addons'
import {
  pageCutpayPools, createCutpayPool, updateCutpayPoolStatus,
  pageCutpays, createCutpay, bindCutpay, unbindCutpay, deductCutpay
} from '../../api'

const dicts = reactive({ poolType: [], poolStatus: [], cutpayType: [], cutpayStatus: [] })
const pools = ref([])
const cutpays = ref([])
const cutpayTotal = ref(0)
const loading = ref(false)
const poolVisible = ref(false)
const cutpayVisible = ref(false)
const poolForm = reactive({ name: '', type: 0, supplier: '', total: '' })
const cutpayForm = reactive({ poolId: null, money: '', memo: '' })
const cutpayFilters = reactive({ page: 1, size: 20 })
const activePools = computed(() => pools.value.filter((p) => p.status === 0))

const yuan = (li) => (Number(li || 0) / 1000).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 3 })
const label = (dict, v) => {
  const hit = (dicts[dict] || []).find((d) => d.key === String(v))
  return hit ? hit.label : v
}

async function load() {
  loading.value = true
  try {
    const [poolsRes, cutpaysRes] = await Promise.all([
      pageCutpayPools({ page: 1, size: 100 }),
      pageCutpays({ page: cutpayFilters.page, size: cutpayFilters.size })
    ])
    pools.value = (poolsRes.data && poolsRes.data.rows) || []
    cutpays.value = (cutpaysRes.data && cutpaysRes.data.rows) || []
    cutpayTotal.value = (cutpaysRes.data && cutpaysRes.data.total) || 0
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

async function onCreatePool() {
  try {
    await createCutpayPool({
      name: poolForm.name || undefined, type: poolForm.type,
      supplier: poolForm.supplier || undefined, total: Math.round(Number(poolForm.total) * 1000)
    })
    ElMessage.success('已创建')
    poolVisible.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '创建失败')
  }
}

async function onTogglePool(row) {
  try {
    await updateCutpayPoolStatus(row.id, { status: row.status === 0 ? 1 : 0 })
    ElMessage.success(row.status === 0 ? '已停用' : '已启用')
    load()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function onCreateCutpay() {
  try {
    await createCutpay({ poolId: cutpayForm.poolId, money: Math.round(Number(cutpayForm.money) * 1000), memo: cutpayForm.memo || undefined })
    ElMessage.success('已创建,待绑定')
    cutpayVisible.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '创建失败')
  }
}

async function onBind(row) {
  try {
    const { value } = await ElMessageBox.prompt('结算单 id', '绑定扣款单 → 结算单', { type: 'warning' })
    await bindCutpay(row.id, { settlementId: Number(value) })
    ElMessage.success('已绑定(池与结算单余量双向冲抵)')
    load()
  } catch (e) {
    if (e?.message) ElMessage.error(e.message)
  }
}

async function onUnbind(row) {
  try {
    const { value } = await ElMessageBox.prompt('解绑原因(可空)', '解绑回补(池+结算单)', {})
    await unbindCutpay(row.id, { reason: value || undefined })
    ElMessage.success('已解绑')
    load()
  } catch (e) {
    if (e?.message) ElMessage.error(e.message)
  }
}

async function onDeduct(row) {
  try {
    const { value } = await ElMessageBox.prompt('子流水金额(元,Σ不得超扣款额)', '拆子流水(仅留痕)', { type: 'warning' })
    await deductCutpay(row.id, { money: Math.round(Number(value) * 1000) })
    ElMessage.success('已留痕')
    load()
  } catch (e) {
    if (e?.message) ElMessage.error(e.message)
  }
}

onMounted(() => {
  const balance = addons.list.find((a) => a.id === 'balance')
  const d = (balance && balance.dicts) || {}
  dicts.poolType = d.poolType || []
  dicts.poolStatus = d.poolStatus || []
  dicts.cutpayType = d.cutpayType || []
  dicts.cutpayStatus = d.cutpayStatus || []
  load()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; }
h4 { margin: 14px 0 8px; }
</style>
