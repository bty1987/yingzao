<template>
  <div>
    <el-card shadow="never">
      <div style="display: flex; gap: 8px; margin-bottom: 12px; flex-wrap: wrap">
        <el-input v-model="query.keyword" placeholder="名称 / 编号 / 拼音 / 条码" style="width: 220px" clearable
                  @keyup.enter="load(1)" />
        <el-select v-model="query.categoryId" placeholder="品类" style="width: 160px" clearable>
          <el-option v-for="c in categoryOptions" :key="c.id" :value="c.id"
                     :label="c.indentLabel || c.name" />
        </el-select>
        <el-select v-model="query.brandId" placeholder="品牌" style="width: 140px" clearable>
          <el-option v-for="b in brands" :key="b.id" :value="b.id" :label="b.name" />
        </el-select>
        <el-select v-model="query.status" placeholder="状态" style="width: 110px" clearable>
          <el-option label="启用" :value="0" />
          <el-option label="停用" :value="1" />
        </el-select>
        <el-select v-model="query.type" placeholder="类型" style="width: 110px" clearable>
          <el-option label="商品" :value="0" />
          <el-option label="套餐" :value="1" />
        </el-select>
        <el-button type="primary" @click="load(1)">查询</el-button>
        <el-button @click="resetQuery">重置</el-button>
        <div style="flex: 1" />
        <el-button @click="dictVisible = true">品类 / 品牌字典</el-button>
        <el-button type="primary" @click="openCreate">新建商品</el-button>
      </div>

      <el-table :data="rows" border stripe>
        <el-table-column prop="no" label="编号" width="110" />
        <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
        <el-table-column prop="category_name" label="品类" width="120" show-overflow-tooltip />
        <el-table-column prop="brand_name" label="品牌" width="110" />
        <el-table-column prop="unit" label="单位" width="70" />
        <el-table-column label="类型" width="80">
          <template #default="{ row }">
            <el-tag :type="row.type === 1 ? 'warning' : 'info'" size="small">
              {{ row.type === 1 ? '套餐' : '商品' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="进价(元)" width="100" align="right">
          <template #default="{ row }">{{ yuan(row.purchase) }}</template>
        </el-table-column>
        <el-table-column label="售价(元)" width="100" align="right">
          <template #default="{ row }">{{ yuan(row.selling) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 0 ? 'success' : 'info'" size="small">
              {{ row.status === 0 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="created_at" label="建档时间" width="170" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link :type="row.status === 0 ? 'danger' : 'success'" @click="toggleStatus(row)">
              {{ row.status === 0 ? '停用' : '启用' }}</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination style="margin-top: 12px; justify-content: flex-end"
                     layout="total, prev, pager, next" :total="total" :page-size="size"
                     :current-page="page" @current-change="load" />
    </el-card>

    <!-- 新建 / 编辑商品 -->
    <el-dialog v-model="editVisible" :title="editId ? '编辑商品' : '新建商品'" width="520px">
      <el-form label-width="90px">
        <el-form-item label="名称" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="别名"><el-input v-model="form.alias" /></el-form-item>
        <el-form-item label="拼音码"><el-input v-model="form.pinyin" placeholder="用于模糊检索" /></el-form-item>
        <el-form-item label="条码"><el-input v-model="form.barcode" /></el-form-item>
        <el-form-item label="品类" required>
          <el-tree-select v-model="form.categoryId" :data="categoryTreeData" check-strictly
                          :props="{ label: 'label', value: 'value' }" node-key="value"
                          style="width: 100%" placeholder="选择品类(仅启用)" />
        </el-form-item>
        <el-form-item label="品牌">
          <el-select v-model="form.brandId" style="width: 100%" clearable placeholder="无品牌可空">
            <el-option v-for="b in enabledBrands" :key="b.id" :value="b.id" :label="b.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="单位" required><el-input v-model="form.unit" placeholder="如 块 / 樘 / m²" /></el-form-item>
        <el-form-item v-if="!editId" label="类型" required>
          <el-radio-group v-model="form.type">
            <el-radio :value="0">商品</el-radio>
            <el-radio :value="1">套餐(建档后不可改)</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="进价(元)"><el-input-number v-model="form.purchaseYuan" :min="0" :precision="3" style="width: 100%" /></el-form-item>
        <el-form-item label="售价(元)"><el-input-number v-model="form.sellingYuan" :min="0" :precision="3" style="width: 100%" /></el-form-item>
        <el-form-item label="限价(元)"><el-input-number v-model="form.confineYuan" :min="0" :precision="3" style="width: 100%" placeholder="空=不限价" /></el-form-item>
        <el-form-item label="销售倍率">
          <el-input-number v-model="form.unitRate" :min="1" :precision="0" style="width: 100%" />
          <div class="form-hint">千分位,1000 = 1.0(销售单位换基础单位的倍率)</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="doSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 商品详情:基础信息 + SKU 属性 + 价格体系 + 套餐成员 -->
    <el-drawer v-model="detailVisible" :title="detail ? `商品档案 · ${detail.name}` : '商品档案'" size="680px">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="编号">{{ detail.no }}</el-descriptions-item>
          <el-descriptions-item label="类型">{{ detail.type === 1 ? '套餐' : '商品' }}</el-descriptions-item>
          <el-descriptions-item label="品类">{{ detail.category_name }}</el-descriptions-item>
          <el-descriptions-item label="品牌">{{ detail.brand_name || '-' }}</el-descriptions-item>
          <el-descriptions-item label="单位">{{ detail.unit }}</el-descriptions-item>
          <el-descriptions-item label="条码">{{ detail.barcode || '-' }}</el-descriptions-item>
          <el-descriptions-item label="进价(元)">{{ yuan(detail.purchase) }}</el-descriptions-item>
          <el-descriptions-item label="售价(元)">{{ yuan(detail.selling) }}</el-descriptions-item>
          <el-descriptions-item label="限价(元)">{{ detail.confine == null ? '不限' : yuan(detail.confine) }}</el-descriptions-item>
          <el-descriptions-item label="销售倍率">{{ (detail.unit_rate / 1000).toFixed(3) }}</el-descriptions-item>
        </el-descriptions>

        <h4>SKU 属性(颜色 / 规格)</h4>
        <el-table :data="skuAttrs" size="small" border>
          <el-table-column label="类型" width="80">
            <template #default="{ row }">{{ row.attr_type === 1 ? '规格' : '颜色' }}</template>
          </el-table-column>
          <el-table-column prop="value" label="属性值" />
          <el-table-column label="状态" width="70">
            <template #default="{ row }">
              <el-tag :type="row.status === 0 ? 'success' : 'info'" size="small">
                {{ row.status === 0 ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80">
            <template #default="{ row }">
              <el-button link size="small" :type="row.status === 0 ? 'danger' : 'success'"
                         @click="toggleSkuAttr(row)">{{ row.status === 0 ? '停用' : '启用' }}</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div style="display: flex; gap: 8px; margin-top: 8px">
          <el-select v-model="newSku.type" style="width: 100px">
            <el-option label="颜色" :value="0" />
            <el-option label="规格" :value="1" />
          </el-select>
          <el-input v-model="newSku.value" placeholder="属性值,如 胡桃木色 / 600×1200" style="width: 240px" />
          <el-button @click="addSkuAttr">添加属性</el-button>
        </div>

        <h4>价格体系</h4>
        <el-table :data="prices" size="small" border>
          <el-table-column prop="name" label="体系名" width="110" />
          <el-table-column label="进价(元)" width="90" align="right">
            <template #default="{ row }">{{ yuan(row.purchase) }}</template>
          </el-table-column>
          <el-table-column label="售价(元)" width="90" align="right">
            <template #default="{ row }">{{ yuan(row.selling) }}</template>
          </el-table-column>
          <el-table-column label="有效期" width="200">
            <template #default="{ row }">{{ row.begin_time || '即日' }} ~ {{ row.end_time || '长期' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="70">
            <template #default="{ row }">
              <el-tag :type="row.status === 0 ? 'success' : 'info'" size="small">
                {{ row.status === 0 ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openPriceEdit(row)">编辑</el-button>
              <el-button link size="small" :type="row.status === 0 ? 'danger' : 'success'"
                         @click="togglePrice(row)">{{ row.status === 0 ? '停用' : '启用' }}</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div style="margin-top: 8px">
          <el-button size="small" @click="openPriceEdit(null)">添加价格体系</el-button>
        </div>

        <template v-if="detail.type === 1">
          <h4>套餐成员</h4>
          <el-table :data="members" size="small" border>
            <el-table-column prop="member_no" label="成员编号" width="110" />
            <el-table-column prop="member_name" label="成员名称" />
            <el-table-column prop="member_unit" label="单位" width="70" />
            <el-table-column prop="num" label="数量" width="70" />
            <el-table-column label="状态" width="70">
              <template #default="{ row }">
                <el-tag :type="row.status === 0 ? 'success' : 'info'" size="small">
                  {{ row.status === 0 ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="80">
              <template #default="{ row }">
                <el-button link size="small" :type="row.status === 0 ? 'danger' : 'success'"
                           @click="toggleMember(row)">{{ row.status === 0 ? '停用' : '启用' }}</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div style="display: flex; gap: 8px; margin-top: 8px">
            <el-select v-model="newMember.memberGoodsId" filterable placeholder="选择成员商品"
                       style="width: 280px">
              <el-option v-for="g in memberCandidates" :key="g.id" :value="g.id"
                         :label="`${g.no} ${g.name}`" />
            </el-select>
            <el-input-number v-model="newMember.num" :min="1" :precision="0" style="width: 110px" />
            <el-button @click="addMember">添加成员</el-button>
          </div>
        </template>
      </template>
    </el-drawer>

    <!-- 价格体系 新建/编辑 -->
    <el-dialog v-model="priceVisible" :title="priceForm.id ? '编辑价格体系' : '添加价格体系'" width="460px">
      <el-form label-width="90px">
        <el-form-item label="体系名" required><el-input v-model="priceForm.name" placeholder="如 一级价 / 渠道价" /></el-form-item>
        <el-form-item label="进价(元)"><el-input-number v-model="priceForm.purchaseYuan" :min="0" :precision="3" style="width: 100%" /></el-form-item>
        <el-form-item label="售价(元)"><el-input-number v-model="priceForm.sellingYuan" :min="0" :precision="3" style="width: 100%" /></el-form-item>
        <el-form-item label="限价(元)"><el-input-number v-model="priceForm.confineYuan" :min="0" :precision="3" style="width: 100%" /></el-form-item>
        <el-form-item label="生效日"><el-date-picker v-model="priceForm.beginTime" type="date" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item>
        <el-form-item label="失效日"><el-date-picker v-model="priceForm.endTime" type="date" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="priceVisible = false">取消</el-button>
        <el-button type="primary" @click="doSavePrice">保存</el-button>
      </template>
    </el-dialog>

    <!-- 品类 / 品牌字典 -->
    <el-dialog v-model="dictVisible" title="品类 / 品牌字典" width="760px">
      <div style="display: flex; gap: 16px">
        <div style="flex: 1">
          <div style="display: flex; gap: 8px; margin-bottom: 8px">
            <el-button size="small" @click="promptAddCategory(0)">加根品类</el-button>
          </div>
          <el-tree :data="categoryTree" node-key="id" default-expand-all :expand-on-click-node="false">
            <template #default="{ data }">
              <span class="cat-node">
                <span>{{ data.name }}
                  <el-tag v-if="data.org_id === 0" size="small" type="info">平台</el-tag>
                  <el-tag v-if="data.status === 1" size="small" type="info">停用</el-tag>
                </span>
                <span class="cat-ops">
                  <el-button link type="primary" size="small" @click="promptAddCategory(data.id)">加子</el-button>
                  <el-button link type="primary" size="small" @click="promptRenameCategory(data)">改名</el-button>
                  <el-button link size="small" :type="data.status === 0 ? 'danger' : 'success'"
                             @click="toggleCategory(data)">{{ data.status === 0 ? '停用' : '启用' }}</el-button>
                </span>
              </span>
            </template>
          </el-tree>
        </div>
        <div style="flex: 1">
          <div style="display: flex; gap: 8px; margin-bottom: 8px">
            <el-input v-model="newBrand.name" placeholder="品牌名" size="small" style="width: 140px" />
            <el-input v-model="newBrand.pinyin" placeholder="拼音码" size="small" style="width: 120px" />
            <el-button size="small" @click="addBrand">加品牌</el-button>
          </div>
          <el-table :data="brands" size="small" border max-height="420">
            <el-table-column prop="no" label="编号" width="90" />
            <el-table-column prop="name" label="品牌" />
            <el-table-column label="状态" width="70">
              <template #default="{ row }">
                <el-tag :type="row.status === 0 ? 'success' : 'info'" size="small">
                  {{ row.status === 0 ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120">
              <template #default="{ row }">
                <el-button link type="primary" size="small"
                           @click="promptRenameBrand(row)">改名</el-button>
                <el-button link size="small" :type="row.status === 0 ? 'danger' : 'success'"
                           @click="toggleBrand(row)">{{ row.status === 0 ? '停用' : '启用' }}</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  pageGoods, fetchGoods, createGoods, updateGoods, updateGoodsStatus,
  listCategories, createCategory, renameCategory, updateCategoryStatus,
  listBrands, createBrand, renameBrand, updateBrandStatus,
  listGoodsSkuAttrs, addGoodsSkuAttr, updateGoodsSkuAttrStatus,
  listGoodsPrices, addGoodsPrice, updateGoodsPrice, updateGoodsPriceStatus,
  listGoodsPackages, addGoodsPackageMember, updateGoodsPackageMemberStatus
} from '../../api'

// 金额口径:后端厘(×1000 定点整数),页面元,出入均换算
const yuan = (li) => (li == null ? '-' : (li / 1000).toLocaleString(undefined, { maximumFractionDigits: 3 }))
const toLi = (value) => (value == null || value === undefined ? null : Math.round(value * 1000))

const keyword = ref('')
const query = reactive({ keyword: '', categoryId: null, brandId: null, status: null, type: null })
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = 20

const categories = ref([])
const brands = ref([])

const editVisible = ref(false)
const editId = ref(null)
const form = reactive({
  name: '', alias: '', pinyin: '', barcode: '', categoryId: null, brandId: null,
  unit: '', type: 0, purchaseYuan: null, sellingYuan: null, confineYuan: null, unitRate: 1000
})

const detailVisible = ref(false)
const detail = ref(null)
const skuAttrs = ref([])
const prices = ref([])
const members = ref([])
const memberCandidates = ref([])
const newSku = reactive({ type: 0, value: '' })
const newMember = reactive({ memberGoodsId: null, num: 1 })

const priceVisible = ref(false)
const priceForm = reactive({ id: null, name: '', purchaseYuan: null, sellingYuan: null, confineYuan: null, beginTime: null, endTime: null })

const dictVisible = ref(false)
const newBrand = reactive({ name: '', pinyin: '' })

onMounted(async () => {
  await loadDicts()
  load(1)
})

async function loadDicts() {
  const [c, b] = await Promise.all([listCategories(), listBrands({ includeDisabled: true })])
  categories.value = c.data || []
  brands.value = b.data || []
}

const categoryOptions = computed(() => categories.value.map((c) => ({
  id: c.id, name: c.name, indentLabel: '　'.repeat(Math.max(c.level - 1, 0)) + c.name
})))

const enabledBrands = computed(() => brands.value.filter((b) => b.status === 0))

// el-tree-select 数据:仅启用品类可选(停用品类只出现在过滤条件与字典管理)
const categoryTree = computed(() => buildTree(categories.value, true))
const categoryTreeData = computed(() => buildTree(categories.value.filter((c) => c.status === 0), false))

function buildTree(flat, withMeta) {
  const nodes = new Map()
  const list = []
  for (const c of flat) {
    const node = { id: c.id, name: c.name, org_id: c.org_id, status: c.status, level: c.level, children: [] }
    if (!withMeta) {
      node.label = c.name
      node.value = c.id
    }
    nodes.set(c.id, node)
    list.push(node)
  }
  const roots = []
  for (const node of list) {
    const raw = flat.find((c) => c.id === node.id)
    const parent = nodes.get(raw.parent_id)
    if (raw.parent_id && parent) parent.children.push(node)
    else roots.push(node)
  }
  const strip = (nodes2) => nodes2.map((n) => (n.children.length ? { ...n, children: strip(n.children) } : { ...n, children: undefined }))
  return withMeta ? strip(roots) : roots
}

async function load(target) {
  if (typeof target === 'number') page.value = target
  const res = await pageGoods({ ...query, page: page.value, size })
  rows.value = res.data.rows || []
  total.value = res.data.total || 0
}

function resetQuery() {
  query.keyword = ''
  query.categoryId = null
  query.brandId = null
  query.status = null
  query.type = null
  load(1)
}

function resetForm() {
  form.name = ''
  form.alias = ''
  form.pinyin = ''
  form.barcode = ''
  form.categoryId = null
  form.brandId = null
  form.unit = ''
  form.type = 0
  form.purchaseYuan = null
  form.sellingYuan = null
  form.confineYuan = null
  form.unitRate = 1000
}

function openCreate() {
  resetForm()
  editId.value = null
  editVisible.value = true
}

async function openEdit(row) {
  const res = await fetchGoods(row.id)
  const g = res.data
  editId.value = g.id
  form.name = g.name
  form.alias = g.alias || ''
  form.pinyin = g.pinyin || ''
  form.barcode = g.barcode || ''
  form.categoryId = g.category_id
  form.brandId = g.brand_id
  form.unit = g.unit
  form.purchaseYuan = g.purchase / 1000
  form.sellingYuan = g.selling / 1000
  form.confineYuan = g.confine == null ? null : g.confine / 1000
  form.unitRate = g.unit_rate
  editVisible.value = true
}

async function doSave() {
  if (!form.name || !form.unit || !form.categoryId) {
    ElMessage.warning('名称 / 单位 / 品类为必填')
    return
  }
  const payload = {
    name: form.name, alias: form.alias || null, pinyin: form.pinyin || null, barcode: form.barcode || null,
    categoryId: form.categoryId, brandId: form.brandId, unit: form.unit,
    purchase: toLi(form.purchaseYuan), selling: toLi(form.sellingYuan), confine: toLi(form.confineYuan),
    unitRate: form.unitRate
  }
  if (editId.value) {
    await updateGoods(editId.value, payload)
    ElMessage.success('已保存(价格变更已发事件)')
  } else {
    await createGoods({ ...payload, type: form.type })
    ElMessage.success('已建档')
  }
  editVisible.value = false
  load()
}

async function toggleStatus(row) {
  const disable = row.status === 0
  if (disable) {
    await ElMessageBox.confirm(`确认停用商品 ${row.name}?`, '提示', { type: 'warning' })
  }
  await updateGoodsStatus(row.id, { status: disable ? 1 : 0 })
  await load()
}

async function openDetail(row) {
  const res = await fetchGoods(row.id)
  detail.value = res.data
  detailVisible.value = true
  newSku.type = 0
  newSku.value = ''
  newMember.memberGoodsId = null
  newMember.num = 1
  await reloadSubs(row.id)
  if (detail.value.type === 1) {
    const all = await pageGoods({ size: 100, type: 0, status: 0 })
    memberCandidates.value = (all.data.rows || []).filter((g) => g.id !== detail.value.id)
  }
}

async function reloadSubs(id) {
  const goodsId = id || detail.value.id
  const includeDisabled = true
  const [s, p, m] = await Promise.all([
    listGoodsSkuAttrs(goodsId, { includeDisabled }),
    listGoodsPrices(goodsId),
    detail.value && detail.value.type === 1 ? listGoodsPackages(goodsId, { includeDisabled }) : Promise.resolve({ data: [] })
  ])
  skuAttrs.value = s.data || []
  prices.value = p.data || []
  members.value = m.data || []
}

async function addSkuAttr() {
  if (!newSku.value) return
  await addGoodsSkuAttr(detail.value.id, { attrType: newSku.type, value: newSku.value })
  newSku.value = ''
  await reloadSubs()
  ElMessage.success('属性已添加')
}

async function toggleSkuAttr(row) {
  await updateGoodsSkuAttrStatus(detail.value.id, row.id, { status: row.status === 0 ? 1 : 0 })
  await reloadSubs()
}

function openPriceEdit(row) {
  priceForm.id = row ? row.id : null
  priceForm.name = row ? row.name : ''
  priceForm.purchaseYuan = row && row.purchase != null ? row.purchase / 1000 : null
  priceForm.sellingYuan = row && row.selling != null ? row.selling / 1000 : null
  priceForm.confineYuan = row && row.confine != null ? row.confine / 1000 : null
  priceForm.beginTime = row ? row.begin_time : null
  priceForm.endTime = row ? row.end_time : null
  priceVisible.value = true
}

async function doSavePrice() {
  if (!priceForm.name) {
    ElMessage.warning('体系名为必填')
    return
  }
  const payload = {
    name: priceForm.name,
    purchase: toLi(priceForm.purchaseYuan), selling: toLi(priceForm.sellingYuan), confine: toLi(priceForm.confineYuan),
    beginTime: priceForm.beginTime, endTime: priceForm.endTime
  }
  if (priceForm.id) {
    await updateGoodsPrice(detail.value.id, priceForm.id, payload)
  } else {
    await addGoodsPrice(detail.value.id, payload)
  }
  priceVisible.value = false
  await reloadSubs()
  ElMessage.success('价格体系已保存')
}

async function togglePrice(row) {
  await updateGoodsPriceStatus(detail.value.id, row.id, { status: row.status === 0 ? 1 : 0 })
  await reloadSubs()
}

async function addMember() {
  if (!newMember.memberGoodsId) return
  await addGoodsPackageMember(detail.value.id, { memberGoodsId: newMember.memberGoodsId, num: newMember.num })
  newMember.memberGoodsId = null
  await reloadSubs()
  ElMessage.success('成员已添加')
}

async function toggleMember(row) {
  await updateGoodsPackageMemberStatus(detail.value.id, row.id, { status: row.status === 0 ? 1 : 0 })
  await reloadSubs()
}

// ---- 品类 / 品牌字典 ----

async function promptAddCategory(parentId) {
  const { value } = await ElMessageBox.prompt('品类名称', parentId ? '添加子品类' : '添加根品类', {
    inputPlaceholder: '如 瓷砖 / 木门'
  })
  if (!value) return
  await createCategory({ name: value, parentId: parentId || null })
  await loadDicts()
  ElMessage.success('品类已添加')
}

async function promptRenameCategory(data) {
  const { value } = await ElMessageBox.prompt('品类名称', '改名', { inputValue: data.name })
  if (!value) return
  await renameCategory(data.id, { name: value })
  await loadDicts()
}

async function toggleCategory(data) {
  try {
    await updateCategoryStatus(data.id, { status: data.status === 0 ? 1 : 0 })
  } finally {
    await loadDicts()
  }
}

async function addBrand() {
  if (!newBrand.name) return
  await createBrand({ name: newBrand.name, pinyin: newBrand.pinyin || null })
  newBrand.name = ''
  newBrand.pinyin = ''
  await loadDicts()
  ElMessage.success('品牌已添加')
}

async function promptRenameBrand(row) {
  const { value } = await ElMessageBox.prompt('品牌名称', '改名', { inputValue: row.name })
  if (!value) return
  await renameBrand(row.id, { name: value })
  await loadDicts()
}

async function toggleBrand(row) {
  await updateBrandStatus(row.id, { status: row.status === 0 ? 1 : 0 })
  await loadDicts()
}
</script>

<style scoped>
.cat-node {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding-right: 8px;
}

.cat-ops {
  display: none;
}

.cat-node:hover .cat-ops {
  display: inline;
}

.form-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}
</style>
