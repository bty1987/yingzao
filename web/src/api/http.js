import axios from 'axios'
import router from '../router'

const http = axios.create({ baseURL: '', timeout: 15000 })

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('yz_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && typeof body.code === 'number' && body.code !== 0) {
      const err = new Error(body.message || `错误码 ${body.code}`)
      err.code = body.code
      err.data = body.data
      return Promise.reject(err)
    }
    return body
  },
  (err) => {
    if (err.response && err.response.status === 401) {
      localStorage.removeItem('yz_token')
      if (router.currentRoute.value.path !== '/login') router.push('/login')
    }
    return Promise.reject(err)
  }
)

export default http
