<template>
  <div>
    <el-card shadow="never">
      <div class="toolbar">
        <el-date-picker v-model="year" type="year" placeholder="业务年度" value-format="YYYY"
          style="width: 130px" @change="reload" />
        <el-button @click="reload">查询</el-button>
        <div class="spacer" />
        <el-button type="primary" @click="publish(currentRow.base)">发布</el-button>
        <el-button @click="openVersions">发布记录</el-button>
      </div>

      <el-alert type="info" :closable="false" style="margin-bottom: 12px"
        title="线值单位为百分比(5000 万分比 = 50%);发布必须整年 17 周期完整提交,发布后历史版本只读;实际毛利率随 F1 看板接入后填充" />

      <el-tabs v-model="tab" @tab-change="reload">
        <el-tab-pane label="集团管理线" name="group" />
        <el-tab-pane label="分公司要求线" name="branch" />
      </el-tabs>

      <!-- 分公司:左组织进度 + 右编辑表 -->
      <div v-if="tab === 'branch'" style="display: flex; gap: 12px">
        <div style="width: 280px; flex-shrink: 0">
          <el-input v-model="companyKeyword" placeholder="搜索分公司" clearable @input="loadCompanies" />
          <div style="margin: 8px 0; font-size: 12px; color: #909399">
            共 {{ companyStats.total }} 家:未配置 {{ companyStats.none }} / 已完成 {{ companyStats.completed }}
          </div>
          <el-table :data="companies" border size="small" highlight-current-row @current-change="onPickCompany">
            <el-table-column prop="name" label="分公司" min-width="110" />
            <el-table-column label="进度" width="90">
              <template #default="{ row }">
                <el-tag :type="row.configStatus === 'COMPLETED' ? 'success' : 'info'" size="small">
                  {{ dicts.marginConfigStatus?.[row.configStatus] || row.configStatus }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </div>
        <div style="flex: 1">
          <el-alert v-if="!pickedCompany" type="warning" :closable="false"
            title="选择左侧分公司后编辑其要求线" />
          <template v-else>
            <el-table :data="branchRows" border>
              <el-table-column prop="periodValue" label="周期" width="100" />
              <el-table-column label="状态" width="90">
                <template #default="{ row }">
                  <el-tag :type="row.periodStatus === 'PENDING' ? 'warning' : 'info'" size="small">
                    {{ dicts.marginPeriodStatus?.[row.periodStatus] || row.periodStatus }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="综合线 %" width="130">
                <template #default="{ row }">
                  <el-input-number v-model="row._comp" :min="0.01" :max="100" :step="0.5" :precision="2"
                    size="small" controls-position="right" style="width: 110px" />
                </template>
              </el-table-column>
              <el-table-column label="基装线 %" width="130">
                <template #default="{ row }">
                  <el-input-number v-model="row._proj" :min="0.01" :max="100" :step="0.5" :precision="2"
                    size="small" controls-position="right" style="width: 110px" />
                </template>
              </el-table-column>
              <el-table-column label="主材线 %" width="130">
                <template #default="{ row }">
                  <el-input-number v-model="row._main" :min="0.01" :max="100" :step="0.5" :precision="2"
                    size="small" controls-position="right" style="width: 110px" />
                </template>
              </el-table-column>
              <el-table-column label="集团线对照(综合/基装/主材)">
                <template #default="{ row }">
                  <span v-if="row.groupComprehensiveRef === null" style="color: #c0c4cc">—</span>
                  <span v-else>{{ pct(row.groupComprehensiveRef) }} / {{ pct(row.groupProjectRef) }} /
                    {{ pct(row.groupMainRef) }}</span>
                </template>
              </el-table-column>
            </el-table>
            <div style="margin-top: 12px">
              <el-button type="primary" @click="publishBranch">发布分公司要求线</el-button>
              <el-button @click="openBranchVersions">发布记录</el-button>
            </div>
          </template>
        </div>
      </div>

      <!-- 集团:17 行编辑表 -->
      <template v-else>
        <el-table :data="groupRows" border>
          <el-table-column prop="periodValue" label="周期" width="100" />
          <el-table-column label="类型" width="80">
            <template #default="{ row }">{{ dicts.marginCycleType?.[String(row.cycleType)] || row.cycleType }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.periodStatus === 'PENDING' ? 'warning' : 'info'" size="small">
                {{ dicts.marginPeriodStatus?.[row.periodStatus] || row.periodStatus }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="综合线 %" width="140">
            <template #default="{ row }">
              <el-input-number v-model="row._comp" :min="0.01" :max="100" :step="0.5" :precision="2"
                size="small" controls-position="right" style="width: 120px" />
            </template>
          </el-table-column>
          <el-table-column label="基装线 %" width="140">
            <template #default="{ row }">
              <el-input-number v-model="row._proj" :min="0.01" :max="100" :step="0.5" :precision="2"
                size="small" controls-position="right" style="width: 120px" />
            </template>
          </el-table-column>
          <el-table-column label="主材线 %" width="140">
            <template #default="{ row }">
              <el-input-number v-model="row._main" :min="0.01" :max="100" :step="0.5" :precision="2"
                size="small" controls-position="right" style="width: 120px" />
            </template>
          </el-table-column>
          <el-table-column label="实际综合毛利率">
            <template #default="{ row }">{{ row.actualComprehensiveRate === null ? '—' :
              pct(row.actualComprehensiveRate) }}</template>
          </el-table-column>
        </el-table>
      </template>
    </el-card>

    <!-- 发布记录抽屉 -->
    <el-drawer v-model="versionsDrawer" title="发布记录" size="560px">
      <el-table :data="versionRows" border size="small">
        <el-table-column prop="id" label="版本" width="70" />
        <el-table-column prop="created_by_name" label="操作人" width="110" />
        <el-table-column prop="created_at" label="时间" width="170" />
        <el-table-column prop="change_count" label="变更项" width="80" />
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <!-- 版本修改详情抽屉(diff 仅 before≠after 行) -->
    <el-drawer v-model="detailDrawer" :title="'版本 ' + detail.id + ' 修改详情'" size="640px">
      <el-descriptions :column="2" border size="small" style="margin-bottom: 12px">
        <el-descriptions-item label="主体">{{ detail.org_name }}</el-descriptions-item>
        <el-descriptions-item label="年度">{{ detail.biz_year }}</el-descriptions-item>
        <el-descriptions-item label="操作人">{{ detail.created_by_name }}</el-descriptions-item>
        <el-descriptions-item label="时间">{{ detail.created_at }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="detail.changes || []" border size="small">
        <el-table-column prop="period_value" label="周期" width="100" />
        <el-table-column label="口径" width="110">
          <template #default="{ row }">{{ dicts.marginRateType?.[String(row.rate_type)] || row.rate_type }}</template>
        </el-table-column>
        <el-table-column label="原值 %" width="90" align="right">
          <template #default="{ row }">{{ row.before_value === null ? '—' : pct(row.before_value) }}</template>
        </el-table-column>
        <el-table-column label="新值 %" width="90" align="right">
          <template #default="{ row }">{{ pct(row.after_value) }}</template>
        </el-table-column>
        <el-table-column label="变化" width="90" align="right">
          <template #default="{ row }">
            <span :style="{ color: row.change_value > 0 ? '#67c23a' : '#f56c6c' }">
              {{ row.change_value > 0 ? '+' : '' }}{{ (row.change_value / 100).toFixed(2) }}
            </span>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchAddons, fetchMarginGroup, publishMarginGroup, pageMarginGroupVersions,
  fetchMarginCompanyOptions, fetchMarginBranch, publishMarginBranch,
  pageMarginBranchVersions, fetchMarginVersionDetail
} from '../../api'

const dicts = ref({})
const year = ref(String(new Date().getFullYear()))
const tab = ref('group')

const groupRows = ref([])
const groupBase = reactive({ versionId: null })
const currentRow = reactive({ base: { versionId: null } })

const companies = ref([])
const companyStats = reactive({ total: 0, none: 0, completed: 0 })
const companyKeyword = ref('')
const pickedCompany = ref(null)
const branchRows = ref([])
const branchBase = reactive({ versionId: null })

const versionsDrawer = ref(false)
const versionRows = ref([])
const detailDrawer = ref(false)
const detail = reactive({})

const pct = (bpm) => (Number(bpm) / 100).toFixed(2) + '%'

// 服务端万分比 → 前端百分比编辑值
function toEditRows(periods) {
  return (periods || []).map((p) => ({
    ...p,
    _comp: p.comprehensiveLine === null ? null : p.comprehensiveLine / 100,
    _proj: p.projectLine === null ? null : p.projectLine / 100,
    _main: p.mainMaterialLine === null ? null : p.mainMaterialLine / 100
  }))
}

function editable(periods) {
  return (periods || []).every((p) => p.comprehensiveLine !== null && p.projectLine !== null
    && p.mainMaterialLine !== null)
}

function payload(rows) {
  return rows.map((r) => ({
    cycleType: r.cycleType,
    periodValue: r.periodValue,
    comprehensiveLine: Math.round(r._comp * 100),
    projectLine: Math.round(r._proj * 100),
    mainMaterialLine: Math.round(r._main * 100)
  }))
}

async function loadDicts() {
  const res = await fetchAddons()
  const addon = (res.data || []).find((a) => a.id === 'balance')
  if (addon?.dicts) dicts.value = addon.dicts
}

async function loadGroup() {
  const res = await fetchMarginGroup({ year: year.value })
  groupRows.value = toEditRows(res.data.periods)
  currentRow.base.versionId = res.data.versionId
  groupBase.versionId = res.data.versionId
}

async function loadCompanies() {
  const res = await fetchMarginCompanyOptions({ year: year.value, keyword: companyKeyword.value })
  companies.value = res.data.rows || []
  companyStats.total = res.data.total
  companyStats.none = res.data.none
  companyStats.completed = res.data.completed
}

async function onPickCompany(row) {
  pickedCompany.value = row
  if (!row) return
  const res = await fetchMarginBranch({ orgId: row.orgId, year: year.value })
  branchRows.value = toEditRows(res.data.periods)
  branchBase.versionId = res.data.versionId
}

function reload() {
  loadGroup()
  loadCompanies()
  pickedCompany.value = null
}

async function publishGroup() {
  if (!editable(groupRows.value)) {
    ElMessage.warning('还有周期未填全三口径线值(整年 17 周期必须完整)')
    return
  }
  await ElMessageBox.confirm(
    `确认发布 ${year.value} 年度集团管理线?发布后固化为新版本,历史版本只读`, '发布确认', { type: 'warning' })
  await publishMarginGroup({ bizYear: Number(year.value), baseVersionId: groupBase.versionId, periods: payload(groupRows.value) })
  ElMessage.success('集团管理线已发布')
  loadGroup()
}

async function publishBranchLine() {
  if (!editable(branchRows.value)) {
    ElMessage.warning('还有周期未填全三口径线值(整年 17 周期必须完整)')
    return
  }
  await ElMessageBox.confirm(
    `确认发布 ${pickedCompany.value.name} ${year.value} 年度要求线?`, '发布确认', { type: 'warning' })
  await publishMarginBranch({
    orgId: pickedCompany.value.orgId, bizYear: Number(year.value),
    baseVersionId: branchBase.versionId, periods: payload(branchRows.value)
  })
  ElMessage.success('分公司要求线已发布')
  onPickCompany(pickedCompany.value)
  loadCompanies()
}

function publish() {
  if (tab.value === 'group') publishGroup()
}

async function openVersions() {
  const fn = tab.value === 'group'
    ? pageMarginGroupVersions
    : pageMarginBranchVersions
  const params = { year: year.value, page: 1, size: 50 }
  if (tab.value === 'branch') {
    if (!pickedCompany.value) {
      ElMessage.warning('先选择分公司')
      return
    }
    params.orgId = pickedCompany.value.orgId
  }
  const res = await fn(params)
  versionRows.value = res.data.rows || []
  versionsDrawer.value = true
}

function openBranchVersions() {
  openVersions()
}

async function openDetail(row) {
  const res = await fetchMarginVersionDetail(row.id)
  Object.assign(detail, res.data)
  versionsDrawer.value = false
  detailDrawer.value = true
}

onMounted(() => {
  loadDicts()
  reload()
})
</script>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.spacer {
  flex: 1;
}
</style>
