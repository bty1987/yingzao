<template>
  <view class="yig" :style="{ '--cell': cellRpx + 'rpx' }">
    <view v-for="(img, i) in images" :key="img.fileId || 'i' + i" class="cell" @tap="onPreview(i)">
      <image v-if="img.src" class="pic" :src="img.src" mode="aspectFill" />
      <view v-else class="cell-fallback"><text>图</text></view>
    </view>
    <view v-if="!images.length" class="empty-inline"><text class="empty-text">暂无图片</text></view>
  </view>
</template>

<script setup>
// 只读图片九宫格 + 全屏预览(docs/15 R12 移动端组件基建,与编辑态 yz-upload-image 成对):
// 业务表存 yz_file.id 数组,详情页回显走本组件——带票据 downloadFile 换本地路径展示
// (<image> 发不了 Authorization 头,R11 先例),点图 uni.previewImage 全屏预览。
import { ref, computed, watch } from 'vue'
import { downloadForDisplay } from '../utils/yz-file'

const props = defineProps({
  fileIds: { type: Array, default: () => [] },
  cell: { type: Number, default: 200 }, // 单格边长 rpx
})
const cellRpx = computed(() => props.cell + 'rpx')
const images = ref([]) // { fileId, src | '' }

watch(
  () => props.fileIds,
  (ids) => {
    const list = (ids || []).filter((x) => x != null).map(Number)
    const have = images.value.filter((m) => m.src).map((m) => m.fileId)
    // 新增缺位逐个拉取;外部整体替换时重建
    const same = images.value.length === list.length && list.every((id, i) => images.value[i].fileId === id)
    if (same) return
    images.value = list.map((id) => ({ fileId: id, src: have.includes(id) ? (images.value.find((m) => m.fileId === id) || {}).src || '' : '' }))
    list.forEach((id, i) => {
      if (!images.value[i].src) load(id, i)
    })
  },
  { immediate: true, deep: true }
)

async function load(id, i) {
  try {
    const local = await downloadForDisplay(id)
    if (images.value[i] && images.value[i].fileId === id) images.value.splice(i, 1, { fileId: id, src: local })
  } catch (e) {
    // 留「图」字占位,详情页静默降级
  }
}

function onPreview(i) {
  const urls = images.value.filter((m) => m.src).map((m) => m.src)
  if (!urls.length) return
  const current = Math.max(0, images.value.slice(0, i + 1).filter((m) => m.src).length - 1)
  uni.previewImage({ urls, current })
}
</script>

<style scoped>
.yig { display: flex; flex-wrap: wrap; gap: 16rpx; }
.cell {
  width: var(--cell);
  height: var(--cell);
  border-radius: 4rpx;
  overflow: hidden;
  background: var(--yz-paper-deep);
  border: 1rpx solid var(--yz-line);
  box-sizing: border-box;
}
.pic { width: 100%; height: 100%; display: block; }
.cell-fallback {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}
.cell-fallback text { color: var(--yz-ink-35); font-size: 28rpx; font-family: 'Songti SC', 'SimSun', serif; }
.empty-inline { padding: 8rpx 0; }
.empty-text { color: var(--yz-ink-35); font-size: 24rpx; }
</style>
