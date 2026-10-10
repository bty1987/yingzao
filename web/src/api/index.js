import http from './http'

export const login = (data) => http.post('/api/auth/login', data)
export const fetchAddons = () => http.get('/api/addons')
export const listLeads = () => http.get('/api/customer/leads')
export const createLead = (data) => http.post('/api/customer/leads', data)
export const convertLead = (id) => http.post(`/api/customer/leads/${id}/convert`)
export const listProfiles = () => http.get('/api/customerprofile/profiles')
