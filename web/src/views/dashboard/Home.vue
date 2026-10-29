<script setup>
// 驾驶舱占位页(非最终视觉):huashu-design 三方向初稿已出(docs/design/dashboard-dir-1/2/3.html),
// 风格拍板后按初稿实现正式看板(ECharts/结论条/健康卡);本页仅走通菜单-路由-数据链路,
// 用 Element Plus 默认组件渲染,不含任何视觉定制。
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { dashboardHome, reportMetrics, trackReportEvent } from '../../api'

const home = ref(null)
const metrics = ref([])
const loading = ref(true)

const fmtMoney = (v) =>
  v == null ? '—' : '¥' + Number(v).toLocaleString('zh-CN', { maximumFractionDigits: 0 })
const fmtRate = (v) => (v == null ? '—' : (v / 10000).toFixed(2) + '%')
const fmtPermille = (v) => (v == null ? '—' : (v / 10).toFixed(1) + '%')

const levelTag = { 0: 'success', 1: 'warning', 2: 'danger' }
const statusText = { ok: '正常', pending: '口径确认中', no_data: '暂无数据', stale: '数据延迟' }

function display(card) {
  const d = card.data || {}
  const unit = card.metric ? card.metric.unit : ''
  if (d.status !== 'ok') return statusText[d.status] || d.status
  if (unit === 'cent') return fmtMoney(d.value)
  if (unit === 'ppm') return fmtRate(d.value)
  if (unit === 'permille') return fmtPermille(d.value)
  return String(d.value ?? '—')
}

onMounted(async () => {
  try {
    const [h, m] = await Promise.all([dashboardHome(), reportMetrics()])
    home.value = h.data
    metrics.value = m.data || []
    trackReportEvent({ eventType: 'pageview', metricKey: null, payload: 'dashboard/home' }).catch(() => {})
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div v-loading="loading" style="padding: 16px">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="驾驶舱正式看板待视觉方向拍板"
      description="huashu-design 三方向初稿已出(docs/design/dashboard-dir-1/2/3.html):任选或混搭后实现正式看板(结论条/六维健康卡/双线趋势图/大区排名榜)。本页为数据链路占位,非最终视觉。"
    />
    <template v-if="home">
      <h4 style="margin: 14px 0 8px">结论条</h4>
      <el-alert
        v-for="(c, i) in home.conclusion"
        :key="i"
        :type="c.level === 2 ? 'error' : c.level === 1 ? 'warning' : 'success'"
        :title="c.text"
        :closable="false"
        style="margin-bottom: 6px"
      />
      <h4 style="margin: 14px 0 8px">六维健康卡</h4>
      <el-row :gutter="10">
        <el-col v-for="card in home.cards" :key="card.key" :span="8" style="margin-bottom: 10px">
          <el-card shadow="never" body-style="padding: 12px">
            <div style="display: flex; justify-content: space-between; align-items: center">
              <span>{{ card.metric ? card.metric.name : card.key }}</span>
              <el-tag v-if="card.data" :type="levelTag[card.data.level] || 'info'" size="small">
                {{ statusText[card.data.status] || card.data.status }}
              </el-tag>
            </div>
            <div style="font-size: 22px; margin-top: 6px">{{ display(card) }}</div>
            <div v-if="card.data && card.data.asOf" style="color: #999; font-size: 12px">
              asOf {{ card.data.asOf }}
            </div>
          </el-card>
        </el-col>
      </el-row>
      <h4 style="margin: 14px 0 8px">签约-回款双线(近 6 月,厘)</h4>
      <el-table :data="home.dualLines.months.map((m, i) => ({ month: m, sign: home.dualLines.sign[i].value, collection: home.dualLines.collection[i].value }))" size="small" border>
        <el-table-column prop="month" label="月份" width="120" />
        <el-table-column prop="sign" label="签约额" />
        <el-table-column prop="collection" label="回款额" />
      </el-table>
      <h4 style="margin: 14px 0 8px">大区签约额排名</h4>
      <el-table v-if="home.regionRank && (home.regionRank.series || []).length" :data="home.regionRank.series" size="small" border>
        <el-table-column type="index" label="#" width="60" />
        <el-table-column prop="label" label="大区" />
        <el-table-column prop="value" label="签约额(厘)" />
      </el-table>
      <el-alert v-else type="info" :closable="false" title="大区排名暂无数据(单据公司级归属,大区维随归属模型点亮)" />
    </template>
    <h4 style="margin: 14px 0 8px">指标目录(五要素元数据)</h4>
    <el-table :data="metrics" size="small" border>
      <el-table-column prop="key" label="指标 key" min-width="220" />
      <el-table-column prop="name" label="名称" min-width="120" />
      <el-table-column prop="status" label="口径状态" width="100" />
      <el-table-column prop="frequency" label="频率" width="90" />
      <el-table-column prop="unit" label="单位" width="80" />
      <el-table-column prop="addonId" label="归属插件" width="100" />
    </el-table>
  </div>
</template>
