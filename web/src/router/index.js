import { createRouter, createWebHistory } from 'vue-router'
import Login from '../views/Login.vue'
import Layout from '../views/Layout.vue'

// 静态路由:登录 + 主布局。业务路由由插件菜单动态注册(见 registerAddonRoutes)。
const routes = [
  { path: '/login', component: Login },
  { path: '/', component: Layout, children: [] },
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
      router.addRoute('/', { path: menu.path, component: comp, meta: { addon: addon.id, title: menu.title } })
      existing.add(menu.path)
    }
  }
}

export default router
