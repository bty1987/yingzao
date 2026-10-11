import http from './http'

export const login = (data) => http.post('/api/auth/login', data)
export const fetchAddons = () => http.get('/api/addons')
export const listOrgUsers = () => http.get('/api/users')
export const listLeads = () => http.get('/api/customer/leads')
export const createLead = (data) => http.post('/api/customer/leads', data)
export const convertLead = (id) => http.post(`/api/customer/leads/${id}/convert`)
export const assignLead = (id, data) => http.post(`/api/customer/leads/${id}/assign`, data)
export const listChances = () => http.get('/api/customer/chances')
export const confirmChance = (id) => http.post(`/api/customer/chances/${id}/confirm`)
export const completeChance = (id) => http.post(`/api/customer/chances/${id}/complete`)
export const listProfiles = () => http.get('/api/customerprofile/profiles')

// 通知收件箱(内核)
export const listNotifications = (params) => http.get('/api/notifications', { params })
export const fetchUnreadCount = () => http.get('/api/notifications/unread-count')
export const markNotificationRead = (id) => http.post(`/api/notifications/${id}/read`)
export const markAllNotificationsRead = () => http.post('/api/notifications/read-all')

// 轻量审批(内核):三视角列表/详情/通过/驳回/转交/撤回
export const listApprovals = (params) => http.get('/api/approvals', { params })
export const fetchApprovalDetail = (id) => http.get(`/api/approvals/${id}`)
export const approveApproval = (id, data) => http.post(`/api/approvals/${id}/approve`, data)
export const rejectApproval = (id, data) => http.post(`/api/approvals/${id}/reject`, data)
export const transferApproval = (id, data) => http.post(`/api/approvals/${id}/transfer`, data)
export const cancelApproval = (id) => http.post(`/api/approvals/${id}/cancel`)

// 请假示例(addon-example 示范工程)
export const listLeaves = () => http.get('/api/example/leaves')
export const createLeave = (data) => http.post('/api/example/leaves', data)
export const cancelLeave = (id) => http.post(`/api/example/leaves/${id}/cancel`)

// 角色与授权(RBAC,内核;仅管理员可操作,服务端 122003 守卫)
export const listRoles = () => http.get('/api/roles')
export const createRole = (data) => http.post('/api/roles', data)
export const fetchRole = (id) => http.get(`/api/roles/${id}`)
export const deleteRole = (id) => http.delete(`/api/roles/${id}`)
export const updateRoleMenus = (id, menus) => http.put(`/api/roles/${id}/menus`, { menus })
export const updateRoleCapabilities = (id, capabilities) => http.put(`/api/roles/${id}/capabilities`, { capabilities })
export const updateRoleUsers = (id, userIds) => http.put(`/api/roles/${id}/users`, { userIds })

