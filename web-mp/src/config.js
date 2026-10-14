// 接口地址约定(与员工端 app/src/config.js 同款):
// - H5:同源直连(dev 走 vite 代理,生产反向代理);
// - 小程序:直连后端绝对地址。
// #ifdef H5
export const API_BASE = ''
// #endif
// #ifndef H5
export const API_BASE = 'http://192.168.5.21:8080'
// #endif
