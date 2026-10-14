// C 端门户 API(/api/c/**,addon-portal):六函数对齐阶段 A+B 端点。
import { get, post } from './http'

export const sendCode = (mobile) => post('/api/c/auth/code', { mobile })
export const login = (mobile, code) => post('/api/c/auth/login', { mobile, code })
export const listCases = (params) => get('/api/c/cases', params)
export const listMyOrders = () => get('/api/c/orders')
export const fetchProgress = (orderId) => get('/api/c/progress', { orderId })
export const fetchPoints = () => get('/api/c/points')
