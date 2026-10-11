# yingzao-app(uni-app 移动端)

营造移动端:uni-app + Vue3 + Vite,小程序(微信)+ APP + H5 一码双出(RFC §2 端规划)。

## 运行

```bash
npm install
npm run dev:h5          # H5 开发(5174 端口,/api 代理到 localhost:8080 内核)
npm run build:h5        # H5 构建 → dist/build/h5
npm run build:mp-weixin # 微信小程序构建 → dist/build/mp-weixin(微信开发者工具导入)
npm run build:app       # APP 构建(真机调试需 HBuilderX/自定义基座)
```

## 工程约定

- **接口地址**:`src/config.js`——H5 同源(开发期 vite 代理,生产反向代理);小程序/APP 直连绝对地址(当前指向 dev 库环境 192.168.5.21:8080,正式发布前改构建注入);
- **认证**:与 web 端同一套——`yz_token`/`yz_user_id`/`yz_display_name` 存本地存储,请求带 `Authorization: Bearer`,401 清票据回登录页(`src/api/http.js`);
- **视觉**:当前为工程骨架(无正式视觉);按 AGENTS.md 前端工作流,风格方向在 `docs/design/` 三选一经用户拍板后,由 huashu-design 统一出移动端视觉规范再重做;
- **后端数据源**:页面消费的接口与 web 端 `web/src/api/index.js` 完全同源(认证/客源/机会/通知/审批),字段口径以后端 Service 为准。

## 已知边界(骨架期)

- 页面在 `pages.json` 静态注册;插件页面的移动端挂载形态(对应 Web 端槽位/动态路由 RFC §3.4)待 v2 设计——当前移动端只覆盖内核 + addon-customer 的店头/巡场高频场景;
- "我的机会"为前端按 `assignee_id` 过滤(接口为组织全量),服务端按人过滤参数待 addon-customer V2 补;
- 企微/钉钉免登(宿主 H5 免登进同一页面)待凭证,预留登录页单点改造点。
