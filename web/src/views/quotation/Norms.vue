<template>
  <div>
    <el-card shadow="never">
      <el-tabs v-model="tab">
        <el-tab-pane label="定额档案" name="archives" />
        <el-tab-pane label="装修单实例" name="orders" />
        <el-tab-pane label="附加确认线" name="additions" />
      </el-tabs>

      <!-- 定额档案:三 kind 列表 + 维护 -->
      <template v-if="tab === 'archives'">
        <div class="toolbar">
          <el-select v-model="arFilter.kind" placeholder="全部类型" clearable style="width: 120px" @change="loadArchives">
            <el-option v-for="(v, k) in dicts.normKind" :key="k" :label="v" :value="k" />
          </el-select>
          <el-input v-model="arFilter.keyword" placeholder="定额名" clearable style="width: 180px" @input="loadArchives" />
          <el-button @click="loadArchives">查询</el-button>
          <div class="spacer" />
          <el-button @click="openAdditionTpl">附加项档案</el-button>
          <el-button @click="openPacks">定额套餐</el-button>
          <el-button type="primary" @click="openArchive()">新建定额</el-button>
        </div>
        <el-table :data="archives" border>
          <el-table-column label="类型" width="80">
            <template #default="{ row }">{{ dicts.normKind?.[String(row.kind)] || row.kind }}</template>
          </el-table-column>
          <el-table-column prop="catalog_name" label="报价项" min-width="130" />
          <el-table-column prop="name" label="定额名" min-width="130" />
          <el-table-column label="材料口径" min-width="180">
            <template #default="{ row }">
              <span v-if="row.kind === '0'">{{ row.goods_name }} / {{ row.norm_unit }}×{{ (row.norm_num / 1000).toFixed(2) }}</span>
              <span v-else style="color: #c0c4cc">—</span>
            </template>
          </el-table-column>
          <el-table-column label="人工/工时口径" min-width="200">
            <template #default="{ row }">
              <span v-if="row.kind !== '0'">{{ row.node_name }} / {{ row.unit }}
                ×{{ row.kind === '1' ? (row.salary / 1000).toFixed(2) + ' 元' : (row.workdays / 1000).toFixed(1) + ' 工日' }}</span>
              <span v-else style="color: #c0c4cc">—</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag :type="row.status === 0 ? 'success' : 'info'" size="small">
                {{ row.status === 0 ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="210" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openArchive(row)">编辑</el-button>
              <el-button link :type="row.status === 0 ? 'warning' : 'success'" @click="toggleArchive(row)">
                {{ row.status === 0 ? '停用' : '启用' }}
              </el-button>
              <el-button link type="danger" @click="removeArchive(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <!-- 装修单实例:单号查实例 + 重新生成 -->
      <template v-if="tab === 'orders'">
        <div class="toolbar">
          <el-input v-model="orderQuery" placeholder="装修单 id" style="width: 160px" />
          <el-button @click="loadOrder">查询实例</el-button>
          <el-button type="primary" :disabled="!normHead" @click="onGenerate">重新生成(全量)</el-button>
          <el-button type="warning" :disabled="!normHead" @click="openAdd">登记附加人工费</el-button>
          <div class="spacer" />
          <template v-if="normHead">
            <el-tag :type="normHead.sealed === 0 ? '' : 'danger'" style="margin-right: 8px">
              {{ normHead.sealed === 0 ? '生成中' : '已完工固化' }}
            </el-tag>
            <span v-if="summary['1']" style="margin-right: 12px">
              人工工费 <b>{{ (summary['1'].laborFee / 1000).toFixed(2) }}</b> 元
            </span>
            <span v-if="summary['2']">工日 <b>{{ (summary['2'].workdays / 1000).toFixed(1) }}</b></span>
          </template>
        </div>
        <el-alert v-if="!normHead" type="info" :closable="false" title="输入装修单 id 查看定额实例(放线成功自动生成;亦可手动重新生成)" />
        <el-table v-else :data="items" border>
          <el-table-column label="来源" width="80">
            <template #default="{ row }">{{ dicts.normSourceType?.[String(row.source_type)] || row.source_type }}</template>
          </el-table-column>
          <el-table-column label="类型" width="70">
            <template #default="{ row }">{{ dicts.normKind?.[String(row.kind)] || row.kind }}</template>
          </el-table-column>
          <el-table-column prop="name" label="名称" min-width="130" />
          <el-table-column prop="node_name" label="节点" width="110" />
          <el-table-column label="方向" width="60">
            <template #default="{ row }">{{ dicts.normAtttype?.[String(row.atttype)] || row.atttype }}</template>
          </el-table-column>
          <el-table-column label="明细量" width="90" align="right">
            <template #default="{ row }">{{ (row.num / 1000).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column label="定额量" width="90" align="right">
            <template #default="{ row }">{{ (row.norm_num / 1000).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column label="实际用量" width="90" align="right">
            <template #default="{ row }">{{ (row.num * row.norm_num / 1000000).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column label="单价(元)" width="90" align="right">
            <template #default="{ row }">{{ row.kind === '1' ? (row.salary / 1000).toFixed(2) : '—' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.source_type === 3" :type="row.status === 2 ? 'success' : row.status === 3 ? 'danger' : 'warning'" size="small">
                {{ dicts.normStatus?.[String(row.status)] || row.status }}
              </el-tag>
              <el-tag v-else type="info" size="small">初始</el-tag>
            </template>
          </el-table-column>
          <el-table-column v-if="normHead && normHead.sealed === 0" label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <template v-if="row.source_type === 3">
                <el-button v-if="row.status === 0" link type="success" @click="onConfirm(row, true)">确认</el-button>
                <el-button v-if="row.status === 0" link type="danger" @click="onConfirm(row, false)">驳回</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <!-- 附加项档案(公司级模板) -->
      <template v-if="tab === 'additions'">
        <div class="toolbar">
          <el-button type="primary" @click="openTpl()">新建附加项档案</el-button>
        </div>
        <el-table :data="tpls" border>
          <el-table-column prop="name" label="名称" min-width="140" />
          <el-table-column prop="node_name" label="施工节点" width="130" />
          <el-table-column prop="unit" label="单位" width="80" />
          <el-table-column label="单价(元)" width="110" align="right">
            <template #default="{ row }">{{ (row.price / 1000).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 0 ? 'success' : 'info'" size="small">
                {{ row.status === 0 ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openTpl(row)">编辑</el-button>
              <el-button link :type="row.status === 0 ? 'warning' : 'success'" @click="toggleTpl(row)">
                {{ row.status === 0 ? '停用' : '启用' }}
              </el-button>
              <el-button link type="danger" @click="removeTpl(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-card>

    <!-- 定额档案编辑弹窗 -->
    <el-dialog v-model="arDialog" :title="arForm.id ? '编辑定额' : '新建定额'" width="560px">
      <el-form label-width="100px">
        <el-form-item label="类型">
          <el-select v-model="arForm.kind" :disabled="!!arForm.id" style="width: 100%">
            <el-option v-for="(v, k) in dicts.normKind" :key="k" :label="v" :value="Number(k)" />
          </el-select>
        </el-form-item>
        <el-form-item label="报价项">
          <el-input v-model="arForm.catalogItemId" placeholder="报价项库 id" />
        </el-form-item>
        <el-form-item label="定额名">
          <el-input v-model="arForm.name" />
        </el-form-item>
        <template v-if="arForm.kind === 0">
          <el-form-item label="商品 id"><el-input v-model="arForm.goodsId" /></el-form-item>
          <el-form-item label="定额单位/用量">
            <el-input v-model="arForm.normUnit" style="width: 45%" placeholder="单位" />
            <el-input v-model="arForm.normNum" style="width: 45%; margin-left: 8%" placeholder="千分位用量" />
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item label="施工节点"><el-input v-model="arForm.constructionNodeId" placeholder="节点 id" /></el-form-item>
          <el-form-item label="单位"><el-input v-model="arForm.unit" /></el-form-item>
          <el-form-item v-if="arForm.kind === 1" label="单价(厘)"><el-input v-model="arForm.salary" /></el-form-item>
          <el-form-item v-if="arForm.kind === 2" label="工日(千分位)"><el-input v-model="arForm.workdays" /></el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="arDialog = false">取消</el-button>
        <el-button type="primary" @click="saveArchive">保存</el-button>
      </template>
    </el-dialog>

    <!-- 登记附加人工费弹窗 -->
    <el-dialog v-model="addDialog" title="登记附加人工费(登记即待审)" width="520px">
      <el-form label-width="100px">
        <el-form-item label="从模板带出">
          <el-select v-model="addForm.additionId" clearable placeholder="附加项档案(可空=全手工)" style="width: 100%">
            <el-option v-for="t in tpls.filter((x) => x.status === 0)" :key="t.id" :label="t.name" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称"><el-input v-model="addForm.name" placeholder="留空=用模板名" /></el-form-item>
        <el-form-item label="施工节点"><el-input v-model="addForm.constructionNodeId" placeholder="节点 id(模板带出可空)" /></el-form-item>
        <el-form-item label="数量(千分位)"><el-input v-model="addForm.num" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addDialog = false">取消</el-button>
        <el-button type="primary" @click="saveAdd">登记</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchAddons, pageNormArchives, createNormArchive, updateNormArchive, statusNormArchive,
  deleteNormArchive, listNormAdditions, createNormAddition, updateNormAddition,
  statusNormAddition, deleteNormAddition, listNormPacks, createNormPack,
  fetchNormOrder, pageNormItems, generateNorms, addNormAdditionItem,
  confirmNormAddition, rejectNormAddition, deleteNormAdditionItem
} from '../../api'

const dicts = ref({})
const tab = ref('archives')

const archives = ref([])
const arFilter = reactive({ kind: null, keyword: '' })
const arDialog = ref(false)
const arForm = reactive({ id: null, kind: 0, catalogItemId: '', name: '', goodsId: '', normUnit: '', normNum: '', constructionNodeId: '', unit: '', salary: '', workdays: '' })

const tpls = ref([])
const tplDialog = ref(false)
const tplForm = reactive({ id: null, name: '', constructionNodeId: '', nodeName: '', unit: '', price: '' })

const orderQuery = ref('')
const normHead = ref(null)
const summary = ref({})
const items = ref([])
const addDialog = ref(false)
const addForm = reactive({ additionId: null, name: '', constructionNodeId: '', num: '' })

async function loadDicts() {
  const res = await fetchAddons()
  const addon = (res.data || []).find((a) => a.id === 'quotation')
  if (addon?.dicts) dicts.value = addon.dicts
}

async function loadArchives() {
  const res = await pageNormArchives({ kind: arFilter.kind, keyword: arFilter.keyword, page: 1, size: 50 })
  archives.value = res.data.rows || []
}

function openArchive(row) {
  Object.assign(arForm, { id: null, kind: 0, catalogItemId: '', name: '', goodsId: '', normUnit: '', normNum: '', constructionNodeId: '', unit: '', salary: '', workdays: '' })
  if (row) {
    Object.assign(arForm, {
      id: row.id, kind: Number(row.kind), catalogItemId: String(row.catalog_item_id), name: row.name,
      goodsId: row.goods_id ? String(row.goods_id) : '', normUnit: row.norm_unit || '',
      normNum: row.norm_num ? String(row.norm_num) : '', constructionNodeId: row.construction_node_id ? String(row.construction_node_id) : '',
      unit: row.unit || '', salary: row.salary ? String(row.salary) : '', workdays: row.workdays ? String(row.workdays) : ''
    })
  }
  arDialog.value = true
}

async function saveArchive() {
  const body = {
    kind: arForm.kind, catalogItemId: Number(arForm.catalogItemId), name: arForm.name,
    goodsId: arForm.goodsId ? Number(arForm.goodsId) : null,
    normUnit: arForm.normUnit || null, normNum: arForm.normNum ? Number(arForm.normNum) : null,
    constructionNodeId: arForm.constructionNodeId ? Number(arForm.constructionNodeId) : null,
    nodeName: null, unit: arForm.unit || null,
    salary: arForm.salary ? Number(arForm.salary) : null,
    workdays: arForm.workdays ? Number(arForm.workdays) : null
  }
  if (arForm.id) {
    await updateNormArchive(arForm.id, body)
  } else {
    await createNormArchive(body)
  }
  ElMessage.success('已保存')
  arDialog.value = false
  loadArchives()
}

async function toggleArchive(row) {
  await statusNormArchive(row.id, row.status === 0 ? 1 : 0)
  loadArchives()
}

async function removeArchive(row) {
  await ElMessageBox.confirm('删除后不可恢复;被实例引用的档案须改停用', '删除确认', { type: 'warning' })
  await deleteNormArchive(row.id)
  loadArchives()
}

async function openAdditionTpl() {
  tab.value = 'additions'
  loadTpls()
}

async function loadTpls() {
  const res = await listNormAdditions({})
  tpls.value = res.data || []
}

function openTpl(row) {
  Object.assign(tplForm, { id: null, name: '', constructionNodeId: '', nodeName: '', unit: '', price: '' })
  if (row) {
    Object.assign(tplForm, {
      id: row.id, name: row.name, constructionNodeId: String(row.construction_node_id),
      nodeName: row.node_name || '', unit: row.unit || '', price: String(row.price)
    })
  }
  tplDialog.value = true
}

async function saveTpl() {
  const body = {
    name: tplForm.name, constructionNodeId: Number(tplForm.constructionNodeId),
    nodeName: tplForm.nodeName || null, unit: tplForm.unit || null, price: Number(tplForm.price || 0)
  }
  if (tplForm.id) {
    await updateNormAddition(tplForm.id, body)
  } else {
    await createNormAddition(body)
  }
  tplDialog.value = false
  loadTpls()
}

async function toggleTpl(row) {
  await statusNormAddition(row.id, row.status === 0 ? 1 : 0)
  loadTpls()
}

async function removeTpl(row) {
  await ElMessageBox.confirm('删除该附加项档案?', '删除确认', { type: 'warning' })
  await deleteNormAddition(row.id)
  loadTpls()
}

async function loadOrder() {
  if (!orderQuery.value) return
  const res = await fetchNormOrder(orderQuery.value)
  normHead.value = res.data
  summary.value = res.data.summary || {}
  const its = await pageNormItems(orderQuery.value, { page: 1, size: 200 })
  items.value = its.data.rows || []
}

async function onGenerate() {
  await ElMessageBox.confirm('全量重新生成将禁用并重建报价/增减来源行(附加行不动),确认?', '重新生成', { type: 'warning' })
  const res = await generateNorms(orderQuery.value, {})
  ElMessage.success(`生成完成:新增 ${res.data.inserted} / 复用 ${res.data.reused}`)
  loadOrder()
}

function openAdd() {
  Object.assign(addForm, { additionId: null, name: '', constructionNodeId: '', num: '' })
  loadTpls()
  addDialog.value = true
}

async function saveAdd() {
  await addNormAdditionItem(orderQuery.value, {
    additionId: addForm.additionId, name: addForm.name || null,
    constructionNodeId: addForm.constructionNodeId ? Number(addForm.constructionNodeId) : null,
    num: Number(addForm.num)
  })
  ElMessage.success('已登记(待审)')
  addDialog.value = false
  loadOrder()
}

async function onConfirm(row, ok) {
  if (ok) {
    await confirmNormAddition(orderQuery.value, row.id)
  } else {
    await rejectNormAddition(orderQuery.value, row.id)
  }
  loadOrder()
}

onMounted(() => {
  loadDicts()
  loadArchives()
})
</script>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
  align-items: center;
}

.spacer {
  flex: 1;
}
</style>
