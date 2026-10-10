<template>
  <div>
    <div style="margin-bottom: 12px">
      <el-button @click="load">刷新</el-button>
    </div>

    <el-table :data="profiles" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="lead_id" label="来源客源" width="100" />
      <el-table-column prop="name" label="姓名" />
      <el-table-column prop="phone" label="电话" />
      <el-table-column prop="created_at" label="建档时间" width="170" />
    </el-table>

    <template v-for="m in mounts" :key="m.slot">
      <el-card shadow="never" style="margin-top: 16px">
        <template #header>{{ m.slot }} <el-tag size="small" style="margin-left: 8px">{{ m.capability }}</el-tag></template>
        <slot-renderer :mount="m" />
      </el-card>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { listProfiles } from '../../api'
import { mountsFor } from '../../store/addons'
import SlotRenderer from '../../components/SlotRenderer.vue'

const profiles = ref([])

// 演示:客户档案页底部挂载插件槽位(正式页面结构待用户选定视觉方向后落地)
const mounts = computed(() => mountsFor('customerprofile'))

onMounted(load)

async function load() {
  const res = await listProfiles()
  profiles.value = res.data || []
}
</script>
