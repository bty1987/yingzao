<template>
  <div class="shell">
    <!-- 倒 L 布局:左侧竖向菜单(按业务链固定顺序分组)+顶栏(页题/通知/用户) -->
    <aside class="sidebar">
      <div class="brand" @click="$router.push(firstMenuPath)">
        <span class="yz-seal-stamp stamp">法</span>
        <span class="logo yz-display">营造</span>
      </div>
      <nav class="menu">
        <template v-for="group in menuGroups" :key="group.key">
          <p class="group-name">{{ group.name }}</p>
          <router-link
            v-for="m in group.items"
            :key="m.path"
            :to="m.path"
            class="menu-item"
            :class="{ active: $route.path === m.path }"
          >{{ m.title }}</router-link>
        </template>
      </nav>
    </aside>
    <div class="body">
      <header class="topbar">
        <p class="page-title yz-display">{{ $route.meta.title || '' }}</p>
        <div class="right">
          <NotificationBell />
          <span class="user">{{ displayName }}</span>
          <el-button link @click="logout">退出</el-button>
        </div>
      </header>
      <main class="main">
        <router-view />
      </main>
    </div>
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

// 左侧菜单固定业务链顺序:系统 → 流程 → 数据(档案/厂商)→ 获客 → 客户 → 订单履约(报价/装修/施工)→
// 供应销售(销售/库存)→ 资金(款项/结算);未登记插件自动追加尾部(前向兼容)
const MENU_ORDER = ['roles', 'approvals', 'basedata', 'supplier', 'market', 'member', 'customerprofile', 'customer',
  'quotation', 'decoration', 'construction', 'sales', 'inventory', 'payment', 'balance']

const menuGroups = computed(() => {
  const byKey = {
    roles: { key: 'roles', name: '系统管理', items: [
      { path: '/roles', title: '角色权限' },
      { path: '/events', title: '事件总线' },
      { path: '/plans', title: '计划任务' },
    ] },
    approvals: { key: 'approvals', name: '流程协作', items: [{ path: '/approvals', title: '审批中心' }] },
  }
  for (const a of addons.list) {
    if (a.menus && a.menus.length > 0) {
      byKey[a.id] = { key: a.id, name: a.name, items: a.menus }
    }
  }
  const groups = []
  const used = new Set()
  for (const key of MENU_ORDER) {
    if (byKey[key]) {
      groups.push(byKey[key])
      used.add(key)
    }
  }
  for (const key of Object.keys(byKey)) {
    if (!used.has(key)) groups.push(byKey[key])
  }
  return isAdmin ? groups : groups.filter((g) => g.key !== 'roles')
})

const firstMenuPath = computed(() =>
  (menuGroups.value[0] && menuGroups.value[0].items[0] && menuGroups.value[0].items[0].path) || '/approvals'
)

onMounted(async () => {
  const list = await loadAddons()
  registerAddonRoutes(list)
  if (router.currentRoute.value.path === '/') {
    router.push(firstMenuPath.value)
  }
})

function logout() {
  localStorage.removeItem('yz_token')
  localStorage.removeItem('yz_roles')
  router.push('/login')
}
</script>

<style scoped>
.shell {
  min-height: 100vh;
  display: flex;
  background: var(--yz-paper);
}

/* 左侧栏:融入纸白暖底(与内容区同底色)+右缘细墨线;brand=朱印方章+宋体 */
.sidebar {
  width: 220px;
  flex-shrink: 0;
  background: var(--yz-paper);
  border-right: 1px solid var(--yz-line);
  display: flex;
  flex-direction: column;
  position: sticky;
  top: 0;
  height: 100vh;
  overflow-y: auto;
  box-sizing: border-box;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 20px;
  height: 64px;
  border-bottom: 1px solid var(--yz-line);
  cursor: pointer;
  flex-shrink: 0;
}
.logo { font-size: 22px; font-weight: 600; letter-spacing: 8px; line-height: 1; color: var(--yz-ink); }
.stamp { width: 26px; height: 26px; font-size: 13px; }

.menu { padding: 12px 0 24px; }

.group-name {
  margin: 16px 20px 6px;
  font-size: 12px;
  letter-spacing: 3px;
  color: var(--yz-ink-35);
}

.menu-item {
  display: block;
  padding: 10px 20px;
  font-size: 14px;
  color: var(--yz-ink-70);
  text-decoration: none;
  border-left: 3px solid transparent;
  line-height: 1.4;
}

.menu-item:hover { color: var(--yz-ink); background: var(--yz-paper-deep); }

.menu-item.active {
  color: var(--yz-ink);
  font-weight: 600;
  background: var(--yz-paper-deep);
  border-left-color: var(--yz-seal);
}

/* 右侧:顶栏(页题+通知+用户)+内容区 */
.body {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 32px;
  height: 64px;
  background: var(--yz-paper);
  border-bottom: 1px solid var(--yz-line);
  position: sticky;
  top: 0;
  z-index: 10;
}

.page-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: 4px;
  color: var(--yz-ink);
}

.right { display: flex; align-items: center; gap: 16px; }
.user { color: var(--yz-ink-70); font-size: 14px; }

.main {
  flex: 1;
  padding: 24px 32px;
  max-width: 1280px;
  width: 100%;
  margin: 0 auto;
  box-sizing: border-box;
}
</style>
