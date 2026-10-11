<template>
  <div>
    <div class="toolbar">
      <el-input v-model="filters.bizId" placeholder="业务单号" style="width: 160px" clearable @keyup.enter="load" />
      <el-select v-model="filters.status" placeholder="状态" style="width: 120px" clearable>
        <el-option v-for="d in dicts.billStatus" :key="d.key" :label="d.label" :value="Number(d.key)" />
      </el-select>
      <el-select v-model="filters.direction" placeholder="方向" style="width: 110px" clearable>
        <el-option v-for="d in dicts.billDirection" :key="d.key" :label="d.label" :value="Number(d.key)" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <el-button @click="openCreate">登记款项单</el-button>
      <el-button @click="typeDialog = true">类型字典</el-button>
    </div>

    <el-table :data="rows" border stripe v-loading="loading" @row-click="openDetail">
      <el-table-column prop="id" label="款项号" width="80" />
      <el-table-column prop="type_name" label="款项类型" width="120" />
      <el-table-column label="方向" width="80">
        <template #default="{ row }">
          <el-tag :type="row.direction === 1 ? 'danger' : 'success'" effect="plain">
            {{ dirLabel(row.direction) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="金额(元)" width="120" align="right">
        <template #default="{ row }">{{ yuan(row.amount) }}</template>
      </el-table-column>
      <el-table-column label="业务对象" min-width="200">
        <template #default="{ row }">
          <span class="mono">{{ row.biz_addon }}/{{ row.biz_type }}/{{ row.biz_id }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="sort" label="顺序" width="70" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTag(row)" effect="plain">{{ statusLabel(row) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="created_at" label="创建时间" width="170" />
      <el-table-column label="操作" width="200">
        <template #default="{ row }">
          <el-button v-if="row.direction === 0 && row.status === 0" type="primary" link @click.stop="openCollect(row)">收款</el-button>
          <el-button v-if="row.direction === 0 && row.status === 1" type="success" link @click.stop="onConfirm(row)">确认</el-button>
          <el-button v-if="row.direction === 0 && [1, 2].includes(row.status)" type="warning" link @click.stop="openRefund(row)">退款</el-button>
          <el-button v-if="row.direction === 0 && row.status === 0" type="danger" link @click.stop="onCancel(row)">作废</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination layout="prev, pager, next, total" :total="total" :page-size="filters.size"
      :current-page="filters.page" @current-change="(p) => { filters.page = p; load() }" style="margin-top: 12px" />

    <!-- 登记款项单 -->
    <el-dialog v-model="createDialog" title="登记款项单(应收)" width="520px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="款项类型">
          <el-select v-model="form.typeCode" style="width: 100%">
            <el-option v-for="t in enabledTypes" :key="t.code" :label="t.name + '(顺序 ' + t.sort + ')'"
              :value="t.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="业务插件">
          <el-input v-model="form.bizAddon" placeholder="如 decoration" />
        </el-form-item>
        <el-form-item label="业务类型">
          <el-input v-model="form.bizType" placeholder="如 order" />
        </el-form-item>
        <el-form-item label="业务单号">
          <el-input v-model="form.bizId" />
        </el-form-item>
        <el-form-item label="应收金额">
          <el-input-number v-model="form.yuan" :min="0.001" :precision="3" :step="100" />
          <span class="tip">元(厘存储,三位小数)</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onCreate">提交</el-button>
      </template>
    </el-dialog>

    <!-- 收款:多渠道动态行 -->
    <el-dialog v-model="collectDialog" title="收款(渠道合计须等于应收)" width="560px">
      <el-alert :title="'应收 ' + yuan(current.amount) + ' 元,渠道行合计须相等'" type="info" :closable="false"
        style="margin-bottom: 12px" />
      <div v-for="(line, i) in channelLines" :key="i" class="channel-line">
        <el-select v-model="line.channelCode" style="width: 200px" placeholder="渠道">
          <el-option v-for="d in dicts.channel" :key="d.key" :label="d.label" :value="d.key" />
        </el-select>
        <el-input-number v-model="line.yuan" :min="0.001" :precision="3" :step="100" />
        <el-button v-if="channelLines.length > 1" type="danger" link @click="channelLines.splice(i, 1)">删除</el-button>
      </div>
      <el-button link type="primary" @click="channelLines.push({ channelCode: 'cash', yuan: 0 })">+ 加渠道行</el-button>
      <div class="tip" style="margin-top: 8px">
        合计 {{ yuan(channelSum) }} 元
        <el-tag v-if="channelSum === current.amount" type="success" size="small">平衡</el-tag>
        <el-tag v-else type="danger" size="small">不平衡</el-tag>
      </div>
      <template #footer>
        <el-button @click="collectDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="channelSum !== current.amount"
          @click="onCollect">确认收款</el-button>
      </template>
    </el-dialog>

    <!-- 退款(走内核审批) -->
    <el-dialog v-model="refundDialog" title="发起退款(提交内核审批)" width="480px">
      <el-form label-width="90px">
        <el-form-item label="审批人">
          <el-select v-model="refundForm.assigneeId" style="width: 100%">
            <el-option v-for="u in users" :key="u.id" :label="u.display_name" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="退款金额">
          <span>¥{{ yuan(current.amount) }}(V1 整单退款)</span>
        </el-form-item>
        <el-form-item label="退款事由">
          <el-input v-model="refundForm.reason" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="refundDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onRefund">提交审批</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉:收据(双人留痕+渠道行)与退款单 -->
    <el-drawer v-model="detailDrawer" title="款项详情" size="560px">
      <template v-if="detail">
        <h4 class="section">款项单</h4>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="款项号">{{ detail.id }}</el-descriptions-item>
          <el-descriptions-item label="类型">{{ detail.type_name }}</el-descriptions-item>
          <el-descriptions-item label="金额">{{ yuan(detail.amount) }} 元</el-descriptions-item>
          <el-descriptions-item label="状态">{{ statusLabel(detail) }}</el-descriptions-item>
          <el-descriptions-item label="业务对象" :span="2">
            <span class="mono">{{ detail.biz_addon }}/{{ detail.biz_type }}/{{ detail.biz_id }}</span>
          </el-descriptions-item>
        </el-descriptions>
        <h4 class="section">收据(双人留痕)</h4>
        <el-empty v-if="!(detail.receipts || []).length" description="未收款" :image-size="60" />
        <div v-for="r in detail.receipts || []" :key="r.id" class="receipt">
          <div>
            <b>{{ r.receipt_no }}</b>({{ r.direction === 1 ? '退款' : '收款' }} ¥{{ yuan(r.amount) }})
          </div>
          <div class="tip">
            执行:{{ r.collector_name }} @ {{ r.collect_time }}
            <template v-if="r.confirmer_name">| 确认:{{ r.confirmer_name }} @ {{ r.confirm_time }}</template>
            <template v-else-if="r.direction === 0">| 待财务确认</template>
          </div>
          <div class="tip">渠道:
            <el-tag v-for="(c, i) in r.channels || []" :key="i" size="small" effect="plain" style="margin-right: 6px">
              {{ channelLabel(c.channel_code) }} ¥{{ yuan(c.amount) }}
            </el-tag>
          </div>
        </div>
        <h4 class="section">退款单</h4>
        <el-empty v-if="!(detail.refunds || []).length" description="无退款记录" :image-size="60" />
        <div v-for="rf in detail.refunds || []" :key="rf.id" class="receipt">
          <div>退款单 #{{ rf.id }} ¥{{ yuan(rf.amount) }}
            <el-tag size="small" effect="plain"
              :type="rf.status === 3 ? 'success' : rf.status === 0 ? 'warning' : 'info'">
              {{ refundLabel(rf.status) }}
            </el-tag>
          </div>
          <div class="tip" v-if="rf.origin_status_snapshot != null">原款项退款时状态快照:{{ rf.origin_status_snapshot }}</div>
        </div>
      </template>
    </el-drawer>

    <!-- 类型字典管理 -->
    <el-dialog v-model="typeDialog" title="款项类型字典(组织级,code 协议键)" width="680px">
      <el-table :data="types" border size="small">
        <el-table-column prop="code" label="编码" width="120" />
        <el-table-column prop="name" label="名称" width="140" />
        <el-table-column prop="sort" label="顺序" width="70" />
        <el-table-column prop="scale_template" label="比例模板" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 0 ? 'success' : 'info'" size="small">
              {{ row.status === 0 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <el-button link @click="toggleType(row)">{{ row.status === 0 ? '停用' : '启用' }}</el-button>
            <el-button link type="primary" @click="editTypeTemplate(row)">编辑模板</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="new-type">
        <el-input v-model="newType.code" placeholder="编码(小写字母开头)" style="width: 180px" />
        <el-input v-model="newType.name" placeholder="名称" style="width: 140px" />
        <el-input-number v-model="newType.sort" :min="1" />
        <el-button type="primary" @click="onCreateType">新增类型</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref, reactive, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  pageBills, fetchBill, createBill, collectBill, confirmBill, refundBill, cancelBill,
  listBillTypes, createBillType, updateBillTypeStatus, listOrgUsers
} from '../../api'
import { addons } from '../../store/addons'

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const filters = reactive({ bizId: '', status: null, direction: null, page: 1, size: 20 })

const dicts = reactive({ billStatus: [], billDirection: [], refundStatus: [], channel: [] })
const types = ref([])
const users = ref([])

const createDialog = ref(false)
const collectDialog = ref(false)
const refundDialog = ref(false)
const detailDrawer = ref(false)
const typeDialog = ref(false)
const current = ref({})
const detail = ref(null)
const channelLines = ref([{ channelCode: 'cash', yuan: 0 }])
const form = ref({ typeCode: null, bizAddon: '', bizType: '', bizId: '', yuan: 0, remark: '' })
const refundForm = ref({ assigneeId: null, reason: '' })
const newType = reactive({ code: '', name: '', sort: 1 })

const enabledTypes = computed(() => types.value.filter((t) => t.status === 0))
const channelSum = computed(() =>
  channelLines.value.reduce((s, l) => s + Math.round((Number(l.yuan) || 0) * 1000), 0))

const yuan = (li) => (Number(li || 0) / 1000).toLocaleString('zh-CN', { minimumFractionDigits: 3 })
const statusLabel = (row) => {
  const key = row.direction === 1 ? 'refundStatus' : 'billStatus'
  const hit = (dicts[key] || []).find((d) => d.key === String(row.status))
  return hit ? hit.label : row.status
}
const statusTag = (row) => ({ 0: 'info', 1: 'warning', 2: 'success', 3: 'danger', 4: 'info' })[row.status] ?? 'info'
const dirLabel = (d) => (dicts.billDirection.find((x) => x.key === String(d)) || {}).label || d
const refundLabel = (s) => (dicts.refundStatus.find((x) => x.key === String(s)) || {}).label || s
const channelLabel = (c) => (dicts.channel.find((x) => x.key === c) || {}).label || c

async function load() {
  loading.value = true
  try {
    const res = await pageBills({ ...filters, bizId: filters.bizId || undefined })
    rows.value = (res.data && res.data.rows) || []
    total.value = (res.data && res.data.total) || 0
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

async function loadTypes() {
  const res = await listBillTypes({ includeDisabled: true })
  types.value = res.data || []
}

function openCreate() {
  form.value = { typeCode: null, bizAddon: '', bizType: '', bizId: '', yuan: 0, remark: '' }
  createDialog.value = true
}

async function onCreate() {
  const f = form.value
  if (!f.typeCode || !f.bizAddon || !f.bizType || !f.bizId || !(f.yuan > 0)) {
    return ElMessage.warning('请补全类型/业务三元组/金额')
  }
  saving.value = true
  try {
    await createBill({ ...f, amount: Math.round(f.yuan * 1000) })
    ElMessage.success('款项单已登记')
    createDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '登记失败')
  } finally {
    saving.value = false
  }
}

function openCollect(row) {
  current.value = row
  channelLines.value = [{ channelCode: 'cash', yuan: Number(row.amount) / 1000 }]
  collectDialog.value = true
}

async function onCollect() {
  saving.value = true
  try {
    const channels = channelLines.value
      .filter((l) => l.yuan > 0)
      .map((l) => ({ channelCode: l.channelCode, amount: Math.round(l.yuan * 1000) }))
    const res = await collectBill(current.value.id, channels)
    ElMessage.success('已收款,收据 ' + ((res.data && res.data.receiptNo) || ''))
    collectDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '收款失败')
  } finally {
    saving.value = false
  }
}

async function onConfirm(row) {
  try {
    await confirmBill(row.id)
    ElMessage.success('已确认(终态)')
    load()
  } catch (e) {
    ElMessage.error(e.message || '确认失败')
  }
}

function openRefund(row) {
  current.value = row
  refundForm.value = { assigneeId: null, reason: '' }
  if (users.value.length === 0) {
    listOrgUsers().then((res) => { users.value = res.data || [] })
  }
  refundDialog.value = true
}

async function onRefund() {
  if (!refundForm.value.assigneeId) return ElMessage.warning('请选择退款审批人')
  saving.value = true
  try {
    await refundBill(current.value.id, refundForm.value)
    ElMessage.success('退款审批已提交')
    refundDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '提交失败')
  } finally {
    saving.value = false
  }
}

async function onCancel(row) {
  try {
    await ElMessageBox.confirm('确定作废该未收款项单?', '作废', { type: 'warning' })
  } catch (e) {
    return
  }
  try {
    await cancelBill(row.id)
    ElMessage.success('已作废')
    load()
  } catch (e) {
    ElMessage.error(e.message || '作废失败')
  }
}

async function openDetail(row) {
  const res = await fetchBill(row.id)
  detail.value = res.data
  detailDrawer.value = true
}

async function toggleType(row) {
  try {
    await updateBillTypeStatus(row.id, { status: row.status === 0 ? 1 : 0 })
    loadTypes()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function editTypeTemplate(row) {
  const { value } = await ElMessageBox.prompt('比例模板 JSON([{sort,scale}],留空=不拆)', '编辑 ' + row.code,
    { inputValue: row.scale_template || '' })
  await updateBillType(row.id, { name: row.name, sort: row.sort, scaleTemplate: value || null })
  loadTypes()
}

async function onCreateType() {
  if (!newType.code || !newType.name) return ElMessage.warning('请填编码与名称')
  try {
    await createBillType({ ...newType })
    newType.code = ''
    newType.name = ''
    loadTypes()
  } catch (e) {
    ElMessage.error(e.message || '新增失败')
  }
}

onMounted(async () => {
  const payment = addons.list.find((a) => a.id === 'payment')
  const d = (payment && payment.dicts) || {}
  dicts.billStatus = d.billStatus || []
  dicts.billDirection = d.billDirection || []
  dicts.refundStatus = d.refundStatus || []
  dicts.channel = d.channel || []
  await loadTypes()
  load()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; }
.tip { color: var(--yz-ink-50); font-size: 12px; }
.mono { font-family: Consolas, monospace; font-size: 12px; }
.channel-line { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; }
.section { margin: 16px 0 8px; }
.receipt { border-left: 3px solid var(--yz-cinnabar, #b03a2e); padding: 6px 10px; margin-bottom: 8px; background: var(--yz-paper-2, #f5f3ee); }
.new-type { display: flex; gap: 8px; margin-top: 12px; }
</style>
