<template>
  <el-container style="height: 100vh">
    <el-aside width="220px" class="aside">
      <div class="logo">营造</div>
      <el-menu :default-active="$route.path" router>
        <el-menu-item index="/approvals">审批中心</el-menu-item>
        <el-menu-item v-for="m in menus" :key="m.path" :index="m.path">{{ m.title }}</el-menu-item>
        <el-menu-item v-if="isAdmin" index="/roles">角色权限</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span>{{ $route.meta.title || '' }}</span>
        <span class="spacer" />
        <NotificationBell />
        <span class="user">{{ displayName }}</span>
        <el-button link @click="logout">退出</el-button>
      </el-header>
      <el-main><router-view /></el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { loadAddons, addons } from '../store/addons'
import { registerAddonRoutes } from '../router'
import NotificationBell from '../components/NotificationBell.vue'

const router = useRouter()
const displayName = ref(localStorage.getItem('yz_display_name') || '')

// 管理类入口可见性(RBAC):以登录回显的角色编码为准,服务端守卫才是真闸门
const isAdmin = JSON.parse(localStorage.getItem('yz_roles') || '[]').includes('admin')

const menus = computed(() =>
  addons.list.flatMap((a) => (a.menus || []).map((m) => ({ ...m, addon: a.id })))
)

onMounted(async () => {
  const list = await loadAddons()
  registerAddonRoutes(list)
  if (router.currentRoute.value.path === '/') {
    const first = menus.value[0]
    if (first) router.push(first.path)
  }
})

function logout() {
  localStorage.removeItem('yz_token')
  localStorage.removeItem('yz_roles')
  router.push('/login')
}
</script>

<style scoped>
.aside { background: #fff; border-right: 1px solid #e4e7ed; }
.logo { height: 60px; display: flex; align-items: center; justify-content: center; font-size: 22px; letter-spacing: 6px; font-weight: 600; }
.header { display: flex; align-items: center; border-bottom: 1px solid #e4e7ed; background: #fff; }
.spacer { flex: 1; }
.user { margin-right: 12px; color: #606266; }
</style>
