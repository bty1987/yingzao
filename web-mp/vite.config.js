import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

// C 端 H5 开发期 /api 代理到本地内核(8080);生产反向代理同源。端口 5175(员工端 app 占 5174)。
export default defineConfig({
  plugins: [uni()],
  server: {
    port: 5175,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true }
    }
  }
})
