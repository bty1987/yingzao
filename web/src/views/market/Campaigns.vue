<template>
  <div>
    <el-tabs v-model="tab">
      <el-tab-pane label="营销活动" name="campaigns">
        <div style="margin-bottom: 12px">
          <el-button type="primary" @click="openCampaign(null)">新建活动</el-button>
          <el-button @click="loadCampaigns">刷新</el-button>
        </div>
        <el-table :data="campaigns" border stripe>
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="name" label="活动名称" min-width="160" />
          <el-table-column prop="description" label="说明" min-width="180" show-overflow-tooltip />
          <el-table-column label="预算(元)" width="110">
            <template #default="{ row }">{{ (row.budget / 1000).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="campaignTag(row.status)">{{ dictLabel('campaignStatus', row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="publish_time" label="发布时间" width="170" />
          <el-table-column label="操作" width="240" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.status === 0" type="primary" link @click="openCampaign(row)">编辑</el-button>
              <el-button v-if="row.status === 0 || row.status === 3" type="success" link @click="onPublish(row)">发布</el-button>
              <el-button v-if="row.status === 2" type="warning" link @click="onDisable(row)">停用</el-button>
              <el-button v-if="row.status !== 5" type="danger" link @click="onArchive(row)">归档</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="投放渠道" name="channels">
        <div style="margin-bottom: 12px">
          <el-button type="primary" @click="openChannel(null)">新建渠道</el-button>
          <el-button @click="loadChannels">刷新</el-button>
        </div>
        <el-table :data="channels" border stripe>
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="name" label="渠道名称" min-width="200" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 0 ? 'success' : 'info'">{{ dictLabel('channelStatus', row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="created_at" label="创建时间" width="170" />
          <el-table-column label="操作" width="160" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" link @click="openChannel(row)">改名</el-button>
              <el-button v-if="row.status === 0" type="warning" link @click="onChannelDisable(row)">停用</el-button>
              <el-button v-else type="success" link @click="onChannelEnable(row)">恢复</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="投放源" name="sources">
        <div style="margin-bottom: 12px">
          <el-button type="primary" @click="openSource">新建投放源</el-button>
          <el-button @click="loadSources">刷新</el-button>
        </div>
        <el-table :data="sources" border stripe>
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="name" label="名称" min-width="140" />
          <el-table-column prop="code" label="渠道码" width="150" />
          <el-table-column prop="campaign_name" label="活动" min-width="120" />
          <el-table-column prop="channel_name" label="渠道" width="110" />
          <el-table-column prop="utm_url" label="投放链接(带UTM)" min-width="220" show-overflow-tooltip />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 0 ? 'success' : 'info'">{{ dictLabel('sourceStatus', row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" link @click="onQrcode(row)">二维码</el-button>
              <el-button v-if="row.status === 0" type="warning" link @click="onSourceDisable(row)">停用</el-button>
              <el-button v-else type="success" link @click="onSourceEnable(row)">恢复</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="campaignDialog" title="营销活动" width="520px">
      <el-form :model="campaignForm" label-width="90px">
        <el-form-item label="活动名称"><el-input v-model="campaignForm.name" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="campaignForm.description" type="textarea" /></el-form-item>
        <el-form-item label="预算(元)"><el-input-number v-model="campaignForm.budgetYuan" :min="0" :precision="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="campaignDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveCampaign">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="channelDialog" title="投放渠道" width="440px">
      <el-form label-width="90px">
        <el-form-item label="渠道名称"><el-input v-model="channelForm.name" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="channelDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveChannel">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="sourceDialog" title="新建投放源" width="560px">
      <el-form :model="sourceForm" label-width="110px">
        <el-form-item label="活动">
          <el-select v-model="sourceForm.campaignId" filterable placeholder="选择已发布/草稿活动" style="width: 100%">
            <el-option v-for="c in campaignOptions" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="渠道">
          <el-select v-model="sourceForm.channelId" filterable placeholder="选择启用渠道" style="width: 100%">
            <el-option v-for="c in enabledChannels" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称"><el-input v-model="sourceForm.name" placeholder="如:抖音-同城页-A组" /></el-form-item>
        <el-form-item label="落地页URL"><el-input v-model="sourceForm.url" /></el-form-item>
        <el-form-item label="utm_source"><el-input v-model="sourceForm.utmSource" /></el-form-item>
        <el-form-item label="utm_medium"><el-input v-model="sourceForm.utmMedium" /></el-form-item>
        <el-form-item label="utm_campaign"><el-input v-model="sourceForm.utmCampaign" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="sourceDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveSource">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="qrcodeDialog" title="投放二维码" width="360px" align-center>
      <div style="text-align: center">
        <img v-if="qrcodeUrl" :src="qrcodeUrl" alt="二维码" style="width: 260px; height: 260px" />
        <p style="word-break: break-all; font-size: 12px; color: #666">{{ qrcodeTarget }}</p>
        <a v-if="qrcodeUrl" :href="qrcodeUrl" download="qrcode.png">下载二维码</a>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import QRCode from 'qrcode'
import {
  listCampaigns, createCampaign, updateCampaign, publishCampaign, disableCampaign, archiveCampaign,
  listChannels, createChannel, updateChannel, disableChannel, enableChannel,
  listSources, createSource, disableSource, enableSource,
} from '../../api'
import { addons } from '../../store/addons'

const tab = ref('campaigns')
const campaigns = ref([])
const channels = ref([])
const sources = ref([])
const saving = ref(false)

const campaignDialog = ref(false)
const campaignForm = ref({ id: null, name: '', description: '', budgetYuan: 0 })
const channelDialog = ref(false)
const channelForm = ref({ id: null, name: '' })
const sourceDialog = ref(false)
const sourceForm = ref({ campaignId: null, channelId: null, name: '', url: '', utmSource: '', utmMedium: '', utmCampaign: '' })
const qrcodeDialog = ref(false)
const qrcodeUrl = ref('')
const qrcodeTarget = ref('')

const dicts = ref({})
const campaignOptions = computed(() => campaigns.value.filter((c) => c.status === 0 || c.status === 2))
const enabledChannels = computed(() => channels.value.filter((c) => c.status === 0))

onMounted(() => {
  const market = addons.list.find((a) => a.id === 'market')
  dicts.value = (market && market.dicts) || {}
  loadCampaigns()
  loadChannels()
  loadSources()
})

function dictLabel(group, key) {
  const list = dicts.value[group] || []
  const hit = list.find((d) => String(d.key) === String(key))
  return hit ? hit.label : key
}

function campaignTag(status) {
  return { 0: 'info', 2: 'success', 3: 'warning', 5: 'danger' }[status] || 'info'
}

async function loadCampaigns() {
  campaigns.value = (await listCampaigns()).data || []
}
async function loadChannels() {
  channels.value = (await listChannels()).data || []
}
async function loadSources() {
  sources.value = (await listSources()).data || []
}

function openCampaign(row) {
  campaignForm.value = row
    ? { id: row.id, name: row.name, description: row.description, budgetYuan: row.budget / 1000 }
    : { id: null, name: '', description: '', budgetYuan: 0 }
  campaignDialog.value = true
}

async function saveCampaign() {
  saving.value = true
  try {
    const data = { name: campaignForm.value.name, description: campaignForm.value.description, budget: Math.round(campaignForm.value.budgetYuan * 1000) }
    if (campaignForm.value.id) {
      await updateCampaign(campaignForm.value.id, data)
    } else {
      await createCampaign(data)
    }
    ElMessage.success('保存成功')
    campaignDialog.value = false
    loadCampaigns()
  } finally {
    saving.value = false
  }
}

async function onPublish(row) {
  await publishCampaign(row.id)
  ElMessage.success('已发布')
  loadCampaigns()
}
async function onDisable(row) {
  await disableCampaign(row.id)
  ElMessage.success('已停用')
  loadCampaigns()
}
async function onArchive(row) {
  await ElMessageBox.confirm('归档为终态不可恢复,确认?', '提示', { type: 'warning' })
  await archiveCampaign(row.id)
  ElMessage.success('已归档')
  loadCampaigns()
}

function openChannel(row) {
  channelForm.value = row ? { id: row.id, name: row.name } : { id: null, name: '' }
  channelDialog.value = true
}
async function saveChannel() {
  saving.value = true
  try {
    if (channelForm.value.id) {
      await updateChannel(channelForm.value.id, { name: channelForm.value.name })
    } else {
      await createChannel({ name: channelForm.value.name })
    }
    ElMessage.success('保存成功')
    channelDialog.value = false
    loadChannels()
  } finally {
    saving.value = false
  }
}
async function onChannelDisable(row) {
  await disableChannel(row.id)
  loadChannels()
}
async function onChannelEnable(row) {
  await enableChannel(row.id)
  loadChannels()
}

function openSource() {
  sourceForm.value = { campaignId: null, channelId: null, name: '', url: '', utmSource: '', utmMedium: '', utmCampaign: '' }
  sourceDialog.value = true
}
async function saveSource() {
  saving.value = true
  try {
    await createSource(sourceForm.value)
    ElMessage.success('已创建,渠道码服务端生成')
    sourceDialog.value = false
    loadSources()
  } finally {
    saving.value = false
  }
}
async function onSourceDisable(row) {
  await disableSource(row.id)
  loadSources()
}
async function onSourceEnable(row) {
  await enableSource(row.id)
  loadSources()
}

async function onQrcode(row) {
  const target = row.utm_url || row.url
  if (!target) {
    ElMessage.warning('该投放源未配置落地页 URL,无法生成二维码')
    return
  }
  qrcodeTarget.value = target
  qrcodeUrl.value = await QRCode.toDataURL(target, { width: 520, margin: 1 })
  qrcodeDialog.value = true
}
</script>
