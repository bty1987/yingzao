<script setup>
// 主材销售工作台(W2,docs/prd/addon-dashboard-workbench.md §三.3):分角色驾驶舱首作,
// 与 D4 驾驶舱同族「账册·版框」(淡墨单线框卡 2px 方角无投影+朱印方章,token 逐字 yingzao.css),
// 管理页判例不走 huashu(第 123 次拍板口径)。
// 数据 = /api/dashboard/material/summary(KPI+漏斗+预警+目标拆分)+ /material/tracking(跟单明细),
// 全只读聚合;阶段判定后端 W1 纯函数,页面只呈报——红仅用于异常(逾期/落后),绿=苔绿功能色。
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { materialSummary, materialTracking, fetchAddons } from '../../api'

const summary = ref(null)
const loading = ref(true)
const dicts = ref({})
const month = ref(new Date().toISOString().slice(0, 7))
const node = ref(null)

const track = reactive({ stage: null, keyword: '', page: 1, size: 20 })
const tracking = ref(null)
const trackLoading = ref(false)

const warn = '#856011'
const STAGE_COLORS = ['rgba(26,26,24,.25)', 'rgba(26,26,24,.45)', '#5A7247', '#4c6039', '#1A1A18']

const dictLabel = (list, v) => {
  const hit = (list || []).find((d) => String(d.key) === String(v))
  return hit ? hit.label : v
}
const wan = (li) => (Number(li || 0) / 10000000).toLocaleString('zh-CN', { maximumFractionDigits: 1 })

const flatNodes = computed(() => {
  const out = []
  const walk = (list, parentId, depth) => {
    for (const n of list.filter((x) => x.parentId === parentId)) {
      out.push({ id: n.id, name: '　'.repeat(depth) + n.name })
      walk(list, n.id, depth + 1)
    }
  }
  if (summary.value?.nodes) walk(summary.value.nodes, null, 0)
  return out
})

const kpi = computed(() => summary.value?.kpi || {})
// 业绩轨:达成率%(万分比→%),时间进度刻度;落后(未追上时间进度)变朱
const rateBar = computed(() => {
  const k = kpi.value
  if (!k.hasTarget || k.ratePpm == null) return null
  const pct = Math.min(k.ratePpm / 100, 100)
  const tp = Math.min((k.timeProgressPpm || 0) / 100, 100)
  return { pct, tp, breached: k.lagging }
})

async function loadDicts() {
  const res = await fetchAddons()
  const addon = (res.data || []).find((a) => a.id === 'dashboard')
  if (addon?.dicts) dicts.value = addon.dicts
}

async function loadSummary() {
  const params = {}
  if (month.value) params.ym = month.value
  if (node.value) params.node = node.value
  const res = await materialSummary(params)
  summary.value = res.data
}

async function loadTracking() {
  trackLoading.value = true
  try {
    const params = { page: track.page, size: track.size }
    if (node.value) params.node = node.value
    if (track.stage) params.stage = track.stage
    if (track.keyword) params.keyword = track.keyword
    const res = await materialTracking(params)
    tracking.value = res.data
  } finally {
    trackLoading.value = false
  }
}

function pickStage(s) {
  track.stage = track.stage === s ? null : s
  track.page = 1
  loadTracking()
}

function searchTracking() {
  track.page = 1
  loadTracking()
}

function onMonthChange() {
  loadSummary().catch((e) => ElMessage.error(e.message || '加载失败'))
}

function onNodeChange() {
  loadSummary().catch(() => {})
  track.page = 1
  loadTracking()
}

function dwellClass(row) {
  const overdue = row.stage <= 4 && row.dwellDays > (summary.value?.alerts?.dwellThresholdDays ?? 30)
  return overdue ? 'dwell-over' : ''
}

onMounted(async () => {
  try {
    await loadDicts()
    await Promise.all([loadSummary(), loadTracking()])
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div v-loading="loading" class="wbench">
    <template v-if="summary">
      <!-- 头部:朱印方章 + 月份 + 组织范围 -->
      <div class="head">
        <span class="seal yz-display">材</span>
        <h3 class="yz-display title">主材工作台</h3>
        <span class="fill" />
        <el-select v-model="node" placeholder="组织范围" clearable filterable style="width: 180px" @change="onNodeChange">
          <el-option v-for="n in flatNodes" :key="n.id" :label="n.name" :value="n.id" />
        </el-select>
        <el-date-picker v-model="month" type="month" value-format="YYYY-MM" style="width: 130px" @change="onMonthChange" />
      </div>

      <!-- KPI 卡族:业绩达成 / 跟单户数 / 异常预警 -->
      <div class="cards3">
        <div class="card">
          <div class="name yz-display">本月主材业绩(开单口径)</div>
          <div class="num yz-display tabular">{{ wan(kpi.actual) }}<small> 万</small></div>
          <div v-if="rateBar" class="bar">
            <i :style="{ width: rateBar.pct + '%', background: rateBar.breached ? 'var(--yz-seal)' : '#5A7247' }" />
            <s :style="{ left: rateBar.tp + '%' }" />
          </div>
          <div class="sub">
            <template v-if="kpi.hasTarget">
              目标 {{ wan(kpi.target) }} 万 · 缺口 {{ wan(kpi.gap) }} 万 ·
              达成 {{ kpi.ratePpm == null ? '—' : (kpi.ratePpm / 100).toFixed(1) + '%' }}
              · 时间进度 {{ ((kpi.timeProgressPpm || 0) / 100).toFixed(0) }}%
            </template>
            <template v-else>本月未立主材目标(target 主材线),仅呈实绩</template>
          </div>
        </div>
        <div class="card">
          <div class="name yz-display">跟单户数</div>
          <div class="num yz-display tabular">{{ summary.tracked.total }}<small> 户</small></div>
          <div class="sub">临近签约 {{ summary.tracked.nearSign }} 户(阶段②签约待开工) · 阶段判定=状态推导只读</div>
        </div>
        <div class="card">
          <div class="name yz-display">异常预警</div>
          <div class="num yz-display tabular" :class="{ 'num-alert': summary.alerts.overdue > 0 }">
            {{ summary.alerts.overdue }}
          </div>
          <div class="sub">
            逾期跟进(停留 &gt; {{ summary.alerts.dwellThresholdDays }} 天,阶段①~④)
            <template v-if="summary.alerts.targetLagging"> · <b class="alert-text">业绩落后时间进度</b></template>
          </div>
        </div>
      </div>

      <!-- 五阶段漏斗条(点击下钻跟单列表) -->
      <div class="card">
        <div class="card-h">
          <span class="t yz-display">五阶段跟单漏斗</span>
          <span class="n">装修单维度 · 状态推导 · 点击阶段筛选明细</span>
        </div>
        <div class="funnel">
          <div
            v-for="(f, i) in summary.funnel"
            :key="f.stage"
            class="seg"
            :class="{ picked: track.stage === f.stage }"
            :style="{ flexGrow: Math.max(f.count, 0) + 0.6 }"
            @click="pickStage(f.stage)"
          >
            <div class="seg-count yz-display tabular">{{ f.count }}</div>
            <div class="seg-label">{{ dictLabel(dicts.materialStage, f.stage) }}</div>
            <i class="seg-fill" :style="{ background: STAGE_COLORS[i] }" />
          </div>
        </div>
      </div>

      <!-- 跟单明细表 -->
      <div class="card">
        <div class="card-h">
          <span class="t yz-display">跟单明细</span>
          <span class="n" v-if="track.stage">已筛阶段 {{ dictLabel(dicts.materialStage, track.stage) }}(再点漏斗取消)</span>
          <span class="fill" />
          <el-input
            v-model="track.keyword" placeholder="客户 / 楼盘 / 单号" clearable style="width: 200px"
            @keyup.enter="searchTracking" @clear="searchTracking" />
          <el-button @click="searchTracking">查询</el-button>
        </div>
        <el-table v-loading="trackLoading" :data="tracking?.rows || []" size="small" border>
          <el-table-column prop="orderNo" label="装修单号" width="150" />
          <el-table-column prop="customerName" label="客户" min-width="100" />
          <el-table-column prop="houseLabel" label="楼盘" min-width="140" show-overflow-tooltip />
          <el-table-column label="阶段" width="110">
            <template #default="{ row }">
              <span class="stage-dot" :style="{ background: STAGE_COLORS[row.stage - 1] }" />
              {{ dictLabel(dicts.materialStage, row.stage) }}
            </template>
          </el-table-column>
          <el-table-column label="停留天数" width="100" align="right">
            <template #default="{ row }">
              <span :class="dwellClass(row)">{{ row.dwellDays }} 天</span>
            </template>
          </el-table-column>
          <el-table-column prop="designer" label="设计师" width="100">
            <template #default="{ row }">{{ row.designer || '—' }}</template>
          </el-table-column>
          <el-table-column prop="salesman" label="业务员" width="100">
            <template #default="{ row }">{{ row.salesman || '—' }}</template>
          </el-table-column>
          <el-table-column prop="createdAt" label="登记日" width="110" />
        </el-table>
        <el-pagination
          v-if="(tracking?.total || 0) > track.size"
          class="pager"
          layout="total, prev, pager, next"
          :total="tracking?.total || 0"
          :page-size="track.size"
          :current-page="track.page"
          @current-change="(p) => { track.page = p; loadTracking() }" />
      </div>

      <!-- 目标拆分视图(主材线,embedded) -->
      <div class="card">
        <div class="card-h">
          <span class="t yz-display">主材目标拆分 · {{ month }}</span>
          <span class="n">逐节点目标与子树实绩(实绩=开单口径) · 目标维护在经营目标页</span>
        </div>
        <el-table v-if="(summary.plans || []).length" :data="summary.plans" size="small" border>
          <el-table-column prop="nodeName" label="节点" min-width="140" />
          <el-table-column label="目标(万)" width="120" align="right">
            <template #default="{ row }">{{ wan(row.amount) }}</template>
          </el-table-column>
          <el-table-column label="实绩(万)" width="120" align="right">
            <template #default="{ row }">{{ row.status === 0 ? wan(row.actual) : '—' }}</template>
          </el-table-column>
          <el-table-column label="达成率" width="200">
            <template #default="{ row }">
              <el-progress
                v-if="row.status === 0 && row.ratePpm != null"
                :percentage="Math.min(row.ratePpm / 100, 100)"
                :stroke-width="8"
                :format="() => (row.ratePpm / 100).toFixed(1) + '%'"
                :color="row.ratePpm < (kpi.timeProgressPpm || 0) ? warn : '#5A7247'" />
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 0 ? 'success' : 'info'" size="small">
                {{ row.status === 0 ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
        <div v-else class="empty">本月暂无主材线目标——在「目标计划」页按 material 业绩线立标后,此处逐节点拆分呈现</div>
      </div>

      <!-- 口径脚注(V1 诚实边界) -->
      <div class="foot">
        <div>阶段判定=状态推导只读(零新表):①活跃报价 ②合同线已签未开工 ③开工 ④工程完工未全完结 ⑤销售全完结或完工决算;废单/终止退出跟单</div>
        <div>停留天数锚点:①登记 ②首签 ③④⑤最近状态变更/完工——工程开工无原生时间戳,以单据更新近似(V1 诚实边界)</div>
        <div>业绩实绩=当月开单额(剔除废退含完结,口径同经营目标主材线);户数目标 V2;跟进记录写操作 V1 不做</div>
      </div>
    </template>
  </div>
</template>

<style scoped>
/* ── 账册·版框(与 Home.vue 同族):淡墨单线框卡+朱印方章;token 逐字 yingzao.css ── */
.wbench {
  --warn: #856011; /* 深赭(体系内衍生暖色,过 4.5:1) */
  padding: 20px 22px 28px;
}
.tabular { font-variant-numeric: tabular-nums; }
.fill { flex: 1; }

.head { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; }
.seal {
  width: 34px;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--yz-seal);
  color: var(--yz-paper);
  font-size: 19px;
}
.title { margin: 0; font-size: 20px; }

.card {
  background: var(--yz-paper);
  border: 2px solid var(--yz-line-strong);
  border-radius: var(--yz-radius);
  padding: 14px 18px;
  margin-bottom: 12px;
}
.card-h { display: flex; align-items: baseline; gap: 10px; margin-bottom: 10px; }
.card-h .t { font-size: 15px; }
.card-h .n { font-size: 12px; color: var(--yz-ink-50); }

.cards3 { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-bottom: 12px; }
.cards3 .card { margin-bottom: 0; }
.name { font-size: 14px; color: var(--yz-ink-62); }
.num { font-size: 26px; color: var(--yz-ink); margin-top: 4px; }
.num small { font-size: 13px; color: var(--yz-ink-50); }
.num-alert { color: var(--yz-seal); }
.sub { font-size: 12px; color: var(--yz-ink-50); margin-top: 6px; }
.alert-text { color: var(--yz-seal); }

/* 8px 达成轨 + 时间进度刻度(落后变朱=异常) */
.bar {
  position: relative;
  height: 8px;
  background: var(--yz-paper-deep);
  border: 1px solid var(--yz-line);
  margin-top: 10px;
}
.bar i { display: block; height: 100%; }
.bar s {
  position: absolute;
  top: -3px;
  bottom: -3px;
  width: 2px;
  background: var(--yz-ink);
  text-decoration: none;
}

/* 五阶段漏斗条:分段弹性宽,点选下钻 */
.funnel { display: flex; gap: 6px; }
.seg {
  position: relative;
  padding: 12px 12px 22px;
  border: 1px solid var(--yz-line);
  background: var(--yz-paper);
  cursor: pointer;
  overflow: hidden;
  min-width: 0;
}
.seg:hover { border-color: var(--yz-ink-35); }
.seg.picked { border-color: var(--yz-seal); box-shadow: inset 0 0 0 1px var(--yz-seal); }
.seg-count { font-size: 22px; color: var(--yz-ink); position: relative; z-index: 1; }
.seg-label { font-size: 12px; color: var(--yz-ink-62); position: relative; z-index: 1; margin-top: 2px; }
.seg-fill { position: absolute; left: 0; bottom: 0; height: 4px; width: 100%; opacity: .8; }

.stage-dot { display: inline-block; width: 8px; height: 8px; margin-right: 6px; }
.dwell-over { color: var(--yz-seal); }
.pager { margin-top: 10px; justify-content: flex-end; }
.empty { padding: 26px 10px; text-align: center; color: var(--yz-ink-50); font-size: 13px; }

.foot {
  border-top: 1px solid var(--yz-line);
  padding-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 14px;
  color: var(--yz-ink-50);
}

@media (max-width: 1100px) {
  .cards3 { grid-template-columns: 1fr; }
}
</style>
