<template>
  <el-card v-if="isAdmin">
    <div style="margin-bottom: 12px; display: flex; align-items: center; gap: 12px; flex-wrap: wrap">
      <span style="color: var(--yz-ink-50); font-size: 12px">
        组织账号管理:建号/停用/重置密码。停用与重置密码即时生效(在管会话全部踢出);角色授权在「角色权限」页维护。
      </span>
      <span style="flex: 1" />
      <el-input v-model="filter.keyword" placeholder="用户名/显示名" clearable style="width: 220px"
        @clear="load" @keyup.enter="load" />
      <el-select v-model="filter.status" placeholder="全部状态" clearable style="width: 130px" @change="load">
        <el-option label="启用" :value="0" />
        <el-option label="停用" :value="1" />
      </el-select>
      <el-button @click="load">查询</el-button>
      <el-button type="primary" @click="openCreate">新建账号</el-button>
    </div>

    <el-table :data="rows" border>
      <el-table-column prop="id" label="ID" width="72" />
      <el-table-column prop="username" label="用户名" min-width="140" />
      <el-table-column prop="display_name" label="显示名" min-width="140" />
      <el-table-column prop="roles" label="角色" min-width="180">
        <template #default="{ row }">{{ row.roles || '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 0 ? 'success' : 'danger'" size="small">
            {{ row.status === 0 ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="created_at" label="创建时间" width="170" />
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 0" size="small" type="warning"
            :disabled="row.id === meId" @click="onToggle(row, 1)">停用</el-button>
          <el-button v-else size="small" type="success" @click="onToggle(row, 0)">启用</el-button>
          <el-button size="small" @click="openReset(row)">重置密码</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div style="margin-top: 12px; display: flex; justify-content: flex-end">
      <el-pagination layout="total, prev, pager, next" :total="total" :page-size="size"
        :current-page="page" @current-change="(p) => { page = p; load() }" />
    </div>

    <!-- 新建账号 -->
    <el-dialog v-model="createDialog" title="新建账号" width="480px">
      <el-form label-width="90px">
        <el-form-item label="用户名" required>
          <el-input v-model="createForm.username" placeholder="登录名,全局唯一" />
        </el-form-item>
        <el-form-item label="显示名" required>
          <el-input v-model="createForm.displayName" />
        </el-form-item>
        <el-form-item label="初始密码" required>
          <el-input v-model="createForm.password" show-password placeholder="至少 6 位" />
        </el-form-item>
        <el-form-item label="初始角色">
          <el-select v-model="createForm.roleIds" multiple filterable style="width: 100%" placeholder="可空,后续在角色权限页调整">
            <el-option v-for="r in roles" :key="r.id" :label="r.name" :value="r.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码 -->
    <el-dialog v-model="resetDialog" :title="`重置密码 - ${resetTarget?.username || ''}`" width="420px">
      <el-form label-width="90px">
        <el-form-item label="新密码" required>
          <el-input v-model="resetPassword" show-password placeholder="至少 6 位" />
        </el-form-item>
      </el-form>
      <div class="tip">重置后该账号全部在管会话立即失效,需重新登录</div>
      <template #footer>
        <el-button @click="resetDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onReset">确认重置</el-button>
      </template>
    </el-dialog>
  </el-card>
  <el-empty v-else description="仅管理员可访问(122003)" />
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pageUsersManage, createUser, changeUserStatus, resetUserPassword, listRoles } from '../api'

const isAdmin = JSON.parse(localStorage.getItem('yz_roles') || '[]').includes('admin')
const meId = Number(localStorage.getItem('yz_user_id') || 0)

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = 20
const filter = reactive({ keyword: '', status: null })
const roles = ref([])
const saving = ref(false)

const createDialog = ref(false)
const createForm = reactive({ username: '', displayName: '', password: '', roleIds: [] })
const resetDialog = ref(false)
const resetTarget = ref(null)
const resetPassword = ref('')

async function load() {
  const params = { page: page.value, size }
  if (filter.keyword && filter.keyword.trim()) params.keyword = filter.keyword.trim()
  if (filter.status !== null && filter.status !== '') params.status = filter.status
  const { data } = await pageUsersManage(params)
  rows.value = data.rows || []
  total.value = data.total || 0
}

function openCreate() {
  Object.assign(createForm, { username: '', displayName: '', password: '', roleIds: [] })
  if (roles.value.length === 0) {
    listRoles().then(({ data }) => { roles.value = (data || []).filter((r) => r.code !== 'admin') })
  }
  createDialog.value = true
}

async function onCreate() {
  const f = createForm
  if (!f.username.trim()) return ElMessage.warning('请填用户名')
  if (!f.displayName.trim()) return ElMessage.warning('请填显示名')
  if (f.password.length < 6) return ElMessage.warning('初始密码至少 6 位')
  saving.value = true
  try {
    await createUser({ ...f, roleIds: f.roleIds.length ? f.roleIds : null })
    ElMessage.success('账号已创建')
    createDialog.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function onToggle(row, status) {
  if (status === 1) {
    try {
      await ElMessageBox.confirm(
        `停用「${row.display_name}」后该账号无法登录,在管会话立即失效。确认停用?`, '停用账号',
        { type: 'warning' })
    } catch { return }
  }
  await changeUserStatus(row.id, { status })
  ElMessage.success(status === 1 ? '已停用' : '已启用')
  load()
}

function openReset(row) {
  resetTarget.value = row
  resetPassword.value = ''
  resetDialog.value = true
}

async function onReset() {
  if (resetPassword.value.length < 6) return ElMessage.warning('新密码至少 6 位')
  saving.value = true
  try {
    await resetUserPassword(resetTarget.value.id, { password: resetPassword.value })
    ElMessage.success('密码已重置,该账号会话已全部失效')
    resetDialog.value = false
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  if (!isAdmin) return
  await load()
})
</script>

<style scoped>
.tip {
  color: var(--yz-ink-50);
  font-size: 12px;
  margin-bottom: 8px;
}
</style>
