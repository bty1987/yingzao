<template>
  <div>
    <el-card shadow="never">
      <div class="toolbar">
        <el-select v-model="type" placeholder="适用域" clearable style="width: 130px" @change="load(1)">
          <el-option v-for="(label, key) in dicts.activityType || {}" :key="key" :label="label" :value="key" />
        </el-select>
        <el-select v-model="status" placeholder="状态" clearable style="width: 110px" @change="load(1)">
          <el-option v-for="(label, key) in dicts.activityStatus || {}" :key="key" :label="label" :value="key" />
        </el-select>
        <el-input v-model="keyword" placeholder="活动名称" style="width: 200px" @keyup.enter="load(1)" />
        <el-button @click="load(1)">查询</el-button>
        <div class="spacer" />
        <el-button type="primary" @click="openForm()">新建活动</el-button>
      </div>
      <el-table :data="rows" border stripe>
        <el-table-column prop="id" label="#" width="64" />
        <el-table-column prop="name" label="活动名称" min-width="140" />
        <el-table-column label="适用域" width="100">
          <template #default="{ row }">{{ dicts.activityType?.[String(row.type)] || row.type }}</template>
        </el-table-column>
        <el-table-column label="倍率" width="90">
          <template #default="{ row }">{{ (row.multiple / 1000).toFixed(1) }} 倍</template>
        </el-table-column>
        <el-table-column prop="validays" label="有效天数" width="90" />
        <el-table-column prop="start_time" label="生效起" width="160" />
        <el-table-column prop="end_time" label="生效止" width="160" />
        <el-table-column label="默认" width="70">
          <template #default="{ row }">
            <el-tag v-if="row.is_default === 1" type="danger" size="small">默认</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">{{ dicts.activityStatus?.[String(row.status)] || row.status }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openForm(row)">编辑</el-button>
            <el-button link @click="onToggle(row)">{{ row.status === 0 ? '停用' : '启用' }}</el-button>
            <el-button v-if="row.is_default !== 1 && row.status === 0" link type="primary" @click="onDefault(row)">
              设默认
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" layout="total, prev, pager, next" :total="total" :page-size="size"
        :current-page="page" @current-change="load" />
    </el-card>

    <el-dialog v-model="formVisible" :title="form.id ? '编辑活动' : '新建活动'" width="560px">
      <el-form label-width="110px">
        <el-form-item label="活动名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="适用域" required>
          <el-select v-model="form.type" :disabled="!!form.id" style="width: 100%">
            <el-option v-for="(label, key) in dicts.activityType || {}" :key="key" :label="label" :value="Number(key)" />
          </el-select>
        </el-form-item>
        <el-form-item label="积分倍率" required>
          <el-input-number v-model="form.multiple" :min="1" :step="100" style="width: 100%" />
          <div class="hint">千分位:1000 = 1 倍(收款确认时按 款项 × 倍率 ÷ 1000 记积分)</div>
        </el-form-item>
        <el-form-item label="有效天数" required>
          <el-input-number v-model="form.validays" :min="1" style="width: 100%" />
          <div class="hint">收款后该批次积分在此天数内可兑换,过期只拦兑换不清零</div>
        </el-form-item>
        <el-form-item label="生效起" required>
          <el-date-picker v-model="form.startTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%" />
        </el-form-item>
        <el-form-item label="生效止" required>
          <el-date-picker v-model="form.endTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%" />
        </el-form-item>
        <el-form-item v-if="!form.id" label="设为默认">
          <el-switch v-model="form.isDefault" />
          <span class="hint" style="margin-left: 8px">同组织同域至多一个默认,新默认自动顶替旧默认</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchAddons,
  pageMemberActivities, createMemberActivity, updateMemberActivity,
  changeMemberActivityStatus, setMemberActivityDefault,
} from '../../api'

const dicts = ref({})
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = 20
const type = ref(null)
const status = ref(null)
const keyword = ref('')
const formVisible = ref(false)
const form = reactive({})

async function loadDicts() {
  const res = await fetchAddons()
  const addon = (res.data || []).find((a) => a.id === 'member')
  if (addon?.dicts) dicts.value = addon.dicts
}

async function load(p) {
  const target = p || page.value
  const params = { page: target, size, keyword: keyword.value || undefined }
  if (type.value !== null && type.value !== '') params.type = type.value
  if (status.value !== null && status.value !== '') params.status = status.value
  const res = await pageMemberActivities(params)
  rows.value = res.data.rows || []
  total.value = res.data.total || 0
  page.value = target
}

function openForm(row) {
  Object.assign(form, row || {}, {
    id: row?.id,
    multiple: row?.multiple ?? 1000,
    validays: row?.validays ?? 30,
    isDefault: false,
  })
  formVisible.value = true
}

async function save() {
  if (!form.name) return ElMessage.warning('请填写活动名称')
  if (!form.type) return ElMessage.warning('请选择适用域')
  if (!form.startTime || !form.endTime) return ElMessage.warning('请选择生效时间窗')
  const payload = {
    name: form.name, type: form.type, multiple: form.multiple, validays: form.validays,
    startTime: form.startTime, endTime: form.endTime, isDefault: form.isDefault, remark: form.remark,
  }
  if (form.id) await updateMemberActivity(form.id, payload)
  else await createMemberActivity(payload)
  ElMessage.success('已保存')
  formVisible.value = false
  await load()
}

async function onToggle(row) {
  if (row.is_default === 1 && row.status === 0) {
    await ElMessageBox.confirm('该活动是默认活动,停用后收款将无法记分(事件进入死信,重新启用后可补记)。确认停用?', '提示',
      { type: 'warning' })
  }
  await changeMemberActivityStatus(row.id, { status: row.status === 0 ? 1 : 0 })
  ElMessage.success('已变更')
  await load()
}

async function onDefault(row) {
  await setMemberActivityDefault(row.id)
  ElMessage.success('已设为默认')
  await load()
}

onMounted(async () => {
  await loadDicts()
  await load()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 12px; }
.spacer { flex: 1; }
.pager { margin-top: 12px; justify-content: flex-end; }
.hint { color: var(--yz-ink-50); font-size: 12px; }
</style>
