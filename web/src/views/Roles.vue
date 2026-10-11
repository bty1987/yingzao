<template>
  <el-card v-if="isAdmin">
    <div style="margin-bottom: 12px; display: flex; align-items: center">
      <span style="color: #909399; font-size: 12px">
        角色 = 菜单/功能点授权的载体;内置管理员旁路全部过滤。授权语义为全量替换(勾选即目标态)。
      </span>
      <span style="flex: 1" />
      <el-button type="primary" @click="openCreate">新增角色</el-button>
    </div>
    <el-table :data="roles" border>
      <el-table-column prop="id" label="ID" width="64" />
      <el-table-column prop="code" label="编码" width="160" />
      <el-table-column prop="name" label="名称" width="160" />
      <el-table-column label="内置" width="80">
        <template #default="{ row }">
          <el-tag v-if="row.builtin === 1" type="warning" size="small">内置</el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column prop="user_count" label="成员" width="72" />
      <el-table-column prop="menu_count" label="菜单" width="72" />
      <el-table-column prop="capability_count" label="功能点" width="72" />
      <el-table-column prop="remark" label="备注" />
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <el-button size="small" :disabled="row.builtin === 1" @click="openGrant(row)">授权</el-button>
          <el-button size="small" type="danger" :disabled="row.builtin === 1" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="createVisible" title="新增角色" width="420px">
      <el-form label-width="72px">
        <el-form-item label="编码">
          <el-input v-model="createForm.code" placeholder="小写字母开头,可含数字/下划线" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="createForm.name" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="createForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="grantVisible" :title="`授权:${grantRole?.name || ''}`" size="520px">
      <template v-if="grantDetail">
        <h4>菜单授权(可见菜单 = 各角色授权并集)</h4>
        <div v-for="addon in addonCatalog" :key="addon.id" style="margin-bottom: 8px">
          <strong>{{ addon.name }}</strong>
          <el-checkbox-group v-model="grantForm.menus" style="margin-left: 12px">
            <el-checkbox v-for="m in addon.menus" :key="m.path" :value="m.path">{{ m.title }}</el-checkbox>
          </el-checkbox-group>
        </div>

        <h4 style="margin-top: 16px">功能点授权(槽位挂载 capability)</h4>
        <el-checkbox-group v-model="grantForm.capabilities">
          <el-checkbox v-for="c in capabilityCatalog" :key="c.value" :value="c.value">{{ c.label }}</el-checkbox>
        </el-checkbox-group>
        <p v-if="!capabilityCatalog.length" style="color: #909399; font-size: 12px">当前无插件声明功能点。</p>

        <h4 style="margin-top: 16px">成员(本组织用户)</h4>
        <el-select v-model="grantForm.userIds" multiple filterable style="width: 100%">
          <el-option v-for="u in users" :key="u.id" :value="u.id" :label="u.display_name" />
        </el-select>

        <div style="margin-top: 20px; text-align: right">
          <el-button type="primary" :loading="saving" @click="saveGrant">保存授权</el-button>
        </div>
      </template>
    </el-drawer>
  </el-card>
  <el-empty v-else description="仅管理员可访问(122003)" />
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listRoles, createRole, fetchRole, deleteRole,
  updateRoleMenus, updateRoleCapabilities, updateRoleUsers,
  listOrgUsers
} from '../api'
import { loadAddons } from '../store/addons'

const isAdmin = JSON.parse(localStorage.getItem('yz_roles') || '[]').includes('admin')
const roles = ref([])
const users = ref([])
const addonsCatalog = ref([])
const createVisible = ref(false)
const grantVisible = ref(false)
const saving = ref(false)
const grantRole = ref(null)
const grantDetail = ref(null)
const grantForm = reactive({ menus: [], capabilities: [], userIds: [] })
const createForm = reactive({ code: '', name: '', remark: '' })

// 可授权菜单目录 = /api/addons 原样下发(管理员旁路,即全量声明)
const addonCatalog = computed(() => addonsCatalog.value)
const capabilityCatalog = computed(() =>
  addonsCatalog.value
    .flatMap((a) => (a.mounts || []).filter((m) => m.capability).map((m) => ({
      value: m.capability,
      label: `${m.capability}(${a.name} · ${m.slot})`
    })))
    .filter((c, i, arr) => arr.findIndex((x) => x.value === c.value) === i)
)

async function loadRoles() {
  roles.value = (await listRoles()).data || []
}

onMounted(async () => {
  if (!isAdmin) return
  addonsCatalog.value = await loadAddons()
  users.value = (await listOrgUsers()).data || []
  await loadRoles()
})

function openCreate() {
  createForm.code = ''
  createForm.name = ''
  createForm.remark = ''
  createVisible.value = true
}

async function doCreate() {
  saving.value = true
  try {
    await createRole(createForm)
    ElMessage.success('已创建')
    createVisible.value = false
    await loadRoles()
  } finally {
    saving.value = false
  }
}

async function openGrant(row) {
  grantRole.value = row
  grantDetail.value = await fetchRole(row.id).then((r) => r.data)
  grantForm.menus = [...(grantDetail.value.menus || [])]
  grantForm.capabilities = [...(grantDetail.value.capabilities || [])]
  grantForm.userIds = (grantDetail.value.users || []).map((u) => u.id)
  grantVisible.value = true
}

async function saveGrant() {
  saving.value = true
  try {
    const id = grantRole.value.id
    await updateRoleMenus(id, grantForm.menus)
    await updateRoleCapabilities(id, grantForm.capabilities)
    await updateRoleUsers(id, grantForm.userIds)
    ElMessage.success('授权已保存(全量替换)')
    grantVisible.value = false
    await loadRoles()
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  await ElMessageBox.confirm(`删除角色「${row.name}」?其授权与成员绑定将一并清除。`, '删除确认', { type: 'warning' })
  await deleteRole(row.id)
  ElMessage.success('已删除')
  await loadRoles()
}
</script>
