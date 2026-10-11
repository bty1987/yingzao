<template>
  <div>
    <div class="toolbar">
      <el-select v-model="filters.warehouseId" placeholder="仓库" style="width: 160px" clearable @change="load">
        <el-option v-for="w in myWarehouses" :key="w.id" :label="w.name" :value="w.id" />
      </el-select>
      <el-input v-model="filters.keyword" placeholder="商品/仓库名" style="width: 180px" clearable @keyup.enter="load" />
      <el-button type="primary" @click="load">查询</el-button>
      <el-button @click="whDialog = true">仓库管理</el-button>
      <span class="tip">库存按授权仓库过滤(无授权 = 空列表);数量 = 库存原子守卫的唯一记账面</span>
    </div>

    <el-table :data="rows" border stripe v-loading="loading">
      <el-table-column prop="goods_name" label="商品" min-width="160" />
      <el-table-column prop="color_name" label="颜色" width="100" />
      <el-table-column prop="format_name" label="规格" width="100" />
      <el-table-column prop="warehouse_name" label="仓库" width="130" />
      <el-table-column label="数量" width="130" align="right">
        <template #default="{ row }">{{ units(row.num) }}</template>
      </el-table-column>
      <el-table-column prop="updated_at" label="最近变动" width="170" />
    </el-table>

    <el-pagination layout="total, prev, pager, next" :total="total" :page-size="size"
      v-model:current-page="page" @current-change="load" style="margin-top: 12px" />

    <!-- 仓库管理 -->
    <el-dialog v-model="whDialog" title="仓库档案与授权" width="760px">
      <el-table :data="warehouses" border size="small">
        <el-table-column prop="code" label="编号" width="110" />
        <el-table-column prop="name" label="名称" min-width="130" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 0 ? 'success' : 'info'">
              {{ row.status === 0 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170">
          <template #default="{ row }">
            <el-button link type="primary" @click="openGrant(row)">授权</el-button>
            <el-button link @click="toggleWh(row)">{{ row.status === 0 ? '停用' : '启用' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="new-wh">
        <el-input v-model="newWh.code" placeholder="编号" style="width: 130px" />
        <el-input v-model="newWh.name" placeholder="名称" style="width: 160px" />
        <el-button type="primary" @click="onCreateWh">新增仓库</el-button>
      </div>
    </el-dialog>

    <!-- 授权 -->
    <el-dialog v-model="grantDialog" :title="'授权 — ' + (grantWh && grantWh.name || '')" width="520px">
      <el-table :data="grantUsers" border size="small">
        <el-table-column prop="display_name" label="用户" min-width="120" />
        <el-table-column prop="created_at" label="授权时间" width="170" />
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button link type="danger" @click="onRevoke(row.user_id)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="new-wh">
        <el-select v-model="grantUserId" placeholder="选择用户" style="width: 200px">
          <el-option v-for="u in users" :key="u.id" :label="u.display_name" :value="u.id" />
        </el-select>
        <el-button type="primary" @click="onGrant">授权</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import {
  pageStocks, listWarehouses, createWarehouse, updateWarehouse,
  listWarehouseUsers, grantWarehouseUser, revokeWarehouseUser, listOrgUsers
} from '../../api'

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)
const filters = reactive({ warehouseId: null, keyword: '' })
const warehouses = ref([])
const myWarehouses = ref([])
const users = ref([])
const whDialog = ref(false)
const grantDialog = ref(false)
const grantWh = ref(null)
const grantUsers = ref([])
const grantUserId = ref(null)
const newWh = reactive({ code: '', name: '' })

const units = (num) => (Number(num || 0) / 1000).toLocaleString('zh-CN', { maximumFractionDigits: 3 })

async function load() {
  loading.value = true
  try {
    const res = await pageStocks({ page: page.value, size,
      warehouseId: filters.warehouseId ?? undefined, keyword: filters.keyword || undefined })
    rows.value = (res.data && res.data.rows) || []
    total.value = (res.data && res.data.total) || 0
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

async function loadWarehouses() {
  const res = await listWarehouses({ includeDisabled: true })
  warehouses.value = res.data || []
  const visible = await listWarehouses()
  myWarehouses.value = visible.data || []
}

function openGrant(row) {
  grantWh.value = row
  grantUserId.value = null
  listWarehouseUsers(row.id).then((res) => { grantUsers.value = res.data || [] })
  grantDialog.value = true
}

async function onGrant() {
  if (!grantUserId.value) return ElMessage.warning('请选择用户')
  try {
    await grantWarehouseUser(grantWh.value.id, { userId: grantUserId.value })
    openGrant(grantWh.value)
  } catch (e) {
    ElMessage.error(e.message || '授权失败')
  }
}

async function onRevoke(userId) {
  try {
    await revokeWarehouseUser(grantWh.value.id, userId)
    openGrant(grantWh.value)
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function toggleWh(row) {
  try {
    await updateWarehouse(row.id, { status: row.status === 0 ? 1 : 0 })
    loadWarehouses()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function onCreateWh() {
  if (!newWh.code || !newWh.name) return ElMessage.warning('请填编号与名称')
  try {
    await createWarehouse({ ...newWh })
    newWh.code = ''
    newWh.name = ''
    loadWarehouses()
  } catch (e) {
    ElMessage.error(e.message || '新增失败')
  }
}

onMounted(async () => {
  const us = await listOrgUsers()
  users.value = us.data || []
  await loadWarehouses()
  load()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; align-items: center; flex-wrap: wrap; }
.tip { color: var(--yz-ink-50); font-size: 12px; }
.new-wh { display: flex; gap: 8px; margin-top: 12px; }
</style>
