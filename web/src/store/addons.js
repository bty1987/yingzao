import { reactive } from 'vue'
import { fetchAddons } from '../api'

// 已启用插件清单 + 槽位挂载声明(登录后加载一次,菜单/路由/槽位的数据源)。
export const addons = reactive({ list: [], loaded: false })

export async function loadAddons() {
  const res = await fetchAddons()
  addons.list = res.data || []
  addons.loaded = true
  return addons.list
}

export function mountsFor(slotPrefix) {
  return addons.list.flatMap((a) => (a.mounts || []).filter((m) => m.slot.startsWith(slotPrefix)))
}
