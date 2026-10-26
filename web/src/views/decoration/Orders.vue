<template>
  <div>
    <div class="toolbar">
      <el-input v-model="filters.orderNo" placeholder="单号" style="width: 150px" clearable @keyup.enter="load" />
      <el-input v-model="filters.customerName" placeholder="客户姓名" style="width: 130px" clearable @keyup.enter="load" />
      <el-select v-model="filters.status" placeholder="合同状态" style="width: 120px" clearable>
        <el-option v-for="d in dicts.orderStatus" :key="d.key" :label="d.label" :value="Number(d.key)" />
      </el-select>
      <el-select v-model="filters.type" placeholder="类型" style="width: 110px" clearable>
        <el-option v-for="d in dicts.orderType" :key="d.key" :label="d.label" :value="Number(d.key)" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <el-button @click="openCreate">登记装修单</el-button>
      <el-button v-if="isAdmin" @click="openAccConfig">决算检查配置</el-button>
    </div>

    <el-table :data="rows" border stripe v-loading="loading" @row-click="openDetail">
      <el-table-column prop="order_no" label="单号" width="150" />
      <el-table-column prop="customer_name" label="客户" width="100" />
      <el-table-column prop="house_label" label="房屋" min-width="200" show-overflow-tooltip />
      <el-table-column label="类型" width="80">
        <template #default="{ row }">{{ dictLabel('orderType', row.type) }}</template>
      </el-table-column>
      <el-table-column label="合同线" width="90">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" effect="plain">{{ dictLabel('orderStatus', row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="工程/设计/确认" width="170">
        <template #default="{ row }">
          <span class="lines">
            {{ dictLabel('projectStatus', row.project_status) }} /
            {{ dictLabel('designStatus', row.design_status) }} /
            {{ sourceLabel(row.source_status) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column prop="contract_time" label="签约时间" width="110" />
      <el-table-column prop="created_at" label="登记时间" width="110" />
      <el-table-column label="操作" width="230">
        <template #default="{ row }">
          <el-button v-if="[0, 1].includes(row.status)" type="primary" link @click.stop="onPresign(row)">预签</el-button>
          <el-button v-if="[0, 1, 2, 4].includes(row.status)" type="success" link @click.stop="openSign(row)">签约</el-button>
          <el-button v-if="row.status === 3" type="warning" link @click.stop="onResign(row)">重签</el-button>
          <el-button v-if="[0, 1, 2].includes(row.status)" type="danger" link @click.stop="onObsolete(row)">废单</el-button>
          <el-button v-if="row.status === 3" type="danger" link @click.stop="openTerminate(row)">终止</el-button>
          <el-button v-if="row.status === 3" type="primary" link @click.stop="openAccount(row)">决算</el-button>
          <el-button v-if="row.status === 7" type="success" link @click.stop="onFinish(row)">完工</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination layout="prev, pager, next, total" :total="total" :page-size="filters.size"
      :current-page="filters.page" @current-change="(p) => { filters.page = p; load() }" style="margin-top: 12px" />

    <!-- 决算前置检查 -->
    <el-dialog v-model="accountDialog" title="决算前置检查" width="620px">
      <el-alert v-if="accountCheck && !accountCheck.checkEnable" type="info" :closable="false"
        title="决算检查未启用:门槛为该单款项全部收齐;组织可在「决算检查配置」开启六项检查" style="margin-bottom: 10px" />
      <el-table :data="accountItems" size="small" border>
        <el-table-column prop="label" label="检查项" width="180" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag v-if="!row.enabled" type="info" effect="plain" size="small">未启用</el-tag>
            <el-tag v-else-if="row.status === 1" type="success" effect="plain" size="small">通过</el-tag>
            <el-tag v-else type="danger" effect="plain" size="small">未通过</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="detailText" label="明细" min-width="220" />
      </el-table>
      <template #footer>
        <el-button @click="accountDialog = false">取消</el-button>
        <el-button type="primary" :disabled="accountBlocked" :loading="saving" @click="onDoAccount">确认决算</el-button>
      </template>
    </el-dialog>

    <!-- 决算检查配置(组织级) -->
    <el-dialog v-model="configDialog" title="决算检查配置(组织级)" width="560px">
      <el-form label-width="150px">
        <el-form-item label="检查总闸">
          <el-switch v-model="accConfig.checkEnable" />
          <span class="tip">关闭 = 决算门槛为款项全部收齐;开启 = 六项检查合成</span>
        </el-form-item>
        <el-form-item label="已签约"><el-switch v-model="accConfig.signEnable" /></el-form-item>
        <el-form-item label="主材销售完结"><el-switch v-model="accConfig.salesEnable" /></el-form-item>
        <el-form-item label="决算节点完工"><el-switch v-model="accConfig.constructionEnable" /></el-form-item>
        <el-form-item label="选材完成(预留)"><el-switch v-model="accConfig.selectionEnable" disabled />
          <span class="tip">选材域插件未接入,恒不拦</span></el-form-item>
        <el-form-item label="装修收款比例闸">
          <el-switch v-model="accConfig.decoPaymentEnable" />
          <el-input-number v-model="accConfig.decoPaymentTarget" :min="0" :max="1000" size="small"
            style="margin-left: 8px; width: 110px" />
          <span class="tip">千分比;开启后替代「款项全收齐」硬闸</span>
        </el-form-item>
        <el-form-item label="销售收款比例闸">
          <el-switch v-model="accConfig.salePaymentEnable" />
          <el-input-number v-model="accConfig.salePaymentTarget" :min="0" :max="1000" size="small"
            style="margin-left: 8px; width: 110px" />
          <span class="tip">千分比,经销售款收款比例</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="configDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSaveAccConfig">保存</el-button>
      </template>
    </el-dialog>

    <!-- 登记装修单 -->
    <el-dialog v-model="createDialog" title="登记装修单" width="640px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="客户" required>
          <el-select v-model="form.customerId" filterable remote :remote-method="searchCustomers"
            :loading="customerLoading" placeholder="输入姓名搜索客户档案" style="width: 100%">
            <el-option v-for="c in customerOptions" :key="c.id" :label="c.name + '(' + (c.no || '') + ')'"
              :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="房屋" required>
          <el-select v-model="form.villageId" placeholder="选择楼盘" style="width: 45%" @change="form.houseId = null">
            <el-option v-for="v in villages" :key="v.id" :label="v.name" :value="v.id" />
          </el-select>
          <el-select v-model="form.houseId" :disabled="!form.villageId" placeholder="选择房屋"
            style="width: 53%; margin-left: 2%">
            <el-option v-for="h in houses" :key="h.id" :value="h.id"
              :label="(h.built || '') + '栋 ' + (h.unit || '') + '单元 ' + (h.floor_num || '') + '层 ' + (h.door_num || '')" />
          </el-select>
        </el-form-item>
        <el-form-item label="单类型" required>
          <el-radio-group v-model="form.type">
            <el-radio v-for="d in dicts.orderType" :key="d.key" :value="Number(d.key)">{{ d.label }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="设计师" required>
          <el-select v-model="form.designerId" style="width: 45%">
            <el-option v-for="u in users" :key="u.id" :label="u.display_name" :value="u.id" />
          </el-select>
          <el-select v-model="form.salesmanId" placeholder="销售顾问" style="width: 53%; margin-left: 2%">
            <el-option v-for="u in users" :key="u.id" :label="u.display_name" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="定金金额">
          <el-input-number v-model="form.depositYuan" :min="0" :precision="3" :step="1000" />
          <span class="tip">元,0 = 不生成定金款线</span>
        </el-form-item>
        <el-form-item label="套内面积">
          <el-input-number v-model="form.areaM2" :min="0" :precision="1" :step="10" />
          <span class="tip">m²</span>
        </el-form-item>
        <el-form-item label="房型/风格">
          <el-input v-model="form.houseType" placeholder="如 三室两厅" style="width: 45%" />
          <el-input v-model="form.style" placeholder="装修风格" style="width: 53%; margin-left: 2%" />
        </el-form-item>
        <el-form-item label="客户需求">
          <el-input v-model="form.note" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onCreate">登记</el-button>
      </template>
    </el-dialog>

    <!-- 签约:合同总额 -->
    <el-dialog v-model="signDialog" title="签约(按款项类型比例模板拆分款线)" width="460px">
      <el-form label-width="100px">
        <el-form-item label="合同总额" required>
          <el-input-number v-model="signForm.contractYuan" :min="0.001" :precision="3" :step="10000" />
          <span class="tip">元(厘存储)</span>
        </el-form-item>
        <el-form-item label="门槛提示">
          <span class="tip">须已有已收款项(如定金),且无进行中审批</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="signDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSign">签约</el-button>
      </template>
    </el-dialog>

    <!-- 终止:走内核审批 -->
    <el-dialog v-model="terminateDialog" title="发起终止(提交内核审批)" width="460px">
      <el-form label-width="90px">
        <el-form-item label="审批人" required>
          <el-select v-model="terminateForm.assigneeId" style="width: 100%">
            <el-option v-for="u in users" :key="u.id" :label="u.display_name" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="终止事由">
          <el-input v-model="terminateForm.reason" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="terminateDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onTerminate">提交审批</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉:四线状态 + profile + 团队/归属/量房/款项/轨迹 -->
    <!-- 三口径建单弹窗 -->
    <el-dialog v-model="caliberDialog" :title="caliberTitle" width="520px" append-to-body>
      <el-form label-width="110px">
        <template v-if="caliberKind === 'midpack'">
          <el-form-item label="主材配比(‰)">
            <el-input-number v-model="midpackForm.materialRatePct" :min="0" :max="100" :step="1" :precision="1" />
            <span class="tip" style="margin-left: 6px">0 = 手填主材额</span>
          </el-form-item>
          <el-form-item v-if="midpackForm.materialRatePct === 0" label="主材配额(元)">
            <el-input-number v-model="midpackForm.materialPriceYuan" :min="0" :precision="3" />
          </el-form-item>
          <el-form-item label="服务费率(‰)">
            <el-input-number v-model="midpackForm.serviceRatePct" :min="0" :max="100" :step="1" :precision="1" />
          </el-form-item>
        </template>
        <template v-if="caliberKind === 'holonomy'">
          <el-form-item label="满减基数(元)">
            <el-input-number v-model="holonomyForm.discountBaseYuan" :min="0.001" :precision="3" />
          </el-form-item>
          <el-form-item label="折扣(%)">
            <el-input-number v-model="holonomyForm.discountPct" :min="1" :max="100" :step="1" :precision="0" />
          </el-form-item>
          <el-form-item label="管理费跟随">
            <el-switch v-model="holonomyForm.manageFollow" />
          </el-form-item>
          <el-form-item label="主材配比(‰)">
            <el-input-number v-model="holonomyForm.machingPct" :min="0" :max="100" :step="1" :precision="1" />
          </el-form-item>
          <el-form-item label="服务费率(‰)">
            <el-input-number v-model="holonomyForm.serviceRatePct" :min="0" :max="100" :step="1" :precision="1" />
          </el-form-item>
        </template>
        <template v-if="caliberKind === 'inclusive'">
          <el-form-item label="常规增减(元)">
            未折 <el-input-number v-model="inclusiveForm.commonUndisYuan" :precision="3" style="width: 130px" />
            折后 <el-input-number v-model="inclusiveForm.commonDisYuan" :precision="3" style="width: 130px" />
          </el-form-item>
          <el-form-item label="常规管理/税">
            <el-input-number v-model="inclusiveForm.commonManageYuan" :precision="3" style="width: 130px" />
            <el-input-number v-model="inclusiveForm.commonTaxYuan" :precision="3" style="width: 130px" />
          </el-form-item>
          <el-form-item label="MET增减(元)">
            未折 <el-input-number v-model="inclusiveForm.metUndisYuan" :precision="3" style="width: 130px" />
            折后 <el-input-number v-model="inclusiveForm.metDisYuan" :precision="3" style="width: 130px" />
          </el-form-item>
          <el-form-item label="MET口径">
            <el-radio-group v-model="inclusiveForm.metFlag">
              <el-radio :value="1">仅正计</el-radio>
              <el-radio :value="2">仅负计</el-radio>
              <el-radio :value="3">无条件</el-radio>
            </el-radio-group>
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="caliberDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onMakeCaliber">推导</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailDrawer" size="640px" :title="detail ? detail.order_no : '装修单详情'">
      <template v-if="detail">
        <div class="status-grid">
          <div class="status-cell">
            <div class="status-name">合同线</div>
            <el-tag :type="statusTag(detail.status)" effect="plain">{{ dictLabel('orderStatus', detail.status) }}</el-tag>
          </div>
          <div class="status-cell">
            <div class="status-name">工程线</div>
            <el-tag effect="plain">{{ dictLabel('projectStatus', detail.project_status) }}</el-tag>
          </div>
          <div class="status-cell">
            <div class="status-name">设计线</div>
            <el-tag effect="plain">{{ dictLabel('designStatus', detail.design_status) }}</el-tag>
          </div>
          <div class="status-cell">
            <div class="status-name">确认线</div>
            <el-tag effect="plain">{{ sourceLabel(detail.source_status) }}</el-tag>
          </div>
        </div>
        <el-descriptions :column="2" border size="small" style="margin-top: 12px">
          <el-descriptions-item label="客户">{{ detail.customer_name }}</el-descriptions-item>
          <el-descriptions-item label="类型">{{ dictLabel('orderType', detail.type) }}</el-descriptions-item>
          <el-descriptions-item label="房屋" :span="2">{{ detail.house_label }}</el-descriptions-item>
          <el-descriptions-item label="定金时间">{{ detail.deposit_time || '—' }}</el-descriptions-item>
          <el-descriptions-item label="签约时间">{{ detail.contract_time || '—' }}</el-descriptions-item>
          <el-descriptions-item label="重签时间">{{ detail.recontract_time || '—' }}</el-descriptions-item>
          <el-descriptions-item label="决算时间">{{ detail.account_time || '—' }}</el-descriptions-item>
          <el-descriptions-item label="套内面积">{{ profileArea }}</el-descriptions-item>
          <el-descriptions-item label="房型/风格">{{ (detail.profile.house_type || '—') + ' / ' + (detail.profile.style || '—') }}</el-descriptions-item>
          <el-descriptions-item label="客户需求" :span="2">{{ detail.profile.note || '—' }}</el-descriptions-item>
        </el-descriptions>

        <div class="section">
          <div class="section-title">交付操作</div>
          <el-button size="small" :disabled="![2, 3, 4].includes(detail.status)" @click="onDesignConfirm(0)">合同方案确认</el-button>
          <el-button size="small" :disabled="![2, 3, 4].includes(detail.status)" @click="onDesignConfirm(1)">确认(微改)</el-button>
          <el-button size="small" :disabled="![2, 3, 4].includes(detail.status)" @click="onDesignConfirm(2)">确认(大改)</el-button>
          <el-button size="small" :disabled="![2, 3, 4].includes(detail.status) || detail.source_status % 10 !== 1"
            @click="onDrawlineConfirm">放线确认</el-button>
          <el-button size="small" :disabled="![2, 3, 4].includes(detail.status)" @click="openMeasure">量房申请</el-button>
        </div>

        <div class="section">
          <div class="section-title">服务团队 / 归属</div>
          <div v-for="t in detail.team" :key="t.role + t.user_id" class="kv">
            {{ roleLabel(t.role) }}:{{ t.user_name }}
            <el-button v-if="t.role === 'designer' || t.role === 'salesman'" link size="small"
              @click="openTransfer(t.role)">转归属</el-button>
          </div>
        </div>

        <div class="section">
          <div class="section-title">量房计划</div>
          <div v-for="m in detail.measures" :key="m.id" class="kv">
            {{ dictLabel('measureStatus', m.status) }} | 申请 {{ m.apply_time || '—' }}
            <template v-if="m.status === 1">
              <el-button link type="success" size="small" @click="onMeasureSuccess(m)">成功</el-button>
              <el-button link type="danger" size="small" @click="onMeasureFail(m)">失败</el-button>
            </template>
          </div>
          <div v-if="!detail.measures.length" class="tip">暂无量房计划</div>
        </div>

        <div class="section">
          <div class="section-title">三口径表(半包配比 / 整装满减 / 包干增项)</div>
          <el-button size="small" @click="loadCalibers">刷新口径</el-button>
          <div v-if="calibers.midpack" class="kv" style="margin-top: 6px">
            <el-tag size="small" :type="calibers.midpack.status === 1 ? 'success' : 'info'" effect="plain">
              半包{{ calibers.midpack.status === 1 ? '已确认' : '初始' }}</el-tag>
            主材配比 {{ ppmView(calibers.midpack.material_rate) }} · 主材配额 ¥{{ yuan(calibers.midpack.material_price) }}
            · 服务费 ¥{{ yuan(calibers.midpack.service_price) }} · 推导总额 ¥{{ yuan(calibers.midpack.total_price) }}
            <el-button v-if="calibers.midpack.status === 0" link size="small" @click="onConfirmCaliber('midpack')">确认</el-button>
          </div>
          <div v-if="calibers.holonomy" class="kv">
            <el-tag size="small" :type="calibers.holonomy.status === 1 ? 'success' : 'info'" effect="plain">
              整装{{ calibers.holonomy.status === 1 ? '已确认' : '初始' }}</el-tag>
            折 {{ (calibers.holonomy.discount / 100).toFixed(2) }}(基数 ¥{{ yuan(calibers.holonomy.discount_base) }})
            · 折后工程 ¥{{ yuan(calibers.holonomy.project_price) }} · 主材配额 ¥{{ yuan(calibers.holonomy.material_price) }}
            · 推导总额 ¥{{ yuan(calibers.holonomy.total_price) }}
            <el-button v-if="calibers.holonomy.status === 0" link size="small" @click="onConfirmCaliber('holonomy')">确认</el-button>
          </div>
          <div v-if="calibers.inclusive" class="kv">
            <el-tag size="small" :type="calibers.inclusive.status === 1 ? 'success' : 'info'" effect="plain">
              包干{{ calibers.inclusive.status === 1 ? '已确认' : '初始' }}</el-tag>
            报价 ¥{{ yuan(calibers.inclusive.base_total) }} + 常规增减 ¥{{ yuan(calibers.inclusive.common_undis + calibers.inclusive.common_dis) }}
            · MET 口径 {{ ['仅正计', '仅负计', '无条件'][calibers.inclusive.met_flag - 1] }}
            · 合同总额 ¥{{ yuan(calibers.inclusive.total_price) }}
            <el-button v-if="calibers.inclusive.status === 0" link size="small" @click="onConfirmCaliber('inclusive')">确认</el-button>
          </div>
          <div class="tip" style="margin-top: 4px">
            建口径:{{ orderEditable ? '合同线初始/定金/预签/重签可建(需默认报价已定稿锁定)' : '当前合同线不可建' }}
          </div>
          <div v-if="orderEditable" style="margin-top: 6px">
            <el-button size="small" @click="openCaliberDialog('midpack')">建半包口径</el-button>
            <el-button size="small" @click="openCaliberDialog('holonomy')">建整装口径</el-button>
            <el-button size="small" @click="openCaliberDialog('inclusive')">建包干口径</el-button>
          </div>
        </div>

        <div class="section">
          <div class="section-title">款项单(经 PaymentApi 契约)</div>
          <div v-for="b in detail.bills" :key="b.billId" class="kv">
            <el-tag :type="b.status === 0 ? 'info' : b.status >= 1 && b.status <= 2 ? 'success' : 'danger'"
              size="small" effect="plain">{{ b.status === 0 ? '未收' : b.status === 1 ? '已收' : b.status === 2 ? '已确认' : b.status === 3 ? '已退款' : '已作废' }}</el-tag>
            {{ b.typeName }} ¥{{ yuan(b.amount) }}
          </div>
          <div v-if="!detail.bills.length" class="tip">暂无款项单</div>
        </div>

        <div class="section">
          <div class="section-title">操作轨迹</div>
          <div v-for="(t, i) in detail.tracks" :key="i" class="kv track">
            <span class="track-time">{{ t.created_at }}</span>
            {{ trackLabel(t.action) }}
            <span v-if="t.from_value !== null && t.from_value !== undefined && t.to_value !== null">
              ({{ t.from_value }} → {{ t.to_value }})</span>
            <span v-if="t.operator_name"> | {{ t.operator_name }}</span>
            <span v-if="t.comment" class="tip"> {{ t.comment }}</span>
          </div>
          <div v-if="!detail.tracks.length" class="tip">暂无轨迹</div>
        </div>
      </template>
    </el-drawer>

    <!-- 量房申请 -->
    <el-dialog v-model="measureDialog" title="量房/放线申请" width="420px">
      <el-form label-width="90px">
        <el-form-item label="预计时间">
          <el-date-picker v-model="measureForm.planTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="measureForm.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="measureDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onMeasureApply">提交</el-button>
      </template>
    </el-dialog>

    <!-- 转归属 -->
    <el-dialog v-model="transferDialog" title="转归属(即时生效,旧归属置失效)" width="420px">
      <el-form label-width="90px">
        <el-form-item label="新归属人" required>
          <el-select v-model="transferForm.userId" style="width: 100%">
            <el-option v-for="u in users" :key="u.id" :label="u.display_name" :value="u.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="transferDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onTransfer">确认转移</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref, reactive, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  pageDecorations, fetchDecoration, createDecoration, presignDecoration, signDecoration,
  resignDecoration, obsoleteDecoration, terminateDecoration, accountDecoration, finishDecoration,
  accountCheckDecoration, fetchDecorationConfig, updateDecorationConfig,
  makeMidpackCaliber, makeHolonomyCaliber, makeInclusiveCaliber, confirmCaliber, listCalibers,
  designConfirmDecoration, drawlineConfirmDecoration, applyDecorationMeasure,
  successDecorationMeasure, failDecorationMeasure, transferDecorationBelong,
  pageCustomers, pageVillages, pageHouses, listOrgUsers
} from '../../api'
import { addons } from '../../store/addons'

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const filters = reactive({ orderNo: '', customerName: '', status: null, type: null, page: 1, size: 20 })

const dicts = reactive({ orderStatus: [], orderType: [], projectStatus: [], designStatus: [],
  sourceStatus: [], measureStatus: [], teamRole: [], trackAction: [] })
const users = ref([])
const villages = ref([])
const houses = ref([])

const createDialog = ref(false)
const signDialog = ref(false)
const terminateDialog = ref(false)
const accountDialog = ref(false)
const accountCheck = ref(null)
const accountRow = ref(null)
const configDialog = ref(false)
const accConfig = ref({})
const measureDialog = ref(false)
const transferDialog = ref(false)
const detailDrawer = ref(false)
const detail = ref(null)
const current = ref(null)
const transferRole = ref('designer')

const customerOptions = ref([])
const customerLoading = ref(false)
const form = ref({ customerId: null, villageId: null, houseId: null, type: 0, designerId: null,
  salesmanId: null, depositYuan: 0, areaM2: null, houseType: '', style: '', note: '', remark: '' })
const signForm = ref({ contractYuan: 0 })
const terminateForm = ref({ assigneeId: null, reason: '' })
const measureForm = ref({ planTime: null, remark: '' })
const transferForm = ref({ userId: null })

const profileArea = computed(() => {
  const a = detail.value && detail.value.profile && detail.value.profile.inner_area
  return a ? (a / 1000) + ' m²' : '—'
})
const yuan = (li) => (Number(li || 0) / 1000).toLocaleString('zh-CN', { minimumFractionDigits: 3 })

function dictLabel(name, value) {
  const hit = (dicts[name] || []).find((d) => d.key === String(value))
  return hit ? hit.label : (value ?? '—')
}
function sourceLabel(v) {
  const hit = (dicts.sourceStatus || []).find((d) => d.key === String(v))
  return hit ? hit.label : v
}
function roleLabel(r) {
  const hit = (dicts.teamRole || []).find((d) => d.key === r)
  return hit ? hit.label : r
}
function trackLabel(a) {
  const hit = (dicts.trackAction || []).find((d) => d.key === a)
  return hit ? hit.label : a
}
const statusTag = (s) => ({ 0: 'info', 1: 'warning', 2: 'warning', 3: 'success', 4: 'warning',
  5: 'danger', 6: 'danger', 7: 'success', 8: 'success' })[s] ?? 'info'

async function load() {
  loading.value = true
  try {
    const res = await pageDecorations({ ...filters,
      orderNo: filters.orderNo || undefined, customerName: filters.customerName || undefined })
    rows.value = (res.data && res.data.rows) || []
    total.value = (res.data && res.data.total) || 0
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

async function searchCustomers(keyword) {
  if (!keyword) return
  customerLoading.value = true
  try {
    const res = await pageCustomers({ keyword, page: 1, size: 20 })
    customerOptions.value = (res.data && res.data.rows) || []
  } finally {
    customerLoading.value = false
  }
}

async function loadVillages() {
  const res = await pageVillages({ page: 1, size: 100 })
  villages.value = (res.data && res.data.rows) || []
}

async function loadHouses(villageId) {
  const res = await pageHouses({ villageId, page: 1, size: 100 })
  houses.value = (res.data && res.data.rows) || []
}

function openCreate() {
  form.value = { customerId: null, villageId: null, houseId: null, type: 0, designerId: null,
    salesmanId: null, depositYuan: 0, areaM2: null, houseType: '', style: '', note: '', remark: '' }
  customerOptions.value = []
  houses.value = []
  createDialog.value = true
}

async function onCreate() {
  const f = form.value
  if (!f.customerId || !f.houseId || f.designerId === null || f.salesmanId === null) {
    return ElMessage.warning('请补全客户/房屋/设计师/销售')
  }
  saving.value = true
  try {
    if (f.villageId) await loadHouses(f.villageId)
    await createDecoration({
      customerId: f.customerId, houseId: f.houseId, type: f.type,
      designerId: f.designerId, salesmanId: f.salesmanId,
      depositAmount: f.depositYuan > 0 ? Math.round(f.depositYuan * 1000) : null,
      innerArea: f.areaM2 ? Math.round(f.areaM2 * 1000) : null,
      houseType: f.houseType || null, style: f.style || null, note: f.note || null, remark: f.remark || null
    })
    ElMessage.success('装修单已登记')
    createDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '登记失败')
  } finally {
    saving.value = false
  }
}

async function openDetail(row) {
  const res = await fetchDecoration(row.id)
  detail.value = res.data
  detailDrawer.value = true
  loadCalibers()
}

// ---- 三口径表(A6) ----
const calibers = ref({ midpack: null, holonomy: null, inclusive: null })
const caliberDialog = ref(false)
const caliberKind = ref('midpack')
const caliberTitle = computed(() => ({ midpack: '建半包配比口径', holonomy: '建整装满减口径', inclusive: '建包干增项口径' })[caliberKind.value])
const midpackForm = ref({ materialRatePct: 20, serviceRatePct: 10, materialPriceYuan: 0 })
const holonomyForm = ref({ discountBaseYuan: 1000, discountPct: 90, manageFollow: false, machingPct: 15, serviceRatePct: 10 })
const inclusiveForm = ref({ commonUndisYuan: 0, commonDisYuan: 0, commonManageYuan: 0, commonTaxYuan: 0,
  metUndisYuan: 0, metDisYuan: 0, metManageYuan: 0, metTaxYuan: 0, metFlag: 1 })
const orderEditable = computed(() => detail.value && [0, 1, 2, 4].includes(detail.value.status))

const ppmView = (ppm) => (Number(ppm || 0) / 10000).toFixed(2) + '%'

async function loadCalibers() {
  if (!detail.value) return
  try {
    const res = await listCalibers(detail.value.id)
    calibers.value = res.data || { midpack: null, holonomy: null, inclusive: null }
  } catch (e) {
    calibers.value = { midpack: null, holonomy: null, inclusive: null }
  }
}

function openCaliberDialog(kind) {
  caliberKind.value = kind
  caliberDialog.value = true
}

async function onMakeCaliber() {
  if (!detail.value) return
  saving.value = true
  try {
    const id = detail.value.id
    if (caliberKind.value === 'midpack') {
      const f = midpackForm.value
      await makeMidpackCaliber(id, {
        materialRate: Math.round(f.materialRatePct * 10000),
        serviceRate: Math.round(f.serviceRatePct * 10000),
        materialPrice: f.materialRatePct === 0 ? Math.round(f.materialPriceYuan * 1000) : null
      })
    } else if (caliberKind.value === 'holonomy') {
      const f = holonomyForm.value
      await makeHolonomyCaliber(id, {
        discountBase: Math.round(f.discountBaseYuan * 1000),
        discount: Math.round(f.discountPct * 100),
        manageFollow: f.manageFollow ? 1 : 0,
        maching: Math.round(f.machingPct * 10000),
        serviceRate: Math.round(f.serviceRatePct * 10000)
      })
    } else {
      const f = inclusiveForm.value
      await makeInclusiveCaliber(id, {
        commonUndis: Math.round(f.commonUndisYuan * 1000), commonDis: Math.round(f.commonDisYuan * 1000),
        commonManage: Math.round(f.commonManageYuan * 1000), commonTax: Math.round(f.commonTaxYuan * 1000),
        metUndis: Math.round(f.metUndisYuan * 1000), metDis: Math.round(f.metDisYuan * 1000),
        metManage: Math.round(f.metManageYuan * 1000), metTax: Math.round(f.metTaxYuan * 1000),
        metFlag: f.metFlag
      })
    }
    ElMessage.success('口径已推导')
    caliberDialog.value = false
    await loadCalibers()
  } catch (e) {
    ElMessage.error(e.message || '推导失败')
  } finally {
    saving.value = false
  }
}

async function onConfirmCaliber(kind) {
  try {
    await confirmCaliber(detail.value.id, kind)
    ElMessage.success('口径已确认冻结')
    await loadCalibers()
  } catch (e) {
    ElMessage.error(e.message || '确认失败')
  }
}

async function act(promise, done) {
  try {
    await promise
    ElMessage.success(done || '操作成功')
    load()
    if (detail.value) {
      const res = await fetchDecoration(detail.value.id)
      detail.value = res.data
    }
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

const onPresign = (row) => act(presignDecoration(row.id), '已预签')
const onResign = (row) => act(resignDecoration(row.id), '已重签')
const onObsolete = (row) => act(obsoleteDecoration(row.id), '已废单')
const isAdmin = JSON.parse(localStorage.getItem('yz_roles') || '[]').includes('admin')

const ITEM_LABELS = {
  sign: '已签约', sales: '主材销售全部完结', construction: '决算节点全部完工',
  selection: '选材全部完成(预留)', decoPayment: '装修收款达标', salePayment: '销售收款达标'
}
const accountItems = computed(() => {
  const ck = accountCheck.value
  if (!ck || !ck.items) return []
  return Object.keys(ck.items).map((key) => {
    const it = ck.items[key]
    const d = it.detail || {}
    let text = ''
    if (key === 'sales') text = d.unfinish + ' 件未完结'
    else if (key === 'construction') text = d.total === 0
      ? '无施工单或无决算节点' : d.finished + '/' + d.total + ' 完工' + (d.firstPendingName ? '(未完:' + d.firstPendingName + ')' : '')
    else if (key === 'decoPayment' || key === 'salePayment') text = '收款 ' + d.ratio + '千分比 / 目标 ' + d.target + '千分比'
    else if (key === 'selection') text = '选材域插件未接入,恒不拦'
    return { key, label: ITEM_LABELS[key], enabled: it.enabled, status: it.status, detailText: text }
  })
})
const accountBlocked = computed(() =>
  accountCheck.value && accountCheck.value.checkEnable && accountCheck.value.overall === 2)

async function openAccount(row) {
  accountRow.value = row
  try {
    const res = await accountCheckDecoration(row.id)
    accountCheck.value = res.data
    accountDialog.value = true
  } catch (e) {
    ElMessage.error(e.message || '预检失败')
  }
}
async function onDoAccount() {
  saving.value = true
  try {
    await accountDecoration(accountRow.value.id)
    ElMessage.success('已决算')
    accountDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '决算失败')
  } finally {
    saving.value = false
  }
}

async function openAccConfig() {
  try {
    const res = await fetchDecorationConfig()
    const c = res.data || {}
    accConfig.value = {
      checkEnable: c.check_enable === 1, signEnable: c.check_sign_enable === 1,
      salesEnable: c.check_sales_enable === 1, constructionEnable: c.check_construction_enable === 1,
      selectionEnable: c.check_selection_enable === 1, decoPaymentEnable: c.check_deco_payment_enable === 1,
      salePaymentEnable: c.check_sale_payment_enable === 1,
      decoPaymentTarget: c.deco_payment_target, salePaymentTarget: c.sale_payment_target
    }
    configDialog.value = true
  } catch (e) {
    ElMessage.error(e.message || '配置加载失败')
  }
}
async function onSaveAccConfig() {
  saving.value = true
  try {
    await updateDecorationConfig({
      checkEnable: accConfig.value.checkEnable, signEnable: accConfig.value.signEnable,
      salesEnable: accConfig.value.salesEnable, constructionEnable: accConfig.value.constructionEnable,
      selectionEnable: accConfig.value.selectionEnable, decoPaymentEnable: accConfig.value.decoPaymentEnable,
      salePaymentEnable: accConfig.value.salePaymentEnable,
      decoPaymentTarget: accConfig.value.decoPaymentTarget, salePaymentTarget: accConfig.value.salePaymentTarget
    })
    ElMessage.success('决算检查配置已保存')
    configDialog.value = false
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}
const onFinish = (row) => act(finishDecoration(row.id), '已完工')
const onDrawlineConfirm = () => act(drawlineConfirmDecoration(detail.value.id), '放线已确认')

const onDesignConfirm = (variant) =>
  act(designConfirmDecoration(detail.value.id, { variant }),
    variant === 0 ? '合同方案已确认' : variant === 1 ? '已确认(微改)' : '已确认(大改)')

function openSign(row) {
  current.value = row
  signForm.value = { contractYuan: 0 }
  signDialog.value = true
}
async function onSign() {
  if (!(signForm.value.contractYuan > 0)) return ElMessage.warning('请填写合同总额')
  saving.value = true
  try {
    await signDecoration(current.value.id, { contractAmount: Math.round(signForm.value.contractYuan * 1000) })
    ElMessage.success('已签约,款线已按模板拆分')
    signDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '签约失败')
  } finally {
    saving.value = false
  }
}

function openTerminate(row) {
  current.value = row
  terminateForm.value = { assigneeId: null, reason: '' }
  if (users.value.length === 0) listOrgUsers().then((res) => { users.value = res.data || [] })
  terminateDialog.value = true
}
async function onTerminate() {
  if (!terminateForm.value.assigneeId) return ElMessage.warning('请选择审批人')
  saving.value = true
  try {
    await terminateDecoration(current.value.id, terminateForm.value)
    ElMessage.success('终止审批已提交')
    terminateDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '提交失败')
  } finally {
    saving.value = false
  }
}

function openMeasure() {
  measureForm.value = { planTime: null, remark: '' }
  measureDialog.value = true
}
async function onMeasureApply() {
  saving.value = true
  try {
    await applyDecorationMeasure(detail.value.id, measureForm.value)
    ElMessage.success('量房申请已提交')
    measureDialog.value = false
    const res = await fetchDecoration(detail.value.id)
    detail.value = res.data
  } catch (e) {
    ElMessage.error(e.message || '申请失败')
  } finally {
    saving.value = false
  }
}
const onMeasureSuccess = (m) => act(successDecorationMeasure(m.id), '量房成功,工程线已开工')
const onMeasureFail = (m) => act(failDecorationMeasure(m.id, {}), '量房失败(可重新申请)')

function openTransfer(role) {
  transferRole.value = role
  transferForm.value = { userId: null }
  if (users.value.length === 0) listOrgUsers().then((res) => { users.value = res.data || [] })
  transferDialog.value = true
}
async function onTransfer() {
  if (!transferForm.value.userId) return ElMessage.warning('请选择新归属人')
  saving.value = true
  try {
    await transferDecorationBelong(detail.value.id, {
      line: transferRole.value === 'designer' ? 0 : 1, userId: transferForm.value.userId })
    ElMessage.success('归属已转移')
    transferDialog.value = false
    const res = await fetchDecoration(detail.value.id)
    detail.value = res.data
  } catch (e) {
    ElMessage.error(e.message || '转移失败')
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  const decoration = addons.list.find((a) => a.id === 'decoration')
  const d = (decoration && decoration.dicts) || {}
  for (const key of Object.keys(dicts)) dicts[key] = d[key] || []
  listOrgUsers().then((res) => { users.value = res.data || [] })
  loadVillages()
  load()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; }
.tip { color: var(--yz-ink-50, #8a887f); font-size: 12px; }
.lines { font-size: 12px; color: var(--yz-ink-70, #55534c); }
.status-grid { display: flex; gap: 12px; }
.status-cell { flex: 1; border: 1px solid var(--yz-paper-3, #e6e2d8); padding: 8px; text-align: center; }
.status-name { font-size: 12px; color: var(--yz-ink-50, #8a887f); margin-bottom: 6px; }
.section { margin-top: 14px; }
.section-title { font-weight: 600; margin-bottom: 6px; border-left: 3px solid var(--yz-cinnabar, #b03a2e);
  padding-left: 8px; }
.kv { line-height: 1.9; font-size: 13px; }
.track { font-size: 12px; }
.track-time { color: var(--yz-ink-50, #8a887f); margin-right: 8px; }
</style>
