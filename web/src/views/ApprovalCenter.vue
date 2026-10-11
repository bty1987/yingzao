<template>
  <div>
    <el-tabs v-model="role" @tab-change="load">
      <el-tab-pane label="待我处理" name="todo" />
      <el-tab-pane label="我已办结" name="done" />
      <el-tab-pane label="我发起的" name="created" />
    </el-tabs>

    <el-table :data="rows" border stripe v-loading="loading">
      <el-table-column prop="id" label="单号" width="70" />
      <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
      <el-table-column prop="applicant_name" label="发起人" width="110" />
      <el-table-column v-if="role === 'created'" prop="assignee_name" label="当前处理人" width="110" />
      <el-table-column label="业务溯源" width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ row.biz_addon }} / {{ row.biz_type }} #{{ row.biz_id }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="role === 'done' ? '办结时间' : '发起时间'" width="170">
        <template #default="{ row }">{{ role === 'done' ? (row.finished_at || '') : (row.created_at || '') }}</template>
      </el-table-column>
      <el-table-column label="操作" width="240">
        <template #default="{ row }">
          <template v-if="role === 'todo'">
            <el-button type="success" link @click="onAct(row, 'approve')">通过</el-button>
            <el-button type="danger" link @click="onAct(row, 'reject')">驳回</el-button>
            <el-button type="primary" link @click="openTransfer(row)">转交</el-button>
          </template>
          <el-button
            v-if="role === 'created' && row.status === 0"
            type="warning" link @click="onCancel(row)">撤回</el-button>
          <el-button link @click="openDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="drawer" title="审批详情" size="480px">
      <template v-if="detail">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="单号">{{ detail.id }}</el-descriptions-item>
          <el-descriptions-item label="标题">{{ detail.title }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTag(detail.status)">{{ statusLabel(detail.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="发起人">{{ detail.applicant_name }}</el-descriptions-item>
          <el-descriptions-item label="当前处理人">{{ detail.assignee_name }}</el-descriptions-item>
          <el-descriptions-item label="业务溯源">
            {{ detail.biz_addon }} / {{ detail.biz_type }} #{{ detail.biz_id }}
          </el-descriptions-item>
          <el-descriptions-item label="发起时间">{{ detail.created_at }}</el-descriptions-item>
          <el-descriptions-item label="办结时间">{{ detail.finished_at || '—' }}</el-descriptions-item>
        </el-descriptions>

        <h4>流水</h4>
        <el-timeline>
          <el-timeline-item v-for="r in detail.records || []" :key="r.id" :timestamp="r.created_at" placement="top">
            <div>
              <b>{{ actionLabel(r.action) }}</b>
              <span style="margin-left: 8px; color: #606266">{{ r.operator_name }}</span>
              <span v-if="r.action === 4 && r.assignee_name" style="margin-left: 4px; color: #606266">
                → {{ r.assignee_name }}
              </span>
            </div>
            <div v-if="r.comment" style="color: #606266; font-size: 12px">{{ r.comment }}</div>
          </el-timeline-item>
        </el-timeline>
      </template>
    </el-drawer>

    <el-dialog v-model="transferDialog" title="转交审批" width="420px">
      <el-form label-width="80px">
        <el-form-item label="转交给人">
          <el-select v-model="transferTo" style="width: 100%" placeholder="选择本组织用户">
            <el-option v-for="u in users" :key="u.id" :label="u.display_name" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="意见">
          <el-input v-model="transferComment" type="textarea" placeholder="可空" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="transferDialog = false">取消</el-button>
        <el-button type="primary" :loading="acting" @click="onTransfer">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listApprovals, fetchApprovalDetail, approveApproval, rejectApproval,
  transferApproval, cancelApproval, listOrgUsers
} from '../api'

const role = ref('todo')
const rows = ref([])
const loading = ref(false)
const drawer = ref(false)
const detail = ref(null)
const users = ref([])
const transferDialog = ref(false)
const transferTo = ref(null)
const transferComment = ref('')
const transferRow = ref(null)
const acting = ref(false)

const me = Number(localStorage.getItem('yz_user_id') || 0)

const STATUS = { 0: '审批中', 1: '已通过', 2: '已驳回', 3: '已撤回' }
const STATUS_TAG = { 0: 'info', 1: 'success', 2: 'danger', 3: 'warning' }
const ACTION = { 1: '提交', 2: '通过', 3: '驳回', 4: '转交', 5: '撤回' }

const statusLabel = (s) => STATUS[s] ?? s
const statusTag = (s) => STATUS_TAG[s] ?? 'info'
const actionLabel = (a) => ACTION[a] ?? a

async function load() {
  loading.value = true
  try {
    const res = await listApprovals({ role: role.value, page: 1, size: 50 })
    rows.value = res.data || []
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

async function openDetail(row) {
  const res = await fetchApprovalDetail(row.id)
  detail.value = res.data
  drawer.value = true
}

async function onAct(row, kind) {
  let comment = ''
  try {
    const r = await ElMessageBox.prompt('审批意见(可空)', kind === 'approve' ? '通过审批' : '驳回审批',
      { confirmButtonText: '确定', cancelButtonText: '取消', inputPlaceholder: '意见' })
    comment = r.value || ''
  } catch (e) {
    return
  }
  acting.value = true
  try {
    if (kind === 'approve') await approveApproval(row.id, { comment })
    else await rejectApproval(row.id, { comment })
    ElMessage.success('已办结')
    load()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  } finally {
    acting.value = false
  }
}

async function onCancel(row) {
  try {
    await ElMessageBox.confirm('确定撤回该审批?', '撤回', { type: 'warning' })
  } catch (e) {
    return
  }
  try {
    await cancelApproval(row.id)
    ElMessage.success('已撤回')
    load()
  } catch (e) {
    ElMessage.error(e.message || '撤回失败')
  }
}

async function openTransfer(row) {
  transferRow.value = row
  transferTo.value = null
  transferComment.value = ''
  if (users.value.length === 0) {
    const res = await listOrgUsers()
    users.value = (res.data || []).filter((u) => u.id !== me)
  }
  transferDialog.value = true
}

async function onTransfer() {
  if (!transferTo.value) return ElMessage.warning('请选择转交目标')
  acting.value = true
  try {
    await transferApproval(transferRow.value.id, { toUserId: transferTo.value, comment: transferComment.value })
    ElMessage.success('已转交')
    transferDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '转交失败')
  } finally {
    acting.value = false
  }
}

onMounted(load)
</script>
