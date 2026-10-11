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
// 客户与楼盘档案(addon-customerprofile)
export const pageCustomers = (params) => http.get('/api/customerprofile/customers', { params })
export const createCustomer = (data) => http.post('/api/customerprofile/customers', data)
export const existCustomer = (data) => http.post('/api/customerprofile/customers/exist', data)
export const fetchCustomer = (id) => http.get(`/api/customerprofile/customers/${id}`)
export const updateCustomer = (id, data) => http.put(`/api/customerprofile/customers/${id}`, data)
export const listCustomerPhones = (id) => http.get(`/api/customerprofile/customers/${id}/phones`)
export const addCustomerPhone = (id, data) => http.post(`/api/customerprofile/customers/${id}/phones`, data)
export const setDefaultCustomerPhone = (id, phoneId) =>
  http.post(`/api/customerprofile/customers/${id}/phones/${phoneId}/default`)
export const updateCustomerPhoneStatus = (id, phoneId, data) =>
  http.post(`/api/customerprofile/customers/${id}/phones/${phoneId}/status`, data)
export const revealCustomerPhone = (id, phoneId, data) =>
  http.post(`/api/customerprofile/customers/${id}/phones/${phoneId}/reveal`, data)
export const listPhoneAccessLogs = (id, params) =>
  http.get(`/api/customerprofile/customers/${id}/phone-access-logs`, { params })
export const listCustomerAccounts = (id) => http.get(`/api/customerprofile/customers/${id}/accounts`)
export const addCustomerAccount = (id, data) =>
  http.post(`/api/customerprofile/customers/${id}/accounts`, data)
export const setDefaultCustomerAccount = (id, accountId) =>
  http.post(`/api/customerprofile/customers/${id}/accounts/${accountId}/default`)
export const updateCustomerAccountStatus = (id, accountId, data) =>
  http.post(`/api/customerprofile/customers/${id}/accounts/${accountId}/status`, data)
export const listCities = () => http.get('/api/customerprofile/cities')
export const createCity = (data) => http.post('/api/customerprofile/cities', data)
export const createCityProper = (cityId, data) =>
  http.post(`/api/customerprofile/cities/${cityId}/propers`, data)
export const pageVillages = (params) => http.get('/api/customerprofile/villages', { params })
export const fetchVillage = (id) => http.get(`/api/customerprofile/villages/${id}`)
export const createVillage = (data) => http.post('/api/customerprofile/villages', data)
export const updateVillage = (id, data) => http.put(`/api/customerprofile/villages/${id}`, data)
export const updateVillageStatus = (id, data) => http.post(`/api/customerprofile/villages/${id}/status`, data)
export const pageHouses = (params) => http.get('/api/customerprofile/houses', { params })
export const fetchHouse = (id) => http.get(`/api/customerprofile/houses/${id}`)
export const createHouse = (data) => http.post('/api/customerprofile/houses', data)
export const existHouse = (data) => http.post('/api/customerprofile/houses/exist', data)
export const updateHouse = (id, data) => http.put(`/api/customerprofile/houses/${id}`, data)

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


// 基础数据(addon-basedata):品类/品牌/商品/SKU 属性/价格体系/套餐成员/型号/定材包
export const listCategories = () => http.get('/api/basedata/categories')
export const createCategory = (data) => http.post('/api/basedata/categories', data)
export const renameCategory = (id, data) => http.put(`/api/basedata/categories/${id}`, data)
export const updateCategoryStatus = (id, data) => http.post(`/api/basedata/categories/${id}/status`, data)
export const listBrands = (params) => http.get('/api/basedata/brands', { params })
export const createBrand = (data) => http.post('/api/basedata/brands', data)
export const renameBrand = (id, data) => http.put(`/api/basedata/brands/${id}`, data)
export const updateBrandStatus = (id, data) => http.post(`/api/basedata/brands/${id}/status`, data)
export const pageGoods = (params) => http.get('/api/basedata/goods', { params })
export const fetchGoods = (id) => http.get(`/api/basedata/goods/${id}`)
export const createGoods = (data) => http.post('/api/basedata/goods', data)
export const updateGoods = (id, data) => http.put(`/api/basedata/goods/${id}`, data)
export const updateGoodsStatus = (id, data) => http.post(`/api/basedata/goods/${id}/status`, data)
export const listGoodsSkuAttrs = (id, params) => http.get(`/api/basedata/goods/${id}/sku-attrs`, { params })
export const addGoodsSkuAttr = (id, data) => http.post(`/api/basedata/goods/${id}/sku-attrs`, data)
export const updateGoodsSkuAttrStatus = (id, attrId, data) => http.post(`/api/basedata/goods/${id}/sku-attrs/${attrId}/status`, data)
export const listGoodsPrices = (id) => http.get(`/api/basedata/goods/${id}/prices`)
export const addGoodsPrice = (id, data) => http.post(`/api/basedata/goods/${id}/prices`, data)
export const updateGoodsPrice = (id, priceId, data) => http.put(`/api/basedata/goods/${id}/prices/${priceId}`, data)
export const updateGoodsPriceStatus = (id, priceId, data) => http.post(`/api/basedata/goods/${id}/prices/${priceId}/status`, data)
export const listGoodsPackages = (id, params) => http.get(`/api/basedata/goods/${id}/packages`, { params })
export const addGoodsPackageMember = (id, data) => http.post(`/api/basedata/goods/${id}/packages`, data)
export const updateGoodsPackageMemberStatus = (id, memberId, data) => http.post(`/api/basedata/goods/${id}/packages/${memberId}/status`, data)
export const pageMaterialModels = (params) => http.get('/api/basedata/models', { params })
export const createMaterialModel = (data) => http.post('/api/basedata/models', data)
export const updateMaterialModel = (id, data) => http.put(`/api/basedata/models/${id}`, data)
export const updateMaterialModelStatus = (id, data) => http.post(`/api/basedata/models/${id}/status`, data)
export const pageMaterialPackages = (params) => http.get('/api/basedata/packages', { params })
export const createMaterialPackage = (data) => http.post('/api/basedata/packages', data)
export const createMaterialPackageReference = (id, data) => http.post(`/api/basedata/packages/${id}/reference`, data)
export const updateMaterialPackage = (id, data) => http.put(`/api/basedata/packages/${id}`, data)
export const updateMaterialPackageStatus = (id, data) => http.post(`/api/basedata/packages/${id}/status`, data)

// 款项(addon-payment):款项单/收款/确认/退款/类型字典/预付款
export const pageBills = (params) => http.get('/api/payment/bills', { params })
export const fetchBill = (id) => http.get(`/api/payment/bills/${id}`)
export const createBill = (data) => http.post('/api/payment/bills', data)
export const collectBill = (id, channels) => http.post(`/api/payment/bills/${id}/collect`, { channels })
export const confirmBill = (id) => http.post(`/api/payment/bills/${id}/confirm`)
export const refundBill = (id, data) => http.post(`/api/payment/bills/${id}/refund`, data)
export const cancelBill = (id) => http.post(`/api/payment/bills/${id}/cancel`)
export const listBillTypes = (params) => http.get('/api/payment/bill-types', { params })
export const createBillType = (data) => http.post('/api/payment/bill-types', data)
export const updateBillType = (id, data) => http.put(`/api/payment/bill-types/${id}`, data)
export const updateBillTypeStatus = (id, data) => http.post(`/api/payment/bill-types/${id}/status`, data)
export const listAdvanceSubjects = (params) => http.get('/api/payment/advance/subjects', { params })
export const createAdvanceSubject = (data) => http.post('/api/payment/advance/subjects', data)
export const updateAdvanceSubjectStatus = (id, data) =>
  http.post(`/api/payment/advance/subjects/${id}/status`, data)
export const listAdvanceAccounts = (params) => http.get('/api/payment/advance/accounts', { params })
export const listAdvanceFlows = (id, params) => http.get(`/api/payment/advance/accounts/${id}/flows`, { params })
export const chargeAdvance = (data) => http.post('/api/payment/advance/charge', data)
export const deductAdvance = (data) => http.post('/api/payment/advance/deduct', data)
export const withdrawAdvance = (data) => http.post('/api/payment/advance/withdraw', data)

// 装修单(addon-decoration):登记/四线状态/合同线流转/确认线/量房/归属
export const pageDecorations = (params) => http.get('/api/decoration/orders', { params })
export const fetchDecoration = (id) => http.get(`/api/decoration/orders/${id}`)
export const createDecoration = (data) => http.post('/api/decoration/orders', data)
export const saveDecorationProfile = (id, data) => http.post(`/api/decoration/orders/${id}/profile`, data)
export const presignDecoration = (id) => http.post(`/api/decoration/orders/${id}/presign`)
export const signDecoration = (id, data) => http.post(`/api/decoration/orders/${id}/sign`, data)
export const resignDecoration = (id) => http.post(`/api/decoration/orders/${id}/resign`)
export const obsoleteDecoration = (id) => http.post(`/api/decoration/orders/${id}/obsolete`)
export const terminateDecoration = (id, data) => http.post(`/api/decoration/orders/${id}/terminate`, data)
export const accountDecoration = (id) => http.post(`/api/decoration/orders/${id}/account`)
export const finishDecoration = (id) => http.post(`/api/decoration/orders/${id}/finish`)
export const designConfirmDecoration = (id, data) =>
  http.post(`/api/decoration/orders/${id}/design-confirm`, data)
export const drawlineConfirmDecoration = (id) => http.post(`/api/decoration/orders/${id}/drawline-confirm`)
export const applyDecorationMeasure = (id, data) => http.post(`/api/decoration/orders/${id}/measures`, data)
export const successDecorationMeasure = (mid) => http.post(`/api/decoration/measures/${mid}/success`)
export const failDecorationMeasure = (mid, data) => http.post(`/api/decoration/measures/${mid}/fail`, data)
export const transferDecorationBelong = (id, data) =>
  http.post(`/api/decoration/orders/${id}/belong/transfer`, data)

// 报价(addon-quotation):版本字典/报价单/定稿线/审核线/明细管理
export const listQuotationVersions = (params) => http.get('/api/quotation/versions', { params })
export const createQuotationVersion = (data) => http.post('/api/quotation/versions', data)
export const updateQuotationVersion = (id, data) => http.put(`/api/quotation/versions/${id}`, data)
export const updateQuotationVersionStatus = (id, data) =>
  http.post(`/api/quotation/versions/${id}/status`, data)
export const pageQuotations = (params) => http.get('/api/quotation/quotations', { params })
export const fetchQuotation = (id) => http.get(`/api/quotation/quotations/${id}`)
export const createQuotation = (data) => http.post('/api/quotation/quotations', data)
export const updateQuotationPricing = (id, data) => http.post(`/api/quotation/quotations/${id}/pricing`, data)
export const lockQuotation = (id) => http.post(`/api/quotation/quotations/${id}/lock`)
export const unlockQuotation = (id) => http.post(`/api/quotation/quotations/${id}/unlock`)
export const submitQuotation = (id) => http.post(`/api/quotation/quotations/${id}/submit`)
export const auditQuotation = (id) => http.post(`/api/quotation/quotations/${id}/audit`)
export const finishQuotation = (id) => http.post(`/api/quotation/quotations/${id}/finish`)
export const setDefaultQuotation = (id) => http.post(`/api/quotation/quotations/${id}/default`)
export const addQuotationArea = (id, data) => http.post(`/api/quotation/quotations/${id}/areas`, data)
export const updateQuotationArea = (id, areaId, data) =>
  http.put(`/api/quotation/quotations/${id}/areas/${areaId}`, data)
export const deleteQuotationArea = (id, areaId) => http.delete(`/api/quotation/quotations/${id}/areas/${areaId}`)
export const addQuotationItem = (id, areaId, data) =>
  http.post(`/api/quotation/quotations/${id}/areas/${areaId}/items`, data)
export const updateQuotationItem = (id, itemId, data) =>
  http.put(`/api/quotation/quotations/${id}/items/${itemId}`, data)
export const deleteQuotationItem = (id, itemId) => http.delete(`/api/quotation/quotations/${id}/items/${itemId}`)

// 施工管理(addon-construction):放线三段/节点推进/团队留痕/验收/节点模板/档案
export const pageConstructions = (params) => http.get('/api/construction/orders', { params })
export const fetchConstruction = (orderId) => http.get(`/api/construction/orders/${orderId}`)
export const updateConstructionSupervisor = (orderId, data) =>
  http.put(`/api/construction/orders/${orderId}/supervisor`, data)
export const switchConstructionAccessory = (orderId, data) =>
  http.post(`/api/construction/orders/${orderId}/accessory`, data)
export const submitConstructionAcceptance = (orderId, data) =>
  http.post(`/api/construction/orders/${orderId}/acceptance`, data)
export const fetchConstructionAcceptance = (orderId) =>
  http.get(`/api/construction/orders/${orderId}/acceptance`)
export const resetConstructionSchedules = (orderId, data) =>
  http.post(`/api/construction/orders/${orderId}/schedules/reset`, data)
export const applyConstructionDrawline = (data) => http.post('/api/construction/drawlines', data)
export const scheduleConstructionDrawline = (id, data) =>
  http.post(`/api/construction/drawlines/${id}/schedule`, data)
export const successConstructionDrawline = (id) => http.post(`/api/construction/drawlines/${id}/success`)
export const failConstructionDrawline = (id, data) => http.post(`/api/construction/drawlines/${id}/fail`, data)
export const retroactiveConstructionDrawline = (data) =>
  http.post('/api/construction/drawlines/retroactive', data)
export const beginConstructionSchedule = (id) => http.post(`/api/construction/schedules/${id}/begin`)
export const finishConstructionSchedule = (id) => http.post(`/api/construction/schedules/${id}/finish`)
export const openConstructionSalary = (id) => http.post(`/api/construction/schedules/${id}/open-salary`)
export const checkoutConstructionSchedule = (id, data) =>
  http.post(`/api/construction/schedules/${id}/checkout`, data)
export const deleteConstructionSchedule = (id) => http.delete(`/api/construction/schedules/${id}`)
export const addConstructionWorker = (id, data) => http.post(`/api/construction/schedules/${id}/workers`, data)
export const listConstructionWorkers = (id) => http.get(`/api/construction/schedules/${id}/workers`)
export const disableConstructionWorker = (id) => http.post(`/api/construction/workers/${id}/disable`)
export const sendConstructionRateLink = (id) => http.post(`/api/construction/workers/${id}/rate-link`)
export const rateConstructionWorker = (id, data) => http.post(`/api/construction/workers/${id}/rate`, data)
export const enterConstructionWorker = (id) => http.post(`/api/construction/workers/${id}/enter`)
export const leaveConstructionWorker = (id) => http.post(`/api/construction/workers/${id}/leave`)
export const listConstructionRecords = (id) => http.get(`/api/construction/workers/${id}/records`)
export const addConstructionDiary = (id, data) => http.post(`/api/construction/schedules/${id}/diaries`, data)
export const listConstructionDiaries = (id) => http.get(`/api/construction/schedules/${id}/diaries`)
export const listConstructionNodes = (params) => http.get('/api/construction/nodes', { params })
export const createConstructionNode = (data) => http.post('/api/construction/nodes', data)
export const updateConstructionNode = (id, data) => http.put(`/api/construction/nodes/${id}`, data)
export const updateConstructionNodeStatus = (id, data) =>
  http.post(`/api/construction/nodes/${id}/status`, data)
export const fetchConstructionConfig = () => http.get('/api/construction/config')
export const updateConstructionConfig = (data) => http.put('/api/construction/config', data)
export const listConstructionProfileTemplates = (params) =>
  http.get('/api/construction/profile-templates', { params })
export const createConstructionProfileTemplate = (data) =>
  http.post('/api/construction/profile-templates', data)
export const deleteConstructionProfileTemplate = (id) =>
  http.delete(`/api/construction/profile-templates/${id}`)
export const addConstructionProfileTemplateItem = (id, data) =>
  http.post(`/api/construction/profile-templates/${id}/items`, data)
export const deleteConstructionProfileTemplateItem = (id, itemId) =>
  http.delete(`/api/construction/profile-templates/${id}/items/${itemId}`)
export const instantiateConstructionProfile = (orderId, data) =>
  http.post(`/api/construction/orders/${orderId}/profiles`, data)
export const listConstructionProfiles = (orderId) => http.get(`/api/construction/orders/${orderId}/profiles`)
export const fillConstructionProfileItem = (id, itemId, data) =>
  http.put(`/api/construction/profiles/${id}/items/${itemId}`, data)
export const completeConstructionProfile = (id) => http.post(`/api/construction/profiles/${id}/complete`)
