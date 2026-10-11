<template>
  <div>
    <div style="margin-bottom: 12px; display: flex; gap: 8px; flex-wrap: wrap">
      <el-button type="primary" @click="openRegister">登记进线</el-button>
      <el-select v-model="query.status" placeholder="状态" clearable style="width: 130px" @change="load">
        <el-option v-for="d in statusDict" :key="d.key" :label="d.label" :value="Number(d.key)" />
      </el-select>
      <el-select v-model="query.campaignId" placeholder="活动" clearable filterable style="width: 180px" @change="load">
        <el-option v-for="c in campaigns" :key="c.id" :label="c.name" :value="c.id" />
      </el-select>
      <el-select v-model="query.channelId" placeholder="渠道" clearable filterable style="width: 150px" @change="load">
        <el-option v-for="c in channels" :key="c.id" :label="c.name" :value="c.id" />
      </el-select>
      <el-input v-model="query.name" placeholder="访客姓名" clearable style="width: 150px" @keyup.enter="load" />
      <el-button @click="load">查询</el-button>
    </div>

    <el-table :data="rows" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="inbound_no" label="进线号" width="160" />
      <el-table-column prop="name" label="访客" width="110" />
      <el-table-column prop="campaign_name" label="活动" min-width="120" />
      <el-table-column prop="channel_name" label="渠道" width="110" />
      <el-table-column prop="case_name" label="案例" min-width="110" />
      <el-table-column label="承接档" width="100">
        <template #default="{ row }">{{ row.accept_mode === 'chance' ? '客源+机会' : '仅客源' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)">{{ dictLabel('inboundStatus', row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="承接结果" min-width="140">
        <template #default="{ row }">
          <span v-if="row.status === 1">客源#{{ row.customer_lead_id }}<template v-if="row.chance_id"> / 机会#{{ row.chance_id }}</template></span>
          <span v-else-if="row.status === 2" style="color: #b03a2e">{{ row.error_msg }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="created_at" label="登记时间" width="170" />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link @click="openDetail(row.id)">详情</el-button>
          <el-button v-if="row.status === 0 || row.status === 2" type="success" link @click="onRetry(row)">重试</el-button>
          <el-button v-if="row.status === 0 || row.status === 2" type="warning" link @click="onIgnore(row)">忽略</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div style="margin-top: 12px; display: flex; justify-content: flex-end">
      <el-pagination layout="total, prev, pager, next" :total="total" :page-size="query.size"
        v-model:current-page="query.page" @current-change="load" />
    </div>

    <el-dialog v-model="registerDialog" title="登记进线" width="560px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="投放源">
          <el-select v-model="form.sourceId" filterable clearable placeholder="选择投放源(或留空走案例报名)" style="width: 100%">
            <el-option v-for="s in enabledSources" :key="s.id"
              :label="`${s.name}(${s.campaign_name}/${s.channel_name})`" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="承接档">
          <el-radio-group v-model="form.acceptMode">
            <el-radio v-for="d in modeDict" :key="d.key" :value="d.key">{{ d.label }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="访客姓名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="form.phone" />
          <el-checkbox v-model="form.allowAnonymous">无手机号,登记为匿名进线</el-checkbox>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="form.note" type="textarea" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="registerDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveRegister">登记</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailDrawer" title="进线详情" size="520px">
      <template v-if="detail">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="进线号">{{ detail.inbound_no }}</el-descriptions-item>
          <el-descriptions-item label="访客">{{ detail.name || '(匿名)' }}</el-descriptions-item>
          <el-descriptions-item label="活动">{{ detail.campaign_name || '-' }}</el-descriptions-item>
          <el-descriptions-item label="渠道">{{ detail.channel_name || '-' }}</el-descriptions-item>
          <el-descriptions-item label="渠道码">{{ detail.source_code || '-' }}</el-descriptions-item>
          <el-descriptions-item label="案例">{{ detail.case_name || '-' }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTag(detail.status)">{{ dictLabel('inboundStatus', detail.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.error_msg" label="失败原因">
            <span style="color: #b03a2e">{{ detail.error_msg }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="承接结果">
            <span v-if="detail.status === 1">客源#{{ detail.customer_lead_id }}<template v-if="detail.chance_id"> / 机会#{{ detail.chance_id }}</template>({{ detail.accept_time }})</span>
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item label="备注">{{ detail.note || '-' }}</el-descriptions-item>
        </el-descriptions>

        <h4>动态字段</h4>
        <el-table :data="detail.items" border size="small">
          <el-table-column prop="field_key" label="字段" width="140" />
          <el-table-column prop="field_value" label="值" />
        </el-table>

        <h4>归因流水(insert-only)</h4>
        <el-table :data="detail.attributions" border size="small">
          <el-table-column label="事件" width="120">
            <template #default="{ row }">{{ dictLabel('attrEventType', row.event_type) }}</template>
          </el-table-column>
          <el-table-column prop="ref_id" label="引用ID" width="100" />
          <el-table-column prop="created_at" label="时间" width="170" />
        </el-table>

        <h4>操作留痕</h4>
        <el-table :data="detail.tracks" border size="small">
          <el-table-column prop="action" label="动作" width="100" />
          <el-table-column prop="reason" label="原因" />
          <el-table-column prop="operator_id" label="操作人" width="80" />
          <el-table-column prop="created_at" label="时间" width="170" />
        </el-table>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  pageInbounds, fetchInbound, registerInbound, retryInbound, ignoreInbound,
  listCampaigns, listChannels, listSources,
} from '../../api'
import { addons } from '../../store/addons'

const rows = ref([])
const total = ref(0)
const campaigns = ref([])
const channels = ref([])
const sources = ref([])
const dicts = ref({})
const query = ref({ status: null, campaignId: null, channelId: null, name: '', page: 1, size: 20 })
const registerDialog = ref(false)
const saving = ref(false)
const form = ref({ sourceId: null, acceptMode: 'chance', name: '', phone: '', note: '', allowAnonymous: false })
const detailDrawer = ref(false)
const detail = ref(null)

const statusDict = computed(() => dicts.value.inboundStatus || [])
const modeDict = computed(() => dicts.value.acceptMode || [])
const enabledSources = computed(() => sources.value.filter((s) => s.status === 0))

onMounted(() => {
  const market = addons.list.find((a) => a.id === 'market')
  dicts.value = (market && market.dicts) || {}
  load()
  listCampaigns().then((r) => (campaigns.value = r.data || []))
  listChannels().then((r) => (channels.value = r.data || []))
  listSources().then((r) => (sources.value = r.data || []))
})

function dictLabel(group, key) {
  const list = dicts.value[group] || []
  const hit = list.find((d) => String(d.key) === String(key))
  return hit ? hit.label : key
}

function statusTag(status) {
  return { 0: 'info', 1: 'success', 2: 'danger', 3: 'warning', 4: 'info' }[status] || 'info'
}

async function load() {
  const res = await pageInbounds(query.value)
  rows.value = (res.data && res.data.rows) || []
  total.value = (res.data && res.data.total) || 0
}

function openRegister() {
  form.value = { sourceId: null, acceptMode: 'chance', name: '', phone: '', note: '', allowAnonymous: false }
  registerDialog.value = true
}

async function saveRegister() {
  saving.value = true
  try {
    await registerInbound(form.value)
    ElMessage.success('登记成功,承接进行中')
    registerDialog.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function openDetail(id) {
  detail.value = (await fetchInbound(id)).data
  detailDrawer.value = true
}

async function onRetry(row) {
  await retryInbound(row.id)
  ElMessage.success('已重发承接')
  load()
}

async function onIgnore(row) {
  const { value } = await ElMessageBox.prompt('忽略原因(留痕)', '忽略进线', { inputValue: '' })
  await ignoreInbound(row.id, { reason: value || '' })
  ElMessage.success('已忽略')
  load()
}
</script>
