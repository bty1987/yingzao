<template>
  <div>
    <el-card shadow="never">
      <div style="display: flex; gap: 8px; margin-bottom: 12px; flex-wrap: wrap">
        <el-input v-model="query.keyword" placeholder="定材包名称" style="width: 200px" clearable
                  @keyup.enter="load(1)" />
        <el-select v-model="query.status" placeholder="状态" style="width: 110px" clearable>
          <el-option label="启用" :value="0" />
          <el-option label="停用" :value="1" />
        </el-select>
        <el-select v-model="query.onlyMaster" placeholder="级别" style="width: 130px" clearable>
          <el-option label="仅主数据" :value="true" />
        </el-select>
        <el-button type="primary" @click="load(1)">查询</el-button>
        <el-button @click="resetQuery">重置</el-button>
        <div style="flex: 1" />
        <el-button type="primary" @click="openCreate">新建定材包</el-button>
      </div>

      <el-table :data="rows" border stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="级别" width="90">
          <template #default="{ row }">
            <el-tag :type="row.level === 0 ? 'success' : 'warning'" size="small">
              {{ row.level === 0 ? '主数据' : '引用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="160" show-overflow-tooltip />
        <el-table-column label="销售价(元)" width="110" align="right">
          <template #default="{ row }">{{ yuan(row.selling) }}</template>
        </el-table-column>
        <el-table-column label="结算额(元)" width="110" align="right">
          <template #default="{ row }">{{ yuan(row.balance) }}</template>
        </el-table-column>
        <el-table-column label="浮动量(元)" width="110" align="right">
          <template #default="{ row }">{{ yuan(row.surplus) }}</template>
        </el-table-column>
        <el-table-column label="归属" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.owner_org_id === 0" size="small" type="info">平台级</el-tag>
            <span v-else>组织</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 0 ? 'success' : 'info'" size="small">
              {{ row.status === 0 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.level === 0" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="row.level === 0" link type="primary" @click="createRef(row)">引用</el-button>
            <el-button v-if="row.level === 1" link type="primary" @click="editSurplus(row)">调浮动量</el-button>
            <el-button link :type="row.status === 0 ? 'danger' : 'success'" @click="toggleStatus(row)">
              {{ row.status === 0 ? '停用' : '启用' }}</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination style="margin-top: 12px; justify-content: flex-end"
                     layout="total, prev, pager, next" :total="total" :page-size="size"
                     :current-page="page" @current-change="load" />
    </el-card>

    <!-- 新建 / 编辑主数据 -->
    <el-dialog v-model="editVisible" :title="editId ? '编辑定材包' : '新建定材包'" width="460px">
      <el-form label-width="90px">
        <el-form-item label="名称" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="销售价(元)"><el-input-number v-model="form.sellingYuan" :min="0" :precision="3" style="width: 100%" /></el-form-item>
        <el-form-item label="结算额(元)"><el-input-number v-model="form.balanceYuan" :min="0" :precision="3" style="width: 100%" /></el-form-item>
        <el-form-item label="浮动量(元)"><el-input-number v-model="form.surplusYuan" :min="0" :precision="3" style="width: 100%" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="doSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  pageMaterialPackages, createMaterialPackage, createMaterialPackageReference,
  updateMaterialPackage, updateMaterialPackageStatus
} from '../../api'

const yuan = (li) => (li == null ? '-' : (li / 1000).toLocaleString(undefined, { maximumFractionDigits: 3 }))
const toLi = (value) => (value == null ? null : Math.round(value * 1000))

const query = reactive({ keyword: '', status: null, onlyMaster: null })
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = 20

const editVisible = ref(false)
const editId = ref(null)
const form = reactive({ name: '', sellingYuan: null, balanceYuan: null, surplusYuan: null })

onMounted(() => load(1))

async function load(target) {
  if (typeof target === 'number') page.value = target
  const res = await pageMaterialPackages({ ...query, page: page.value, size })
  rows.value = res.data.rows || []
  total.value = res.data.total || 0
}

function resetQuery() {
  query.keyword = ''
  query.status = null
  query.onlyMaster = null
  load(1)
}

function openCreate() {
  editId.value = null
  form.name = ''
  form.sellingYuan = null
  form.balanceYuan = null
  form.surplusYuan = null
  editVisible.value = true
}

async function openEdit(row) {
  editId.value = row.id
  form.name = row.name
  form.sellingYuan = row.selling / 1000
  form.balanceYuan = row.balance / 1000
  form.surplusYuan = row.surplus / 1000
  editVisible.value = true
}

async function doSave() {
  if (!form.name) {
    ElMessage.warning('名称为必填')
    return
  }
  const payload = {
    name: form.name, selling: toLi(form.sellingYuan), balance: toLi(form.balanceYuan),
    surplus: toLi(form.surplusYuan)
  }
  if (editId.value) {
    await updateMaterialPackage(editId.value, payload)
    ElMessage.success('已保存')
  } else {
    await createMaterialPackage(payload)
    ElMessage.success('定材包已创建')
  }
  editVisible.value = false
  load()
}

async function createRef(row) {
  const { value } = await ElMessageBox.prompt(
    `引用主数据「${row.name}」为本组织引用行,建后名称/价格随快照、仅浮动量可改。初始浮动量(元)`,
    '引用定材包', { inputPlaceholder: '0' })
  await createMaterialPackageReference(row.id, { surplus: toLi(Number(value || 0)) })
  ElMessage.success('引用行已创建')
  load(1)
}

async function editSurplus(row) {
  const { value } = await ElMessageBox.prompt('浮动量(元)', '调整浮动量', {
    inputValue: String(row.surplus / 1000)
  })
  await updateMaterialPackage(row.id, { surplus: toLi(Number(value || 0)) })
  ElMessage.success('浮动量已调整')
  load()
}

async function toggleStatus(row) {
  const disable = row.status === 0
  if (disable) {
    await ElMessageBox.confirm(`确认停用「${row.name}」?(已建引用行不受影响)`, '提示', { type: 'warning' })
  }
  await updateMaterialPackageStatus(row.id, { status: disable ? 1 : 0 })
  await load()
}
</script>
