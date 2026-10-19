import { createRouter, createWebHistory } from 'vue-router'
import Login from '../views/Login.vue'
import Layout from '../views/Layout.vue'
import ApprovalCenter from '../views/ApprovalCenter.vue'
import Roles from '../views/Roles.vue'
import Events from '../views/Events.vue'
import Plans from '../views/Plans.vue'

// 静态路由:登录 + 主布局 + 内核平台页(审批中心/角色权限/事件总线)。业务路由由插件菜单动态注册(见 registerAddonRoutes)。
const routes = [
  { path: '/login', component: Login },
  { path: '/', name: 'layout', component: Layout, children: [] },
  { path: '/approvals', component: Layout, children: [{ path: '', component: ApprovalCenter, meta: { title: '审批中心' } }] },
  { path: '/roles', component: Layout, children: [{ path: '', component: Roles, meta: { title: '角色权限' } }] },
  { path: '/events', component: Layout, children: [{ path: '', component: Events, meta: { title: '事件总线' } }] },
  { path: '/plans', component: Layout, children: [{ path: '', component: Plans, meta: { title: '计划任务' } }] },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({ history: createWebHistory(), routes })

/**
 * 按 /api/addons 下发的插件菜单动态注册路由。
 * 约定:菜单 path = /<addonId>/<page> → 组件 = views/<addonId>/<Page>.vue(首字母大写)。
 * 禁止硬编码插件路由——菜单与路由完全由后端已启用插件决定。
 */
export function registerAddonRoutes(addons) {
  const views = import.meta.glob('../views/**/*.vue')
  const existing = new Set(router.getRoutes().map((r) => r.path))
  for (const addon of addons) {
    for (const menu of addon.menus || []) {
      if (existing.has(menu.path)) continue
      const segs = menu.path.split('/').filter(Boolean)
      if (segs.length < 2) continue
      const page = segs[segs.length - 1]
      const compKey = `../views/${segs[0]}/${page.charAt(0).toUpperCase()}${page.slice(1)}.vue`
      const comp = views[compKey]
      if (!comp) continue
      // 挂在 layout 名下(修:'/' 无 name 时按父名挂载失败,业务页被注册成顶层路由脱离布局,导航消失)
      router.addRoute('layout', { path: menu.path, component: comp, meta: { addon: addon.id, title: menu.title } })
      existing.add(menu.path)
    }
  }
}

export default router
