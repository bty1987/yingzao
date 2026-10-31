<template>
  <view class="u9">
    <view v-for="(img, i) in images" :key="i" class="cell">
      <image class="pic" :src="img.src" mode="aspectFill" @tap="onPreview(i)" />
      <view class="del" @tap.stop="onRemove(i)">×</view>
      <view v-if="img.uploading" class="mask">上传中</view>
      <view v-else-if="img.failed" class="mask fail" @tap.stop="onRetry(i)">重试</view>
    </view>
    <view v-if="images.length < limit" class="cell add" @tap="onChoose">+</view>
  </view>
</template>

<script setup>
// 移动端图片上传组件(F1 内核 file 模块,docs/15 R11):选图→压缩→直传 POST /api/files,
// v-model 持有 yz_file.id 数组(业务表存 id 即完成挂接,施工日记 P0-3 首个消费方)。
// 展示与预览用本地临时路径(uni.downloadFile 换取——<image> 发不了 Authorization 头,
// 网络图必须经带票据的 downloadFile 落本地;小程序/H5 同构)。
import { ref, watch } from 'vue'
import { API_BASE } from '../config'

const props = defineProps({
  modelValue: { type: Array, default: () => [] },
  limit: { type: Number, default: 9 },
  bizType: { type: String, default: '' },
  bizId: { type: [Number, String], default: '' },
})
const emit = defineEmits(['update:modelValue'])

// images: { fileId, src(本地路径), uploading, failed, localPath }
const images = ref([])
let token = () => uni.getStorageSync('yz_token') || ''

watch(
  () => props.modelValue,
  (ids) => {
    // 外部清空(编辑态重置)时同步;新上传的 id 由本组件 emit,不在这里反向拉取
    const have = images.value.filter((m) => m.fileId).map((m) => m.fileId)
    if (!ids || ids.length === 0) {
      if (have.length) images.value = []
    } else {
      // 回显:外部带入的 id 本地缺位则下载
      ids.forEach((id) => {
        if (!have.includes(id)) resolveRemote(Number(id))
      })
    }
  },
  { immediate: true, deep: true }
)

function emitIds() {
  emit('update:modelValue', images.value.filter((m) => m.fileId).map((m) => m.fileId))
}

function onChoose() {
  const left = props.limit - images.value.length
  if (left <= 0) return
  uni.chooseImage({
    count: left,
    sizeType: ['compressed'],
    success: (res) => {
      res.tempFilePaths.forEach((p) => uploadOne(p))
    },
  })
}

function uploadOne(localPath) {
  const item = { fileId: 0, src: localPath, localPath, uploading: true, failed: false }
  images.value.push(item)
  uni.uploadFile({
    url: API_BASE + '/api/files',
    filePath: localPath,
    name: 'file',
    formData: props.bizType ? { bizType: props.bizType, bizId: props.bizId === undefined ? '' : String(props.bizId) } : {},
    header: { Authorization: 'Bearer ' + token() },
    success: (res) => {
      let body = null
      try {
        body = JSON.parse(res.data)
      } catch (e) {
        body = null
      }
      const idx = images.value.indexOf(item)
      if (body && body.code === 0 && body.data && body.data.id) {
        item.fileId = body.data.id
        item.uploading = false
        if (idx >= 0) images.value.splice(idx, 1, { ...item })
        emitIds()
      } else {
        item.uploading = false
        item.failed = true
        if (idx >= 0) images.value.splice(idx, 1, { ...item })
        uni.showToast({ title: (body && body.message) || '上传失败', icon: 'none' })
      }
    },
    fail: () => {
      const idx = images.value.indexOf(item)
      item.uploading = false
      item.failed = true
      if (idx >= 0) images.value.splice(idx, 1, { ...item })
      uni.showToast({ title: '网络异常,上传失败', icon: 'none' })
    },
  })
}

function onRetry(i) {
  const item = images.value[i]
  if (!item || !item.localPath) return
  images.value.splice(i, 1)
  uploadOne(item.localPath)
}

function onRemove(i) {
  images.value.splice(i, 1)
  emitIds()
}

function onPreview(i) {
  const urls = images.value.filter((m) => !m.uploading).map((m) => m.src)
  const current = images.value.filter((m) => !m.uploading).indexOf(images.value[i])
  if (current >= 0) uni.previewImage({ urls, current })
}

function resolveRemote(id) {
  const item = { fileId: id, src: '', uploading: true, failed: false, localPath: '' }
  images.value.push(item)
  uni.downloadFile({
    url: API_BASE + '/api/files/' + id + '/preview',
    header: { Authorization: 'Bearer ' + token() },
    success: (res) => {
      const idx = images.value.indexOf(item)
      if (res.statusCode === 200) {
        item.src = res.tempFilePath
        item.localPath = res.tempFilePath
        item.uploading = false
      } else {
        item.uploading = false
        item.failed = true
      }
      if (idx >= 0) images.value.splice(idx, 1, { ...item })
    },
    fail: () => {
      const idx = images.value.indexOf(item)
      item.uploading = false
      item.failed = true
      if (idx >= 0) images.value.splice(idx, 1, { ...item })
    },
  })
}
</script>

<style scoped>
.u9 {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
}
.cell {
  position: relative;
  width: 200rpx;
  height: 200rpx;
  border-radius: 8rpx;
  overflow: hidden;
  background: #f2f2f2;
}
.pic {
  width: 100%;
  height: 100%;
}
.del {
  position: absolute;
  top: 0;
  right: 0;
  width: 48rpx;
  height: 48rpx;
  line-height: 44rpx;
  text-align: center;
  color: #fff;
  background: rgba(0, 0, 0, 0.55);
  border-radius: 0 0 0 8rpx;
  font-size: 32rpx;
}
.mask {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  text-align: center;
  font-size: 22rpx;
  color: #fff;
  background: rgba(0, 0, 0, 0.45);
  padding: 4rpx 0;
}
.mask.fail {
  background: rgba(176, 58, 46, 0.7);
}
.add {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 64rpx;
  color: #999;
  border: 2rpx dashed #ccc;
  box-sizing: border-box;
}
</style>
