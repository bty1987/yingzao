<template>
  <div>
    <el-card shadow="never">
      <div style="display: flex; gap: 8px; margin-bottom: 12px">
        <el-input v-model="keyword" placeholder="名称 / 编号 / 外部编号" style="width: 240px" clearable
                  @keyup.enter="load(1)" />
        <el-select v-model="status" placeholder="状态" style="width: 110px" clearable @change="load(1)">
          <el-option v-for="(label, key) in dicts.supplierStatus" :key="key" :label="label" :value="key" />
        </el-select>
        <el-button type="primary" @click="load(1)">查询</el-button>
        <el-button @click="load(1)">刷新</el-button>
        <div style="flex: 1" />
        <el-button type="primary" @click="openCreate">新建厂商</el-button>
      </div>

      <el-table :data="rows" border stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="no" label="编号" width="110" />
        <el-table-column prop="name" label="厂商简称" min-width="130" />
        <el-table-column prop="full_name" label="企业全称" min-width="160" show-overflow-tooltip />
        <el-table-column prop="contact" label="联系人" width="90" />
        <el-table-column prop="phone" label="电话" width="130" />
        <el-table-column prop="ext_no" label="外部编号" width="120" show-overflow-tooltip />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="Number(row.status) === 0 ? 'success' : 'info'" size="small">
              {{ dicts.supplierStatus?.[String(row.status)] || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="created_at" label="建档时间" width="170" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link :type="Number(row.status) === 0 ? 'danger' : 'success'" @click="toggleStatus(row)">
              {{ Number(row.status) === 0 ? '停用' : '启用' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination style="margin-top: 12px; justify-content: flex-end"
                     layout="total, prev, pager, next" :total="total" :page-size="size"
                     :current-page="page" @current-change="load" />
    </el-card>

    <!-- 新建/编辑厂商 -->
    <el-dialog v-model="formVisible" :title="form.id ? '编辑厂商' : '新建厂商'" width="520px">
      <el-form label-width="110px">
        <el-form-item label="厂商简称" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="企业全称"><el-input v-model="form.fullName" /></el-form-item>
        <el-form-item label="联系人"><el-input v-model="form.contact" /></el-form-item>
        <el-form-item label="电话"><el-input v-model="form.phone" /></el-form-item>
        <el-form-item label="地址"><el-input v-model="form.address" /></el-form-item>
        <el-form-item label="统一信用代码"><el-input v-model="form.creditCode" /></el-form-item>
        <el-form-item label="外部编号"><el-input v-model="form.extNo" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" @click="doSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 厂商详情:基本信息 + 品牌关联 + 三层结算条款 -->
    <el-drawer v-model="detailVisible" :title="detail ? `厂商 · ${detail.name}` : '厂商详情'" size="720px">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="编号">{{ detail.no }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ dicts.supplierStatus?.[String(detail.status)] || detail.status }}
          </el-descriptions-item>
          <el-descriptions-item label="厂商简称">{{ detail.name }}</el-descriptions-item>
          <el-descriptions-item label="企业全称">{{ detail.full_name || '-' }}</el-descriptions-item>
          <el-descriptions-item label="联系人">{{ detail.contact || '-' }}</el-descriptions-item>
          <el-descriptions-item label="电话">{{ detail.phone || '-' }}</el-descriptions-item>
          <el-descriptions-item label="统一信用代码">{{ detail.credit_code || '-' }}</el-descriptions-item>
          <el-descriptions-item label="外部编号">{{ detail.ext_no || '-' }}</el-descriptions-item>
          <el-descriptions-item label="地址" :span="2">{{ detail.address || '-' }}</el-descriptions-item>
        </el-descriptions>

        <div style="display: flex; align-items: center; margin: 16px 0 8px">
          <h4 style="margin: 0; flex: 1">品牌关联</h4>
          <el-select v-model="bindBrandId" filterable placeholder="选择品牌" style="width: 180px" size="small">
            <el-option v-for="b in brandOptions" :key="b.id" :label="b.name" :value="b.id" />
          </el-select>
          <el-checkbox v-model="bindPrimary" style="margin: 0 8px">直属</el-checkbox>
          <el-button type="primary" size="small" @click="doBind">关联</el-button>
        </div>
        <el-table :data="brands" size="small" border>
          <el-table-column prop="brand_name" label="品牌" min-width="120" />
          <el-table-column label="直属" width="70">
            <template #default="{ row }">
              <el-tag v-if="row.is_primary === 1" type="danger" size="small">直属</el-tag>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column prop="created_at" label="关联时间" width="160" />
          <el-table-column label="操作" width="150">
            <template #default="{ row }">
              <el-button v-if="row.is_primary !== 1" link type="primary" size="small"
                         @click="doPrimary(row)">设直属</el-button>
              <el-button link type="danger" size="small" @click="doUnbind(row)">解绑</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div style="display: flex; align-items: center; margin: 16px 0 8px">
          <h4 style="margin: 0; flex: 1">结算条款(品牌级 → 品类细化 → 商品价)</h4>
          <el-select v-model="termBrandId" filterable placeholder="选择已关联品牌" style="width: 180px"
                     size="small">
            <el-option v-for="b in brands" :key="b.brand_id" :label="b.brand_name" :value="b.brand_id" />
          </el-select>
        </div>

        <template v-if="termBrandId">
          <el-form inline size="small">
            <el-form-item label="结算类型">
              <el-select v-model="brandTerm.settleType" style="width: 90px">
                <el-option v-for="(label, key) in dicts.settleType" :key="key" :label="label" :value="Number(key)" />
              </el-select>
            </el-form-item>
            <el-form-item label="结算值">
              <el-input-number v-model="brandTerm.settleValue" :min="1" controls-position="right" />
            </el-form-item>
            <el-form-item label="违约金(厘)">
              <el-input-number v-model="brandTerm.penalty" :min="0" controls-position="right" />
            </el-form-item>
            <el-form-item label="质保(月)">
              <el-input-number v-model="brandTerm.warrantyMonths" :min="0" controls-position="right" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" size="small" @click="doSaveBrandTerm">保存品牌条款</el-button>
            </el-form-item>
          </el-form>

          <div style="margin: 8px 0 4px; font-weight: 600">品类细化</div>
          <el-form inline size="small">
            <el-form-item label="品类">
              <el-select v-model="catTerm.categoryId" filterable style="width: 150px">
                <el-option v-for="c in categoryOptions" :key="c.id" :label="c.name" :value="c.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="类型">
              <el-select v-model="catTerm.settleType" style="width: 90px">
                <el-option v-for="(label, key) in dicts.settleType" :key="key" :label="label" :value="Number(key)" />
              </el-select>
            </el-form-item>
            <el-form-item label="结算值">
              <el-input-number v-model="catTerm.settleValue" :min="1" controls-position="right" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" size="small" @click="doAddCategory">添加</el-button>
            </el-form-item>
          </el-form>
          <el-table :data="currentTerm?.categories || []" size="small" border style="margin-bottom: 8px">
            <el-table-column prop="category_id" label="品类ID" width="90" />
            <el-table-column label="类型" width="90">
              <template #default="{ row }">{{ dicts.settleType?.[String(row.settle_type)] || row.settle_type }}</template>
            </el-table-column>
            <el-table-column prop="settle_value" label="结算值" />
            <el-table-column label="操作" width="80">
              <template #default="{ row }">
                <el-button link type="danger" size="small"
                           @click="doRemoveCategory(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>

          <div style="margin: 8px 0 4px; font-weight: 600">商品供货价 / 结算价(厘)</div>
          <el-form inline size="small">
            <el-form-item label="商品">
              <el-select v-model="goodsTerm.goodsId" filterable remote :remote-method="searchGoods"
                         :loading="goodsLoading" placeholder="搜商品名称" style="width: 200px">
                <el-option v-for="g in goodsOptions" :key="g.id" :label="`${g.name}(${g.brand_name || '-'})`"
                           :value="g.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="供货价">
              <el-input-number v-model="goodsTerm.price" :min="1" controls-position="right" />
            </el-form-item>
            <el-form-item label="结算价">
              <el-input-number v-model="goodsTerm.balance" :min="1" controls-position="right" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" size="small" @click="doAddGoods">添加</el-button>
            </el-form-item>
          </el-form>
          <el-table :data="currentTerm?.goods || []" size="small" border>
            <el-table-column prop="goods_name" label="商品" min-width="140" />
            <el-table-column prop="price" label="供货价" width="110" />
            <el-table-column prop="balance" label="结算价" width="110" />
            <el-table-column label="操作" width="80">
              <template #default="{ row }">
                <el-button link type="danger" size="small" @click="doRemoveGoods(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  pageSuppliers, fetchSupplier, createSupplier, updateSupplier, changeSupplierStatus,
  listSupplierBrands, bindSupplierBrand, unbindSupplierBrand, setSupplierPrimary,
  listSupplierTerms, saveBrandTerm, addCategoryTerm, removeCategoryTerm,
  addGoodsTerm, removeGoodsTerm, listBrands, pageGoods, listCategories, fetchAddons,
} from '../../api'

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = 20
const keyword = ref('')
const status = ref(null)
const dicts = ref({})

const formVisible = ref(false)
const form = ref({})

const detailVisible = ref(false)
const detail = ref(null)
const brands = ref([])
const bindBrandId = ref(null)
const bindPrimary = ref(false)
const brandOptions = ref([])

const termBrandId = ref(null)
const brandTerm = ref({ settleType: 0, settleValue: 100, penalty: 0, warrantyMonths: 0 })
const catTerm = ref({ categoryId: null, settleType: 0, settleValue: 100 })
const goodsTerm = ref({ goodsId: null, price: 1000, balance: 900 })
const categoryOptions = ref([])
const goodsOptions = ref([])
const goodsLoading = ref(false)
const terms = ref([])

const currentTerm = computed(() => (terms.value || []).find((t) => t.brand_id === termBrandId.value))

async function load(p) {
  const target = p || page.value
  const params = { page: target, size, keyword: keyword.value || undefined }
  if (status.value !== null && status.value !== '') params.status = status.value
  const res = await pageSuppliers(params)
  rows.value = res.data.rows || []
  total.value = res.data.total || 0
  page.value = target
}

async function loadDicts() {
  const res = await fetchAddons()
  const addon = (res.data || []).find((a) => a.id === 'supplier')
  if (addon?.dicts) dicts.value = addon.dicts
}

function openCreate() {
  form.value = {}
  formVisible.value = true
}

function openEdit(row) {
  form.value = { ...row }
  formVisible.value = true
}

async function doSave() {
  if (form.value.id) {
    await updateSupplier(form.value.id, form.value)
  } else {
    await createSupplier(form.value)
  }
  ElMessage.success('已保存')
  formVisible.value = false
  load(1)
}

async function toggleStatus(row) {
  const next = Number(row.status) === 0 ? 1 : 0
  await ElMessageBox.confirm(
    next === 1 ? '停用后不可再挂新单,存量单不受影响。确认停用?' : '确认启用该厂商?',
    '提示', { type: 'warning' })
  await changeSupplierStatus(row.id, { status: next })
  ElMessage.success('已变更')
  load(page.value)
}

async function openDetail(row) {
  const res = await fetchSupplier(row.id)
  detail.value = res.data
  detailVisible.value = true
  termBrandId.value = null
  await Promise.all([reloadBrands(), reloadTerms(), loadBindOptions()])
}

async function reloadBrands() {
  const res = await listSupplierBrands(detail.value.id)
  brands.value = res.data || []
}

async function reloadTerms() {
  const res = await listSupplierTerms(detail.value.id)
  terms.value = res.data || []
}

async function loadBindOptions() {
  const res = await listBrands({ page: 1, size: 100 })
  brandOptions.value = res.data || []
  try {
    const cats = await listCategories()
    categoryOptions.value = Array.isArray(cats.data) ? cats.data : (cats.data?.rows || [])
  } catch (e) {
    categoryOptions.value = []
  }
}

async function doBind() {
  if (!bindBrandId.value) return ElMessage.warning('请选择品牌')
  await bindSupplierBrand(detail.value.id, { brandId: bindBrandId.value, isPrimary: bindPrimary.value })
  ElMessage.success('已关联')
  bindBrandId.value = null
  bindPrimary.value = false
  await reloadBrands()
  await reloadTerms()
}

async function doUnbind(row) {
  await ElMessageBox.confirm(`解绑品牌「${row.brand_name}」?历史结算/选材快照不受影响。`, '提示',
    { type: 'warning' })
  await unbindSupplierBrand(detail.value.id, row.brand_id)
  await reloadBrands()
  await reloadTerms()
}

async function doPrimary(row) {
  await setSupplierPrimary(detail.value.id, row.brand_id)
  ElMessage.success('已设为直属(原直属被顶替)')
  await reloadBrands()
}

async function doSaveBrandTerm() {
  await saveBrandTerm(detail.value.id, termBrandId.value, brandTerm.value)
  ElMessage.success('品牌条款已保存')
  await reloadTerms()
}

async function doAddCategory() {
  if (!catTerm.value.categoryId) return ElMessage.warning('请选择品类')
  await addCategoryTerm(detail.value.id, termBrandId.value, catTerm.value)
  ElMessage.success('品类细化已添加')
  await reloadTerms()
}

async function doRemoveCategory(row) {
  await removeCategoryTerm(detail.value.id, termBrandId.value, row.category_id)
  await reloadTerms()
}

async function searchGoods(query) {
  if (!query) return
  goodsLoading.value = true
  try {
    const res = await pageGoods({ keyword: query, page: 1, size: 20 })
    goodsOptions.value = res.data?.rows || []
  } finally {
    goodsLoading.value = false
  }
}

async function doAddGoods() {
  if (!goodsTerm.value.goodsId) return ElMessage.warning('请选择商品')
  await addGoodsTerm(detail.value.id, termBrandId.value, goodsTerm.value)
  ElMessage.success('商品条款已添加')
  await reloadTerms()
}

async function doRemoveGoods(row) {
  await removeGoodsTerm(detail.value.id, termBrandId.value, row.goods_id)
  await reloadTerms()
}

onMounted(() => {
  loadDicts()
  load(1)
})
</script>
