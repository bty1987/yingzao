<template>
  <div>
    <div class="toolbar">
      <el-input v-model="filters.orderNo" placeholder="装修单号" style="width: 150px" clearable @keyup.enter="load" />
      <el-input v-model="filters.customerName" placeholder="客户" style="width: 120px" clearable @keyup.enter="load" />
      <el-select v-model="filters.process" placeholder="竣工态" style="width: 110px" clearable>
        <el-option v-for="d in dicts.processStatus" :key="d.key" :label="d.label" :value="Number(d.key)" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <el-button @click="openApply">放线登记</el-button>
      <el-button @click="openRetroactive">显式补录</el-button>
      <el-button @click="openNodes">节点模板</el-button>
      <el-button @click="openTemplates">档案模板</el-button>
      <el-button @click="openConfig">验收门槛</el-button>
    </div>

    <el-table :data="rows" border stripe v-loading="loading" @row-click="openDetail">
      <el-table-column prop="order_no" label="装修单号" width="140" />
      <el-table-column prop="customer_name" label="客户" width="90" />
      <el-table-column prop="house_label" label="房屋" min-width="180" show-overflow-tooltip />
      <el-table-column label="当前节点" width="100" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.current_sort > 0" effect="plain">第{{ row.current_sort }}节点</el-tag>
          <span v-else class="tip">未开工</span>
        </template>
      </el-table-column>
      <el-table-column label="总工期" width="80" align="right">
        <template #default="{ row }">{{ days(row.period) }} 天</template>
      </el-table-column>
      <el-table-column prop="supervisor_name" label="监理" width="90">
        <template #default="{ row }">{{ row.supervisor_name || '—' }}</template>
      </el-table-column>
      <el-table-column label="竣工" width="90">
        <template #default="{ row }">
          <el-tag :type="row.process === 1 ? 'success' : 'info'" effect="plain">
            {{ dictLabel('processStatus', row.process) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="辅料库" width="90">
        <template #default="{ row }">
          <el-tag :type="row.accessory === 1 ? 'warning' : 'success'" size="small" effect="plain">
            {{ dictLabel('accessoryStatus', row.accessory) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="created_at" label="点火时间" width="150" />
    </el-table>
    <el-pagination layout="prev, pager, next, total" :total="total" :page-size="filters.size"
      :current-page="filters.page" @current-change="(p) => { filters.page = p; load() }" style="margin-top: 12px" />

    <!-- 放线登记 -->
    <el-dialog v-model="applyDialog" title="放线登记(开工点火)" width="480px">
      <el-form label-width="90px">
        <el-form-item label="装修单" required>
          <el-select v-model="applyForm.orderId" filterable remote :remote-method="searchOrders"
            :loading="orderLoading" placeholder="输入装修单号搜索" style="width: 100%">
            <el-option v-for="o in orderOptions" :key="o.id" :value="o.id"
              :label="o.order_no + ' ' + o.customer_name" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明">
          <span class="tip">前置:无进行中放线 + 存在已锁定报价;排队后由放线小组排期</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="applyDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onApply">登记</el-button>
      </template>
    </el-dialog>

    <!-- 显式补录 -->
    <el-dialog v-model="retroDialog" title="显式补录(免审后门正名,带审计)" width="520px">
      <el-form label-width="100px">
        <el-form-item label="装修单" required>
          <el-select v-model="retroForm.orderId" filterable remote :remote-method="searchOrders"
            :loading="orderLoading" placeholder="输入装修单号搜索" style="width: 100%">
            <el-option v-for="o in orderOptions" :key="o.id" :value="o.id"
              :label="o.order_no + ' ' + o.customer_name" />
          </el-select>
        </el-form-item>
        <el-form-item label="放线小组" required>
          <el-input v-model="retroForm.groupName" />
        </el-form-item>
        <el-form-item label="起止时间" required>
          <el-date-picker v-model="retroForm.range" type="datetimerange" value-format="YYYY-MM-DDTHH:mm:ss"
            style="width: 100%" />
        </el-form-item>
        <el-form-item label="补录说明">
          <el-input v-model="retroForm.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="retroDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onRetroactive">补录并点火</el-button>
      </template>
    </el-dialog>

    <!-- 排期 -->
    <el-dialog v-model="scheduleDialog" title="放线排期(指派小组+起止时间)" width="480px">
      <el-form label-width="100px">
        <el-form-item label="放线小组" required>
          <el-input v-model="scheduleForm.groupName" />
        </el-form-item>
        <el-form-item label="起止时间" required>
          <el-date-picker v-model="scheduleForm.range" type="datetimerange" value-format="YYYY-MM-DDTHH:mm:ss"
            style="width: 100%" />
        </el-form-item>
        <el-form-item label="说明">
          <span class="tip">同小组时间重叠将被拒绝(真查已排期单,修老失效查重)</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="scheduleDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSchedule">排期</el-button>
      </template>
    </el-dialog>

    <!-- 免检 -->
    <el-dialog v-model="checkoutDialog" title="免检放行(比例闸绕过,必须留痕)" width="460px">
      <el-form label-width="90px">
        <el-form-item label="节点">
          <span>{{ checkoutForm.nodeName }}</span>
        </el-form-item>
        <el-form-item label="免检理由" required>
          <el-input v-model="checkoutForm.reason" type="textarea" :rows="2"
            placeholder="必填;免检操作人将记入轨迹" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="checkoutDialog = false">取消</el-button>
        <el-button type="warning" :loading="saving" @click="onCheckout">确认免检</el-button>
      </template>
    </el-dialog>

    <!-- 工人管理 -->
    <el-dialog v-model="workerDialog" :title="'施工工人 — ' + (workerForm.scheduleName || '')" width="720px">
      <div class="toolbar">
        <el-select v-model="workerForm.userId" filterable placeholder="选择工人(用户)" style="width: 220px">
          <el-option v-for="u in users" :key="u.id" :value="u.id" :label="u.display_name" />
        </el-select>
        <el-button type="primary" size="small" @click="onAddWorker">指派</el-button>
      </div>
      <el-table :data="workers" border size="small">
        <el-table-column prop="user_name" label="工人" width="90" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">{{ dictLabel('workerStatus', row.status) }}</template>
        </el-table-column>
        <el-table-column label="在场" width="90">
          <template #default="{ row }">{{ dictLabel('workStatus', row.work_status) }}</template>
        </el-table-column>
        <el-table-column label="评分(质/服/卫)" width="130" align="center">
          <template #default="{ row }">
            <span v-if="row.status === 3">{{ row.score1 }}/{{ row.score2 }}/{{ row.score3 }}</span>
            <span v-else class="tip">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="assessment" label="评语" min-width="110" show-overflow-tooltip />
        <el-table-column label="操作" width="260">
          <template #default="{ row }">
            <el-button v-if="row.status === 0" link size="small" @click="onEnter(row)">进场</el-button>
            <el-button v-if="row.work_status === 1" link size="small" @click="onLeave(row)">出场</el-button>
            <el-button v-if="row.status === 0" link size="small" type="primary" @click="onSendRate(row)">发评分</el-button>
            <el-button v-if="row.status === 2" link size="small" type="success" @click="openRate(row)">评分</el-button>
            <el-button v-if="row.status === 0" link size="small" type="danger" @click="onDisableWorker(row)">停用</el-button>
            <el-button link size="small" @click="onRecords(row)">流水</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 评分 -->
    <el-dialog v-model="rateDialog" title="客户评分(三率 1-5 星)" width="420px" append-to-body>
      <el-form label-width="80px">
        <el-form-item v-for="k in ['score1', 'score2', 'score3']" :key="k" :label="rateLabels[k]" required>
          <el-rate v-model="rateForm[k]" />
        </el-form-item>
        <el-form-item label="评语">
          <el-input v-model="rateForm.assessment" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rateDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onRate">提交评分</el-button>
      </template>
    </el-dialog>

    <!-- 施工日志 -->
    <el-dialog v-model="diaryDialog" :title="'施工日志 — ' + (workerForm.scheduleName || '')" width="640px">
      <div class="toolbar">
        <el-input v-model="diaryForm.content" placeholder="日志内容(首笔自动判进场,次笔出场)" style="flex: 1" />
        <el-checkbox v-model="diaryForm.hasDrawing">带图</el-checkbox>
        <el-button type="primary" size="small" @click="onAddDiary">登记</el-button>
      </div>
      <el-table :data="diaries" border size="small">
        <el-table-column prop="created_at" label="时间" width="150" />
        <el-table-column prop="user_name" label="登记人" width="90" />
        <el-table-column label="类型" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.atttype === 1" size="small" type="success" effect="plain">进场</el-tag>
            <el-tag v-else-if="row.atttype === 2" size="small" type="warning" effect="plain">出场</el-tag>
            <el-tag v-else size="small" effect="plain">日志</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="content" label="内容" min-width="200" show-overflow-tooltip />
      </el-table>
    </el-dialog>

    <!-- 节点模板管理 -->
    <el-dialog v-model="nodeDialog" title="节点模板(组织施工流,快照式)" width="880px">
      <div class="toolbar">
        <el-button type="primary" size="small" @click="openNodeForm()">新建节点</el-button>
        <el-checkbox v-model="nodeIncludeDisabled" @change="loadNodes">显示停用</el-checkbox>
        <span class="tip">点火时按启用的节点快照生成排期;模板后续修改不影响已生成单</span>
      </div>
      <el-table :data="nodes" border size="small">
        <el-table-column prop="sort" label="顺序" width="60" />
        <el-table-column prop="name" label="节点" width="110" />
        <el-table-column label="开工闸" width="80" align="right">
          <template #default="{ row }">{{ permille(row.precheck) }}‰</template>
        </el-table-column>
        <el-table-column label="完工闸" width="80" align="right">
          <template #default="{ row }">{{ permille(row.postcheck) }}‰</template>
        </el-table-column>
        <el-table-column label="工期" width="80" align="right">
          <template #default="{ row }">{{ days(row.time_limit) }} 天</template>
        </el-table-column>
        <el-table-column label="工费上限" width="100" align="right">
          <template #default="{ row }">¥{{ yuan(row.salary_limit) }}</template>
        </el-table-column>
        <el-table-column label="辅料限额" width="100" align="right">
          <template #default="{ row }">¥{{ yuan(row.accessory_limit) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small" effect="plain">
              {{ row.status === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <el-button link size="small" @click="openNodeForm(row)">编辑</el-button>
            <el-button link size="small" @click="onNodeStatus(row)">{{ row.status === 1 ? '停用' : '启用' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 节点编辑 -->
    <el-dialog v-model="nodeFormDialog" :title="nodeForm.id ? '编辑节点' : '新建节点'" width="560px" append-to-body>
      <el-form :model="nodeForm" label-width="110px">
        <el-form-item label="节点名" required>
          <el-input v-model="nodeForm.name" placeholder="如 水电/瓦工/油工" />
        </el-form-item>
        <el-form-item label="施工顺序" required>
          <el-input-number v-model="nodeForm.sort" :min="1" />
        </el-form-item>
        <el-form-item label="开工比例闸‰">
          <el-input-number v-model="nodeForm.precheck" :min="0" :max="1000" />
          <span class="tip">0 = 不校验;完工闸 postcheck 同</span>
          <el-input-number v-model="nodeForm.postcheck" :min="0" :max="1000" style="margin-left: 8px" />
        </el-form-item>
        <el-form-item label="工期(‰天)">
          <el-input-number v-model="nodeForm.timeLimit" :min="0" :step="1000" />
          <span class="tip">1000 = 1 天</span>
        </el-form-item>
        <el-form-item label="工费上限(元)">
          <el-input-number v-model="nodeForm.salaryLimitYuan" :min="0" :precision="0" />
          <span class="tip">‰元落库;999999000 = 无上限</span>
        </el-form-item>
        <el-form-item label="辅料限额(元)">
          <el-input-number v-model="nodeForm.accessoryLimitYuan" :min="0" :precision="0" />
        </el-form-item>
        <el-form-item label="开工话术">
          <el-input v-model="nodeForm.startTalk" />
        </el-form-item>
        <el-form-item label="完工话术">
          <el-input v-model="nodeForm.endTalk" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="nodeFormDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSaveNode">保存</el-button>
      </template>
    </el-dialog>

    <!-- 档案模板管理 -->
    <el-dialog v-model="templateDialog" title="工程档案模板(放线/施工/验收检查项)" width="880px">
      <div class="toolbar">
        <el-radio-group v-model="templateMount" @change="loadTemplates">
          <el-radio-button v-for="d in dicts.profileMount" :key="d.key" :value="Number(d.key)">
            {{ d.label }}</el-radio-button>
        </el-radio-group>
        <el-button type="primary" size="small" @click="openTemplateForm">新建模板</el-button>
      </div>
      <div v-for="t in templates" :key="t.id" class="section">
        <div class="section-title">
          {{ t.name }}
          <el-popconfirm title="删除该模板?" @confirm="onDeleteTemplate(t)">
            <template #reference>
              <el-button link type="danger" size="small" style="float: right">删模板</el-button>
            </template>
          </el-popconfirm>
          <el-button link size="small" style="float: right" @click="openItemForm(t.id)">加检查项</el-button>
        </div>
        <el-table :data="t.items" border size="small">
          <el-table-column prop="name" label="项目" min-width="160" show-overflow-tooltip />
          <el-table-column label="类型" width="80">
            <template #default="{ row }">{{ dictLabel('profileItemType', row.type) }}</template>
          </el-table-column>
          <el-table-column label="必填" width="60" align="center">
            <template #default="{ row }">{{ row.require === 1 ? '是' : '否' }}</template>
          </el-table-column>
          <el-table-column label="选项" min-width="160">
            <template #default="{ row }">
              {{ (row.options || []).map((o) => o.title).join(' / ') || '—' }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80">
            <template #default="{ row }">
              <el-popconfirm title="删除该项?" @confirm="onDeleteTemplateItem(t, row)">
                <template #reference>
                  <el-button link type="danger" size="small">删</el-button>
                </template>
              </el-popconfirm>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-dialog>

    <!-- 模板项编辑 -->
    <el-dialog v-model="itemFormDialog" title="加检查项" width="520px" append-to-body>
      <el-form :model="itemForm" label-width="90px">
        <el-form-item label="项目名" required>
          <el-input v-model="itemForm.name" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="itemForm.type" style="width: 160px" @change="itemForm.optionTitles = ''">
            <el-option v-for="d in dicts.profileItemType" :key="d.key" :label="d.label" :value="Number(d.key)" />
          </el-select>
        </el-form-item>
        <el-form-item label="必填">
          <el-switch v-model="itemForm.require" />
        </el-form-item>
        <el-form-item v-if="[2, 3].includes(itemForm.type)" label="选项">
          <el-input v-model="itemForm.optionTitles" placeholder="用 / 分隔,如 合格 / 不合格" />
        </el-form-item>
        <el-form-item v-else label="预置内容">
          <el-input v-model="itemForm.content" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="itemFormDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onAddTemplateItem">保存</el-button>
      </template>
    </el-dialog>

    <!-- 验收门槛 -->
    <el-dialog v-model="configDialog" title="验收门槛(组织级)" width="420px">
      <el-form label-width="120px">
        <el-form-item label="收款比例门槛‰">
          <el-input-number v-model="configForm.acceptanceFloor" :min="0" :max="1000" />
          <span class="tip">800 = 80%</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="configDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSaveConfig">保存</el-button>
      </template>
    </el-dialog>

    <!-- 验收提交 -->
    <el-dialog v-model="acceptDialog" title="验收登记(整改分支:存在不合格项可重提)" width="640px">
      <div class="toolbar">
        <el-select v-model="acceptForm.templateId" clearable placeholder="从验收模板带出检查项" style="width: 260px">
          <el-option v-for="t in acceptanceTemplates" :key="t.id" :value="t.id" :label="t.name" />
        </el-select>
        <el-button size="small" @click="applyAcceptTemplate">带出</el-button>
        <el-button size="small" @click="acceptForm.items.push({ name: '', pass: 1, remark: '' })">加一项</el-button>
      </div>
      <el-table :data="acceptForm.items" border size="small">
        <el-table-column label="检查项" min-width="160">
          <template #default="{ row }"><el-input v-model="row.name" /></template>
        </el-table-column>
        <el-table-column label="结果" width="110">
          <template #default="{ row }">
            <el-radio-group v-model="row.pass">
              <el-radio :value="1">合格</el-radio>
              <el-radio :value="0">不合格</el-radio>
            </el-radio-group>
          </template>
        </el-table-column>
        <el-table-column label="备注" min-width="140">
          <template #default="{ row }"><el-input v-model="row.remark" /></template>
        </el-table-column>
        <el-table-column label="操作" width="70">
          <template #default="{ $index }">
            <el-button link type="danger" size="small" @click="acceptForm.items.splice($index, 1)">删</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-input v-model="acceptForm.remark" placeholder="验收备注" style="margin-top: 10px" />
      <template #footer>
        <el-button @click="acceptDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onAccept">提交验收</el-button>
      </template>
    </el-dialog>

    <!-- 档案填写 -->
    <el-dialog v-model="profileFillDialog" :title="'档案填写 — ' + (profileFill.name || '')" width="640px"
      append-to-body>
      <div v-for="item in profileFill.items" :key="item.id" class="profile-item">
        <div class="profile-name">{{ item.name }}<span v-if="item.require === 1" class="required">*</span></div>
        <template v-if="[2, 3].includes(item.type)">
          <el-checkbox-group v-model="profileFill.checked[item.id]">
            <el-checkbox v-for="o in item.options" :key="o.id" :value="o.id">{{ o.title }}</el-checkbox>
          </el-checkbox-group>
        </template>
        <el-input v-else-if="item.type === 0" v-model="profileFill.contents[item.id]" type="textarea" :rows="2" />
        <el-date-picker v-else-if="item.type === 4" v-model="profileFill.contents[item.id]" type="date"
          value-format="YYYY-MM-DD" />
        <el-input v-else v-model="profileFill.contents[item.id]" />
      </div>
      <template #footer>
        <el-button @click="profileFillDialog = false">取消</el-button>
        <el-button :loading="saving" @click="onSaveProfileItems">保存填写</el-button>
        <el-button type="primary" :loading="saving" @click="onCompleteProfile">完成档案</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailDrawer" size="760px" :title="detail ? '施工单 ' + detail.order_no : '施工详情'">
      <template v-if="detail">
        <div class="status-grid">
          <div class="status-cell">
            <div class="status-name">竣工</div>
            <el-tag :type="detail.process === 1 ? 'success' : 'info'" effect="plain">
              {{ dictLabel('processStatus', detail.process) }}</el-tag>
          </div>
          <div class="status-cell">
            <div class="status-name">当前节点</div>
            <div>{{ detail.current_sort > 0 ? '第' + detail.current_sort + '节点' : '未开工' }}</div>
          </div>
          <div class="status-cell">
            <div class="status-name">总工期</div>
            <div>{{ days(detail.period) }} 天</div>
          </div>
          <div class="status-cell">
            <div class="status-name">监理</div>
            <div>{{ detail.supervisor_name || '—' }}</div>
          </div>
        </div>
        <el-descriptions :column="2" border size="small" style="margin-top: 12px">
          <el-descriptions-item label="装修单">{{ detail.order_no }}</el-descriptions-item>
          <el-descriptions-item label="客户">{{ detail.customer_name }}</el-descriptions-item>
          <el-descriptions-item label="房屋" :span="2">{{ detail.house_label }}</el-descriptions-item>
        </el-descriptions>

        <div class="section">
          <div class="section-title">流转操作</div>
          <el-select v-model="supervisorId" filterable placeholder="指定监理" style="width: 160px" size="small">
            <el-option v-for="u in users" :key="u.id" :value="u.id" :label="u.display_name" />
          </el-select>
          <el-button size="small" style="margin-left: 8px" @click="onSupervisor">更监理</el-button>
          <el-button v-if="detail.accessory === 0" size="small" type="warning"
            @click="onAccessory(true)">锁辅料库</el-button>
          <el-button v-else size="small" @click="onAccessory(false)">开辅料库</el-button>
          <el-button size="small" @click="openReset">排期重排</el-button>
        </div>

        <div class="section">
          <div class="section-title">放线单</div>
          <el-table :data="detail.drawlines" border size="small">
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="{ 0: 'info', 1: 'warning', 2: 'success', 3: 'danger' }[row.status]" effect="plain">
                  {{ dictLabel('drawlineStatus', row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="group_name" label="小组" width="90" />
            <el-table-column prop="plan_start" label="计划开始" width="150" />
            <el-table-column prop="plan_end" label="计划结束" width="150" />
            <el-table-column label="补录" width="60" align="center">
              <template #default="{ row }"><span v-if="row.is_retroactive === 1" class="default-mark">补</span></template>
            </el-table-column>
            <el-table-column prop="operator_name" label="申请人" width="90" />
            <el-table-column label="操作" width="150">
              <template #default="{ row }">
                <el-button v-if="row.status === 0" link size="small" type="primary"
                  @click="openSchedule(row)">排期</el-button>
                <el-button v-if="row.status === 1" link size="small" type="success"
                  @click="onDrawlineSuccess(row)">成功</el-button>
                <el-button v-if="row.status === 0 || row.status === 1" link size="small" type="danger"
                  @click="onDrawlineFail(row)">失败</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div class="section">
          <div class="section-title">节点排期(‰天单一口径)</div>
          <el-table :data="detail.schedules" border size="small">
            <el-table-column prop="sort" label="序" width="46" />
            <el-table-column prop="node_name" label="节点" width="90" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="{ 0: 'info', 1: 'warning', 2: 'success', 3: 'warning' }[row.status]" effect="plain">
                  {{ dictLabel('scheduleStatus', row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="计划" width="170">
              <template #default="{ row }">
                <div class="tip">{{ short(row.plan_start) }} → {{ short(row.plan_end) }}</div>
              </template>
            </el-table-column>
            <el-table-column label="实际" width="170">
              <template #default="{ row }">
                <div class="tip">{{ short(row.start_time) }} → {{ short(row.end_time) }}</div>
              </template>
            </el-table-column>
            <el-table-column label="免检" width="60" align="center">
              <template #default="{ row }">
                <el-tooltip v-if="row.checkout === 1" :content="row.checkout_reason">
                  <span class="default-mark">免</span>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="240">
              <template #default="{ row }">
                <el-button v-if="row.status === 0" link size="small" type="primary" @click="onBegin(row)">开工</el-button>
                <el-button v-if="row.status === 1" link size="small" @click="onOpenSalary(row)">开放工费</el-button>
                <el-button v-if="row.status === 1 || row.status === 3" link size="small" type="success"
                  @click="onFinish(row)">完工</el-button>
                <el-button v-if="row.status !== 0 && row.precheck > 0 && row.checkout === 0" link size="small"
                  type="warning" @click="openCheckout(row)">免检</el-button>
                <el-button v-if="row.status !== 0" link size="small" @click="openWorkers(row)">工人</el-button>
                <el-button link size="small" @click="openDiary(row)">日志</el-button>
                <el-popconfirm v-if="row.status === 0" title="删除该节点?" @confirm="onDeleteSchedule(row)">
                  <template #reference>
                    <el-button link type="danger" size="small">删</el-button>
                  </template>
                </el-popconfirm>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div class="section">
          <div class="section-title">工程档案</div>
          <div class="toolbar">
            <el-select v-model="profileTemplateId" placeholder="从模板实例化" style="width: 220px" size="small"
              clearable>
              <el-option v-for="t in allTemplates" :key="t.id" :value="t.id"
                :label="mountLabel(t.mount) + ' · ' + t.name" />
            </el-select>
            <el-button size="small" @click="onInstantiate">实例化</el-button>
          </div>
          <div v-for="p in orderProfiles" :key="p.id" class="kv">
            {{ mountLabel(p.mount) }} · {{ p.name }}
            <el-tag size="small" effect="plain" style="margin-left: 6px">{{ dictLabel('profileStatus', p.status) }}</el-tag>
            <el-button v-if="p.status !== 2" link size="small" style="margin-left: 8px"
              @click="openProfileFill(p)">填写</el-button>
          </div>
          <div v-if="!orderProfiles.length" class="tip">暂无档案实例</div>
        </div>

        <div class="section">
          <div class="section-title">验收</div>
          <template v-if="detail.acceptance">
            <div class="kv">
              <el-tag :type="detail.acceptance.status === 1 ? 'success' : 'warning'" effect="plain">
                {{ dictLabel('acceptanceStatus', detail.acceptance.status) }}</el-tag>
              <span style="margin-left: 8px">{{ detail.acceptance.checker_name }}</span>
              <span class="tip" style="margin-left: 8px">{{ detail.acceptance.check_time || '' }}</span>
            </div>
            <div v-for="(item, i) in detail.acceptanceItems || []" :key="i" class="kv">
              {{ item.pass === 1 ? '✓' : '✗' }} {{ item.name }}
              <span v-if="item.remark" class="tip">({{ item.remark }})</span>
            </div>
            <el-button v-if="detail.acceptance.status === 0 && detail.process === 0" size="small" type="primary"
              style="margin-top: 8px" @click="openAccept">整改后重新提交</el-button>
          </template>
          <el-button v-if="!detail.acceptance && detail.process === 0" size="small" type="primary"
            @click="openAccept">提交验收</el-button>
          <div v-if="!detail.acceptance && detail.process === 1" class="tip">已竣工(历史验收单归档)</div>
        </div>

        <div class="section">
          <div class="section-title">操作轨迹</div>
          <div v-for="(t, i) in detail.tracks" :key="i" class="kv track">
            <span class="track-time">{{ t.created_at }}</span>
            {{ trackLabel(t.action) }}
            <span v-if="t.detail"> | {{ t.detail }}</span>
            <span v-if="t.operator_name"> | {{ t.operator_name }}</span>
          </div>
          <div v-if="!detail.tracks.length" class="tip">暂无轨迹</div>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, ref, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  pageConstructions, fetchConstruction, updateConstructionSupervisor, switchConstructionAccessory,
  submitConstructionAcceptance, fetchConstructionAcceptance, resetConstructionSchedules,
  applyConstructionDrawline, scheduleConstructionDrawline, successConstructionDrawline,
  failConstructionDrawline, retroactiveConstructionDrawline,
  beginConstructionSchedule, finishConstructionSchedule, openConstructionSalary,
  checkoutConstructionSchedule, deleteConstructionSchedule,
  addConstructionWorker, listConstructionWorkers, disableConstructionWorker, sendConstructionRateLink,
  rateConstructionWorker, enterConstructionWorker, leaveConstructionWorker, listConstructionRecords,
  addConstructionDiary, listConstructionDiaries,
  listConstructionNodes, createConstructionNode, updateConstructionNode, updateConstructionNodeStatus,
  fetchConstructionConfig, updateConstructionConfig,
  listConstructionProfileTemplates, createConstructionProfileTemplate, deleteConstructionProfileTemplate,
  addConstructionProfileTemplateItem, deleteConstructionProfileTemplateItem,
  instantiateConstructionProfile, listConstructionProfiles, fillConstructionProfileItem,
  completeConstructionProfile, pageDecorations, listOrgUsers
} from '../../api'
import { addons } from '../../store/addons'

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const filters = reactive({ orderNo: '', customerName: '', process: null, page: 1, size: 20 })
const dicts = reactive({
  drawlineStatus: [], scheduleStatus: [], workStatus: [], workerStatus: [], processStatus: [],
  accessoryStatus: [], profileMount: [], profileStatus: [], profileItemType: [], acceptanceStatus: [],
  trackAction: []
})
const users = ref([])
const orderOptions = ref([])
const orderLoading = ref(false)

const applyDialog = ref(false)
const retroDialog = ref(false)
const scheduleDialog = ref(false)
const checkoutDialog = ref(false)
const workerDialog = ref(false)
const rateDialog = ref(false)
const diaryDialog = ref(false)
const nodeDialog = ref(false)
const nodeFormDialog = ref(false)
const templateDialog = ref(false)
const itemFormDialog = ref(false)
const configDialog = ref(false)
const acceptDialog = ref(false)
const profileFillDialog = ref(false)
const detailDrawer = ref(false)
const detail = ref(null)

const applyForm = ref({ orderId: null })
const retroForm = ref({ orderId: null, groupName: '', range: [], remark: '' })
const scheduleForm = ref({ id: null, groupName: '', range: [] })
const checkoutForm = ref({ id: null, nodeName: '', reason: '' })
const workerForm = ref({ scheduleId: null, scheduleName: '', userId: null })
const workers = ref([])
const rateForm = ref({ id: null, score1: 5, score2: 5, score3: 5, assessment: '' })
const rateLabels = { score1: '质量', score2: '服务', score3: '卫生' }
const diaryForm = ref({ content: '', hasDrawing: false })
const diaries = ref([])
const nodes = ref([])
const nodeIncludeDisabled = ref(false)
const nodeForm = ref({})
const templates = ref([])
const allTemplates = ref([])
const templateMount = ref(2)
const itemForm = ref({ templateId: null, name: '', type: 2, require: true, content: '', optionTitles: '合格 / 不合格' })
const configForm = ref({ acceptanceFloor: 800 })
const acceptForm = ref({ items: [], remark: '' })
const acceptanceTemplates = ref([])
const supervisorId = ref(null)
const profileTemplateId = ref(null)
const orderProfiles = ref([])
const profileFill = ref({ id: null, name: '', items: [], contents: {}, checked: {} })

const days = (milli) => (Number(milli || 0) / 1000).toLocaleString('zh-CN')
const yuan = (li) => (Number(li || 0) / 1000).toLocaleString('zh-CN')
const permille = (v) => Number(v || 0)
const short = (t) => (t ? String(t).replace('T', ' ').slice(0, 16) : '—')

function dictLabel(name, value) {
  const hit = (dicts[name] || []).find((d) => d.key === String(value))
  return hit ? hit.label : (value ?? '—')
}
function trackLabel(a) {
  const hit = (dicts.trackAction || []).find((d) => d.key === a)
  return hit ? hit.label : a
}
function mountLabel(m) { return dictLabel('profileMount', m) }

async function load() {
  loading.value = true
  try {
    const res = await pageConstructions({ ...filters, orderNo: filters.orderNo || undefined })
    rows.value = (res.data && res.data.rows) || []
    total.value = (res.data && res.data.total) || 0
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

async function loadUsers() {
  const res = await listOrgUsers()
  users.value = res.data || []
}

async function searchOrders(keyword) {
  if (!keyword) return
  orderLoading.value = true
  try {
    const res = await pageDecorations({ orderNo: keyword, page: 1, size: 20 })
    orderOptions.value = (res.data && res.data.rows) || []
  } finally {
    orderLoading.value = false
  }
}

function openApply() {
  applyForm.value = { orderId: null }
  orderOptions.value = []
  applyDialog.value = true
}

async function onApply() {
  if (!applyForm.value.orderId) return ElMessage.warning('请选择装修单')
  saving.value = true
  try {
    await applyConstructionDrawline({ orderId: applyForm.value.orderId })
    ElMessage.success('放线已登记(排队)')
    applyDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '登记失败')
  } finally {
    saving.value = false
  }
}

function openRetroactive() {
  retroForm.value = { orderId: null, groupName: '', range: [], remark: '' }
  orderOptions.value = []
  retroDialog.value = true
}

async function onRetroactive() {
  const f = retroForm.value
  if (!f.orderId || !f.groupName || !f.range || f.range.length < 2) return ElMessage.warning('请补全补录信息')
  saving.value = true
  try {
    await retroactiveConstructionDrawline({
      orderId: f.orderId, groupName: f.groupName, planStart: f.range[0], planEnd: f.range[1],
      remark: f.remark || null
    })
    ElMessage.success('补录完成,已点火')
    retroDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '补录失败')
  } finally {
    saving.value = false
  }
}

function openSchedule(row) {
  scheduleForm.value = { id: row.id, groupName: row.group_name || '', range: [] }
  scheduleDialog.value = true
}

async function onSchedule() {
  const f = scheduleForm.value
  if (!f.groupName || !f.range || f.range.length < 2) return ElMessage.warning('请补全小组与起止时间')
  saving.value = true
  try {
    await scheduleConstructionDrawline(f.id, { groupName: f.groupName, planStart: f.range[0], planEnd: f.range[1] })
    ElMessage.success('排期完成')
    scheduleDialog.value = false
    await refreshDetail()
  } catch (e) {
    ElMessage.error(e.message || '排期失败')
  } finally {
    saving.value = false
  }
}

async function onDrawlineSuccess(row) {
  try {
    await successConstructionDrawline(row.id)
    ElMessage.success('放线成功,已点火生成施工单')
    await refreshDetail()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function onDrawlineFail(row) {
  try {
    await failConstructionDrawline(row.id, { remark: '现场放弃' })
    ElMessage.success('已置失败(设计师将收到通知)')
    await refreshDetail()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function openDetail(row) {
  const res = await fetchConstruction(row.order_id)
  detail.value = res.data
  detailDrawer.value = true
  supervisorId.value = null
  profileTemplateId.value = null
  loadProfiles()
}

async function refreshDetail() {
  if (detail.value) {
    const res = await fetchConstruction(detail.value.order_id)
    detail.value = res.data
    loadProfiles()
  }
  load()
}

async function onSupervisor() {
  if (!supervisorId.value) return ElMessage.warning('请选择监理')
  try {
    await updateConstructionSupervisor(detail.value.order_id, { userId: supervisorId.value })
    ElMessage.success('监理已更新')
    await refreshDetail()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function onAccessory(lock) {
  try {
    await switchConstructionAccessory(detail.value.order_id, { lock })
    ElMessage.success(lock ? '辅料库已锁定' : '辅料库已开启')
    await refreshDetail()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function onBegin(row) {
  try {
    await beginConstructionSchedule(row.id)
    ElMessage.success('节点已开工')
    await refreshDetail()
  } catch (e) {
    if (Number(e.code) === 240006) openCheckout(row)
    else ElMessage.error(e.message || '操作失败')
  }
}

async function onFinish(row) {
  try {
    await finishConstructionSchedule(row.id)
    ElMessage.success('节点已完工')
    await refreshDetail()
  } catch (e) {
    if (Number(e.code) === 240006) openCheckout(row)
    else ElMessage.error(e.message || '操作失败')
  }
}

async function onOpenSalary(row) {
  try {
    await openConstructionSalary(row.id)
    ElMessage.success('已开放工费')
    await refreshDetail()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

function openCheckout(row) {
  checkoutForm.value = { id: row.id, nodeName: row.node_name, reason: '' }
  checkoutDialog.value = true
}

async function onCheckout() {
  if (!checkoutForm.value.reason) return ElMessage.warning('免检理由必填(留痕)')
  saving.value = true
  try {
    await checkoutConstructionSchedule(checkoutForm.value.id, { reason: checkoutForm.value.reason })
    ElMessage.success('免检已留痕,可重新开工/完工')
    checkoutDialog.value = false
    await refreshDetail()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  } finally {
    saving.value = false
  }
}

async function onDeleteSchedule(row) {
  try {
    await deleteConstructionSchedule(row.id)
    ElMessage.success('节点已删除')
    await refreshDetail()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

function openReset() {
  const running = (detail.value.schedules || []).find((s) => s.status !== 0)
  const anchor = running || (detail.value.schedules || [])[0]
  const anchorId = anchor ? anchor.id : null
  resetConstructionSchedules(detail.value.order_id, { anchorScheduleId: anchorId }).then(() => {
    ElMessage.success('已按锚点重排计划时间')
    refreshDetail()
  }).catch((e) => ElMessage.error(e.message || '重排失败'))
}

async function openWorkers(row) {
  workerForm.value = { scheduleId: row.id, scheduleName: row.node_name, userId: null }
  const res = await listConstructionWorkers(row.id)
  workers.value = res.data || []
  workerDialog.value = true
}

async function onAddWorker() {
  if (!workerForm.value.userId) return ElMessage.warning('请选择工人')
  saving.value = true
  try {
    await addConstructionWorker(workerForm.value.scheduleId, { userId: workerForm.value.userId })
    ElMessage.success('工人已指派')
    const res = await listConstructionWorkers(workerForm.value.scheduleId)
    workers.value = res.data || []
  } catch (e) {
    ElMessage.error(e.message || '指派失败')
  } finally {
    saving.value = false
  }
}

async function reloadWorkers() {
  const res = await listConstructionWorkers(workerForm.value.scheduleId)
  workers.value = res.data || []
}

async function onEnter(row) {
  try {
    await enterConstructionWorker(row.id)
    ElMessage.success('已进场')
    await reloadWorkers()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function onLeave(row) {
  try {
    await leaveConstructionWorker(row.id)
    ElMessage.success('已出场')
    await reloadWorkers()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function onSendRate(row) {
  try {
    await sendConstructionRateLink(row.id)
    ElMessage.success('评分已发送(状态置已发送评分)')
    await reloadWorkers()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

function openRate(row) {
  rateForm.value = { id: row.id, score1: 5, score2: 5, score3: 5, assessment: '' }
  rateDialog.value = true
}

async function onRate() {
  saving.value = true
  try {
    await rateConstructionWorker(rateForm.value.id, rateForm.value)
    ElMessage.success('评分已提交')
    rateDialog.value = false
    await reloadWorkers()
  } catch (e) {
    ElMessage.error(e.message || '评分失败')
  } finally {
    saving.value = false
  }
}

async function onDisableWorker(row) {
  try {
    await disableConstructionWorker(row.id)
    ElMessage.success('已停用')
    await reloadWorkers()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function onRecords(row) {
  const res = await listConstructionRecords(row.id)
  const text = (res.data || []).map((r) => (r.action === 1 ? '进场' : '出场') + ' ' + r.created_at).join('\n')
  ElMessage({ message: text || '暂无流水', type: 'info', duration: 5000, showClose: true })
}

async function openDiary(row) {
  workerForm.value = { scheduleId: row.id, scheduleName: row.node_name, userId: null }
  const res = await listConstructionDiaries(row.id)
  diaries.value = res.data || []
  diaryForm.value = { content: '', hasDrawing: false }
  diaryDialog.value = true
}

async function onAddDiary() {
  if (!diaryForm.value.content) return ElMessage.warning('请填写日志内容')
  saving.value = true
  try {
    await addConstructionDiary(workerForm.value.scheduleId, diaryForm.value)
    const res = await listConstructionDiaries(workerForm.value.scheduleId)
    diaries.value = res.data || []
    diaryForm.value = { content: '', hasDrawing: false }
  } catch (e) {
    ElMessage.error(e.message || '登记失败')
  } finally {
    saving.value = false
  }
}

async function loadNodes() {
  const res = await listConstructionNodes({ includeDisabled: nodeIncludeDisabled.value })
  nodes.value = res.data || []
}

function openNodes() {
  nodeIncludeDisabled.value = true
  loadNodes()
  nodeDialog.value = true
}

function openNodeForm(row) {
  nodeForm.value = row ? {
    id: row.id, name: row.name, sort: row.sort, precheck: row.precheck, postcheck: row.postcheck,
    timeLimit: row.time_limit, salaryLimitYuan: Number(row.salary_limit || 0) / 1000,
    accessoryLimitYuan: Number(row.accessory_limit || 0) / 1000,
    startTalk: row.start_talk || '', endTalk: row.end_talk || ''
  } : {
    id: null, name: '', sort: nodes.value.length + 1, precheck: 0, postcheck: 0, timeLimit: 0,
    salaryLimitYuan: 999999, accessoryLimitYuan: 999999, startTalk: '', endTalk: ''
  }
  nodeFormDialog.value = true
}

async function onSaveNode() {
  const f = nodeForm.value
  if (!f.name) return ElMessage.warning('请填写节点名')
  const payload = {
    name: f.name, sort: f.sort, process: 'main', precheck: f.precheck, postcheck: f.postcheck,
    stacheck: 0, fincheck: 0, timeLimit: f.timeLimit,
    salaryLimit: Math.round(f.salaryLimitYuan * 1000), accessoryLimit: Math.round(f.accessoryLimitYuan * 1000),
    startTalk: f.startTalk || null, endTalk: f.endTalk || null
  }
  saving.value = true
  try {
    if (f.id) await updateConstructionNode(f.id, payload)
    else await createConstructionNode(payload)
    ElMessage.success('节点已保存')
    nodeFormDialog.value = false
    loadNodes()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function onNodeStatus(row) {
  try {
    await updateConstructionNodeStatus(row.id, { status: row.status === 1 ? 0 : 1 })
    loadNodes()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function loadTemplates() {
  const res = await listConstructionProfileTemplates({ mount: templateMount.value })
  templates.value = res.data || []
  const all = await listConstructionProfileTemplates({})
  allTemplates.value = all.data || []
  acceptanceTemplates.value = (all.data || []).filter((t) => t.mount === 2)
}

function openTemplates() {
  templateMount.value = 2
  loadTemplates()
  templateDialog.value = true
}

function openTemplateForm() {
  const label = mountLabel(templateMount.value) || '模板'
  ElMessageBox.prompt('模板名称', '新建' + label).then(({ value }) => {
    if (!value) return
    createConstructionProfileTemplate({ mount: templateMount.value, name: value }).then(() => {
      ElMessage.success('模板已创建')
      loadTemplates()
    }).catch((e) => ElMessage.error(e.message || '创建失败'))
  }).catch(() => {})
}

async function onDeleteTemplate(t) {
  try {
    await deleteConstructionProfileTemplate(t.id)
    ElMessage.success('模板已删除')
    loadTemplates()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

function openItemForm(templateId) {
  itemForm.value = { templateId, name: '', type: 2, require: true, content: '', optionTitles: '合格 / 不合格' }
  itemFormDialog.value = true
}

async function onAddTemplateItem() {
  const f = itemForm.value
  if (!f.name) return ElMessage.warning('请填写项目名')
  const payload = {
    name: f.name, type: f.type, require: f.require ? 1 : 0,
    content: [2, 3].includes(f.type) ? null : (f.content || null),
    optionTitles: [2, 3].includes(f.type)
      ? f.optionTitles.split('/').map((s) => s.trim()).filter(Boolean)
      : null
  }
  saving.value = true
  try {
    await addConstructionProfileTemplateItem(f.templateId, payload)
    ElMessage.success('检查项已添加')
    itemFormDialog.value = false
    loadTemplates()
  } catch (e) {
    ElMessage.error(e.message || '添加失败')
  } finally {
    saving.value = false
  }
}

async function onDeleteTemplateItem(t, item) {
  try {
    await deleteConstructionProfileTemplateItem(t.id, item.id)
    loadTemplates()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

async function openConfig() {
  const res = await fetchConstructionConfig()
  configForm.value = { acceptanceFloor: (res.data && res.data.acceptance_floor) || 800 }
  configDialog.value = true
}

async function onSaveConfig() {
  saving.value = true
  try {
    await updateConstructionConfig(configForm.value)
    ElMessage.success('验收门槛已保存')
    configDialog.value = false
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

function openAccept() {
  const existing = detail.value.acceptanceItems || []
  acceptForm.value = {
    items: existing.length ? existing.map((i) => ({ name: i.name, pass: i.pass, remark: i.remark }))
      : [{ name: '', pass: 1, remark: '' }],
    remark: ''
  }
  acceptDialog.value = true
}

function applyAcceptTemplate() {
  const t = acceptanceTemplates.value.find((x) => x.id === acceptForm.value.templateId)
  if (!t) return
  acceptForm.value.items = (t.items || []).map((i) => ({ name: i.name, pass: 1, remark: '' }))
}

async function onAccept() {
  const items = acceptForm.value.items.filter((i) => i.name)
  if (!items.length) return ElMessage.warning('请至少填写一项检查项')
  saving.value = true
  try {
    await submitConstructionAcceptance(detail.value.order_id, { items, remark: acceptForm.value.remark || null })
    ElMessage.success('验收已提交')
    acceptDialog.value = false
    await refreshDetail()
  } catch (e) {
    ElMessage.error(e.message || '提交失败')
  } finally {
    saving.value = false
  }
}

async function loadProfiles() {
  const res = await listConstructionProfiles(detail.value.order_id)
  orderProfiles.value = res.data || []
}

async function onInstantiate() {
  if (!profileTemplateId.value) return ElMessage.warning('请选择模板')
  try {
    await instantiateConstructionProfile(detail.value.order_id, { templateId: profileTemplateId.value })
    ElMessage.success('档案已实例化')
    await loadProfiles()
  } catch (e) {
    ElMessage.error(e.message || '实例化失败')
  }
}

function openProfileFill(p) {
  const contents = {}
  const checked = {}
  for (const item of p.items || []) {
    contents[item.id] = item.content || ''
    checked[item.id] = (item.options || []).filter((o) => o.checked === 1).map((o) => o.id)
  }
  profileFill.value = { id: p.id, name: mountLabel(p.mount) + ' · ' + p.name, items: p.items || [],
    contents, checked }
  profileFillDialog.value = true
}

async function onSaveProfileItems() {
  saving.value = true
  try {
    for (const item of profileFill.value.items) {
      await fillConstructionProfileItem(profileFill.value.id, item.id, {
        content: [2, 3].includes(item.type) ? null : (profileFill.value.contents[item.id] || null),
        checkedOptionIds: [2, 3].includes(item.type) ? (profileFill.value.checked[item.id] || []) : null
      })
    }
    ElMessage.success('档案已保存')
    profileFillDialog.value = false
    await loadProfiles()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function onCompleteProfile() {
  saving.value = true
  try {
    await onSaveProfileItemsInner()
    await completeConstructionProfile(profileFill.value.id)
    ElMessage.success('档案已完成')
    profileFillDialog.value = false
    await loadProfiles()
  } catch (e) {
    ElMessage.error(e.message || '完成失败(必填项未填?)')
  } finally {
    saving.value = false
  }
}

async function onSaveProfileItemsInner() {
  for (const item of profileFill.value.items) {
    await fillConstructionProfileItem(profileFill.value.id, item.id, {
      content: [2, 3].includes(item.type) ? null : (profileFill.value.contents[item.id] || null),
      checkedOptionIds: [2, 3].includes(item.type) ? (profileFill.value.checked[item.id] || []) : null
    })
  }
}

onMounted(() => {
  const construction = addons.list.find((a) => a.id === 'construction')
  const d = (construction && construction.dicts) || {}
  for (const key of Object.keys(dicts)) dicts[key] = d[key] || []
  load()
  loadUsers()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; align-items: center; }
.tip { color: var(--yz-ink-50, #8a887f); font-size: 12px; }
.default-mark { color: var(--yz-cinnabar, #b03a2e); font-size: 12px; font-weight: 600; }
.status-grid { display: flex; gap: 12px; }
.status-cell { flex: 1; border: 1px solid var(--yz-paper-3, #e6e2d8); padding: 8px; text-align: center; }
.status-name { font-size: 12px; color: var(--yz-ink-50, #8a887f); margin-bottom: 6px; }
.section { margin-top: 14px; }
.section-title { font-weight: 600; margin-bottom: 6px; border-left: 3px solid var(--yz-cinnabar, #b03a2e);
  padding-left: 8px; overflow: hidden; }
.kv { line-height: 1.9; font-size: 13px; }
.track { font-size: 12px; }
.track-time { color: var(--yz-ink-50, #8a887f); margin-right: 8px; }
.profile-item { margin-bottom: 12px; }
.profile-name { font-size: 13px; font-weight: 600; margin-bottom: 4px; }
.required { color: var(--yz-cinnabar, #b03a2e); margin-left: 4px; }
</style>
