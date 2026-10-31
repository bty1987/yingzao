<template>
  <div class="yz-upload">
    <el-upload
      ref="uploadRef"
      :action="action"
      :headers="headers"
      :data="uploadData"
      :accept="accept"
      :limit="limit"
      :list-type="listType"
      :file-list="fileList"
      :on-success="onSuccess"
      :on-error="onError"
      :on-remove="onRemove"
      :on-preview="onPreview"
      :on-exceed="onExceed"
    >
      <template v-if="listType === 'picture-card'">
        <el-icon><Plus /></el-icon>
      </template>
      <template v-else>
        <el-button type="primary" plain>选择文件</el-button>
      </template>
      <template #tip>
        <div v-if="tip" class="tip">{{ tip }}</div>
      </template>
    </el-upload>
  </div>
</template>

<script setup>
// 文件上传组件(F1 内核 file 模块):直传 POST /api/files,v-model 持有 yz_file.id 数组。
// 业务表存 id 即完成挂接;回显用 initFiles(id+fileName)或自行经 listFiles 拉。
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'

const props = defineProps({
  modelValue: { type: Array, default: () => [] },
  bizType: { type: String, default: '' },
  bizId: { type: Number, default: undefined },
  limit: { type: Number, default: 9 },
  accept: { type: String, default: '' },
  listType: { type: String, default: 'picture-card' },
  tip: { type: String, default: '' },
  // 回显已有文件 [{id, fileName}]
  initFiles: { type: Array, default: () => [] },
})
const emit = defineEmits(['update:modelValue'])

const uploadRef = ref()
const fileList = ref([])

const action = '/api/files'
const headers = computed(() => ({ Authorization: `Bearer ${localStorage.getItem('yz_token') || ''}` }))
const uploadData = computed(() => {
  const d = {}
  if (props.bizType) d.bizType = props.bizType
  if (props.bizId !== undefined && props.bizId !== null) d.bizId = String(props.bizId)
  return d
})

watch(
  () => props.initFiles,
  (v) => {
    fileList.value = (v || []).map((f) => ({
      name: f.fileName || `file-${f.id}`,
      uid: f.id,
      status: 'success',
      response: { data: { id: f.id } },
    }))
  },
  { immediate: true }
)

function ids() {
  return fileList.value.filter((f) => f.status === 'success' && f.response && f.response.data && f.response.data.id)
    .map((f) => f.response.data.id)
}

function onSuccess(res, file) {
  if (!res || res.code !== 0 || !res.data) {
    // 业务拒(125xxx):从列表移除并提示
    fileList.value = fileList.value.filter((f) => f.uid !== file.uid)
    ElMessage.error((res && res.message) || '上传失败')
    return
  }
  file.response = res
  emit('update:modelValue', ids())
}

function onError() {
  ElMessage.error('上传失败,请重试')
}

function onRemove(file) {
  fileList.value = fileList.value.filter((f) => f.uid !== file.uid)
  emit('update:modelValue', ids())
}

function onPreview(file) {
  const id = file.response && file.response.data && file.response.data.id
  if (id) window.open(`/api/files/${id}/preview`, '_blank')
}

function onExceed() {
  ElMessage.warning(`最多上传 ${props.limit} 个文件`)
}
</script>

<style scoped>
.yz-upload .tip {
  color: #909399;
  font-size: 12px;
  line-height: 1.6;
}
</style>
