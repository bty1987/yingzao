<template>
  <div>
    <el-card shadow="never">
      <div style="display: flex; gap: 8px; margin-bottom: 12px">
        <el-input v-model="keyword" placeholder="姓名 / 手机号" style="width: 260px" clearable
                  @keyup.enter="load(1)" />
        <el-button type="primary" @click="load(1)">查询</el-button>
        <el-button @click="load(1)">刷新</el-button>
        <div style="flex: 1" />
        <el-button type="primary" @click="createVisible = true">新建客户</el-button>
      </div>

      <el-table :data="rows" border stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="no" label="客户编号" width="130" />
        <el-table-column prop="name" label="姓名" min-width="120" />
        <el-table-column prop="gender" label="性别" width="70" />
        <el-table-column prop="address" label="地址" min-width="160" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '已实名' : '初始' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="created_at" label="建档时间" width="170" />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination style="margin-top: 12px; justify-content: flex-end"
                     layout="total, prev, pager, next" :total="total" :page-size="size"
                     :current-page="page" @current-change="load" />
    </el-card>

    <!-- 新建客户 -->
    <el-dialog v-model="createVisible" title="新建客户" width="480px">
      <el-form label-width="80px">
        <el-form-item label="姓名" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="手机号" required><el-input v-model="form.phone" /></el-form-item>
        <el-form-item label="性别"><el-input v-model="form.gender" /></el-form-item>
        <el-form-item label="地址"><el-input v-model="form.address" /></el-form-item>
        <el-form-item label="身份证"><el-input v-model="form.identity" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="doCreate">保存</el-button>
      </template>
    </el-dialog>

    <!-- 客户详情:基础信息 + 多电话(显式默认/启停/解密留痕)+ 收款账户 + 查询审计 -->
    <el-drawer v-model="detailVisible" :title="detail ? `客户档案 · ${detail.name}` : '客户档案'" size="640px">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="编号">{{ detail.no }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ detail.status === 1 ? '已实名' : '初始' }}</el-descriptions-item>
          <el-descriptions-item label="姓名">{{ detail.name }}</el-descriptions-item>
          <el-descriptions-item label="默认号码">{{ detail.phoneMasked }}</el-descriptions-item>
          <el-descriptions-item label="性别">{{ detail.gender || '-' }}</el-descriptions-item>
          <el-descriptions-item label="地址">{{ detail.address || '-' }}</el-descriptions-item>
        </el-descriptions>

        <h4>电话</h4>
        <el-table :data="phones" size="small" border>
          <el-table-column prop="phoneMasked" label="号码" />
          <el-table-column label="默认" width="70">
            <template #default="{ row }">
              <el-tag v-if="row.is_default === 1" type="success" size="small">默认</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="70">
            <template #default="{ row }">
              <el-tag :type="row.status === 0 ? 'success' : 'info'" size="small">
                {{ row.status === 0 ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="210">
            <template #default="{ row }">
              <el-button v-if="row.is_default !== 1" link type="primary" size="small"
                         :disabled="row.status !== 0" @click="setDefaultPhone(row)">设默认</el-button>
              <el-button link size="small" :type="row.status === 0 ? 'danger' : 'success'"
                         :disabled="row.is_default === 1 && row.status === 0" @click="togglePhone(row)">
                {{ row.status === 0 ? '停用' : '启用' }}</el-button>
              <el-button link type="warning" size="small" @click="reveal(row)">查看明文</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div style="display: flex; gap: 8px; margin-top: 8px">
          <el-input v-model="newPhone" placeholder="新增号码" style="width: 220px" />
          <el-button @click="addPhone">添加号码</el-button>
        </div>

        <h4>收款账户</h4>
        <el-table :data="accounts" size="small" border>
          <el-table-column prop="holder_name" label="户名" />
          <el-table-column prop="bank_name" label="开户行" />
          <el-table-column prop="accountMasked" label="账号" />
          <el-table-column label="类型" width="70">
            <template #default="{ row }">{{ row.type === 1 ? '对公' : '对私' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="170">
            <template #default="{ row }">
              <el-button v-if="row.is_default !== 1" link type="primary" size="small"
                         :disabled="row.status !== 0" @click="setDefaultAccount(row)">设默认</el-button>
              <el-button link size="small" :type="row.status === 0 ? 'danger' : 'success'"
                         :disabled="row.is_default === 1 && row.status === 0" @click="toggleAccount(row)">
                {{ row.status === 0 ? '停用' : '启用' }}</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div style="display: flex; gap: 8px; margin-top: 8px">
          <el-input v-model="newAccount.holderName" placeholder="户名" style="width: 140px" />
          <el-input v-model="newAccount.bankName" placeholder="开户行" style="width: 140px" />
          <el-input v-model="newAccount.accountNo" placeholder="账号" style="width: 180px" />
          <el-button @click="addAccount">添加账户</el-button>
        </div>

        <h4>电话查看审计</h4>
        <el-table :data="accessLogs" size="small" border>
          <el-table-column prop="accessed_by_name" label="查看人" width="110" />
          <el-table-column prop="purpose" label="事由" />
          <el-table-column prop="created_at" label="时间" width="170" />
        </el-table>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, ref, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  pageCustomers, createCustomer, fetchCustomer,
  listCustomerPhones, addCustomerPhone, setDefaultCustomerPhone, updateCustomerPhoneStatus,
  revealCustomerPhone, listPhoneAccessLogs,
  listCustomerAccounts, addCustomerAccount, setDefaultCustomerAccount, updateCustomerAccountStatus
} from '../../api'

const keyword = ref('')
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = 20

const createVisible = ref(false)
const form = reactive({ name: '', phone: '', gender: '', address: '', identity: '' })

const detailVisible = ref(false)
const detail = ref(null)
const phones = ref([])
const accounts = ref([])
const accessLogs = ref([])
const newPhone = ref('')
const newAccount = reactive({ holderName: '', bankName: '', accountNo: '' })

onMounted(() => load(1))

async function load(target) {
  page.value = typeof target === 'number' ? target : page.value
  const res = await pageCustomers({ keyword: keyword.value, page: page.value, size })
  rows.value = res.data.rows || []
  total.value = res.data.total || 0
}

async function doCreate() {
  await createCustomer({ ...form })
  ElMessage.success('已建档')
  createVisible.value = false
  Object.keys(form).forEach((k) => (form[k] = ''))
  load(1)
}

async function openDetail(row) {
  const res = await fetchCustomer(row.id)
  detail.value = res.data
  detailVisible.value = true
  newPhone.value = ''
  await reloadSubs(row.id)
}

async function reloadSubs(id) {
  const customerId = id || detail.value.id
  const [p, a, l] = await Promise.all([
    listCustomerPhones(customerId), listCustomerAccounts(customerId),
    listPhoneAccessLogs(customerId, { page: 1, size: 50 })
  ])
  phones.value = p.data || []
  accounts.value = a.data || []
  accessLogs.value = l.data.rows || []
}

async function addPhone() {
  if (!newPhone.value) return
  await addCustomerPhone(detail.value.id, { phone: newPhone.value })
  newPhone.value = ''
  await refreshDetail()
  ElMessage.success('号码已添加(首个号码自动为默认)')
}

async function setDefaultPhone(row) {
  await setDefaultCustomerPhone(detail.value.id, row.id)
  await refreshDetail()
}

async function togglePhone(row) {
  const status = row.status === 0 ? 1 : 0
  if (status === 1) {
    await ElMessageBox.confirm(`确认停用号码 ${row.phoneMasked}?`, '提示', { type: 'warning' })
  }
  await updateCustomerPhoneStatus(detail.value.id, row.id, { status })
  await refreshDetail()
}

async function reveal(row) {
  const { value } = await ElMessageBox.prompt(
    '查看明文号码将留痕审计,请输入事由', '查看明文', { inputPlaceholder: '如:合同签署核对' })
  const res = await revealCustomerPhone(detail.value.id, row.id, { purpose: value || '' })
  await ElMessageBox.alert(res.data.phone, '明文号码(已留痕)')
  await refreshDetail()
}

async function addAccount() {
  if (!newAccount.holderName || !newAccount.accountNo) return
  await addCustomerAccount(detail.value.id, { ...newAccount, type: 0 })
  Object.keys(newAccount).forEach((k) => (newAccount[k] = ''))
  await reloadSubs()
  ElMessage.success('账户已添加(首个账户自动为默认)')
}

async function setDefaultAccount(row) {
  await setDefaultCustomerAccount(detail.value.id, row.id)
  await reloadSubs()
}

async function toggleAccount(row) {
  const status = row.status === 0 ? 1 : 0
  if (status === 1) {
    await ElMessageBox.confirm('确认停用该账户?', '提示', { type: 'warning' })
  }
  await updateCustomerAccountStatus(detail.value.id, row.id, { status })
  await reloadSubs()
}

async function refreshDetail() {
  const res = await fetchCustomer(detail.value.id)
  detail.value = res.data
  await reloadSubs()
}
</script>
