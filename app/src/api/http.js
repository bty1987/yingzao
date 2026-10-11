import { API_BASE } from '../config'

// 统一请求封装:Bearer token 注入 + ApiResponse(code/message/data)解包 + 401 清票据回登录页。
// 与 web/src/api/http.js 同一套约定。
function request(options) {
  return new Promise((resolve, reject) => {
    uni.request({
      url: API_BASE + options.url,
      method: options.method || 'GET',
      data: options.data,
      header: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer ' + (uni.getStorageSync('yz_token') || '')
      },
      success: (res) => {
        if (res.statusCode === 401) {
          uni.removeStorageSync('yz_token')
          uni.removeStorageSync('yz_user_id')
          uni.removeStorageSync('yz_display_name')
          uni.reLaunch({ url: '/pages/login/login' })
          return reject(new Error('登录已过期,请重新登录'))
        }
        const body = res.data
        if (body && typeof body.code === 'number' && body.code !== 0) {
          return reject(new Error(body.message || ('错误码 ' + body.code)))
        }
        resolve(body)
      },
      fail: (err) => reject(new Error((err && err.errMsg) || '网络请求失败'))
    })
  })
}

export const get = (url, data) => request({ url, data })
export const post = (url, data) => request({ url, method: 'POST', data })
