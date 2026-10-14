import { API_BASE } from '../config'

const TOKEN_KEY = 'c_token'
const CID_KEY = 'c_customer_id'

export const getToken = () => uni.getStorageSync(TOKEN_KEY) || ''
export const getCustomerId = () => uni.getStorageSync(CID_KEY) || ''
export const saveSession = (token, customerId) => {
  uni.setStorageSync(TOKEN_KEY, token)
  uni.setStorageSync(CID_KEY, customerId)
}
export const clearSession = () => {
  uni.removeStorageSync(TOKEN_KEY)
  uni.removeStorageSync(CID_KEY)
}

// C 端统一请求封装:Bearer token + ApiResponse 解包;401 清票据回登录页(带 redirect 回跳)。
// 与员工端 app/src/api/http.js 同一套约定,票据键分离(c_token vs yz_token)互不污染。
function request(options) {
  return new Promise((resolve, reject) => {
    uni.request({
      url: API_BASE + options.url,
      method: options.method || 'GET',
      data: options.data,
      header: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer ' + getToken()
      },
      success: (res) => {
        if (res.statusCode === 401) {
          clearSession()
          const page = getCurrentPages().pop()
          const redirect = page ? '/' + page.route : '/pages/cases/index'
          uni.reLaunch({ url: '/pages/login/index?redirect=' + encodeURIComponent(redirect) })
          return reject(new Error('登录已过期,请重新登录'))
        }
        const body = res.data
        if (body && typeof body.code === 'number' && body.code !== 0) {
          const err = new Error(body.message || ('错误码 ' + body.code))
          err.code = body.code
          if (body.code === 122006) err.message = '暂无权限'
          if (body.code === 360004) err.message = '该装修单未绑定当前账号'
          return reject(err)
        }
        resolve(body)
      },
      fail: (err) => reject(new Error((err && err.errMsg) || '网络请求失败'))
    })
  })
}

export const get = (url, data) => request({ url, data })
export const post = (url, data) => request({ url, method: 'POST', data })
