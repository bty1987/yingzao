<template>
  <el-card v-if="isAdmin">
    <div style="margin-bottom: 12px; display: flex; align-items: center; gap: 12px; flex-wrap: wrap">
      <span style="color: var(--yz-ink-50); font-size: 12px">
        组织树:公司 → 分区 → 部门(层级是标签不是实体,支持任意深度)。停用=子树整体停,该组织用户即无法登录;启用只恢复本节点。
      </span>
      <span style="flex: 1" />
      <el-button type="primary" @click="openCreate(null)">新建子组织</el-button>
    </div>

    <el-table :data="tree" row-key="id" border default-expand-all
      :tree-props="{ children: 'children' }">
      <el-table-column prop="name" label="组织" min-width="240" />
      <el-table-column label="层级" width="110">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ typeLabel(row.level_type) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 0 ? 'success' : 'danger'" size="small">
            {{ row.status === 0 ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="created_at" label="创建时间" width="170" />
      <el-table-column label="操作" width="240" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" :disabled="row.status === 1"
            @click="openCreate(row)">加下级</el-button>
          <el-button size="small" :disabled="row.status === 1" @click="openRename(row)">改名</el-button>
          <el-button v-if="row.status === 0" size="small" type="warning"
            :disabled="row.parent_id === null" @click="onToggle(row, 1)">停用</el-button>
          <el-button v-else size="small" type="success" @click="onToggle(row, 0)">启用</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新建子组织 -->
    <el-dialog v-model="createDialog" :title="`加下级 - ${createParent?.name || ''}`" width="440px">
      <el-form label-width="90px">
        <el-form-item label="名称" required>
          <el-input v-model="createForm.name" placeholder="如:华东分区 / 设计一部" />
        </el-form-item>
        <el-form-item label="层级标签">
          <el-select v-model="createForm.levelType" style="width: 200px">
            <el-option label="分区" value="region" />
            <el-option label="部门" value="department" />
            <el-option label="公司" value="company" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 改名 -->
    <el-dialog v-model="renameDialog" :title="`改名 - ${renameTarget?.name || ''}`" width="440px">
      <el-form label-width="90px">
        <el-form-item label="名称" required>
          <el-input v-model="renameName" />
        </el-form-item>
        <el-form-item label="层级标签">
          <el-select v-model="renameType" style="width: 200px">
            <el-option label="分区" value="region" />
            <el-option label="部门" value="department" />
            <el-option label="公司" value="company" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="renameDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onRename">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
  <el-empty v-else description="仅管理员可访问(122003)" />
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { orgTree, createOrg, updateOrg, changeOrgStatus } from '../api'

const isAdmin = JSON.parse(localStorage.getItem('yz_roles') || '[]').includes('admin')

const tree = ref([])
const saving = ref(false)
const createDialog = ref(false)
const createParent = ref(null)
const createForm = reactive({ name: '', levelType: 'department' })
const renameDialog = ref(false)
const renameTarget = ref(null)
const renameName = ref('')
const renameType = ref('department')

const typeLabel = (t) => ({ company: '公司', region: '分区', department: '部门' }[t] || t)

async function load() {
  const { data } = await orgTree()
  tree.value = data || []
}

function openCreate(parent) {
  createParent.value = parent || { id: null, name: '根' }
  Object.assign(createForm, { name: '', levelType: 'department' })
  createDialog.value = true
}

async function onCreate() {
  if (!createForm.name.trim()) return ElMessage.warning('请填名称')
  if (!createParent.value?.id) return ElMessage.warning('根节点为登录组织,请在节点上加下级')
  saving.value = true
  try {
    await createOrg({ parentId: createParent.value.id, name: createForm.name.trim(),
      levelType: createForm.levelType })
    ElMessage.success('已创建')
    createDialog.value = false
    load()
  } finally {
    saving.value = false
  }
}

function openRename(row) {
  renameTarget.value = row
  renameName.value = row.name
  renameType.value = row.level_type
  renameDialog.value = true
}

async function onRename() {
  if (!renameName.value.trim()) return ElMessage.warning('请填名称')
  saving.value = true
  try {
    await updateOrg(renameTarget.value.id, { name: renameName.value.trim(), levelType: renameType.value })
    ElMessage.success('已保存')
    renameDialog.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function onToggle(row, status) {
  if (status === 1) {
    try {
      await ElMessageBox.confirm(
        `停用「${row.name}」将连同其全部下级一起停用,这些组织下的用户将无法登录。确认停用?`,
        '停用组织', { type: 'warning' })
    } catch { return }
  }
  await changeOrgStatus(row.id, { status })
  ElMessage.success(status === 1 ? '已停用(含子树)' : '已启用(子节点需逐个启用)')
  load()
}

onMounted(async () => {
  if (!isAdmin) return
  await load()
})
</script>
