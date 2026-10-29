// C 端门户 API(/api/c/**,addon-portal):六函数对齐阶段 A+B 端点。
import { get, post } from './http'

export const sendCode = (mobile) => post('/api/c/auth/code', { mobile })
export const login = (mobile, code) => post('/api/c/auth/login', { mobile, code })
export const listCases = (params) => get('/api/c/cases', params)
export const listMyOrders = () => get('/api/c/orders')
export const fetchProgress = (orderId) => get('/api/c/progress', { orderId })
export const fetchPoints = () => get('/api/c/points')
// 匿名名片(R7,/api/c/cards 内核白名单免登;share_token 即路由锚,R6 契约)
export const cardByToken = (token) => get('/api/c/cards/' + token)
export const submitCardNotice = (token, data) => post('/api/c/cards/' + token + '/notices', data)
