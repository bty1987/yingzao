<script setup>
// 驾驶舱正式看板(D4,docs/15 R38~R42 余项):拍板=方向二「账册·版框」(2026-11-01,
// 淡墨单线框卡+头部朱印方章,与 Users/Plans 管理页同族),token 逐字取自 yingzao.css。
// 数据全走第 104 次既有聚合(/api/dashboard/home 六卡+结论条+双线+大区排名 + /api/report/metrics 目录),
// 零后端改动。docs/06 §5 图表硬规则:语义色仅功能性(正常=苔绿/预警=深赭/告警=朱印红,
// 红色仅用于偏离阈值);状态=图标+文字双编码;排名 ≤8 名第 1 名朱标;口径 pending 不以估算出图。
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { dashboardHome, reportMetrics, trackReportEvent } from '../../api'

echarts.use([LineChart, GridComponent, TooltipComponent, CanvasRenderer])

const home = ref(null)
const metrics = ref([])
const loading = ref(true)
const chartEl = ref(null)
let chart = null

// 六维卡维度名(与后端 CARD_KEYS 注释一一对应)
const DIM = {
  'decoration.contract.signedMonthly': '业绩营销',
  'target.achievement.monthly': '经营管理',
  'construction.workload.dailyRate': '工程交付',
  'sales.performance.materialMonthly': '供应链',
  'payment.collection.operatingMonthly': '财务',
  'customer.funnel.stages': '客户'
}
// 结论条锚点 KPI×4(初稿口径:签约额/回款额/达成率/工单达成)
const KPI_KEYS = [
  ['decoration.contract.signedMonthly', '签约额'],
  ['payment.collection.operatingMonthly', '回款额'],
  ['target.achievement.monthly', '达成率'],
  ['construction.workload.dailyRate', '工单达成']
]
const STATUS_TEXT = { ok: '正常', pending: '口径确认中', no_data: '暂无数据', stale: '数据延迟' }
// badge 文字按告警级别(图标+文字双编码;红色仅用于偏离阈值)
function levelText(card) {
  const d = card?.data
  if (!d) return '—'
  if (d.status === 'ok') return d.level >= 2 ? '告警' : d.level === 1 ? '预警' : '正常'
  return STATUS_TEXT[d.status] || d.status
}
function levelClass(card) {
  const d = card?.data
  if (!d) return 'off'
  if (d.status === 'ok') return d.level >= 2 ? 'crit' : d.level === 1 ? 'warn' : 'ok'
  return d.status === 'pending' ? 'pend' : 'off'
}

// ── 值格式化(厘→万元;ppm→%;permille→%;零值/缺数=—) ──
function fmtValue(unit, v) {
  if (v == null) return '—'
  if (unit === 'cent') return wan(v)
  if (unit === 'ppm') return (v / 100).toLocaleString('zh-CN', { maximumFractionDigits: 1 }) + '%'
  if (unit === 'permille') return (v / 10).toLocaleString('zh-CN', { maximumFractionDigits: 1 }) + '%'
  return String(v)
}
function wan(li) {
  return (li / 10000000).toLocaleString('zh-CN', { maximumFractionDigits: 1 })
}
function cardBy(key) {
  return (home.value?.cards || []).find((c) => c.key === key)
}
function cardOk(card) {
  return card && card.data && card.data.status === 'ok' && card.data.value != null
}
// 达成率卡的 8px 轨:百分比宽 + 阈值刻度位置(ppm→%),越线变色
function rateCard(card) {
  if (!cardOk(card)) return null
  const d = card.data
  const unit = d.unit || (card.metric || {}).unit
  if (unit !== 'ppm' && unit !== 'permille') return null
  const div = unit === 'ppm' ? 100 : 10
  const pct = d.value / div
  const warn = (card.metric?.thresholdWarn || 0) / div
  const crit = (card.metric?.thresholdCritical || 0) / div
  const breached = crit > 0 && pct < crit
  return { pct: Math.max(0, Math.min(pct, 100)), warn, crit, breached }
}

function renderChart() {
  const dl = home.value?.dualLines
  if (!dl || !chartEl.value) return
  if (!chart) chart = echarts.init(chartEl.value)
  const ser = (name, pts, color) => ({
    name,
    type: 'line',
    data: pts.map((p) => +(p.value / 10000000).toFixed(2)),
    lineStyle: { color, width: 2 },
    itemStyle: { color },
    symbolSize: 5,
    endLabel: {
      show: true,
      formatter: ({ value }) => Number(value).toLocaleString('zh-CN'),
      color,
      fontFamily: 'Songti SC, SimSun, serif',
      fontSize: 12,
      distance: 8
    }
  })
  chart.setOption({
    grid: { left: 52, right: 64, top: 18, bottom: 30 },
    tooltip: {
      trigger: 'axis',
      valueFormatter: (v) => '¥' + Number(v).toLocaleString('zh-CN') + ' 万'
    },
    xAxis: {
      type: 'category',
      data: dl.months.map((m) => +m.split('-')[1] + '月'),
      axisLine: { lineStyle: { color: 'rgba(26,26,24,.25)' } },
      axisTick: { show: false },
      axisLabel: { color: 'rgba(26,26,24,.62)', fontSize: 12 }
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: 'rgba(26,26,24,.10)' } },
      axisLabel: {
        color: 'rgba(26,26,24,.62)',
        fontSize: 12,
        formatter: (v) => v.toLocaleString('zh-CN')
      }
    },
    series: [
      ser('签约', dl.sign, '#1A1A18'),
      ser('回款', dl.collection, '#5A7247')
    ]
  })
}

onMounted(async () => {
  try {
    const [h, m] = await Promise.all([dashboardHome(), reportMetrics()])
    home.value = h.data
    metrics.value = m.data || []
    trackReportEvent({ eventType: 'pageview', metricKey: null, payload: 'dashboard/home' }).catch(() => {})
    // v-if="home" 容器需先挂载再 init(ECharts 容器必须在 DOM)
    await nextTick()
    renderChart()
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
})
onBeforeUnmount(() => {
  if (chart) chart.dispose()
})
</script>

<template>
  <div v-loading="loading" class="home">
    <template v-if="home">
      <!-- 头部:标题 + 朱印方章(方向二签名)+ 时点 -->
      <div class="head">
        <span class="seal yz-display">营</span>
        <h3 class="yz-display title">驾驶舱</h3>
        <span class="fill" />
        <span class="asof">更新于 {{ (home.cards || [])[0]?.data?.asOf?.slice(11, 16) || '—' }}</span>
      </div>

      <!-- 区块1 结论条卡(异常置顶,朱侧线;全绿=苔绿) -->
      <div
        v-for="(c, i) in home.conclusion"
        :key="i"
        class="card conclusion"
        :class="c.level === 2 ? 'lv2' : c.level === 1 ? 'lv1' : 'lv0'"
      >
        <div class="mark" />
        <div class="ctext">{{ c.text }}</div>
        <div class="kpis">
          <div v-for="[key, label] in KPI_KEYS" :key="key" class="kpi">
            <div class="l">{{ label }}</div>
            <div class="v yz-display tabular">{{ fmtValue(cardBy(key)?.data?.unit, cardBy(key)?.data?.value) }}</div>
          </div>
        </div>
      </div>

      <!-- 区块2 六维健康卡 -->
      <div class="cards6">
        <div
          v-for="card in home.cards"
          :key="card.key"
          class="card dim"
          :class="{ dashed: card.data?.status === 'pending' }"
        >
          <div class="badge" :class="levelClass(card)">
            <i />
            {{ levelText(card) }}
          </div>
          <div class="name yz-display">{{ DIM[card.key] || card.key }}</div>
          <div class="num yz-display tabular">
            <template v-if="card.data?.status === 'pending'">漏斗口径定稿后接入</template>
            <template v-else-if="cardOk(card)">{{ fmtValue(card.data.unit, card.data.value) }}<small> {{ card.data.unit === 'cent' ? '万' : '' }}</small></template>
            <template v-else>{{ STATUS_TEXT[card.data?.status] || '—' }}</template>
          </div>
          <!-- 达成率类卡:8px 轨 + 阈值刻度(黄线/红线),越线变朱 -->
          <div v-if="rateCard(card)" class="bar">
            <i :style="{ width: rateCard(card).pct + '%', background: rateCard(card).breached ? 'var(--yz-seal)' : 'var(--warn)' }" />
            <s
              v-if="rateCard(card).warn > 0 && rateCard(card).warn < 100"
              :style="{ left: rateCard(card).warn + '%' }"
            />
            <s
              v-if="rateCard(card).crit > 0 && rateCard(card).crit < 100"
              :style="{ left: rateCard(card).crit + '%', background: 'var(--yz-ink)' }"
            />
          </div>
          <div class="sub">
            {{ card.metric?.name || card.key }}
            <template v-if="card.data?.status === 'pending'"> · 宁可空不可错</template>
            <template v-else-if="card.data?.status === 'stale'"> · 数据延迟,以最新批次为准</template>
          </div>
        </div>
      </div>

      <!-- 区块3+4 双线 + 大区排名 -->
      <div class="duo">
        <div class="card">
          <div class="card-h">
            <span class="t yz-display">签约与回款双线</span>
            <span class="n">近 6 月 · 万元</span>
            <span class="fill" />
            <span class="legend">
              <span><i class="lg-sign" />签约</span>
              <span><i class="lg-coll" />回款</span>
            </span>
          </div>
          <div ref="chartEl" class="chart" />
        </div>
        <div class="card">
          <div class="card-h">
            <span class="t yz-display">大区签约额排名</span>
            <span class="n">当月 ≤8 名 · 第 1 名朱标</span>
          </div>
          <div v-if="(home.regionRank?.series || []).length" class="rank-list">
            <div
              v-for="(r, i) in home.regionRank.series.slice(0, 8)"
              :key="r.label"
              class="rank-row"
              :class="{ first: i === 0 }"
            >
              <div class="no yz-display">{{ i + 1 }}</div>
              <div class="nm">{{ r.label }}</div>
              <div class="bar">
                <i :style="{ width: (r.value / Math.max(...home.regionRank.series.map((x) => x.value || 1), 1)) * 100 + '%' }" />
              </div>
              <div class="val yz-display tabular">{{ wan(r.value) }} 万</div>
            </div>
          </div>
          <div v-else class="rank-empty">大区排名暂无数据——单据公司级归属,大区维随归属模型点亮</div>
        </div>
      </div>

      <!-- 口径脚注 -->
      <div class="foot">
        <div>
          指标目录 {{ metrics.length }} 项,其中
          <b>{{ metrics.filter((x) => x.status === 'pending').length }} 项口径确认中</b>(灰灯,不以估算出图);
          状态=图标+文字双编码,红色仅用于偏离阈值
        </div>
        <div>混合时点:契约查询期直出=即时,ads 聚合=次日 03:00 批(工单当日达成率为 T+1 日粒度)</div>
      </div>

      <!-- 指标目录(透明面,折叠) -->
      <el-collapse class="catalog">
        <el-collapse-item title="指标目录(五要素元数据)" name="cat">
          <el-table :data="metrics" size="small" border>
            <el-table-column prop="key" label="指标 key" min-width="220" />
            <el-table-column prop="name" label="名称" min-width="120" />
            <el-table-column prop="status" label="口径状态" width="110" />
            <el-table-column prop="frequency" label="频率" width="90" />
            <el-table-column prop="unit" label="单位" width="90" />
            <el-table-column prop="addonId" label="归属插件" width="110" />
          </el-table>
        </el-collapse-item>
      </el-collapse>
    </template>
  </div>
</template>

<style scoped>
/* ── 方向二「账册·版框」:淡墨单线框卡(2px 方角无投影)+ 朱印方章;token 逐字 yingzao.css ── */
.home {
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
.asof { color: var(--yz-ink-50); font-size: 12px; }

/* 框卡基形:2px 方角、无投影、纸色 */
.card {
  background: var(--yz-paper);
  border: 2px solid var(--yz-line-strong);
  border-radius: var(--yz-radius);
  padding: 14px 18px;
}

/* 区块1 结论条 */
.conclusion { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; margin-bottom: 14px; }
.conclusion .mark { width: 4px; align-self: stretch; flex-shrink: 0; }
.conclusion.lv0 .mark { background: var(--el-color-success); }
.conclusion.lv1 .mark { background: var(--warn); }
.conclusion.lv2 .mark { background: var(--yz-seal); }
.ctext { flex: 1 1 320px; font-size: 14px; color: var(--yz-ink-62); line-height: 1.7; }
.kpis { display: flex; gap: 26px; flex-wrap: wrap; }
.kpi .l { font-size: 12px; color: var(--yz-ink-50); }
.kpi .v { font-size: 20px; color: var(--yz-ink); }

/* 区块2 六维健康卡 */
.cards6 {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-bottom: 14px;
}
.card.dim { position: relative; }
.card.dim.dashed { border-style: dashed; border-color: var(--yz-line); }
.badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  padding: 2px 0;
  color: var(--yz-ink-50);
}
.badge i { width: 8px; height: 8px; border-radius: 50%; }
.badge.ok { color: var(--el-color-success); }
.badge.ok i { background: var(--el-color-success); }
.badge.warn { color: var(--warn); }
.badge.warn i { background: var(--warn); }
.badge.crit { color: var(--yz-seal); }
.badge.crit i { background: var(--yz-seal); }
.badge.pend, .badge.off { color: var(--yz-ink-50); }
.badge.pend i, .badge.off i { background: var(--yz-ink-35); }
.name { font-size: 14px; color: var(--yz-ink-62); margin-top: 6px; }
.num { font-size: 26px; color: var(--yz-ink); margin-top: 2px; }
.num small { font-size: 13px; color: var(--yz-ink-50); }
.sub { font-size: 12px; color: var(--yz-ink-50); margin-top: 6px; }
/* 8px 轨 + 阈值刻度 */
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
  background: var(--warn);
  text-decoration: none;
}

/* 区块3+4 */
.duo {
  display: grid;
  grid-template-columns: 3fr 2fr;
  gap: 12px;
  margin-bottom: 14px;
}
.card-h { display: flex; align-items: baseline; gap: 10px; }
.card-h .t { font-size: 15px; }
.card-h .n { font-size: 12px; color: var(--yz-ink-50); }
.legend { display: flex; gap: 14px; font-size: 12px; color: var(--yz-ink-62); }
.legend i { display: inline-block; width: 14px; height: 2px; vertical-align: middle; margin-right: 4px; }
.lg-sign { background: var(--yz-ink); }
.lg-coll { background: var(--el-color-success); }
.chart { width: 100%; height: 300px; }

.rank-list { margin-top: 10px; display: flex; flex-direction: column; gap: 9px; }
.rank-row { display: flex; align-items: center; gap: 10px; }
.rank-row .no { width: 20px; text-align: center; font-size: 14px; color: var(--yz-ink-62); }
.rank-row .nm { width: 72px; font-size: 13px; color: var(--yz-ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rank-row .bar { flex: 1; height: 8px; background: var(--yz-paper-deep); border: 1px solid var(--yz-line); }
.rank-row .bar i { display: block; height: 100%; background: var(--yz-ink-35); }
.rank-row .val { width: 84px; text-align: right; font-size: 13px; }
.rank-row.first .no { color: var(--yz-seal); font-weight: 700; }
.rank-row.first .nm { color: var(--yz-seal); }
.rank-row.first .bar i { background: var(--yz-seal); }
.rank-empty { padding: 48px 10px; text-align: center; color: var(--yz-ink-50); font-size: 13px; }

/* 口径脚注 + 目录 */
.foot {
  border-top: 1px solid var(--yz-line);
  padding-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 14px;
  color: var(--yz-ink-50);
}
.foot b { color: var(--yz-ink-62); }
.catalog { margin-top: 12px; border: 1px solid var(--yz-line); border-radius: var(--yz-radius); }

@media (max-width: 1100px) {
  .cards6, .duo { grid-template-columns: 1fr; }
}
</style>
