<template>
  <div>
    <el-card shadow="never">
      <div class="toolbar">
        <el-select v-model="filter.status" placeholder="全部状态" clearable style="width: 130px" @change="load">
          <el-option v-for="d in dicts.pkStatus || []" :key="d.key" :label="d.label" :value="Number(d.key)" />
        </el-select>
        <el-select v-model="filter.cycleType" placeholder="全部周期" clearable style="width: 110px" @change="load">
          <el-option v-for="d in dicts.pkCycle || []" :key="d.key" :label="d.label" :value="Number(d.key)" />
        </el-select>
        <el-input v-model="filter.period" placeholder="周期键(如 2026-10)" clearable style="width: 150px" @change="load" />
        <el-button @click="load">查询</el-button>
        <div class="spacer" />
        <el-button type="primary" @click="openCreate">发起赛事</el-button>
      </div>

      <el-alert type="info" :closable="false" style="margin-bottom: 12px"
        title="实绩口径:公司级装修单签约额(与目标计划同契约同口径);分区/部门节点自动实绩恒 0,可启用手工实绩双轨(留痕)。奖金只记录不发放,判胜负纯人工。" />

      <el-table :data="rows" border stripe>
        <el-table-column prop="name" label="赛事" min-width="160" />
        <el-table-column label="周期" width="150">
          <template #default="{ row }">{{ dictLabel(dicts.pkCycle, row.cycle_type) }} · {{ row.period }}</template>
        </el-table-column>
        <el-table-column label="起止" width="190">
          <template #default="{ row }">{{ row.start_date }} ~ {{ row.end_date }}</template>
        </el-table-column>
        <el-table-column label="单元(已锁)" width="100" align="center">
          <template #default="{ row }">{{ row.locked_count }}/{{ row.unit_count }}</template>
        </el-table-column>
        <el-table-column label="承诺额" width="120" align="right">
          <template #default="{ row }">{{ fmt(row.commitment) }}</template>
        </el-table-column>
        <el-table-column label="奖金池" width="120" align="right">
          <template #default="{ row }">{{ fmt(row.bonus_pool) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">
              {{ dictLabel(dicts.pkStatus, row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">全景</el-button>
            <el-button v-if="row.status === 0 || row.status === 1" link type="warning"
              @click="onVoid(row)">作废</el-button>
            <el-button link @click="openCopy(row)">复制开新</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 发起/复制赛事 -->
    <el-dialog v-model="createDialog" :title="copySource ? `复制赛事 - ${copySource.name}` : '发起 PK 赛事'" width="520px">
      <el-form label-width="100px">
        <el-form-item label="赛事名" required>
          <el-input v-model="createForm.name" placeholder="如:10月部门签约对赌赛" />
        </el-form-item>
        <el-form-item label="周期类型" required>
          <el-radio-group v-model="createForm.cycleType">
            <el-radio :value="1">月赛</el-radio>
            <el-radio :value="2">季赛</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="周期" required>
          <el-date-picker v-if="createForm.cycleType === 1" v-model="createForm.period" type="month"
            value-format="YYYY-MM" />
          <el-select v-else v-model="createForm.period" style="width: 180px">
            <el-option v-for="q in quarterOptions" :key="q.value" :label="q.label" :value="q.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="起始日" required>
          <el-date-picker v-model="createForm.startDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="截止日" required>
          <el-date-picker v-model="createForm.endDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="缺省基准档">
          <el-select v-model="createForm.baseTier" clearable style="width: 180px" placeholder="锁档默认选中(可选)">
            <el-option v-for="d in dicts.pkTier || []" :key="d.key" :label="d.label" :value="Number(d.key)" />
          </el-select>
        </el-form-item>
      </el-form>
      <div class="tip" v-if="copySource">复制=沿用上一届单元模板(四档/PK 金/手工开关),锁档与实绩清零。</div>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onCreate">{{ copySource ? '复制创建' : '创建' }}</el-button>
      </template>
    </el-dialog>

    <!-- 赛事全景抽屉 -->
    <el-drawer v-model="detailDrawer" :title="`PK 全景 - ${detail?.name || ''}`" size="860px">
      <template v-if="detail">
        <div style="margin-bottom: 10px; display: flex; gap: 16px; align-items: center; flex-wrap: wrap">
          <el-tag :type="statusTag(detail.status)">{{ dictLabel(dicts.pkStatus, detail.status) }}</el-tag>
          <span class="tip">{{ dictLabel(dicts.pkCycle, detail.cycle_type) }} {{ detail.period }}
            · {{ detail.start_date }} ~ {{ detail.end_date }}</span>
          <div class="spacer" />
          <template v-if="detail.status === 0 || detail.status === 1">
            <el-button size="small" @click="openDraftEditor">模板草稿{{ detail.hasDraft ? '(已有草稿)' : '' }}</el-button>
            <el-button size="small" type="warning" @click="onReset">重置锁档</el-button>
            <el-button v-if="detail.status === 1" size="small" type="success" @click="onConfirm">确认开赛</el-button>
          </template>
        </div>
        <div style="margin-bottom: 10px" class="tip">
          参赛单元 {{ detail.lockedCount }}/{{ detail.unitCount }} 已锁 ·
          承诺额 {{ fmt(detail.commitment) }} 元 · 奖金池 {{ fmt(detail.bonusPool) }} 元(查询时聚合)
        </div>

        <el-table :data="detail.units || []" border size="small">
          <el-table-column prop="node_name" label="参赛单元" min-width="110" />
          <el-table-column label="四档目标(万)" min-width="180">
            <template #default="{ row }">
              {{ wan(row.guarantee) }} / {{ wan(row.base) }} / {{ wan(row.breakthrough) }} / {{ wan(row.challenge) }}
            </template>
          </el-table-column>
          <el-table-column label="PK金(万)" width="90" align="right">
            <template #default="{ row }">{{ wan(row.pk_money) }}</template>
          </el-table-column>
          <el-table-column label="档位" width="80">
            <template #default="{ row }">
              <el-tag v-if="row.selected_tier" size="small" type="success">
                {{ dictLabel(dicts.pkTier, row.selected_tier) }}
              </el-tag>
              <el-tag v-else-if="row.target_amount" size="small" type="warning">自定义</el-tag>
              <span v-else class="tip">未锁</span>
            </template>
          </el-table-column>
          <el-table-column label="锁定目标/奖金" width="140" align="right">
            <template #default="{ row }">
              <span v-if="row.selected_tier">{{ fmt(row.target_amount) }} / {{ fmt(row.bonus_amount) }}</span>
              <span v-else class="tip">-</span>
            </template>
          </el-table-column>
          <el-table-column label="实绩" width="110" align="right">
            <template #default="{ row }">
              <span v-if="row.final_amount !== null && row.final_amount !== undefined">{{ fmt(row.final_amount) }}</span>
              <span v-else class="tip">-</span>
            </template>
          </el-table-column>
          <el-table-column label="来源" width="80">
            <template #default="{ row }">
              <span v-if="row.performance_source">{{ dictLabel(dicts.pkSource, row.performance_source) }}</span>
              <span v-else class="tip">-</span>
            </template>
          </el-table-column>
          <el-table-column label="达成率" width="110">
            <template #default="{ row }">
              <el-progress v-if="row.rate !== null && row.rate !== undefined"
                :percentage="Math.min(row.rate / 10, 100)"
                :format="() => (row.rate / 10).toFixed(1) + '%'"
                :status="row.rate >= 1000 ? 'success' : ''" />
              <span v-else class="tip">-</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <template v-if="detail.status === 0 || detail.status === 1">
                <el-button link size="small" type="primary" @click="openLock(row)">锁档</el-button>
                <el-button v-if="row.target_amount && detail.status === 1" link size="small"
                  @click="onUnlock(row)">解锁</el-button>
                <el-button v-if="detail.status === 0" link size="small" type="primary"
                  @click="openUnitEdit(row)">编辑</el-button>
                <el-button v-if="detail.status === 0" link size="small" type="danger"
                  @click="onUnitDelete(row)">删</el-button>
              </template>
              <el-button v-if="detail.status === 2 && row.manual_enabled" link size="small"
                @click="openManual(row)">录实绩</el-button>
            </template>
          </el-table-column>
        </el-table>

        <!-- 归档快照 -->
        <template v-if="detail.status === 3">
          <div style="margin: 14px 0 8px" class="tip">归档快照(append-only,SHA256 防篡改;复盘以此为唯一口径)</div>
          <el-table :data="snapshotRows" border size="small">
            <el-table-column prop="node_name" label="单元" min-width="110" />
            <el-table-column label="目标" width="110" align="right">
              <template #default="{ row }">{{ fmt(row.target_amount) }}</template>
            </el-table-column>
            <el-table-column label="最终实绩" width="110" align="right">
              <template #default="{ row }">{{ fmt(row.final_amount) }}</template>
            </el-table-column>
            <el-table-column label="来源" width="80">
              <template #default="{ row }">{{ dictLabel(dicts.pkSource, row.performance_source) }}</template>
            </el-table-column>
            <el-table-column prop="snapshot_hash" label="快照指纹" min-width="220" show-overflow-tooltip />
          </el-table>
        </template>
      </template>
    </el-drawer>

    <!-- 模板草稿编辑器 -->
    <el-dialog v-model="draftDialog" title="模板草稿(整包编辑,发布后落行清草稿)" width="900px" append-to-body>
      <el-table :data="draftRows" border size="small">
        <el-table-column label="参赛单元" width="200">
          <template #default="{ row }">
            <el-select v-model="row.orgNode" filterable placeholder="组织树节点" style="width: 100%">
              <el-option v-for="n in flatNodes" :key="n.id" :label="n.name" :value="n.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="参赛人(逗号分隔)" min-width="140">
          <template #default="{ row }">
            <el-input v-model="row.staffsText" placeholder="如:张三,李四" />
          </template>
        </el-table-column>
        <el-table-column v-for="tier in ['guaranteeYuan', 'baseYuan', 'breakthroughYuan', 'challengeYuan']"
          :key="tier" :width="110">
          <template #header>{{ tierHeader(tier) }}(万)</template>
          <template #default="{ row }">
            <el-input-number v-model="row[tier]" :min="0" :precision="1" :step="10" :controls="false"
              style="width: 100%" />
          </template>
        </el-table-column>
        <el-table-column label="PK金(万)" width="110">
          <template #default="{ row }">
            <el-input-number v-model="row.pkMoneyYuan" :min="0" :precision="1" :step="1" :controls="false"
              style="width: 100%" />
          </template>
        </el-table-column>
        <el-table-column label="手工实绩" width="80">
          <template #default="{ row }">
            <el-switch v-model="row.manualEnabled" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="60">
          <template #default="{ row, $index }">
            <el-button link size="small" type="danger" @click="draftRows.splice($index, 1)">删</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div style="margin-top: 10px">
        <el-button size="small" @click="addDraftRow">加单元</el-button>
        <span class="tip" style="margin-left: 8px">四档目标直配(保底/冲刺/突破/挑战);奖金=PK金×档位(2/3/4/5)</span>
      </div>
      <template #footer>
        <el-button @click="draftDialog = false">取消</el-button>
        <el-button :loading="saving" @click="onSaveDraft(false)">保存草稿</el-button>
        <el-button type="primary" :loading="saving" @click="onSaveDraft(true)">保存并发布</el-button>
      </template>
    </el-dialog>

    <!-- 单行模板编辑 -->
    <el-dialog v-model="unitDialog" title="编辑单元模板" width="520px" append-to-body>
      <el-form label-width="110px">
        <el-form-item label="参赛单元">
          <span>{{ unitForm.nodeId }}</span>
        </el-form-item>
        <el-form-item label="参赛人">
          <el-input v-model="unitForm.staffsText" placeholder="逗号分隔" />
        </el-form-item>
        <el-form-item v-for="tier in ['guaranteeYuan', 'baseYuan', 'breakthroughYuan', 'challengeYuan']"
          :key="tier" :label="tierHeader(tier) + '(万)'">
          <el-input-number v-model="unitForm[tier]" :min="0.1" :precision="1" :step="10" style="width: 100%" />
        </el-form-item>
        <el-form-item label="PK金(万)">
          <el-input-number v-model="unitForm.pkMoneyYuan" :min="0.1" :precision="1" :step="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="手工实绩">
          <el-switch v-model="unitForm.manualEnabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="unitDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onUnitSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 锁档 -->
    <el-dialog v-model="lockDialog" :title="`锁档 - ${lockRow?.node_name || ''}`" width="480px" append-to-body>
      <el-form label-width="110px">
        <el-form-item label="锁档方式">
          <el-radio-group v-model="lockForm.mode">
            <el-radio value="tier">选档</el-radio>
            <el-radio value="custom">全自定义</el-radio>
          </el-radio-group>
        </el-form-item>
        <template v-if="lockForm.mode === 'tier'">
          <el-form-item label="档位">
            <el-select v-model="lockForm.tier" style="width: 180px">
              <el-option v-for="d in dicts.pkTier || []" :key="d.key" :label="d.label" :value="Number(d.key)" />
            </el-select>
          </el-form-item>
          <el-form-item label="目标(元)">
            <span>{{ fmt(lockPreview.target) }}</span>
          </el-form-item>
          <el-form-item label="奖金(元)">
            <span>{{ fmt(lockPreview.bonus) }} = PK金 {{ fmt(lockRow?.pk_money || 0) }} × {{ lockForm.tier || '-' }}</span>
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item label="目标(元)" required>
            <el-input-number v-model="lockForm.customTargetYuan" :min="0.01" :precision="2" style="width: 100%" />
          </el-form-item>
          <el-form-item label="奖金(元)" required>
            <el-input-number v-model="lockForm.customBonusYuan" :min="0.01" :precision="2" style="width: 100%" />
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="lockDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onLock">确认锁档</el-button>
      </template>
    </el-dialog>

    <!-- 手工实绩 -->
    <el-dialog v-model="manualDialog" :title="`手工实绩 - ${manualRow?.node_name || ''}`" width="440px" append-to-body>
      <el-form label-width="90px">
        <el-form-item label="实绩(元)" required>
          <el-input-number v-model="manualForm.performanceYuan" :min="0" :precision="2" :step="10000"
            style="width: 100%" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="manualForm.remark" type="textarea" :rows="2" placeholder="口径留痕(快照生成即冻结)" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="manualDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onManualSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listPkEvents, createPkEvent, copyPkEvent, fetchPkEvent, savePkDraft, publishPkDraft,
  confirmPkEvent, resetPkEvent, voidPkEvent, upsertPkUnit, deletePkUnit, lockPkUnit,
  unlockPkUnit, savePkManual, listPkSnapshots, orgTree, fetchAddons,
} from '../../api'

const dicts = ref({})
const rows = ref([])
const filter = reactive({ status: null, cycleType: null, period: '' })
const saving = ref(false)
const tree = ref([])
const now = new Date()

const createDialog = ref(false)
const copySource = ref(null)
const createForm = reactive({ name: '', cycleType: 1, period: '', startDate: '', endDate: '', baseTier: null })

const detailDrawer = ref(false)
const detail = ref(null)
const snapshotRows = ref([])

const draftDialog = ref(false)
const draftRows = ref([])
const unitDialog = ref(false)
const unitForm = reactive({ nodeId: null, staffsText: '', guaranteeYuan: 0, baseYuan: 0, breakthroughYuan: 0, challengeYuan: 0, pkMoneyYuan: 0, manualEnabled: false })

const lockDialog = ref(false)
const lockRow = ref(null)
const lockForm = reactive({ mode: 'tier', tier: null, customTargetYuan: 0, customBonusYuan: 0 })
const manualDialog = ref(false)
const manualRow = ref(null)
const manualForm = reactive({ performanceYuan: 0, remark: '' })

const flatNodes = computed(() => {
  const out = []
  const walk = (nodes, depth) => {
    for (const n of nodes || []) {
      out.push({ id: n.id, name: '　'.repeat(depth) + n.name })
      walk(n.children, depth + 1)
    }
  }
  walk(tree.value, 0)
  return out
})

const quarterOptions = computed(() => {
  const out = []
  const y = now.getFullYear()
  for (const yy of [y, y - 1]) {
    for (let q = 1; q <= 4; q++) out.push({ value: `${yy}-Q${q}`, label: `${yy}年第${q}季度` })
  }
  return out
})

const tierNames = { guaranteeYuan: '保底', baseYuan: '冲刺', breakthroughYuan: '突破', challengeYuan: '挑战' }
const tierHeader = (t) => tierNames[t] || t

const lockPreview = computed(() => {
  const row = lockRow.value
  const tier = lockForm.tier
  if (!row || !tier) return { target: 0, bonus: 0 }
  const col = { 2: 'guarantee', 3: 'base', 4: 'breakthrough', 5: 'challenge' }[tier]
  return { target: row[col] || 0, bonus: (row.pk_money || 0) * tier }
})

const dictLabel = (list, v) => {
  const hit = (list || []).find((d) => String(d.key) === String(v))
  return hit ? hit.label : v
}
const fmt = (li) => (Number(li || 0) / 1000).toLocaleString('zh-CN', { minimumFractionDigits: 2 })
const wan = (li) => (Number(li || 0) / 10000000).toFixed(0)
const statusTag = (s) => ({ 0: 'info', 1: '', 2: 'warning', 3: 'success', 4: 'danger' }[s] ?? 'info')

async function loadDicts() {
  const res = await fetchAddons()
  const addon = (res.data || []).find((a) => a.id === 'target')
  if (addon?.dicts) dicts.value = addon.dicts
}

async function load() {
  const params = {}
  if (filter.status !== null && filter.status !== undefined) params.status = filter.status
  if (filter.cycleType !== null && filter.cycleType !== undefined) params.cycleType = filter.cycleType
  if (filter.period) params.period = filter.period
  const res = await listPkEvents(params)
  rows.value = res.data || []
}

async function loadTree() {
  const res = await orgTree()
  tree.value = res.data || []
}

function defaultPeriod(cycleType) {
  const y = now.getFullYear()
  if (cycleType === 1) {
    return `${y}-${String(now.getMonth() + 1).padStart(2, '0')}`
  }
  return `${y}-Q${Math.floor(now.getMonth() / 3) + 1}`
}

function openCreate() {
  if (tree.value.length === 0) loadTree()
  copySource.value = null
  Object.assign(createForm, { name: '', cycleType: 1, period: defaultPeriod(1), startDate: '', endDate: '', baseTier: null })
  createDialog.value = true
}

function openCopy(row) {
  if (tree.value.length === 0) loadTree()
  copySource.value = row
  const cycle = row.cycle_type === 2 ? 2 : 1
  Object.assign(createForm, { name: row.name.includes('复制') ? row.name : row.name + '(复制)',
    cycleType: cycle, period: defaultPeriod(cycle), startDate: '', endDate: '', baseTier: row.base_tier || null })
  createDialog.value = true
}

function eventPayload() {
  return { cycleType: createForm.cycleType, period: createForm.period, name: createForm.name,
    startDate: createForm.startDate, endDate: createForm.endDate, baseTier: createForm.baseTier }
}

async function onCreate() {
  if (!createForm.name) return ElMessage.warning('请填赛事名')
  if (!createForm.period) return ElMessage.warning('请选周期')
  if (!createForm.startDate || !createForm.endDate) return ElMessage.warning('请选起止日')
  saving.value = true
  try {
    if (copySource.value) {
      await copyPkEvent(copySource.value.id, eventPayload())
      ElMessage.success('已复制创建(筹备态)')
    } else {
      await createPkEvent(eventPayload())
      ElMessage.success('赛事已创建')
    }
    createDialog.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function openDetail(row) {
  const res = await fetchPkEvent(row.id)
  detail.value = res.data
  snapshotRows.value = []
  detailDrawer.value = true
  if (res.data?.status === 3) {
    const s = await listPkSnapshots(row.id)
    snapshotRows.value = s.data || []
  }
}

async function reloadDetail() {
  if (!detail.value) return
  const res = await fetchPkEvent(detail.value.id)
  detail.value = res.data
  if (res.data?.status === 3) {
    const s = await listPkSnapshots(detail.value.id)
    snapshotRows.value = s.data || []
  }
}

// ---- 模板草稿 ----

function emptyDraftRow() {
  return { orgNode: null, staffsText: '', guaranteeYuan: 0, baseYuan: 0, breakthroughYuan: 0,
    challengeYuan: 0, pkMoneyYuan: 0, manualEnabled: false }
}

function toDraftRows(units) {
  return (units || []).map((u) => ({
    orgNode: u.orgNode, staffsText: (u.staffs || []).join(','),
    guaranteeYuan: (u.guarantee || 0) / 10000000, baseYuan: (u.base || 0) / 10000000,
    breakthroughYuan: (u.breakthrough || 0) / 10000000, challengeYuan: (u.challenge || 0) / 10000000,
    pkMoneyYuan: (u.pkMoney || 0) / 10000000, manualEnabled: !!u.manualEnabled,
  }))
}

function fromDraftRows() {
  return draftRows.value.map((r) => ({
    orgNode: r.orgNode,
    staffs: (r.staffsText || '').split(/[,，]/).map((s) => s.trim()).filter(Boolean),
    guarantee: Math.round((r.guaranteeYuan || 0) * 10000000), base: Math.round((r.baseYuan || 0) * 10000000),
    breakthrough: Math.round((r.breakthroughYuan || 0) * 10000000),
    challenge: Math.round((r.challengeYuan || 0) * 10000000),
    pkMoney: Math.round((r.pkMoneyYuan || 0) * 10000000), bonusPreset: null,
    manualEnabled: r.manualEnabled ? 1 : 0,
  }))
}

async function openDraftEditor() {
  if (tree.value.length === 0) loadTree()
  let units = []
  if (detail.value.hasDraft && detail.value.draft_data) {
    try { units = JSON.parse(detail.value.draft_data) } catch (e) { units = [] }
  } else {
    units = (detail.value.units || []).map((u) => ({
      orgNode: u.org_node, staffs: safeStaffs(u.staffs), guarantee: u.guarantee, base: u.base,
      breakthrough: u.breakthrough, challenge: u.challenge, pkMoney: u.pk_money, manualEnabled: u.manual_enabled,
    }))
  }
  draftRows.value = toDraftRows(units)
  if (draftRows.value.length === 0) draftRows.value = [emptyDraftRow()]
  draftDialog.value = true
}

function safeStaffs(text) {
  try { return JSON.parse(text || '[]') } catch (e) { return [] }
}

function addDraftRow() {
  draftRows.value.push(emptyDraftRow())
}

async function onSaveDraft(publish) {
  const units = fromDraftRows()
  if (units.some((u) => !u.orgNode)) return ElMessage.warning('每行请选参赛单元')
  saving.value = true
  try {
    await savePkDraft(detail.value.id, { units })
    if (publish) {
      await publishPkDraft(detail.value.id)
      ElMessage.success('已发布落行')
    } else {
      ElMessage.success('草稿已保存')
    }
    draftDialog.value = false
    await reloadDetail()
  } finally {
    saving.value = false
  }
}

// ---- 单行模板 ----

function openUnitEdit(row) {
  Object.assign(unitForm, {
    nodeId: row.org_node, staffsText: safeStaffs(row.staffs).join(','),
    guaranteeYuan: (row.guarantee || 0) / 10000000, baseYuan: (row.base || 0) / 10000000,
    breakthroughYuan: (row.breakthrough || 0) / 10000000, challengeYuan: (row.challenge || 0) / 10000000,
    pkMoneyYuan: (row.pk_money || 0) / 10000000, manualEnabled: !!row.manual_enabled,
  })
  unitDialog.value = true
}

async function onUnitSave() {
  saving.value = true
  try {
    await upsertPkUnit(detail.value.id, unitForm.nodeId, {
      orgNode: unitForm.nodeId,
      staffs: (unitForm.staffsText || '').split(/[,，]/).map((s) => s.trim()).filter(Boolean),
      guarantee: Math.round(unitForm.guaranteeYuan * 10000000), base: Math.round(unitForm.baseYuan * 10000000),
      breakthrough: Math.round(unitForm.breakthroughYuan * 10000000),
      challenge: Math.round(unitForm.challengeYuan * 10000000),
      pkMoney: Math.round(unitForm.pkMoneyYuan * 10000000), bonusPreset: null,
      manualEnabled: unitForm.manualEnabled ? 1 : 0,
    })
    ElMessage.success('模板已保存')
    unitDialog.value = false
    await reloadDetail()
  } finally {
    saving.value = false
  }
}

async function onUnitDelete(row) {
  await ElMessageBox.confirm(`删除参赛单元「${row.node_name}」的模板行?`, '确认', { type: 'warning' })
  await deletePkUnit(detail.value.id, row.org_node)
  ElMessage.success('已删除')
  await reloadDetail()
}

// ---- 状态机动作 ----

function openLock(row) {
  lockRow.value = row
  Object.assign(lockForm, { mode: 'tier', tier: detail.value.base_tier || 2,
    customTargetYuan: 0, customBonusYuan: 0 })
  lockDialog.value = true
}

async function onLock() {
  saving.value = true
  try {
    const payload = lockForm.mode === 'tier'
      ? { tier: lockForm.tier }
      : { customTarget: Math.round(lockForm.customTargetYuan * 1000),
          customBonus: Math.round(lockForm.customBonusYuan * 1000) }
    await lockPkUnit(detail.value.id, lockRow.value.org_node, payload)
    ElMessage.success('已锁档')
    lockDialog.value = false
    await reloadDetail()
  } finally {
    saving.value = false
  }
}

async function onUnlock(row) {
  await ElMessageBox.confirm(`解锁「${row.node_name}」将清空其锁定目标与奖金?`, '确认', { type: 'warning' })
  await unlockPkUnit(detail.value.id, row.org_node)
  ElMessage.success('已解锁')
  await reloadDetail()
}

async function onReset() {
  await ElMessageBox.confirm('重置将清空全部锁档(模板保留),赛事回到筹备态?', '确认重置', { type: 'warning' })
  await resetPkEvent(detail.value.id)
  ElMessage.success('已重置')
  await reloadDetail()
}

async function onConfirm() {
  await ElMessageBox.confirm('确认开赛后进入进行中,单元不可再锁档/改模板?', '确认开赛', { type: 'warning' })
  await confirmPkEvent(detail.value.id)
  ElMessage.success('已开赛(进行中)')
  await reloadDetail()
}

async function onVoid(row) {
  const reason = await ElMessageBox.prompt('作废原因(必填,终态留痕不可恢复)', `作废赛事 - ${row.name}`,
    { type: 'warning', inputPattern: /\S+/, inputErrorMessage: '作废须填写原因' })
  await voidPkEvent(row.id, { reason: reason.value })
  ElMessage.success('已作废')
  await load()
}

// ---- 手工实绩 ----

function openManual(row) {
  manualRow.value = row
  Object.assign(manualForm, { performanceYuan: (row.manual_performance || 0) / 1000, remark: row.manual_remark || '' })
  manualDialog.value = true
}

async function onManualSave() {
  saving.value = true
  try {
    await savePkManual(detail.value.id, manualRow.value.org_node, {
      performance: Math.round(manualForm.performanceYuan * 1000), remark: manualForm.remark || null,
    })
    ElMessage.success('手工实绩已保存(快照生成即冻结)')
    manualDialog.value = false
    await reloadDetail()
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  loadDicts()
  load()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 12px; }
.spacer { flex: 1; }
.tip { color: #909399; font-size: 12px; }
</style>
