import { get, post } from './http'

// 认证
export const login = (data) => post('/api/auth/login', data)

// 本组织用户(转交/指派选择人)
export const listOrgUsers = () => get('/api/users')

// 客源(addon-customer)
export const listLeads = () => get('/api/customer/leads')
export const createLead = (data) => post('/api/customer/leads', data)

// 机会(addon-customer V2):列表为组织全量,移动端按 assignee_id 过滤"我的机会"
export const listChances = () => get('/api/customer/chances')
export const confirmChance = (id) => post(`/api/customer/chances/${id}/confirm`)
export const completeChance = (id) => post(`/api/customer/chances/${id}/complete`)

// 楼盘档案(addon-customerprofile):搜索联想数据源(R12 yz-search-select 远程模式)
export const pageVillages = (params) => get('/api/customerprofile/villages', params)
// 商品档案(addon-basedata):搜索联想数据源(name/no/拼音模糊+条码精确)
export const pageGoods = (params) => get('/api/basedata/goods', params)

// 通知收件箱(内核)
export const listNotifications = (params) => get('/api/notifications', params)
export const fetchUnreadCount = () => get('/api/notifications/unread-count')
export const markNotificationRead = (id) => post(`/api/notifications/${id}/read`)
export const markAllNotificationsRead = () => post('/api/notifications/read-all')

// 请假示例(addon-example 示范工程):我的请假单/发起/撤回;days≥3 须二级审批人 managerId
export const listLeaves = () => get('/api/example/leaves')
export const createLeave = (data) => post('/api/example/leaves', data)
export const cancelLeave = (id) => post(`/api/example/leaves/${id}/cancel`)

// 轻量审批(内核):三视角列表/详情/通过/驳回/转交/撤回
export const listApprovals = (params) => get('/api/approvals', params)
export const fetchApprovalDetail = (id) => get(`/api/approvals/${id}`)
export const approveApproval = (id, data) => post(`/api/approvals/${id}/approve`, data)
export const rejectApproval = (id, data) => post(`/api/approvals/${id}/reject`, data)
export const transferApproval = (id, data) => post(`/api/approvals/${id}/transfer`, data)
export const cancelApproval = (id) => post(`/api/approvals/${id}/cancel`)
