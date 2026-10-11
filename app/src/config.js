// 接口地址约定:
// - H5:同源直连(开发期走 vite.config.js 的 /api 代理,生产反向代理);
// - 小程序/APP:vite 代理不可用,直连后端绝对地址(换环境改这里;正式发布前应做成构建注入)。
// #ifdef H5
export const API_BASE = ''
// #endif
// #ifndef H5
export const API_BASE = 'http://192.168.5.21:8080'
// #endif
