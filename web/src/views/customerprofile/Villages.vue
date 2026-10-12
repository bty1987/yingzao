<template>
  <div style="display: flex; gap: 12px; align-items: flex-start">
    <!-- 左:城市 → 城区 树 -->
    <el-card shadow="never" style="width: 250px; flex-shrink: 0">
      <template #header>
        <div style="display: flex; align-items: center">
          <span>城市 / 城区</span>
          <div style="flex: 1" />
          <el-button link type="primary" @click="cityDialog = true">新建城市</el-button>
        </div>
      </template>
      <div v-for="c in cities" :key="c.id" style="margin-bottom: 4px">
        <div class="yz-city-row" :class="{ active: selectedCity === c.id }" @click="selectCity(c.id)">
          {{ c.name }}
          <el-button v-if="selectedCity === c.id" link type="primary" size="small"
            @click.stop="openProperDialog">加城区</el-button>
        </div>
        <div v-if="selectedCity === c.id" style="padding-left: 16px">
          <div class="yz-proper-row" :class="{ active: !selectedProper }" @click="selectProper(null)">全部城区</div>
          <div v-for="p in c.propers" :key="p.id" class="yz-proper-row"
            :class="{ active: selectedProper === p.id }" @click="selectProper(p.id)">
            {{ p.name }}
          </div>
          <div v-if="!c.propers || !c.propers.length" class="yz-proper-row" style="color: var(--el-text-color-secondary)">
            暂无城区
          </div>
        </div>
      </div>
    </el-card>

    <!-- 右:楼盘列表 -->
    <el-card shadow="never" style="flex: 1">
      <div style="display: flex; gap: 8px; margin-bottom: 12px">
        <el-input v-model="keyword" placeholder="楼盘名称" style="width: 220px" clearable @keyup.enter="load(1)" />
        <el-button type="primary" @click="load(1)">查询</el-button>
        <el-button @click="load(1)">刷新</el-button>
        <div style="flex: 1" />
        <el-button type="primary" @click="openVillageDialog()">新建楼盘</el-button>
      </div>

      <el-table :data="rows" border stripe>
        <el-table-column prop="no" label="编号" width="110" />
        <el-table-column prop="name" label="楼盘" min-width="140" show-overflow-tooltip />
        <el-table-column prop="city_name" label="城市" width="90" />
        <el-table-column prop="proper_name" label="城区" width="90" />
        <el-table-column prop="developer" label="开发商" width="110" show-overflow-tooltip />
        <el-table-column prop="delivery_time" label="交房" width="90" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 0 ? 'success' : 'info'" size="small">
              {{ row.status === 0 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openHouses(row)">房屋</el-button>
            <el-button link type="primary" @click="openVillageDialog(row)">编辑</el-button>
            <el-button link :type="row.status === 0 ? 'danger' : 'success'" @click="toggleStatus(row)">
              {{ row.status === 0 ? '停用' : '启用' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination style="margin-top: 12px; justify-content: flex-end"
                     layout="total, prev, pager, next" :total="total" :page-size="size"
                     :current-page="page" @current-change="load" />
    </el-card>

    <!-- 新建城市 -->
    <el-dialog v-model="cityDialog" title="新建城市" width="380px">
      <el-form label-width="70px">
        <el-form-item label="城市名" required><el-input v-model="cityName" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cityDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doCreateCity">保存</el-button>
      </template>
    </el-dialog>

    <!-- 加城区 -->
    <el-dialog v-model="properDialog" title="新建城区" width="380px">
      <el-form label-width="70px">
        <el-form-item label="城区名" required><el-input v-model="properName" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="properDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doCreateProper">保存</el-button>
      </template>
    </el-dialog>

    <!-- 新建/编辑楼盘 -->
    <el-dialog v-model="villageDialog" :title="villageForm.id ? '编辑楼盘' : '新建楼盘'" width="520px">
      <el-form label-width="90px">
        <el-form-item label="楼盘名称" required><el-input v-model="villageForm.name" /></el-form-item>
        <el-form-item label="城市" required>
          <el-select v-model="villageForm.cityId" style="width: 100%" @change="villageForm.properId = null">
            <el-option v-for="c in cities" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="城区" required>
          <el-select v-model="villageForm.properId" style="width: 100%">
            <el-option v-for="p in formPropers" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="开发商"><el-input v-model="villageForm.developer" /></el-form-item>
        <el-form-item label="交房时间"><el-input v-model="villageForm.deliveryTime" placeholder="如 2026-12" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="villageForm.content" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="villageDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doSaveVillage">保存</el-button>
      </template>
    </el-dialog>

    <!-- 房屋管理抽屉 -->
    <el-drawer v-model="houseDrawer" :title="houseVillage ? `房屋 · ${houseVillage.name}` : '房屋'" size="680px">
      <div style="display: flex; gap: 8px; margin-bottom: 12px">
        <el-input v-model="houseKeyword" placeholder="门牌全文检索(须全匹配)" style="width: 240px" clearable
          @keyup.enter="loadHouses(1)" />
        <el-button type="primary" @click="loadHouses(1)">查询</el-button>
        <div style="flex: 1" />
        <el-button type="primary" @click="houseFormVisible = true">新建房屋</el-button>
      </div>
      <el-table :data="houseRows" border size="small">
        <el-table-column prop="built" label="栋" width="70" />
        <el-table-column prop="unit" label="单元" width="70" />
        <el-table-column prop="floor_num" label="楼层" width="70" />
        <el-table-column prop="door_num" label="门牌号" width="90" />
        <el-table-column prop="house_type" label="户型" width="90" />
        <el-table-column prop="build_area" label="建面㎡" width="90" />
        <el-table-column prop="total_area" label="总面积㎡" width="100" />
        <el-table-column prop="created_at" label="建档" width="170" />
      </el-table>
      <el-pagination style="margin-top: 12px; justify-content: flex-end"
        layout="total, prev, pager, next" :total="houseTotal" :page-size="houseSize"
        :current-page="housePage" @current-change="loadHouses" />

      <!-- 新建房屋 -->
      <el-dialog v-model="houseFormVisible" title="新建房屋" width="480px" append-to-body>
        <el-form label-width="90px">
          <el-form-item label="门牌全文" required>
            <el-input v-model="houseForm.number" placeholder="如 3-2-1801(全文明文,服务端加密)" />
          </el-form-item>
          <el-form-item label="栋"><el-input v-model="houseForm.built" /></el-form-item>
          <el-form-item label="单元"><el-input v-model="houseForm.unit" /></el-form-item>
          <el-form-item label="楼层"><el-input-number v-model="houseForm.floorNum" :min="0" style="width: 100%" /></el-form-item>
          <el-form-item label="门牌号"><el-input-number v-model="houseForm.doorNum" :min="0" style="width: 100%" /></el-form-item>
          <el-form-item label="户型"><el-input v-model="houseForm.houseType" placeholder="如 三室两厅" /></el-form-item>
          <el-form-item label="建面㎡"><el-input-number v-model="houseForm.buildArea" :min="0" :precision="2" style="width: 100%" /></el-form-item>
          <el-form-item label="总面㎡"><el-input-number v-model="houseForm.totalArea" :min="0" :precision="2" style="width: 100%" /></el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="houseFormVisible = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="doCreateHouse">保存</el-button>
        </template>
      </el-dialog>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import {
  listCities, createCity, createCityProper, pageVillages, createVillage, updateVillage,
  updateVillageStatus, pageHouses, createHouse
} from '../../api'

const saving = ref(false)

// 城市/城区
const cities = ref([])
const selectedCity = ref(null)
const selectedProper = ref(null)

// 楼盘列表
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = 20
const keyword = ref('')

// 弹窗
const cityDialog = ref(false)
const cityName = ref('')
const properDialog = ref(false)
const properName = ref('')
const villageDialog = ref(false)
const villageForm = reactive({ id: null, name: '', cityId: null, properId: null, developer: '',
  deliveryTime: '', content: '' })

// 房屋
const houseDrawer = ref(false)
const houseVillage = ref(null)
const houseRows = ref([])
const houseTotal = ref(0)
const housePage = ref(1)
const houseSize = 20
const houseKeyword = ref('')
const houseFormVisible = ref(false)
const houseForm = reactive({ number: '', built: '', unit: '', floorNum: 0, doorNum: 0,
  houseType: '', buildArea: null, totalArea: null })

const formPropers = computed(() => {
  const hit = cities.value.find((c) => c.id === villageForm.cityId)
  return (hit && hit.propers) || []
})

async function loadCities() {
  const res = await listCities()
  cities.value = res.data || []
}

function selectCity(id) {
  selectedCity.value = id
  selectedProper.value = null
  load(1)
}

function selectProper(id) {
  selectedProper.value = id
  load(1)
}

async function load(p) {
  if (p) page.value = p
  const params = { page: page.value, size, keyword: keyword.value || undefined,
    properId: selectedProper.value ?? undefined }
  const res = await pageVillages(params)
  rows.value = (res.data && res.data.rows) || []
  total.value = (res.data && res.data.total) || 0
}

async function doCreateCity() {
  if (!cityName.value.trim()) return ElMessage.warning('请填城市名')
  saving.value = true
  try {
    await createCity({ name: cityName.value.trim() })
    ElMessage.success('已创建')
    cityDialog.value = false
    cityName.value = ''
    loadCities()
  } catch (e) {
    ElMessage.error(e.message || '创建失败')
  } finally {
    saving.value = false
  }
}

function openProperDialog() {
  properName.value = ''
  properDialog.value = true
}

async function doCreateProper() {
  if (!properName.value.trim()) return ElMessage.warning('请填城区名')
  saving.value = true
  try {
    await createCityProper(selectedCity.value, { name: properName.value.trim() })
    ElMessage.success('已创建')
    properDialog.value = false
    loadCities()
  } catch (e) {
    ElMessage.error(e.message || '创建失败')
  } finally {
    saving.value = false
  }
}

function openVillageDialog(row) {
  if (row) {
    Object.assign(villageForm, { id: row.id, name: row.name, cityId: row.city_id,
      properId: row.city_proper_id, developer: row.developer || '', deliveryTime: row.delivery_time || '',
      content: row.content || '' })
  } else {
    Object.assign(villageForm, { id: null, name: '', cityId: selectedCity.value, properId: selectedProper.value,
      developer: '', deliveryTime: '', content: '' })
  }
  villageDialog.value = true
}

async function doSaveVillage() {
  if (!villageForm.name.trim() || !villageForm.cityId || !villageForm.properId) {
    return ElMessage.warning('请补全名称/城市/城区')
  }
  saving.value = true
  try {
    const payload = { name: villageForm.name.trim(), cityId: villageForm.cityId,
      properId: villageForm.properId, developer: villageForm.developer || null,
      deliveryTime: villageForm.deliveryTime || null, content: villageForm.content || null }
    if (villageForm.id) await updateVillage(villageForm.id, payload)
    else await createVillage(payload)
    ElMessage.success('已保存')
    villageDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function toggleStatus(row) {
  try {
    await updateVillageStatus(row.id, { status: row.status === 0 ? 1 : 0 })
    ElMessage.success(row.status === 0 ? '已停用' : '已启用')
    load()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function loadHouses(p) {
  if (p) housePage.value = p
  const res = await pageHouses({ villageId: houseVillage.value.id, page: housePage.value,
    size: houseSize, keyword: houseKeyword.value || undefined })
  houseRows.value = (res.data && res.data.rows) || []
  houseTotal.value = (res.data && res.data.total) || 0
}

function openHouses(row) {
  houseVillage.value = row
  houseKeyword.value = ''
  housePage.value = 1
  houseDrawer.value = true
  loadHouses()
}

async function doCreateHouse() {
  if (!houseForm.number.trim()) return ElMessage.warning('请填门牌全文')
  saving.value = true
  try {
    await createHouse({ villageId: houseVillage.value.id, number: houseForm.number.trim(),
      fields: { built: houseForm.built || null, unit: houseForm.unit || null,
        floorNum: houseForm.floorNum ?? null, doorNum: houseForm.doorNum ?? null,
        buildArea: houseForm.buildArea ?? null, totalArea: houseForm.totalArea ?? null,
        houseType: houseForm.houseType || null } })
    ElMessage.success('已创建')
    houseFormVisible.value = false
    Object.assign(houseForm, { number: '', built: '', unit: '', floorNum: 0, doorNum: 0,
      houseType: '', buildArea: null, totalArea: null })
    loadHouses(1)
  } catch (e) {
    ElMessage.error(e.message || '创建失败')
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  loadCities()
  load(1)
})
</script>

<style scoped>
.yz-city-row {
  padding: 6px 8px;
  cursor: pointer;
  border-radius: 2px;
  display: flex;
  align-items: center;
}
.yz-city-row.active {
  background: var(--el-fill-color-light);
  font-weight: 600;
}
.yz-proper-row {
  padding: 4px 8px;
  cursor: pointer;
  border-radius: 2px;
}
.yz-proper-row:hover {
  background: var(--el-fill-color-lighter);
}
.yz-proper-row.active {
  background: var(--el-fill-color);
  font-weight: 600;
}
</style>
