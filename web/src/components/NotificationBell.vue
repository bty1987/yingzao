<template>
  <el-popover placement="bottom" :width="380" trigger="click" @show="load">
    <template #reference>
      <el-badge :value="unread" :hidden="unread === 0" :max="99" class="bell">
        <span class="bell-icon">🔔</span>
      </el-badge>
    </template>
    <div class="head">
      <span class="title">通知</span>
      <span>
        <el-button link type="primary" size="small" :disabled="unread === 0" @click="onReadAll">全部已读</el-button>
        <el-button link size="small" @click="load">刷新</el-button>
      </span>
    </div>
    <el-empty v-if="items.length === 0" description="暂无通知" :image-size="60" />
    <div v-else class="list">
      <div v-for="n in items" :key="n.id" class="item" :class="{ unread: n.read_flag === 0 }" @click="onRead(n)">
        <div class="line">
          <span class="t">{{ n.title }}</span>
          <span v-if="n.read_flag === 0" class="dot" />
        </div>
        <div class="c">{{ n.content }}</div>
        <div class="time">{{ n.created_at }}</div>
      </div>
    </div>
  </el-popover>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listNotifications, fetchUnreadCount, markNotificationRead, markAllNotificationsRead } from '../api'

const unread = ref(0)
const items = ref([])
let timer = null

async function refreshUnread() {
  try {
    const res = await fetchUnreadCount()
    unread.value = res.data || 0
  } catch (e) {
    /* 轮询失败静默,下个周期重试 */
  }
}

async function load() {
  const res = await listNotifications({ page: 1, size: 20 })
  items.value = res.data || []
  refreshUnread()
}

async function onRead(n) {
  if (n.read_flag !== 0) return
  await markNotificationRead(n.id)
  n.read_flag = 1
  refreshUnread()
}

async function onReadAll() {
  try {
    await markAllNotificationsRead()
    items.value.forEach((n) => { n.read_flag = 1 })
    unread.value = 0
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

onMounted(() => {
  refreshUnread()
  timer = setInterval(refreshUnread, 30000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.bell { cursor: pointer; display: flex; align-items: center; margin-right: 16px; }
.bell-icon { font-size: 16px; line-height: 1; }
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.head .title { font-weight: 600; }
.list { max-height: 360px; overflow-y: auto; }
.item { padding: 8px 4px; border-bottom: 1px solid #f0f0f0; cursor: pointer; }
.item:last-child { border-bottom: none; }
.item .line { display: flex; align-items: center; }
.item .t { font-weight: 600; font-size: 13px; }
.item .dot { width: 8px; height: 8px; border-radius: 50%; background: #f56c6c; margin-left: 6px; }
.item .c { color: #606266; font-size: 12px; margin-top: 2px; }
.item .time { color: #909399; font-size: 12px; margin-top: 2px; }
</style>
