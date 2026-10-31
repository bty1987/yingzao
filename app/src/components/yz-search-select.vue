<template>
  <view class="yss">
    <view class="box" :class="{ open: panelOpen, disabled }" @click="onOpen">
      <text v-if="displayText" class="text" :class="{ placeholder: false }">{{ displayText }}</text>
      <text v-else class="text placeholder">{{ placeholder }}</text>
      <text v-if="clearable && displayText && !disabled" class="clear" @click.stop="onClear">×</text>
      <text v-else class="caret">▾</text>
    </view>

    <view v-if="panelOpen" class="mask" @click="closePanel" @touchmove.stop.prevent />

    <view v-if="panelOpen" class="panel">
      <view class="searchrow">
        <input
          class="kw"
          :value="keyword"
          :focus="autoFocus"
          confirm-type="search"
          :placeholder="local ? '输入关键字过滤' : '输入关键字搜索'"
          placeholder-class="kw-ph"
          @input="onKeyword"
          @confirm="onKeyword"
        />
      </view>
      <scroll-view scroll-y class="opts">
        <view v-if="loading" class="opt-hint">搜索中…</view>
        <view v-else-if="!rows.length" class="opt-hint">{{ keyword && !local ? '无匹配结果' : '暂无数据' }}</view>
        <view
          v-for="(row, i) in rows"
          :key="rowKeyOf(row, i)"
          class="opt"
          :class="{ picked: isPicked(row) }"
          @click="pick(row)"
        >
          <text class="opt-label">{{ row[labelKey] }}</text>
          <text v-if="row[subKey]" class="opt-sub">{{ row[subKey] }}</text>
        </view>
      </scroll-view>
    </view>
  </view>
</template>

<script setup>
// 搜索联想选择器(docs/15 R12 移动端组件基建):uni-app 无 el-select,自写纸墨风下拉。
// 双模式:
//   remote——输入防抖 300ms 远程搜索(fetch(keyword) 返回 rows;空关键字不触发,对齐 web remote 惯例);
//   local —— 打开面板即列出 options 全量,前端按关键字过滤(组织内人员等小数据源,
//            GET /api/users 无 keyword 参数,本地过滤即 web 端 el-select filterable 同形态)。
// v-model = 选中项对象(未选 null);回显取 displayKey,业务页可直接回传 {id, name} 形态。
import { ref, computed, watch } from 'vue'

const props = defineProps({
  modelValue: { type: Object, default: null },
  placeholder: { type: String, default: '请选择' },
  // remote: (keyword) => Promise<rows>;local 模式忽略
  fetch: { type: Function, default: null },
  options: { type: Array, default: () => [] },
  local: { type: Boolean, default: false },
  labelKey: { type: String, default: 'name' },
  subKey: { type: String, default: '' },
  valueKey: { type: String, default: 'id' },
  displayKey: { type: String, default: '' }, // 缺省取 labelKey
  clearable: { type: Boolean, default: true },
  disabled: { type: Boolean, default: false },
  limit: { type: Number, default: 20 },
})
const emit = defineEmits(['update:modelValue', 'change'])

const panelOpen = ref(false)
const keyword = ref('')
const rows = ref([])
const loading = ref(false)
const autoFocus = ref(false)
let debounceTimer = null
let searchSeq = 0

const displayText = computed(() => {
  const key = props.displayKey || props.labelKey
  return props.modelValue ? (props.modelValue[key] != null ? String(props.modelValue[key]) : '') : ''
})

function rowKeyOf(row, i) {
  return row[props.valueKey] != null ? String(row[props.valueKey]) : 'i' + i
}
function isPicked(row) {
  return !!(props.modelValue && row[props.valueKey] === props.modelValue[props.valueKey])
}

watch(
  () => props.modelValue,
  () => {
    if (props.modelValue && !props.local) keyword.value = ''
  }
)

function onOpen() {
  if (props.disabled) return
  panelOpen.value = true
  autoFocus.value = false
  rows.value = props.local ? props.options.slice(0, props.limit) : []
  keyword.value = ''
  // H5 端 input focus 属性在面板渲染后置位才生效(两种模式统一给搜索框)
  setTimeout(() => (autoFocus.value = true), 50)
}

function closePanel() {
  panelOpen.value = false
  loading.value = false
}

function onKeyword(e) {
  keyword.value = e.detail ? e.detail.value : e
  if (debounceTimer) clearTimeout(debounceTimer)
  const kw = keyword.value
  if (!kw) {
    rows.value = []
    loading.value = false
    return
  }
  if (props.local) {
    const k = String(kw).toLowerCase()
    rows.value = props.options.filter((o) => String(o[props.labelKey] || '').toLowerCase().includes(k)).slice(0, props.limit)
    return
  }
  loading.value = true
  debounceTimer = setTimeout(() => doSearch(String(kw)), 300)
}

async function doSearch(kw) {
  const seq = ++searchSeq
  try {
    const res = await props.fetch(kw)
    if (seq !== searchSeq) return // 过期响应丢弃
    const body = res && res.data
    // 兼容 {total, rows} 分页体与扁平数组两种返回
    const list = Array.isArray(body) ? body : (body && body.rows) || []
    rows.value = list.slice(0, props.limit)
  } catch (e) {
    if (seq === searchSeq) {
      rows.value = []
      uni.showToast({ title: e.message || '搜索失败', icon: 'none' })
    }
  } finally {
    if (seq === searchSeq) loading.value = false
  }
}

function pick(row) {
  emit('update:modelValue', row)
  emit('change', row)
  closePanel()
}

function onClear() {
  emit('update:modelValue', null)
  emit('change', null)
}
</script>

<style scoped>
.yss { position: relative; }
.box {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 76rpx;
  padding: 0 20rpx;
  border: 1rpx solid var(--yz-line-strong);
  border-radius: 4rpx;
  background: var(--yz-paper);
  box-sizing: border-box;
}
.box.open { border-color: var(--yz-ink); }
.box.disabled { opacity: 0.5; }
.text { font-size: 28rpx; color: var(--yz-ink); flex: 1; }
.text.placeholder { color: var(--yz-ink-35); }
.clear { color: var(--yz-ink-35); font-size: 34rpx; padding: 0 8rpx; }
.caret { color: var(--yz-ink-35); font-size: 24rpx; }

.mask { position: fixed; left: 0; top: 0; right: 0; bottom: 0; z-index: 90; }
.panel {
  position: absolute;
  left: 0;
  right: 0;
  top: 84rpx;
  z-index: 91;
  background: var(--yz-paper);
  border: 1rpx solid var(--yz-line-strong);
  border-radius: 4rpx;
}
.searchrow { border-bottom: 1rpx solid var(--yz-line); padding: 16rpx; box-sizing: border-box; }
.kw { font-size: 28rpx; height: 56rpx; }
.kw-ph { color: var(--yz-ink-35); }
.opts { max-height: 480rpx; }
.opt {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 20rpx 24rpx;
  border-bottom: 1rpx solid var(--yz-line);
}
.opt:last-child { border-bottom: none; }
.opt.picked { background: var(--yz-paper-deep); }
.opt-label { font-size: 28rpx; color: var(--yz-ink); }
.opt-sub { font-size: 22rpx; color: var(--yz-ink-35); }
.opt-hint { text-align: center; color: var(--yz-ink-35); font-size: 24rpx; padding: 40rpx 0; }
</style>
