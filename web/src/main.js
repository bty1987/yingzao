import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import './styles/yingzao.css'
import App from './App.vue'
import router from './router'
import { loadAddons } from './store/addons'
import { registerAddonRoutes } from './router'

// 深链接修复:先拉插件清单并注册动态路由,再挂载应用——
// 否则初始导航命中 pathMatch 兜底被甩到第一个菜单,刷新/直连业务 URL 全部失效
loadAddons()
  .then((list) => {
    registerAddonRoutes(list)
    createApp(App).use(router).use(ElementPlus, { locale: zhCn }).mount('#app')
  })
  .catch(() => {
    // 清单拉取失败(如未登录 401)也照常挂载,由拦截器与页面自身兜到登录页
    createApp(App).use(router).use(ElementPlus, { locale: zhCn }).mount('#app')
  })
