<template>
  <div>
    <el-tabs v-model="tab" @tab-change="load">
      <el-tab-pane label="按活动" name="campaigns">
        <el-table :data="campaignRows" border stripe>
          <el-table-column prop="name" label="活动" min-width="160" />
          <el-table-column label="预算(元)" width="120">
            <template #default="{ row }">{{ ((row.budget || 0) / 1000).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">{{ dictLabel('campaignStatus', row.status) }}</template>
          </el-table-column>
          <el-table-column label="进线漏斗" min-width="360">
            <template #default="{ row }">
              <div class="funnel">
                <div class="funnel-row">
                  <span class="funnel-label">进线 {{ row.inboundTotal }}</span>
                  <div class="funnel-bar"><div class="funnel-fill" :style="barStyle(row.inboundTotal, maxInbound)"></div></div>
                </div>
                <div class="funnel-row">
                  <span class="funnel-label">有效 {{ row.inboundValid }}</span>
                  <div class="funnel-bar"><div class="funnel-fill" :style="barStyle(row.inboundValid, maxInbound)"></div></div>
                </div>
                <div class="funnel-row">
                  <span class="funnel-label">客源 {{ row.leadCount }}</span>
                  <div class="funnel-bar"><div class="funnel-fill" :style="barStyle(row.leadCount, maxInbound)"></div></div>
                </div>
                <div class="funnel-row">
                  <span class="funnel-label">机会 {{ row.chanceCount }}</span>
                  <div class="funnel-bar"><div class="funnel-fill" :style="barStyle(row.chanceCount, maxInbound)"></div></div>
                </div>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="按渠道" name="channels">
        <div style="margin-bottom: 12px">
          <el-select v-model="campaignFilter" placeholder="限定活动(可空)" clearable filterable style="width: 220px" @change="load">
            <el-option v-for="c in campaigns" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </div>
        <el-table :data="channelRows" border stripe>
          <el-table-column prop="name" label="渠道" min-width="160" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">{{ dictLabel('channelStatus', row.status) }}</template>
          </el-table-column>
          <el-table-column label="进线漏斗" min-width="360">
            <template #default="{ row }">
              <div class="funnel">
                <div class="funnel-row">
                  <span class="funnel-label">进线 {{ row.inboundTotal }}</span>
                  <div class="funnel-bar"><div class="funnel-fill" :style="barStyle(row.inboundTotal, maxChannelInbound)"></div></div>
                </div>
                <div class="funnel-row">
                  <span class="funnel-label">有效 {{ row.inboundValid }}</span>
                  <div class="funnel-bar"><div class="funnel-fill" :style="barStyle(row.inboundValid, maxChannelInbound)"></div></div>
                </div>
                <div class="funnel-row">
                  <span class="funnel-label">客源 {{ row.leadCount }}</span>
                  <div class="funnel-bar"><div class="funnel-fill" :style="barStyle(row.leadCount, maxChannelInbound)"></div></div>
                </div>
                <div class="funnel-row">
                  <span class="funnel-label">机会 {{ row.chanceCount }}</span>
                  <div class="funnel-bar"><div class="funnel-fill" :style="barStyle(row.chanceCount, maxChannelInbound)"></div></div>
                </div>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { funnelByCampaign, funnelByChannel, listCampaigns } from '../../api'
import { addons } from '../../store/addons'

const tab = ref('campaigns')
const campaignRows = ref([])
const channelRows = ref([])
const campaigns = ref([])
const campaignFilter = ref(null)
const dicts = ref({})

const maxInbound = computed(() => Math.max(1, ...campaignRows.value.map((r) => r.inboundTotal)))
const maxChannelInbound = computed(() => Math.max(1, ...channelRows.value.map((r) => r.inboundTotal)))

onMounted(() => {
  const market = addons.list.find((a) => a.id === 'market')
  dicts.value = (market && market.dicts) || {}
  listCampaigns().then((r) => (campaigns.value = r.data || []))
  load()
})

function dictLabel(group, key) {
  const list = dicts.value[group] || []
  const hit = list.find((d) => String(d.key) === String(key))
  return hit ? hit.label : key
}

async function load() {
  if (tab.value === 'campaigns') {
    campaignRows.value = (await funnelByCampaign()).data || []
  } else {
    channelRows.value = (await funnelByChannel(
      campaignFilter.value ? { campaignId: campaignFilter.value } : {})).data || []
  }
}

function barStyle(value, max) {
  return { width: `${Math.round((value / max) * 100)}%` }
}
</script>

<style scoped>
.funnel {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.funnel-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.funnel-label {
  width: 72px;
  font-size: 12px;
  color: #555;
  text-align: right;
}
.funnel-bar {
  flex: 1;
  height: 10px;
  background: #f0efe9;
  border-radius: 2px;
  overflow: hidden;
}
.funnel-fill {
  height: 100%;
  background: #1a1a18;
  min-width: 1px;
}
</style>
