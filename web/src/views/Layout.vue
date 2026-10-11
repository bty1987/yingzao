<template>
  <div class="shell">
    <!-- 方向三:顶部横导航(古籍版式),弃侧栏 -->
    <header class="topbar">
      <div class="brand" @click="$router.push(menus[0] ? menus[0].path : '/approvals')">
        <span class="logo yz-display">营造</span>
        <span class="yz-seal-stamp stamp">法</span>
      </div>
      <nav class="nav">
        <router-link to="/approvals" class="nav-item" :class="{ active: $route.path === '/approvals' }">审批中心</router-link>
        <router-link
          v-for="m in menus"
          :key="m.path"
          :to="m.path"
          class="nav-item"
          :class="{ active: $route.path === m.path }"
        >{{ m.title }}</router-link>
        <router-link v-if="isAdmin" to="/roles" class="nav-item" :class="{ active: $route.path === '/roles' }">角色权限</router-link>
      </nav>
      <div class="right">
        <NotificationBell />
        <span class="user">{{ displayName }}</span>
        <el-button link @click="logout">退出</el-button>
      </div>
    </header>
    <main class="main">
      <p v-if="$route.meta.title" class="page-title yz-display">{{ $route.meta.title }}</p>
      <router-view />
    </main>
  </div>
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
.shell { min-height: 100vh; display: flex; flex-direction: column; background: var(--yz-paper); }

/* 顶栏:纸面+底部细墨线,宋体品牌+朱印 */
.topbar {
  display: flex;
  align-items: center;
  gap: 32px;
  padding: 0 32px;
  height: 64px;
  background: var(--yz-paper);
  border-bottom: 1px solid var(--yz-line);
  position: sticky;
  top: 0;
  z-index: 10;
}
.brand { display: flex; align-items: center; gap: 8px; cursor: pointer; }
.logo { font-size: 24px; font-weight: 600; letter-spacing: 8px; line-height: 1; }
.stamp { width: 24px; height: 24px; font-size: 12px; }

.nav { display: flex; align-items: center; gap: 8px; flex: 1; }
.nav-item {
  padding: 8px 16px;
  font-size: 15px;
  color: var(--yz-ink-70);
  text-decoration: none;
  border-bottom: 2px solid transparent;
  line-height: 1;
}
.nav-item:hover { color: var(--yz-ink); }
.nav-item.active {
  color: var(--yz-seal);
  border-bottom-color: var(--yz-seal);
}

.right { display: flex; align-items: center; gap: 16px; }
.user { color: var(--yz-ink-70); font-size: 14px; }

.main {
  flex: 1;
  padding: 32px;
  max-width: 1280px;
  width: 100%;
  margin: 0 auto;
  box-sizing: border-box;
}
/* 宋体页题:每页统一的书页页题 */
.page-title {
  margin: 0 0 24px;
  font-size: 24px;
  font-weight: 600;
  letter-spacing: 4px;
}
</style>
