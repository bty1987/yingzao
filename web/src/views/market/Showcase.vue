<template>
  <div>
    <el-tabs v-model="tab" @tab-change="load">
      <el-tab-pane label="案例" name="cases">
        <div style="margin-bottom: 12px; display: flex; gap: 8px; flex-wrap: wrap">
          <el-button type="primary" @click="openCase(null)">新建案例</el-button>
          <el-select v-model="caseQuery.cityId" placeholder="城市" clearable style="width: 130px" @change="loadCases">
            <el-option v-for="c in cities" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
          <el-select v-model="caseQuery.type" placeholder="类型" clearable style="width: 110px" @change="loadCases">
            <el-option v-for="d in typeDict" :key="d.key" :label="d.label" :value="Number(d.key)" />
          </el-select>
          <el-select v-model="caseQuery.status" placeholder="状态" clearable style="width: 100px" @change="loadCases">
            <el-option v-for="d in caseStatusDict" :key="d.key" :label="d.label" :value="Number(d.key)" />
          </el-select>
          <el-input v-model="caseQuery.name" placeholder="案例名称" clearable style="width: 150px" @keyup.enter="loadCases" />
          <el-button @click="loadCases">查询</el-button>
        </div>
        <el-table :data="cases" border stripe>
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="name" label="案例" min-width="150" />
          <el-table-column prop="city_name" label="城市" width="100" />
          <el-table-column prop="village" label="楼盘" min-width="120" />
          <el-table-column prop="style" label="风格" width="90" />
          <el-table-column prop="layout" label="户型" width="90" />
          <el-table-column label="面积(㎡)" width="100">
            <template #default="{ row }">{{ row.area ? (row.area / 1000).toFixed(1) : '-' }}</template>
          </el-table-column>
          <el-table-column prop="designer_name" label="设计师" width="100" />
          <el-table-column prop="supervisor_name" label="监理" width="100" />
          <el-table-column label="类型" width="90">
            <template #default="{ row }">{{ dictLabel('caseType', row.type) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ dictLabel('caseStatus', row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" link @click="openCase(row)">编辑</el-button>
              <el-button type="success" link @click="onToggleStatus(row)">{{ row.status === 1 ? '下架' : '上架' }}</el-button>
              <el-button type="primary" link @click="openLead(row)">报名</el-button>
              <el-button v-if="row.status === 0" type="danger" link @click="onDeleteCase(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div style="margin-top: 12px; display: flex; justify-content: flex-end">
          <el-pagination layout="total, prev, pager, next" :total="caseTotal" :page-size="caseQuery.size"
            v-model:current-page="caseQuery.page" @current-change="loadCases" />
        </div>
      </el-tab-pane>

      <el-tab-pane label="城市字典" name="cities">
        <div style="margin-bottom: 12px">
          <el-button type="primary" @click="openCity(null)">新建城市</el-button>
        </div>
        <el-table :data="cities" border stripe>
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="name" label="城市" min-width="140" />
          <el-table-column prop="pinyin" label="拼音" min-width="140" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 0 ? 'success' : 'info'">{{ row.status === 0 ? '启用' : '停用' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="160" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" link @click="openCity(row)">编辑</el-button>
              <el-button type="danger" link @click="onDeleteCity(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="人物库" name="persons">
        <div style="margin-bottom: 12px">
          <el-button type="primary" @click="openPerson(null)">新建人物</el-button>
        </div>
        <el-table :data="persons" border stripe>
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="name" label="姓名" min-width="120" />
          <el-table-column label="类型" width="100">
            <template #default="{ row }">{{ dictLabel('personType', row.type) }}</template>
          </el-table-column>
          <el-table-column prop="title" label="头衔" min-width="120" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 0 ? 'success' : 'info'">{{ row.status === 0 ? '启用' : '停用' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="160" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" link @click="openPerson(row)">编辑</el-button>
              <el-button type="danger" link @click="onDeletePerson(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="报名列表" name="leads">
        <el-table :data="leads" border stripe>
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="inbound_no" label="进线号" width="160" />
          <el-table-column prop="case_name" label="案例" min-width="140" />
          <el-table-column prop="name" label="访客" width="110" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="statusTag(row.status)">{{ dictLabel('inboundStatus', row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="承接结果" min-width="140">
            <template #default="{ row }">
              <span v-if="row.status === 1">客源#{{ row.customer_lead_id }}<template v-if="row.chance_id"> / 机会#{{ row.chance_id }}</template></span>
              <span v-else-if="row.status === 2" style="color: #b03a2e">{{ row.error_msg }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="created_at" label="报名时间" width="170" />
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="caseDialog" title="案例" width="640px">
      <el-form :model="caseForm" label-width="90px">
        <el-form-item label="案例名称"><el-input v-model="caseForm.name" /></el-form-item>
        <el-form-item label="城市">
          <el-select v-model="caseForm.cityId" filterable style="width: 100%">
            <el-option v-for="c in cities" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="楼盘"><el-input v-model="caseForm.village" /></el-form-item>
        <el-form-item label="风格"><el-input v-model="caseForm.style" /></el-form-item>
        <el-form-item label="户型"><el-input v-model="caseForm.layout" /></el-form-item>
        <el-form-item label="面积(㎡)"><el-input-number v-model="caseForm.areaYuan" :min="0" :precision="1" /></el-form-item>
        <el-form-item label="设计师">
          <el-select v-model="caseForm.designerId" filterable clearable style="width: 100%">
            <el-option v-for="p in designers" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="监理">
          <el-select v-model="caseForm.supervisorId" filterable clearable style="width: 100%">
            <el-option v-for="p in supervisors" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="caseForm.type">
            <el-radio v-for="d in typeDict" :key="d.key" :value="Number(d.key)">{{ d.label }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="caseForm.type === 1" label="工期(天)">
          <el-input-number v-model="caseForm.cycle" :min="0" />
        </el-form-item>
        <el-form-item label="封面URL"><el-input v-model="caseForm.coverUrl" /></el-form-item>
        <el-form-item label="图片列表">
          <div v-for="(p, i) in caseForm.pictures" :key="i" style="display: flex; gap: 6px; margin-bottom: 4px; width: 100%">
            <el-input v-model="p.url" placeholder="图片URL" />
            <el-input-number v-model="p.sort" :min="0" style="width: 100px" />
            <el-button type="danger" link @click="caseForm.pictures.splice(i, 1)">删</el-button>
          </div>
          <el-button size="small" @click="caseForm.pictures.push({ url: '', sort: caseForm.pictures.length })">加图片</el-button>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="caseDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveCase">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="cityDialog" title="城市" width="420px">
      <el-form label-width="90px">
        <el-form-item label="城市名"><el-input v-model="cityForm.name" /></el-form-item>
        <el-form-item label="拼音"><el-input v-model="cityForm.pinyin" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cityDialog = false">取消</el-button>
        <el-button type="primary" @click="saveCity">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="personDialog" title="人物" width="440px">
      <el-form label-width="90px">
        <el-form-item label="姓名"><el-input v-model="personForm.name" /></el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="personForm.type">
            <el-radio v-for="d in personTypeDict" :key="d.key" :value="Number(d.key)">{{ d.label }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="头衔"><el-input v-model="personForm.title" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="personDialog = false">取消</el-button>
        <el-button type="primary" @click="savePerson">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="leadDialog" title="案例报名登记" width="480px">
      <el-form label-width="90px">
        <el-form-item label="案例">{{ leadCase && leadCase.name }}</el-form-item>
        <el-form-item label="访客姓名"><el-input v-model="leadForm.name" /></el-form-item>
        <el-form-item label="手机号"><el-input v-model="leadForm.phone" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="leadForm.note" type="textarea" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="leadDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveLead">登记</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  pageEcases, fetchEcase, createEcase, updateEcase, changeEcaseStatus, deleteEcase,
  listEcities, createEcity, updateEcity, deleteEcity,
  listEpersons, createEperson, updateEperson, deleteEperson,
  leadForEcase, listEcaseLeads,
} from '../../api'
import { addons } from '../../store/addons'

const tab = ref('cases')
const dicts = ref({})
const cases = ref([])
const caseTotal = ref(0)
const cities = ref([])
const persons = ref([])
const leads = ref([])
const caseQuery = ref({ cityId: null, type: null, status: null, name: '', page: 1, size: 20 })
const saving = ref(false)

const caseDialog = ref(false)
const caseForm = ref(null)
const cityDialog = ref(false)
const cityForm = ref({ id: null, name: '', pinyin: '' })
const personDialog = ref(false)
const personForm = ref({ id: null, name: '', type: 0, title: '' })
const leadDialog = ref(false)
const leadCase = ref(null)
const leadForm = ref({ name: '', phone: '', note: '' })

const typeDict = computed(() => dicts.value.caseType || [])
const caseStatusDict = computed(() => dicts.value.caseStatus || [])
const personTypeDict = computed(() => dicts.value.personType || [])
const designers = computed(() => persons.value.filter((p) => p.type === 0))
const supervisors = computed(() => persons.value.filter((p) => p.type === 1))

onMounted(() => {
  const market = addons.list.find((a) => a.id === 'market')
  dicts.value = (market && market.dicts) || {}
  load()
})

function dictLabel(group, key) {
  const list = dicts.value[group] || []
  const hit = list.find((d) => String(d.key) === String(key))
  return hit ? hit.label : key
}

function statusTag(status) {
  return { 0: 'info', 1: 'success', 2: 'danger', 3: 'warning', 4: 'info' }[status] || 'info'
}

async function load() {
  if (tab.value === 'cases') return loadCases()
  if (tab.value === 'cities') return loadCities()
  if (tab.value === 'persons') return loadPersons()
  if (tab.value === 'leads') return loadLeads()
}

async function loadCases() {
  const res = await pageEcases(caseQuery.value)
  cases.value = (res.data && res.data.rows) || []
  caseTotal.value = (res.data && res.data.total) || 0
}
async function loadCities() {
  cities.value = (await listEcities()).data || []
}
async function loadPersons() {
  persons.value = (await listEpersons()).data || []
}
async function loadLeads() {
  leads.value = (await listEcaseLeads({ limit: 100 })).data || []
}

function emptyCaseForm() {
  return { id: null, name: '', cityId: null, village: '', style: '', layout: '', areaYuan: 0,
    designerId: null, supervisorId: null, type: 0, cycle: 0, coverUrl: '', pictures: [] }
}

async function openCase(row) {
  if (row) {
    const detail = (await fetchEcase(row.id)).data
    caseForm.value = {
      id: detail.id, name: detail.name, cityId: detail.city_id, village: detail.village,
      style: detail.style, layout: detail.layout, areaYuan: (detail.area || 0) / 1000,
      designerId: detail.designer_id, supervisorId: detail.supervisor_id, type: detail.type,
      cycle: detail.cycle || 0, coverUrl: detail.cover_url,
      pictures: (detail.pictures || []).map((p) => ({ url: p.url, sort: p.sort })),
    }
  } else {
    caseForm.value = emptyCaseForm()
  }
  caseDialog.value = true
}

async function saveCase() {
  saving.value = true
  try {
    const f = caseForm.value
    const data = {
      cityId: f.cityId, designerId: f.designerId, supervisorId: f.supervisorId, name: f.name,
      village: f.village, style: f.style, layout: f.layout, area: Math.round(f.areaYuan * 1000),
      cycle: f.cycle, coverUrl: f.coverUrl, type: f.type,
      pictures: f.pictures.filter((p) => p.url && p.url.trim()),
    }
    if (f.id) {
      await updateEcase(f.id, data)
    } else {
      await createEcase(data)
    }
    ElMessage.success('保存成功')
    caseDialog.value = false
    loadCases()
  } finally {
    saving.value = false
  }
}

async function onToggleStatus(row) {
  await changeEcaseStatus(row.id, { status: row.status === 1 ? 0 : 1 })
  loadCases()
}

async function onDeleteCase(row) {
  await ElMessageBox.confirm('删除案例将级联删除图片(仅下架态可删),确认?', '提示', { type: 'warning' })
  await deleteEcase(row.id)
  ElMessage.success('已删除')
  loadCases()
}

function openCity(row) {
  cityForm.value = row ? { id: row.id, name: row.name, pinyin: row.pinyin } : { id: null, name: '', pinyin: '' }
  cityDialog.value = true
}
async function saveCity() {
  if (cityForm.value.id) {
    await updateEcity(cityForm.value.id, cityForm.value)
  } else {
    await createEcity(cityForm.value)
  }
  ElMessage.success('保存成功')
  cityDialog.value = false
  loadCities()
}
async function onDeleteCity(row) {
  await ElMessageBox.confirm('删除城市(被案例引用将拒绝),确认?', '提示', { type: 'warning' })
  await deleteEcity(row.id)
  loadCities()
}

function openPerson(row) {
  personForm.value = row
    ? { id: row.id, name: row.name, type: row.type, title: row.title }
    : { id: null, name: '', type: 0, title: '' }
  personDialog.value = true
}
async function savePerson() {
  if (personForm.value.id) {
    await updateEperson(personForm.value.id, personForm.value)
  } else {
    await createEperson(personForm.value)
  }
  ElMessage.success('保存成功')
  personDialog.value = false
  loadPersons()
}
async function onDeletePerson(row) {
  await ElMessageBox.confirm('删除人物(被案例引用将拒绝),确认?', '提示', { type: 'warning' })
  await deleteEperson(row.id)
  loadPersons()
}

function openLead(row) {
  leadCase.value = row
  leadForm.value = { name: '', phone: '', note: '' }
  leadDialog.value = true
}
async function saveLead() {
  saving.value = true
  try {
    await leadForEcase(leadCase.value.id, leadForm.value)
    ElMessage.success('报名登记成功,承接进行中')
    leadDialog.value = false
  } finally {
    saving.value = false
  }
}
</script>
