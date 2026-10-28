# 13 - 微信端产品矩阵归纳 PRD(反哺)

> 状态:草稿(2026-10-24,依据 `D:\Git\erp-wechat-mini` Codeup 快照 2026-10-20 全量盘点归纳)
> 老系统参考:方林 ERP 微信侧全家桶(4 个原生微信小程序 + 1 个公众号 H5,直连与钉钉端同一 ERP 后端)
> 本文是什么:微信侧产品矩阵的产品级归纳——**端矩阵定位、业务闭环、功能域×端明细、平台能力依赖**,以及按 01 判断法过堂后的**营造落点**(web-mp 客户端门户增补需求 + portal addon 扩展 + 删除项)。
> 与 12 号文档的关系:12 号切"员工在手机上用什么"(钉钉端),本文切"客户与获客在微信上用什么";两份合起来是老系统移动端全形态。员工微信小程序(Office)的归属判断见 §八。

---

## 一、业务闭环(矩阵一句话)

**客户/访客在微信里完成"被获客 → 确认 → 透明围观工地 → 付款 → 会员复购"的 C 端全旅程;员工用 Office 小程序在线下场景(报备/收款/核销/接单)发起,客户扫码进入 H5/小程序完成确认与支付**;全部端直连同一 ERP 后端(`.do` 接口 + `authentication` 令牌 + `company` 多租户),与钉钉端互为镜像。

## 二、端矩阵总表

| 端 | 名称 | 形态 | 服务对象 | 页面数 | 状态判断 |
|---|---|---|---|---|---|
| `Office` | 方林办公体验 | 原生小程序 | **员工**(业务/设计/监理/厂商/财务/人资) | 30+ | **核心资产**,功能最全;2014-2022 活跃 |
| `Applet` | 方林装饰 | 原生小程序 | C 端业主 | 声明 46,**快照仅存 4** | 主体缺失(tabBar 页不存在,无法编译);公众号菜单主入口 |
| `Public` | 方林装饰公众号 H5 | jQuery+WeUI+jweixin-1.2.0 | C 端业主 | 15+ 片段 | 2017 年技术栈,上一代载体,大概率停更;与 Applet 功能高度重叠 |
| `Store` | 商城 | 原生小程序 | C 端电商 | 18 | 完整含真实支付链路,但 01 判断法已判"store 模块=运营特性,删除" |
| `EasyLive` | 方林易居/方片 | 原生小程序 | 访客获客 + 员工电子名片 | 19 | 基础库 2.3.2 最老;获客表单+名片可用,mine/logs 空模板 |
| `RUL`(文件) | 开发者备忘 | — | — | — | 公众号菜单 JSON、OAuth 测试链接、威富通报文样例、**测试号 secret 明文(泄密风险)** |

> Applet 缺页不是"没做"而是快照不完整/已迁走的信号;其 42 个缺失页的功能可由 app.json 页面名 + Public H5 片段 + RUL 菜单互证还原(§四)。

## 三、用户与场景

| 角色 | 端 | 场景 | 高频动作 |
|---|---|---|---|
| 访客 | EasyLive / 公众号菜单 | 看案例/算价/预约/转名片 | 留资表单、案例浏览、分享 |
| 业主(签约前) | Public / Applet | 到店确认、量房画线确认、客户审批确认 | 扫码 → 确认/驳回 |
| 业主(履约中) | Public / Applet | 看进度/工地/板拍/图纸、发服务圈动态、群享 | 绑定订单、围观、确认节点 |
| 业主(资金) | Public / Applet / Store | 预付款/收款流水、扫码付款、代付、商城购买 | 查流水、`wx.requestPayment`/`wx.chooseWXPay` |
| 业主(售后) | Public / Applet | 报修、会员积分、电子会员卡核销、老带新 | 领积分、出示条码、推荐 |
| 员工-业务 | Office | 客源报备、跟进、收款码、代付码、活动票 | 录入、生成客户扫码二维码 |
| 员工-设计/监理 | Office | 订单查询、节点开竣工、派工与工费、辅料审核、换绑手机号 | 列表→详情→操作 |
| 员工-厂商 | Office | 选材项接单确认 | `confirmSelectionItem.do` |
| 员工-会务 | Office | 活动扫码核销到场、扫码发奖、活动款 | `wx.scanCode` 核销 |

## 四、核心业务闭环(状态机文字版)

1. **获客留资**:访客在 EasyLive 算价/预约表单 → `addEcustomer.do` 进线;老带新:业主在 Public `vustomer` 生成推荐海报二维码 → 好友扫码留资 → `addVustomer.do` + `buildVustomerNet.do` 推荐网 → 积分 `queryVscoreByUnionId.do`/领取 `pickupVscore.do`;
2. **客源报备→确认→转单**:员工 Office 录入 `addCustomerOrigin.do`(性别/A-D 评级/面积/销售/设计师)→ 生成**客户确认二维码**(`fanglin.vip/wx/customerorigine?csid=`)→ 客户微信扫码进 Public `customerSource` → `confirmCustomerOrigin.do` 确认到店 → 公海 `takeCustomerOrigin.do` / 审批 `submitApprovalForCustomerOrigin.do` → `bindCustomerOrigin.do` 转装修单;
3. **量房确认**:设计师传图 → 客户 Public `drawLine` 分步画线页逐张确认/驳回 → `confirmDecorationInfo.do`(sourceStatus 1/2);
4. **客户审批确认**:ERP 审批单产生 → 客户 Applet `approval` 扫码或列表进入 → 展示 17 类审批(客户信息修改/订单退款/主材退货/客源转装修单…)→ `confirmApproval.do`(状态含"5=客户确认")——这就是 11 号批次 C3"客户微信确认链"的方林原型;
5. **收款/支付**:员工 Office 录收款 `addPayment.do`,或生成**收款码**(`fanglin.vip/wx/wxpay?orderId=&pid=&paymentType=`)/**代付码**(`addProxyPayment.do` 后出二维码)→ 客户微信扫码 H5 付款;商城内购买走 `makeWPayment.do` → `wx.requestPayment`(prepay_id,MD5);公众号内 `wx.chooseWXPay`(JS-SDK);渠道含微信原生与**威富通(Swiftpass)**;ERP 侧 `addMpayment.do`/`addPayment.do` 记账,金额 ×1000 换算;
6. **施工透明**:监理 Office 操作节点 → 客户 Public `project/*` 看工地/工人/效果图/板拍(PhotoSwipe 相册)/图纸,Applet `dynamic` 发服务圈动态(订单 picker+9 图),Applet `decorationSchedule` 看进度——C 端只读接口全部走 `queryCus*` 系列独立投影;
7. **会员/积分**:绑定订单(订单号+手机号+验证码 `bindingCusDecoration.do`)→ `addMemberCard.do` 建会员卡 → `queryMemberDetail.do` 积分流水;电子会员卡 `addEmember.do` + **JsBarcode 条码 `fanglin+会员号`** 线下核销(扫券码 `queryVawardByNo.do`);
8. **会销闭环**:Office 领票/赠票(`takeVticket.do`/`freeVticket.do`)→ 现场 `wx.scanCode`(码前缀 `fanglin+票号`)→ `comeVticket.do` 核销到场 → 扫码查奖/发奖(`queryVprizeByNo.do`/`addVaward.do`)→ 活动款 `createVpayment.do`——01 已判删除(运营噱头),此处仅作老系统实证记录;
9. **商城**:Store 购物车 → `addMorder.do` → `makeWPayment.do` 支付 → `addMpayment.do` 记账 → 评价;厂商侧闭环在 Office(`pageSelectionItemForSupplier.do` 接单)。01 已判 store 模块删除,若需要以会员营销插件形态另起(11 号 D8)。

## 五、功能域×端明细(接口为 `.do` 原名)

| 功能域 | 承载端 | 关键接口 |
|---|---|---|
| 留资 | EasyLive | `addEcustomer.do`(姓名/手机/楼盘/面积) |
| 案例/团队展示 | EasyLive | `queryEcase(ByTypeAndStatus/ByCid).do`、`queryEpicture.do`、`queryEperson.do`、按定位城市 `queryEcityByXandY.do` |
| 电子名片(方片) | EasyLive | `addCard/updateCard.do`、`addCardPhoto.do`、`addCnotice.do`、`queryCnoticeByReceiver.do`(访客预约消息) |
| 客源报备/抢单/转单 | Office | `pageCustomerOrigin.do`、`addCustomerOrigin.do`、`takeCustomerOrigin.do`、`bindCustomerOrigin.do`、`submitApprovalForCustomerOrigin(TDecoration).do`、查重 `existCustomer.do`/`existHouse.do` |
| 客户确认类(到店/画线/审批) | Public/Applet | `confirmCustomerOrigin.do`、`confirmDecorationInfo.do`、`confirmApproval.do` |
| 报价/增减项 | Office | `queryQuotationForDefaultByOrderId.do`、`queryQuotationFunctionAreaByQuotationId.do`、`queryAddReduce(ByOrderId).do` |
| 厂商接单 | Office | `pageSelectionItemForSupplier.do`、`confirmSelectionItem.do` |
| 收款/代付 | Office+Public | `addPayment.do`、`makeWPayment.do`、`pageProxyPayment.do`、`addProxyPayment.do` |
| 施工(员工侧) | Office | `queryConstructionNode.do`、`begin/finishConstructionSchedule.do`、`add/deleteConstructer.do`、工费 14 类 `addWorkerSalary.do`、辅料 `pageAccessories.do`、`updateAccessoriesForStatus.do` |
| 施工透明(C 端) | Public/Applet | `queryCusConstruction(Schedule/Picture/Drawing).do`、`queryCusConstructer.do`、`addCircle.do`(业主发圈) |
| 会员/积分/电子卡 | Public | `addMemberCard.do`、`queryMemberDetail.do`、`addEmember.do`、`queryVscoreByUnionId.do`、`pickupVscore.do` |
| 老带新 | Public | `addVustomer.do`、`buildVustomerNet.do`、`countVustomer(Net)ByUnionId.do` |
| 会销票务 | Office | `takeVticket/freeVticket/comeVticket.do`、`addVaward.do`、`createVpayment.do` |
| 商城 | Store | `queryMgoods.do`、`addCart.do`、`addMorder.do`、`makeWPayment.do`、`addMpayment.do` |
| 身份 | 全端 | `makeSession.do`(code→openid/unionid)、`queryPuser.do`(unionId→ERP userId)、`addPuser.do`(绑定)、`doLogin.do`(员工账密+图形验证码) |

## 六、微信平台能力依赖

- **授权登录**:小程序 `wx.login` → `makeSession.do` 换 openid/unionid;**unionid 依赖先关注公众号**(Office 无 union 时强制提示"请先关注公众号");公众号 OAuth(`snsapi_base/userinfo`)→ `queryAccessTokenByCode.do`/`queryUserInfo.do`;
- **JS-SDK**(Public):`wx.config`(签名由 `requestWXConfig.do` 下发)+ `scanQRCode` + `chooseWXPay`;
- **支付三通道**:小程序 `wx.requestPayment`(仅 Store)、公众号 `wx.chooseWXPay`(Public)、**扫码收款码**(员工侧生成,客户 H5 打开);渠道含微信原生 + 威富通聚合(RUL 有报文样例);
- **分享与群**:`onShareAppMessage` 全端;**群享**:scene 1044 shareTicket → `wx.getShareInfo`(已废弃 API)→ `queryGroupInfoByCode.do` 解 openGId → 微信群与装修单绑定;
- **二维码/条码**:生成(`weapp-qrcode.js`、后端 `QRCode.do` 海报、JsBarcode 会员条码)与识别(`wx.scanCode`,自定义码前缀 `fanglin`/`fanglinserver:`)两侧都在前端;
- **未用**:web-view、模板/订阅消息、卡券接口(服务端推送不可见,前端零调用)。

## 七、后端与多租户风格(与钉钉端同源的证据)

- Host 四域:`isvserver`(微信会话/支付/上传/群解码/JS-SDK 签名)+ `platform/`(ERP 业务主域)+ `communion/`(认证:doLogin/requestAuthCode/verifyCode)+ `test/`(C 端旧投影 `queryCus*`);媒体走 `showImg.do` 缩放裁剪;
- 每端一个 `company` id 做多租户(5 端 5 个值);鉴权双轨:unionId/openid 身份 + `authentication` 令牌,C 端直接硬编码"商店级"鉴权码(风险点);员工端 `requestAuthCode.do`(与钉钉端同一机制,secret 不同);
- 列表参数 **`filters[i].field/op/data + paging.page/rows`** 过滤器风格(Office 客源列表)——比钉钉端裸点号参数更进一步;
- 同一后端铁证:`queryDDUser/queryUserByDtid` 钉钉用户表、媒体域名 static.dingtalk.com、`.do`+authentication 风格与 `service-erp` 完全一致。

## 八、营造落点(判断法过堂)

| 微信侧能力 | 判断 | 营造落法 |
|---|---|---|
| C 端案例/进度/积分门户 | **行业**,营造已有对应物 | `web-mp`(docs/05,已上线 5 页)——本文 §九给增补需求 |
| 客户确认类动作(到店/画线/审批/退款确认) | **行业**——装企"客户签字"的线上化 | 11 号批次 C3 客户确认链:portal addon 扩展确认端点 + web-mp 确认页 |
| 老带新推荐+积分 | **行业**(获客是装企命脉) | addon-market 获客链(进线→客源);推荐网/积分归 member/market V2 |
| 电子名片(方片) | **行业** | 11 号 D6 名片族,addon-market V2 |
| 电子会员卡+条码核销 | **行业可选** | addon-member(已有会员/积分);条码核销随渠道凭证 |
| 收款码/代付/商城 | 支付渠道→**商业**(C2);商城→**删除**(01 已判) | PaymentChannelProvider 扩展点;store 以 member V2+ 另议 |
| 会销票务/核销 | **删除**(01 已判) | 不搬 |
| 报修/售后 | **行业** | 11 号 B1 addon-service 三件套,C 端报修入口随该插件 |
| 群享(微信群绑订单) | 依赖已废弃 `wx.getShareInfo`,**删除**,降级为普通分享卡片 | web-mp 分享海报(带参数码)替代 |
| Office 员工微信小程序 | **不建**——员工移动办公收口 `app/`(uni-app 一码多端),微信原生员工小程序是第三条端线,维护不起;员工"扫码生成客户确认码/收款码"类线下场景由 `app/` 承载(见 12 号 P1) | 12 号文档 P0-4/P1-2 |
| C 端业主小程序(Applet) | 不单建原生小程序;`web-mp` 一码双出 mp-weixin 即可(与 05 号工程设计一致) | web-mp |

## 九、web-mp 客户端门户增补需求(V2 输入)

现状(docs/05 阶段 B):案例库/施工进度/进度时间轴/会员积分/我的,手机验证码登录。对照方林微信侧二十年 C 端实证,增补分三档:

| 档 | 能力 | 说明 |
|---|---|---|
| P0 | **收款/付款流水页** | 方林 C 端资金记录是客户最高频动作之一(`queryCusDecorationPayment.do` 对应营造 addon-payment C 端投影):装修款/主材款分列、类型字典(预付/定金/补款/退款)、状态;addon-portal 增 c-customer 只读端点 |
| P0 | **工地相册/板拍** | 效果图+板拍相册(PhotoSwipe 式全屏预览)是"客户围观数"的核心;addon-construction 增 C 端图片投影端点 |
| P1 | **客户确认中心** | 我的待确认列表(画线/变更/退款…)→ 详情 → 确认/驳回(11 号 C3);登录墙 + 实名(手机号匹配订单) |
| P1 | **分享海报/带参码** | 案例分享卡片 + 老带新带参二维码(替代群享);addon-market 获客归因 |
| P2 | 报修入口(随 B1)、会员条码(随渠道)、积分兑换(11 号 D8) | |

工程注意:提审合规机制照 09 号 §F(双版本翻转默认、支付动作转客服咨询);C 端公开接口匿名可逛 + 接口失败兜底默认图(首页永不白屏,09 号 §G)。

## 十、非目标(本期不做)

- 原生微信员工端/业主端小程序(收口 uni-app 两条线,§八);
- 商城/会销/群享/抽奖(01 删除项);直播(EasyLive 名存实亡,仅名字含 Live);
- 支付渠道真实对接(C2 凭证到位后按 PaymentChannelProvider 独立立项);
- 修复方林历史风险(测试号 secret、硬编码鉴权码、废弃 API)——那是老系统自己的债。

## 十一、验收标准(对 §九 P0 而言)

- [ ] web-mp 增"资金流水"页:H5 + mp-weixin 编译绿;登录墙生效;数据与 web 端 addon-payment 口径一致(同一 service);
- [ ] web-mp 增"工地相册"页:节点图片按时间倒序、全屏预览、失败兜底默认图;
- [ ] 新增 c-customer 端点进 enforce 冒烟零未声明;smoke C 端段 +N 步;
- [ ] 截图入 docs/screenshots/(web-mp 375×812)。

## 附:回溯指针(实现者用)

- 仓库:`D:\Git\erp-wechat-mini`(非 git,Codeup 快照 2026-10-20;Eclipse 工程 "Wechat");
- Office 员工端:`Office/pages/`(customer/origine 客源族、supervision 施工族、payment 收款族、information/vactivity 会销族)、登录链 `Office/pages/index/index.js`(`doLogin.do`+`queryVode.do` 图形码)、功能宫格 `platform/queryModuleForLogin.do`;
- C 端 H5:`Public/index.html` + `index.fl`(OAuth state=type:N 片段路由)、`customer/customerSource.fl`(到店确认)、`customer/drawLine.fl`(画线确认)、`vustomer/vustomer.fl`(老带新)、`emember/emember.fl`(电子卡条码)、`payment/collection.fl`(收款流水字典);
- Applet:`app.js`(群享 getGroupOrderId/getUnionId 链)、`pages/approval/approval.js`(17 类客户确认审批 + 常量表 `utils/json.js`);
- Store 支付链:`pages/createrOrder/createrOrder.js`(`makeWPayment.do`→`wx.requestPayment`→`addMpayment.do`);
- 风险证据:`RUL` 文件(测试号 secret 明文)、各端 app.js 硬编码鉴权码、废弃 API(getShareInfo/getUserInfo/open-data)。
