<template>
  <div>
    <div style="margin-bottom: 12px">
      <el-button type="primary" @click="openCreate">申请请假</el-button>
      <el-button @click="load">刷新</el-button>
      <span class="tip">addon-example 示范工程:发起即提交内核审批,办结后由 ApprovalFinishedEvent 回写状态</span>
    </div>

    <el-table :data="rows" border stripe v-loading="loading">
      <el-table-column prop="id" label="单号" width="70" />
      <el-table-column prop="reason" label="事由" min-width="200" show-overflow-tooltip />
      <el-table-column prop="days" label="天数" width="70" />
      <el-table-column prop="assignee_name" label="审批人" width="110" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="审批级别" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.approval_level === 2" type="warning" effect="plain">二级</el-tag>
          <span v-else class="level-plain">一级</span>
        </template>
      </el-table-column>
      <el-table-column prop="created_at" label="申请时间" width="170" />
      <el-table-column label="办结时间" width="170">
        <template #default="{ row }">{{ row.finished_at || '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button v-if="row.status === 0" type="warning" link @click="onCancel(row)">撤回</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" title="申请请假" width="480px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="审批人">
          <el-select v-model="form.assigneeId" style="width: 100%" placeholder="选择本组织用户">
            <el-option v-for="u in users" :key="u.id" :label="u.display_name" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="天数">
          <el-input-number v-model="form.days" :min="1" :max="365" />
          <span v-if="form.days >= 3" class="tip multi-hint">≥3 天自动升级二级审批</span>
        </el-form-item>
        <el-form-item v-if="form.days >= 3" label="二级审批人">
          <el-select v-model="form.managerId" style="width: 100%" placeholder="长请假须二级审批人">
            <el-option v-for="u in managerCandidates" :key="u.id" :label="u.display_name" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="事由">
          <el-input v-model="form.reason" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onCreate">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listLeaves, createLeave, cancelLeave, listOrgUsers } from '../../api'
import { addons } from '../../store/addons'

const rows = ref([])
const users = ref([])
const loading = ref(false)
const dialog = ref(false)
const saving = ref(false)
const form = ref({ assigneeId: null, days: 1, reason: '', managerId: null })

const me = Number(localStorage.getItem('yz_user_id') || 0)

// 二级审批人候选:排除自己与一级审批人(与后端 290006 守卫同口径)
const managerCandidates = computed(() =>
  users.value.filter((u) => u.id !== me && u.id !== form.value.assigneeId))

// 状态字典来自插件下发(addon.yml dicts),前端不硬编码业务枚举
const statusDict = ref([])
const statusLabel = (s) => {
  const hit = statusDict.value.find((d) => d.key === String(s))
  return hit ? hit.label : s
}
const statusTag = (s) => ({ 0: 'info', 1: 'success', 2: 'danger', 3: 'warning' })[s] ?? 'info'

async function load() {
  loading.value = true
  try {
    const res = await listLeaves()
    rows.value = res.data || []
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.value = { assigneeId: null, days: 1, reason: '', managerId: null }
  if (users.value.length === 0) {
    listOrgUsers().then((res) => {
      users.value = (res.data || []).filter((u) => u.id !== me)
    })
  }
  dialog.value = true
}

async function onCreate() {
  if (!form.value.assigneeId) return ElMessage.warning('请选择审批人')
  if (form.value.days >= 3 && !form.value.managerId) return ElMessage.warning('≥3 天请选择二级审批人')
  if (form.value.days >= 3 && form.value.managerId === form.value.assigneeId) {
    return ElMessage.warning('二级审批人不能与一级相同')
  }
  if (!form.value.reason || !form.value.reason.trim()) return ElMessage.warning('请填写请假事由')
  saving.value = true
  try {
    await createLeave(form.value)
    ElMessage.success('已提交,审批单已发起')
    dialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '提交失败')
  } finally {
    saving.value = false
  }
}

async function onCancel(row) {
  try {
    await ElMessageBox.confirm('确定撤回该请假申请?', '撤回', { type: 'warning' })
  } catch (e) {
    return
  }
  try {
    await cancelLeave(row.id)
    ElMessage.success('已撤回')
    load()
  } catch (e) {
    ElMessage.error(e.message || '撤回失败')
  }
}

onMounted(() => {
  const example = addons.list.find((a) => a.id === 'example')
  statusDict.value = (example && example.dicts && example.dicts.leaveStatus) || []
  load()
})
</script>

<style scoped>
.tip { margin-left: 12px; color: var(--yz-ink-50); font-size: 12px; }
.level-plain { color: var(--yz-ink-50); font-size: 12px; }
.multi-hint { margin-left: 8px; }
</style>
