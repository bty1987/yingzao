<template>
  <div>
    <el-tabs v-model="tab">
      <el-tab-pane label="我的名片" name="card" />
      <el-tab-pane label="留资列表" name="notices" />
    </el-tabs>

    <!-- 我的名片 -->
    <div v-if="tab === 'card'">
      <div v-if="!card" class="empty-tip">还没有名片,点击下方创建(一人一卡)</div>
      <el-descriptions v-if="card" :column="2" border size="small" style="margin-bottom: 12px">
        <el-descriptions-item label="姓名">{{ card.user_name }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="card.status === 0 ? 'success' : 'info'" effect="plain">
            {{ card.status === 0 ? '启用' : '停用' }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="微信">{{ card.wechat || '—' }}</el-descriptions-item>
        <el-descriptions-item label="行业/职业">{{ card.industry || '—' }} / {{ card.profession || '—' }}</el-descriptions-item>
        <el-descriptions-item label="地址" :span="2">{{ card.address || '—' }}</el-descriptions-item>
        <el-descriptions-item label="简介" :span="2">{{ card.info || '—' }}</el-descriptions-item>
        <el-descriptions-item label="分享链接" :span="2">
          <span class="mono tip">{{ cardUrl }}</span>
          <el-button link size="small" type="primary" style="margin-left: 8px"
            @click="onResetToken">重置(旧链接失效)</el-button>
        </el-descriptions-item>
      </el-descriptions>
      <div v-if="card" style="margin-bottom: 16px">
        <div class="section">相册({{ (card.photos || []).length }})</div>
        <div class="photo-row">
          <div v-for="p in card.photos" :key="p.id" class="photo-item">
            <el-image :src="p.url" fit="cover" style="width: 88px; height: 88px; border-radius: 6px"
              :preview-src-list="[p.url]" preview-teleported />
            <el-button link type="danger" size="small" @click="onDeletePhoto(p)">删</el-button>
          </div>
          <el-button v-if="photoAdding || (card.photos || []).length === 0" size="small" @click="photoAdding = true">加图片(URL)</el-button>
        </div>
        <div v-if="photoAdding" class="photo-add">
          <el-input v-model="photoUrl" placeholder="图片 URL" style="width: 320px" />
          <el-button type="primary" size="small" @click="onAddPhoto">保存</el-button>
          <el-button size="small" @click="photoAdding = false">取消</el-button>
        </div>
      </div>
      <el-button v-if="!card" type="primary" @click="openEdit(null)">创建名片</el-button>
      <el-button v-else @click="openEdit(card)">编辑资料</el-button>
      <el-button v-if="card" type="warning" @click="onToggleStatus">{{ card.status === 0 ? '停用' : '启用' }}</el-button>
      <el-button v-if="card" type="danger" @click="onDeleteCard">删除名片</el-button>
    </div>

    <!-- 留资列表 -->
    <div v-if="tab === 'notices'">
      <div class="toolbar">
        <el-select v-model="noticeFilter.status" placeholder="状态" style="width: 110px" clearable>
          <el-option v-for="d in dicts.noticeStatus" :key="d.key" :label="d.label" :value="Number(d.key)" />
        </el-select>
        <el-select v-model="noticeFilter.cardId" placeholder="仅看我的名片留资" style="width: 160px" clearable>
          <el-option v-if="card" :label="card.user_name + ' 的名片'" :value="card.id" />
        </el-select>
        <el-button type="primary" @click="loadNotices">查询</el-button>
      </div>
      <el-table :data="noticeRows" border stripe v-loading="noticeLoading">
        <el-table-column prop="card_user_name" label="名片主人" width="110" />
        <el-table-column prop="name" label="访客" width="110" />
        <el-table-column prop="phone" label="电话" width="130" />
        <el-table-column prop="comment" label="留言" min-width="200" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 0 ? 'warning' : 'info'" effect="plain">
              {{ label('noticeStatus', row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="created_at" label="留资时间" width="160" />
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button v-if="row.status === 0" type="primary" link @click="onRead(row)">标记已读</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination layout="prev, pager, next, total" :total="noticeTotal" :page-size="noticeFilter.size"
        :current-page="noticeFilter.page" @current-change="(p) => { noticeFilter.page = p; loadNotices() }"
        style="margin-top: 12px" />
    </div>

    <!-- 名片编辑 -->
    <el-dialog v-model="editDialog" :title="editId ? '编辑名片' : '创建名片'" width="560px">
      <el-form label-width="80px">
        <el-form-item label="姓名"><el-input v-model="form.userName" maxlength="64" /></el-form-item>
        <el-form-item label="手机号"><el-input v-model="form.phone" :placeholder="editId ? '留空=不变更' : '11 位手机号'" maxlength="11" /></el-form-item>
        <el-form-item label="微信"><el-input v-model="form.wechat" /></el-form-item>
        <el-form-item label="行业"><el-input v-model="form.industry" /></el-form-item>
        <el-form-item label="职业"><el-input v-model="form.profession" /></el-form-item>
        <el-form-item label="地址"><el-input v-model="form.address" /></el-form-item>
        <el-form-item label="头像 URL"><el-input v-model="form.userPhoto" /></el-form-item>
        <el-form-item label="简介"><el-input v-model="form.info" type="textarea" :rows="3" maxlength="1000" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { addons } from '../../store/addons'
import {
  fetchMyCard, createCard, updateCard, resetCardToken, setCardStatus, deleteCard,
  addCardPhoto, deleteCardPhoto, pageCardNotices, readCardNotice
} from '../../api'

const dicts = reactive({ noticeStatus: [] })
const tab = ref('card')
const card = ref(null)
const saving = ref(false)
const editDialog = ref(false)
const editId = ref(null)
const form = ref({ userName: '', phone: '', wechat: '', industry: '', profession: '', address: '', info: '', userPhoto: '' })
const photoAdding = ref(false)
const photoUrl = ref('')

const noticeFilter = reactive({ cardId: null, status: null, page: 1, size: 20 })
const noticeRows = ref([])
const noticeTotal = ref(0)
const noticeLoading = ref(false)

const cardUrl = computed(() => (card.value ? `${location.origin}/web-mp/#/pages/card/detail?token=${card.value.share_token}` : ''))

const label = (dict, v) => {
  const hit = (dicts[dict] || []).find((d) => d.key === String(v))
  return hit ? hit.label : v
}

async function load() {
  try {
    const res = await fetchMyCard()
    card.value = res.data
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  }
}

function openEdit(row) {
  editId.value = row ? row.id : null
  form.value = row
    ? { userName: row.user_name, phone: '', wechat: row.wechat || '', industry: row.industry || '',
        profession: row.profession || '', address: row.address || '', info: row.info || '', userPhoto: row.user_photo || '' }
    : { userName: '', phone: '', wechat: '', industry: '', profession: '', address: '', info: '', userPhoto: '' }
  editDialog.value = true
}

async function onSave() {
  if (!form.value.userName) { ElMessage.warning('姓名必填'); return }
  if (!editId.value && !/^1\d{10}$/.test(form.value.phone || '')) {
    ElMessage.warning('手机号必填(11 位)'); return
  }
  saving.value = true
  try {
    if (editId.value) await updateCard(editId.value, form.value)
    else await createCard(form.value)
    ElMessage.success('已保存')
    editDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function onResetToken() {
  try {
    await ElMessageBox.confirm('重置后旧分享链接立即失效,确定?', '重置链接', { type: 'warning' })
  } catch { return }
  try {
    await resetCardToken(card.value.id)
    ElMessage.success('已重置')
    load()
  } catch (e) {
    ElMessage.error(e.message || '重置失败')
  }
}

async function onToggleStatus() {
  try {
    await setCardStatus(card.value.id, { status: card.value.status === 0 ? 1 : 0 })
    ElMessage.success('已更新')
    load()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function onDeleteCard() {
  try {
    await ElMessageBox.confirm('删除名片及其相册与全部留资,不可恢复,确定?', '删除名片', { type: 'warning' })
  } catch { return }
  try {
    await deleteCard(card.value.id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

async function onAddPhoto() {
  if (!photoUrl.value) { ElMessage.warning('URL 必填'); return }
  try {
    await addCardPhoto(card.value.id, { url: photoUrl.value, sortNo: (card.value.photos || []).length })
    ElMessage.success('已添加')
    photoUrl.value = ''
    photoAdding.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '添加失败')
  }
}

async function onDeletePhoto(p) {
  try {
    await deleteCardPhoto(card.value.id, p.id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

async function loadNotices() {
  noticeLoading.value = true
  try {
    const res = await pageCardNotices({ ...noticeFilter })
    noticeRows.value = (res.data && res.data.rows) || []
    noticeTotal.value = (res.data && res.data.total) || 0
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    noticeLoading.value = false
  }
}

async function onRead(row) {
  try {
    await readCardNotice(row.id)
    ElMessage.success('已读')
    loadNotices()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

onMounted(() => {
  const market = addons.list.find((a) => a.id === 'market')
  const d = (market && market.dicts) || {}
  for (const k of Object.keys(dicts)) dicts[k] = d[k] || []
  load()
  loadNotices()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; }
.section { font-weight: 600; margin: 12px 0 8px; }
.tip { color: var(--yz-ink-50); font-size: 12px; }
.mono { font-family: Consolas, monospace; font-size: 12px; word-break: break-all; }
.empty-tip { color: var(--yz-ink-50); margin-bottom: 12px; }
.photo-row { display: flex; gap: 10px; flex-wrap: wrap; align-items: flex-end; }
.photo-item { display: flex; flex-direction: column; align-items: center; gap: 2px; }
.photo-add { display: flex; gap: 8px; margin-top: 8px; }
</style>
