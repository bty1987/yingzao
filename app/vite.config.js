import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

// H5 开发期 /api 代理到本地内核(yingzao-app,8080);生产部署走反向代理同源。
// 小程序/APP 端不经 vite 代理,直连 src/config.js 的 API_BASE 绝对地址。
export default defineConfig({
  plugins: [uni()],
  server: {
    port: 5174,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true }
    }
  }
})
