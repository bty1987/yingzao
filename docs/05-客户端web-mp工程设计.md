# 05 - 客户端 web-mp 工程设计(阶段 B 实施方案)

> 状态:2026-10-14 用户拍板「先出方案指导下一轮开发」——本文档即阶段 B 开工底座,下一轮按此执行,细节自主决策。
> 前置:阶段 A 已交付(阶段 49):RBAC anonymous/c-customer 两型 + addon-portal 五端点(/api/c/auth/code·login、/api/c/cases、/api/c/progress、/api/c/points)。

## 1. 工程定位与形态

- **新 uni-app + Vue3 工程 `web-mp/`**(与员工端 `app/` 平级,独立工程;不混入员工端页面);
- 一码双出:**H5 先行**(appid 不阻塞;`build:h5` 全绿=完成线),mp-weixin 编译通过(真机预览待阶段 C 凭证),app 端暂不出;
- 视觉:方向三「东方纸墨极简」token 对齐 `app/App.vue` 的做法(纯 CSS 变量挂 page 选择器:纸白/墨黑/朱印红唯一点缀/方角;yz-display/yz-eyebrow 品牌工具类移植);
- API_BASE 条件编译:H5 dev 走 vite proxy → 8080;生产/小程序走配置。

## 2. 页面与 tabBar(5 页)

| 页面 | 路由 | 认证 | 内容 |
|---|---|---|---|
| 案例库 | pages/cases/index | 匿名(tabBar 首页) | 城市筛选 chips + 案例卡片流(封面/标题/风格/城市)+ 分页加载;匿名可逛 |
| 施工进度 | pages/progress/index | 登录墙 | 我的装修单列表(单号/房屋/工程线徽章)→ 点进进度时间轴页 |
| 进度时间轴 | pages/progress/detail | 登录墙 | 节点竖向时间轴(节点名/状态/计划/实际;未完节点灰、当前朱印、完工苔绿) |
| 会员积分 | pages/points/index | 登录墙 | 分项余额卡(装修/主材/运营)+ 可用口径注记 + 流水列表(倒序) |
| 我的 | pages/mine/index | 双态 | 已登录:手机脱敏/退出;未登录:登录入口 |

- tabBar 四项:案例 / 进度 / 积分 / 我的;进度与积分页 **onShow 检查 token,无 token 跳登录页(带 redirect)**;
- 登录页 pages/login/index(非 tab):手机号输入 + 验证码输入 + 发码按钮(60s 倒计时)+ 登录;成功存 token/customerId → 跳回 redirect。

## 3. 阶段 B 后端小件(唯一后端增量)

**缺口**:C 端客户不知道自己的 decoration_order.id——进度页需要「我的装修单列表」:

- 契约:`DecorationApi` 增方法 `List<OrderBrief> listByCustomer(long orgId, long customerId)`(只读投影:orderId/orderNo/houseLabel/projectStatus/status);
- 端点:`GET /api/c/orders`(c-customer,addon-portal 聚合,归属=令牌 customerId 天然隔离);
- addon.yml api 段加一条 c-customer 声明;smoke C 端段补列表断言;enforce 样例 +1。

## 4. 前端工程结构(web-mp/)

```
web-mp/
  package.json            # uni-app + Vue3(对齐 app/ 依赖版本,@dcloudio 统一)
  vite.config.js          # H5 dev proxy /api → http://localhost:8080
  src/
    main.js  App.vue      # 全局 token(App.vue 纯 CSS 变量对齐 app/)
    manifest.json  pages.json   # tabBar 四项 + 页面注册
    api/index.js          # sendCode/login/cases/orders/progress/points 六函数 + request 封装
    utils/auth.js         # token/customerId 存取(uni.getStorageSync)+ 401 统一跳登录
    pages/
      cases/index.vue     progress/index.vue  progress/detail.vue
      points/index.vue    mine/index.vue      login/index.vue
  static/                 # tab 图标(方向三:墨线线性图标)
```

- request 封装:JSON/带 token 头/统一 err.code 友好提示(122006/360004 等);
- 状态:无 pinia(页面级 storage 即可,C 端状态薄);
- 分页:cases 用 onReachBottom 累加;进度/积分一次拉全。

## 5. 验收线(下一轮完成定义)

1. `web-mp` `dev:h5` 起得来、`build:h5` 全绿、`build:mp-weixin` 全绿(编译产物,不真机);
2. H5 起服务后浏览器走通:匿名逛案例 → 点进度跳登录 → 发码(dev 后端跑起+库直查验码同款流程)→ 登录 → 我的装修单列表 → 进度时间轴 → 积分页;
3. 后端小件(/api/c/orders)冒烟并入 smoke_all C 端段(+3 步左右),enforce 356→357 样例零未声明;
4. 视觉:方向三 token 落地(纸白底/朱印唯一点缀/方角),截图入 docs/screenshots/(web-mp H5 375×812 两三张);
5. README 界面预览/双端形态节补 web-mp 入口。

## 6. 非目标(防蔓延)

- 不做支付/IM/保修/兑换下单;不做图片懒加载优化之外的动效;不做分享卡片(V2);
- 不做多组织切换(匿名 org 走 yingzao.portal.org-id 配置,与阶段 A 一致);
- 不做自动化 UI 测试(编译绿+后端冒烟+截图核验为完成线,沿用视觉默认通过机制)。
