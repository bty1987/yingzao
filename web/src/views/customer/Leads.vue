<template>
  <div>
    <div style="margin-bottom: 12px">
      <el-button type="primary" @click="dialog = true">登记客源</el-button>
      <el-button @click="load">刷新</el-button>
    </div>

    <el-table :data="leads" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="姓名" />
      <el-table-column prop="phone" label="电话" />
      <el-table-column prop="source" label="来源" width="120" />
      <el-table-column prop="note" label="备注" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 0 ? 'info' : 'success'">{{ row.status === 0 ? '已登记' : '已转化' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="created_at" label="登记时间" width="170" />
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <el-button v-if="row.status === 0" type="primary" link @click="onAssign(row)">分配</el-button>
          <el-button v-if="row.status === 0" type="success" link @click="onConvert(row)">转化</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="assignDialog" title="分配客源" width="440px">
      <el-form label-width="90px">
        <el-form-item label="分配方式">
          <el-radio-group v-model="assignForm.mode">
            <el-radio value="manual">指定责任人</el-radio>
            <el-radio value="auto">自动轮询</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="assignForm.mode === 'manual'" label="责任人">
          <el-select v-model="assignForm.assigneeId" filterable placeholder="选择责任人" style="width: 100%">
            <el-option v-for="u in users" :key="u.id" :label="u.display_name" :value="u.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="assignDialog = false">取消</el-button>
        <el-button type="primary" :loading="assigning" @click="onAssignSave">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dialog" title="登记客源" width="480px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="姓名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="电话"><el-input v-model="form.phone" /></el-form-item>
        <el-form-item label="来源">
          <el-select v-model="form.source">
            <el-option v-for="d in sourceDict" :key="d.key" :label="d.label" :value="d.key" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="form.note" type="textarea" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onCreate">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listLeads, createLead, convertLead, assignLead, listOrgUsers } from '../../api'
import { addons } from '../../store/addons'

const leads = ref([])
const dialog = ref(false)
const saving = ref(false)
const form = ref({ name: '', phone: '', source: '', note: '' })

const assignDialog = ref(false)
const assigning = ref(false)
const assignForm = ref({ lead: null, mode: 'manual', assigneeId: null })
const users = ref([])

// 字典来自插件下发(addon.yml dicts),不在前端硬编码
const sourceDict = ref([])
onMounted(() => {
  const customer = addons.list.find((a) => a.id === 'customer')
  sourceDict.value = (customer && customer.dicts && customer.dicts.leadSource) || []
  load()
})

async function load() {
  const res = await listLeads()
  leads.value = res.data || []
}

async function onCreate() {
  saving.value = true
  try {
    await createLead(form.value)
    ElMessage.success('登记成功')
    dialog.value = false
    form.value = { name: '', phone: '', source: '', note: '' }
    load()
  } catch (e) {
    ElMessage.error(e.message || '登记失败')
  } finally {
    saving.value = false
  }
}

async function onConvert(row) {
  try {
    await convertLead(row.id)
    ElMessage.success('已转化,客户档案已自动生成')
    load()
  } catch (e) {
    ElMessage.error(e.message || '转化失败')
  }
}

function onAssign(row) {
  assignForm.value = { lead: row, mode: 'manual', assigneeId: null }
  if (!users.value.length) {
    listOrgUsers().then((res) => { users.value = res.data || [] })
  }
  assignDialog.value = true
}

async function onAssignSave() {
  const f = assignForm.value
  if (f.mode === 'manual' && !f.assigneeId) {
    ElMessage.warning('请选择责任人')
    return
  }
  assigning.value = true
  try {
    await assignLead(f.lead.id, { mode: f.mode, assigneeId: f.assigneeId })
    ElMessage.success('分配成功,已通知责任人')
    assignDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '分配失败')
  } finally {
    assigning.value = false
  }
}
</script>
