<template>
  <view>
    <!-- 帖面(方向三轻量双框:外强墨线内淡墨线,web-mp 名帖页同语);当前节点宋体第一主角 -->
    <view class="card panel">
      <view class="panel-inner">
        <view class="head">
          <text class="name yz-display">{{ o.customer_name || '…' }}</text>
          <text class="tag" :class="o.process === 1 ? 'tag-success' : 'tag-info'">
            {{ o.process === 1 ? '已竣工' : '施工中' }}
          </text>
        </view>
        <text class="cell-sub">{{ o.order_no }} · {{ o.house_label }}</text>
        <view class="meta-row">
          <text class="cell-sub">监理 {{ o.supervisor_name || '—' }} · 工期 {{ days }} 天</text>
        </view>
        <view class="progress-line">
          <view class="bar">
            <view class="fill" :style="{ width: pct + '%' }" />
          </view>
          <text class="pct">节点 {{ nodeDone }}/{{ nodeTotal }}</text>
        </view>
      </view>
    </view>

    <!-- 墨线 tab(方向二骨架,与 approvals 同构),按需加载 -->
    <view class="tabs">
      <text
        v-for="t in tabs"
        :key="t.key"
        class="tab"
        :class="{ active: tab === t.key }"
        @click="switchTab(t.key)"
      >{{ t.label }}</text>
    </view>

    <!-- 概览:验收 + 操作轨迹 -->
    <view v-if="tab === 'overview'">
      <view class="card">
        <view class="head">
          <text class="cell-title">验收</text>
          <text v-if="acceptance" class="tag" :class="acceptance.status === 1 ? 'tag-success' : 'tag-danger'">
            {{ acceptance.status === 1 ? '验收完工' : '整改中' }}
          </text>
          <text v-else class="tag tag-info">未验收</text>
        </view>
        <view v-if="acceptance" class="cell-sub">
          {{ acceptance.checker_name }} · {{ acceptance.check_time || acceptance.created_at }}
          <text v-if="acceptance.remark"> · {{ acceptance.remark }}</text>
        </view>
        <view v-for="(it, i) in acceptanceItems" :key="i" class="item-row">
          <text class="item-name">{{ it.name }}</text>
          <text class="tag" :class="it.pass === 1 ? 'tag-success' : 'tag-danger'">
            {{ it.pass === 1 ? '合格' : '不合格' }}
          </text>
        </view>
      </view>
      <view class="card">
        <text class="cell-title">操作轨迹</text>
        <yz-timeline :items="trackItems" />
      </view>
    </view>

    <!-- 节点:账册表格 + 行内操作(方向二);免检=朱批带(方向三) -->
    <view v-if="tab === 'nodes'">
      <view v-for="s in schedules" :key="s.id" class="card node">
        <view class="head">
          <text class="cell-title">{{ s.sort }}. {{ s.node_name }}</text>
          <text class="tag" :class="nodeTag(s.status)">{{ nodeLabel(s.status) }}</text>
        </view>
        <view class="cell-sub">
          计划 {{ fmtDate(s.plan_start) }} ~ {{ fmtDate(s.plan_end) }}
          <text v-if="s.time_limit"> · 工期 {{ s.time_limit / 1000 }} 天</text>
        </view>
        <view class="cell-sub" v-if="s.start_time || s.end_time">
          <text v-if="s.start_time">开工 {{ fmtDate(s.start_time) }}</text>
          <text v-if="s.end_time"> · 完工 {{ fmtDate(s.end_time) }}</text>
        </view>
        <view class="cell-sub" v-if="s.labor_fee || s.workdays">
          工费 ¥{{ yuan(s.labor_fee) }} · 工日 {{ s.workdays }}
        </view>
        <!-- 朱批带:免检(纸深底+朱侧线+批印) -->
        <view v-if="s.checkout === 1" class="zhupi">
          <text class="seal-char yz-display">批</text>
          <text class="zhupi-text">免检 · {{ s.checkout_reason }}({{ s.checkout_operator }})</text>
        </view>
        <view v-if="s.status === 0 || s.status === 1 || s.status === 3" class="actions">
          <button v-if="s.status === 0" class="btn-mini" @click="beginNode(s)">开工</button>
          <button v-if="s.status === 1 || s.status === 3" class="btn-mini success" @click="finishNode(s)">完工</button>
        </view>
      </view>
    </view>

    <!-- 日记:稿纸书写 + 流水(方向三稿纸) -->
    <view v-if="tab === 'diary'">
      <view class="card write">
        <view class="head">
          <text class="cell-title">记一笔</text>
          <picker mode="selector" :range="scheduleLabels" :value="scheduleIndex" @change="onPickSchedule">
            <view class="picker-val">
              <text>{{ scheduleLabels[scheduleIndex] || '选节点' }}</text>
              <text class="arrow">▾</text>
            </view>
          </picker>
        </view>
        <textarea
          v-model="diaryContent"
          class="paper"
          maxlength="500"
          placeholder="今日工地进度、到场人员、注意事项…"
          placeholder-class="paper-ph"
        />
        <yz-upload-image
          v-model="diaryImages"
          :limit="9"
          biz-type="construction_diary"
          :biz-id="diaryScheduleId"
        />
        <button class="commit" :disabled="submitting" @click="submitDiary">落款</button>
      </view>
      <view v-for="d in diaries" :key="d.id" class="card diary">
        <view class="head">
          <text class="cell-title">{{ d.node_name }}</text>
          <text v-if="d.atttype === 1" class="tag tag-info">进场</text>
          <text v-else-if="d.atttype === 2" class="tag tag-warning">出场</text>
        </view>
        <text class="diary-content">{{ d.content }}</text>
        <yz-image-grid v-if="(d.images || []).length" :file-ids="d.images" :cell="180" />
        <view class="cell-sub">{{ d.user_name }} · {{ d.created_at }}</view>
      </view>
      <view v-if="diaries.length === 0" class="card">
        <text class="cell-sub">暂无日记</text>
      </view>
    </view>

    <!-- 图纸:只读图墙(yz-image-grid 带票据换路径+全屏预览) -->
    <view v-if="tab === 'drawings'">
      <view v-if="drawings.length === 0" class="card">
        <text class="cell-title">暂无图纸</text>
        <text class="cell-sub">Web 端上传(bizType=construction_drawing)后在此查看</text>
      </view>
      <view v-else class="card">
        <view class="head">
          <text class="cell-title">图纸 {{ drawings.length }}</text>
        </view>
        <yz-image-grid :file-ids="drawingIds" :cell="290" />
      </view>
    </view>
  </view>
</template>

<script>
import {
  getConstructionDetail, beginScheduleNode, finishScheduleNode,
  listOrderDiaries, createDiary, listDrawings
} from '../../api'
import yzTimeline from '../../components/yz-timeline.vue'
import yzImageGrid from '../../components/yz-image-grid.vue'
import yzUploadImage from '../../components/yz-upload-image.vue'

// 施工域详情(R17):方向二骨架(墨线 tab+账册+行内 btn-mini)+方向三朱批带(免检)与稿纸日记。
// 节点状态机:0 初始/1 开工/2 完工/3 开放工费(1→3 旁路);操作幂等交后端状态闸(construction.state.illegal)。
const NODE = { 0: '初始', 1: '开工', 2: '完工', 3: '开放工费' }
const NODE_TAG = { 0: 'tag-info', 1: 'tag-danger', 2: 'tag-success', 3: 'tag-warning' }
const TRACK = {
  ignite: '放线点火', retroactive: '补录点火', supervisor: '更换监理',
  accessoryOpen: '辅料库开启', accessoryLock: '辅料库锁定',
  begin: '节点开工', finish: '节点完工', openSalary: '开放工费',
  checkout: '免检', scheduleDelete: '删除排期', scheduleReset: '重排',
  accept: '验收合格', acceptRectify: '验收整改'
}
const TRACK_SEAL = ['checkout', 'scheduleDelete', 'scheduleReset', 'acceptRectify']
const TRACK_MOSS = ['finish', 'accept']

export default {
  components: { yzTimeline, yzImageGrid, yzUploadImage },
  data() {
    return {
      orderId: null,
      o: {},
      schedules: [],
      acceptance: null,
      acceptanceItems: [],
      tracks: [],
      tab: 'overview',
      tabs: [
        { key: 'overview', label: '概览' },
        { key: 'nodes', label: '节点' },
        { key: 'diary', label: '日记' },
        { key: 'drawings', label: '图纸' }
      ],
      diariesLoaded: false,
      diaries: [],
      diaryContent: '',
      diaryImages: [],
      scheduleIndex: 0,
      submitting: false,
      drawingsLoaded: false,
      drawings: []
    }
  },
  computed: {
    days() {
      return (this.o.period || 0) / 1000
    },
    nodeTotal() {
      return this.schedules.length
    },
    nodeDone() {
      return this.schedules.filter((s) => s.status === 2).length
    },
    pct() {
      return this.nodeTotal ? Math.round((this.nodeDone / this.nodeTotal) * 100) : 0
    },
    drawingIds() {
      return this.drawings.map((d) => d.id)
    },
    scheduleLabels() {
      return this.schedules.map((s) => s.sort + '. ' + s.node_name)
    },
    diaryScheduleId() {
      const s = this.schedules[this.scheduleIndex]
      return s ? s.id : ''
    },
    trackItems() {
      return (this.tracks || []).map((t) => ({
        title: TRACK[t.action] || t.action,
        time: (t.created_at || '').slice(0, 16),
        desc: [t.detail, t.operator_name].filter(Boolean).join(' · '),
        tone: TRACK_SEAL.includes(t.action) ? 'seal' : (TRACK_MOSS.includes(t.action) ? 'moss' : 'ink')
      }))
    }
  },
  onLoad(query) {
    this.orderId = Number(query.orderId)
  },
  onShow() {
    this.load()
  },
  onPullDownRefresh() {
    this.load().finally(() => uni.stopPullDownRefresh())
  },
  methods: {
    async load() {
      try {
        const res = await getConstructionDetail(this.orderId)
        const d = res.data || {}
        this.o = d
        this.schedules = d.schedules || []
        this.acceptance = d.acceptance || null
        this.acceptanceItems = d.acceptanceItems || []
        this.tracks = d.tracks || []
        if (!this.diariesLoaded) this.resetDiaryTarget()
      } catch (e) {
        uni.showToast({ title: e.message || '加载失败', icon: 'none' })
      }
    },
    switchTab(key) {
      if (this.tab === key) return
      this.tab = key
      if (key === 'diary' && !this.diariesLoaded) this.loadDiaries()
      if (key === 'drawings' && !this.drawingsLoaded) this.loadDrawings()
    },
    nodeLabel(s) {
      return NODE[s] != null ? NODE[s] : s
    },
    nodeTag(s) {
      return NODE_TAG[s] != null ? NODE_TAG[s] : 'tag-info'
    },
    fmtDate(v) {
      return v ? String(v).slice(0, 16) : '—'
    },
    yuan(li) {
      return ((li || 0) / 1000).toLocaleString()
    },
    async beginNode(s) {
      const ok = await new Promise((r) => uni.showModal({
        title: '节点开工', content: s.sort + '. ' + s.node_name,
        success: (res) => r(!!res.confirm)
      }))
      if (!ok) return
      try {
        await beginScheduleNode(s.id)
        uni.showToast({ title: '已开工', icon: 'none' })
        this.load()
      } catch (e) {
        uni.showToast({ title: e.message || '操作失败', icon: 'none' })
      }
    },
    async finishNode(s) {
      const ok = await new Promise((r) => uni.showModal({
        title: '节点完工', content: s.sort + '. ' + s.node_name,
        success: (res) => r(!!res.confirm)
      }))
      if (!ok) return
      try {
        await finishScheduleNode(s.id)
        uni.showToast({ title: '已完工', icon: 'none' })
        this.load()
      } catch (e) {
        uni.showToast({ title: e.message || '操作失败', icon: 'none' })
      }
    },
    resetDiaryTarget() {
      // 默认落款节点=当前进行中节点(未完工的第一个),无则首节点
      const cur = this.schedules.find((s) => s.id === this.o.current_schedule_id && s.status !== 2)
        || this.schedules.find((s) => s.status !== 2)
        || this.schedules[0]
      this.scheduleIndex = cur ? this.schedules.indexOf(cur) : 0
    },
    onPickSchedule(e) {
      this.scheduleIndex = Number(e.detail.value)
    },
    async loadDiaries() {
      try {
        const res = await listOrderDiaries(this.orderId)
        this.diaries = res.data || []
        this.diariesLoaded = true
      } catch (e) {
        uni.showToast({ title: e.message || '加载失败', icon: 'none' })
      }
    },
    async submitDiary() {
      if (!this.diaryScheduleId) {
        uni.showToast({ title: '先选节点', icon: 'none' })
        return
      }
      if (!this.diaryContent.trim()) {
        uni.showToast({ title: '写点什么再落款', icon: 'none' })
        return
      }
      this.submitting = true
      try {
        await createDiary(this.diaryScheduleId, {
          content: this.diaryContent.trim(),
          images: this.diaryImages
        })
        this.diaryContent = ''
        this.diaryImages = []
        uni.showToast({ title: '已落款', icon: 'none' })
        await this.loadDiaries()
      } catch (e) {
        uni.showToast({ title: e.message || '提交失败', icon: 'none' })
      } finally {
        this.submitting = false
      }
    },
    async loadDrawings() {
      try {
        const res = await listDrawings(this.orderId)
        this.drawings = res.data || []
        this.drawingsLoaded = true
      } catch (e) {
        uni.showToast({ title: e.message || '加载失败', icon: 'none' })
      }
    }
  }
}
</script>

<style scoped>
/* 帖面:外强墨线内淡墨线(方向三轻量双框) */
.panel { padding: 6rpx; border-color: var(--yz-line-strong); }
.panel-inner { border: 1rpx solid var(--yz-line); padding: 24rpx 26rpx; }
.head { display: flex; align-items: center; justify-content: space-between; }
.name { font-size: 40rpx; color: var(--yz-ink); font-weight: 700; }
.meta-row { margin-top: 4rpx; }
.progress-line { display: flex; align-items: center; margin-top: 20rpx; }
.bar {
  flex: 1;
  height: 12rpx;
  background: var(--yz-paper-deep);
  border: 1rpx solid var(--yz-line);
  border-radius: 0;
}
.fill { height: 100%; background: var(--yz-moss); }
.pct { font-size: 24rpx; color: var(--yz-ink-62); margin-left: 16rpx; flex-shrink: 0; }

/* 墨线 tab(与 approvals 同构) */
.tabs { display: flex; margin: 20rpx 0; }
.tab {
  flex: 1;
  text-align: center;
  font-size: 28rpx;
  color: var(--yz-ink-62);
  padding: 16rpx 0;
  border-bottom: 3rpx solid transparent;
}
.tab.active { color: var(--yz-ink); font-weight: 700; border-bottom-color: var(--yz-seal); }

.item-row { display: flex; align-items: center; justify-content: space-between; padding: 12rpx 0; }
.item-name { font-size: 26rpx; color: var(--yz-ink); }

.node { padding: 24rpx 28rpx; }
.actions { display: flex; justify-content: flex-end; gap: 16rpx; margin-top: 16rpx; }

/* 朱批带(方向三):纸深底+朱侧线+批印 */
.zhupi {
  display: flex;
  align-items: center;
  margin-top: 16rpx;
  padding: 14rpx 18rpx;
  background: var(--yz-paper-deep);
  border-left: 5rpx solid var(--yz-seal);
}
.seal-char {
  width: 44rpx;
  height: 44rpx;
  line-height: 44rpx;
  text-align: center;
  color: var(--yz-paper);
  background: var(--yz-seal);
  font-size: 26rpx;
  flex-shrink: 0;
  margin-right: 16rpx;
}
.zhupi-text { font-size: 24rpx; color: var(--yz-ink-62); line-height: 1.6; }

/* 稿纸书写与流水(方向三) */
.write { padding: 24rpx 28rpx; }
.picker-val { display: flex; align-items: center; gap: 8rpx; font-size: 26rpx; color: var(--yz-ink-62); }
.paper {
  width: 100%;
  box-sizing: border-box;
  min-height: 200rpx;
  margin-top: 16rpx;
  padding: 8rpx 0;
  font-size: 26rpx;
  color: var(--yz-ink);
  line-height: 44rpx;
  /* 稿纸横格 */
  background: repeating-linear-gradient(
    to bottom,
    transparent 0,
    transparent 43rpx,
    var(--yz-line) 43rpx,
    var(--yz-line) 44rpx
  );
}
.paper-ph { color: var(--yz-ink-35); }
.commit {
  margin-top: 20rpx;
  background: var(--yz-ink);
  color: var(--yz-paper);
  font-size: 28rpx;
  border-radius: 0;
}
.diary { padding: 24rpx 28rpx; margin-top: 20rpx; }
.diary-content {
  display: block;
  font-size: 26rpx;
  color: var(--yz-ink);
  line-height: 44rpx;
  margin: 12rpx 0;
}
</style>
