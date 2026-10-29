# PRD - addon-market 名片子域(电子名片+留资,精简版)

> 状态:**已确认(2026-10-28 用户拍板「后续无特殊说明一律按推荐」五项全按推荐)+ R6 已实现(第 100 次:V1002 四表/名片 CRUD+share_token/匿名留资 AES+hash+幂等回显+频次防刷/CardNoticeCreatedEvent 零网络出事务+通知中心消费/已读守卫/管理端+Web Cards.vue;实现定案回写 rules/market.md §5;余 R7=web-mp 匿名名片页——三方向初稿已出(第 101 次,docs/design/card-dir-1/2/3.html,待拍板)**
> 老系统参考:market 模块名片族(card/cuser/cardPhoto/cnotice 四 PO + CardServiceImpl/CuserServiceImpl/CardPhotoServiceImpl/CnoticeServiceImpl);实现口径见 `docs/rules/market.md` §5(已深读在档);老前台在**外部 ISV 白标平台**(代码不在本仓库)
> 归属:**addon-market V2 子域**(V1 PRD 非目标已预留:「名片族——V2,重做要点已沉淀 rules §5」)
> 本子域声明:错误码 **280100–280150 小段**(addon-market 280000 段内分段,280026 已占用);迁移 **V1002+**;表前缀 `market_card_`

## 业务闭环(一句话)

销售员在后台**制作电子名片**(个人信息+相册),通过**分享链接/二维码**发给客户;客户(访客,无需登录)打开**自托管 H5 名片页**浏览并**留资**(姓名/电话/留言);留资落库即**通知名片主人**跟进,并可经承接桥转化为进线。闭环起点=名片创建;闭环终点=留资通知送达+跟进处置;**前台去 ISV 化自托管**(开放问题 1),老外部白标平台对接降级为扩展点。

## 谁与场景

| 角色 | 场景 | 高频动作 |
|---|---|---|
| 销售员 | 制作/维护自己的电子名片 | 编辑名片资料、上传相册、转发链接 |
| 访客(客户,免登录) | 打开名片页了解销售员并留联系方式 | 浏览名片/相册、提交留资表单 |
| 销售员/主管 | 查看谁留了资、跟进转化 | 留资列表、已读处理、转进线 |

## 核心模型(四表,老四表重做)

- **market_card(名片)**:org_id/user_id(归属销售员 yz_user,**一人一卡 uk(org,user)**)/user_photo/user_name/phone(**AES 密文+hash 列**,红线)/wechat/industry/profession/address/info/status(0 启用/1 停用)/share_token(分享链接随机串,名片页路由锚,重置即旧链接失效);
- **market_card_photo(相册)**:card_id/url/sort_no——老「删名片不级联图片(孤儿文件记录)」修法=删除级联(文件实体删归 OSS 清理策略);
- **market_card_visitor(访客身份)**:id/union_id(可空,微信授权后回填;**uk(org,union_id) 强约束**——修老「一 unionId 一卡规则未实现+upsert 先查后插无唯一索引」)/first_visit_time/last_visit_time;
- **market_card_notice(留资)**:card_id/visitor_id(可空,匿名留资无 visitor)/name/phone(**AES 密文+hash 列**)/comment/status(**0 待跟进/1 已读**;已读守卫=本人或管理员,修老「无校验任意写」)/create_time——老 cnotice suid/ruid 语义(suid 发起者/ruid 接收者)在重做中单义化:notice 直接挂 card_id,visitor 独立表;
- 【不迁】老 CardPO.cuser 外键直挂(nullable 但无条件解引用→未绑卡整页 500)——名片与访客解耦(访客经留资行关联,卡片零解引用风险)。

## 留资链路与通知(修老三重违例)

- 访客提交留资(name+phone+comment)→服务端校验(手机号格式/hash 查重:同人同卡重复留资**幂等回显**非报错,对齐获客链幂等惯例)→落库→**同事务发 `CardNoticeCreatedEvent`(orgId/cardId/noticeId,零网络调用)**→名片主通知经内核通知中心(宿主通道 V2;V1=Web 通知中心+列表徽章)——**修老「事务内同步 httpGet 把姓名电话明文拼 GET query 外发」三重违例**(PII 泄漏/中文未编码截断/慢响应拖长事务);
- **老 `isv` 配置键两义**(后端当 URL 基址/前端当白标开关)——营造拆 `market.card.isv.enabled`/`market.card.isv.notifyUrl` 两键,V1 均不启用(留扩展点,ISV 白标延续为可选部署形态);
- 留资转进线(可选动作):经既有承接桥语义(MarketCustomerBridge/进线登记契约)——V1 仅人工转(留资详情页一键「转进线」带姓名电话),自动转化随获客链 V2(开放问题 3)。

## 前台与后台

- **前台(访客侧,免登录)**:推荐 **web-mp(C 端 uni-app)新增匿名页** `/pages/card/detail`(案例页同形态:匿名白名单路由+share_token 路由参数+留资表单)——访客无需任何登录;微信授权(unionId 回填 visitor)为可选增强,V1 匿名可用(开放问题 2);
- **后台(Web 管理侧)**:addon-market Web 工程新增「电子名片」页(我的名片编辑器+相册管理+留资列表含已读徽章);主管视角=全员留资列表(权限归 market 既有菜单闸);
- **视觉纪律**:前台名片页为新视觉面——**实现前走 huashu-design 三方向初稿流程**(AGENTS.md 前端军规),后台管理页沿用 addon-market 既有工程风格不触发。

## 老缺陷收敛表(重做即修复)

| 老缺陷(rules/market.md §5 实证) | 营造处置 |
|---|---|
| 手机号/姓名明文存储+GET query 外发 | phone AES+hash 双列,全域红线;通知事件化零外发 |
| cuser upsert 无唯一索引/一 unionId 一卡未实现 | uk(org,union_id) 强约束+幂等 upsert |
| pageCard 未绑卡无条件解引用整页 500 | 卡片与访客解耦,零外键解引用 |
| cnotice status 无校验任意写 | 已读守卫=本人或管理员 |
| 全族零 FLException 静默 return | 全链错误码+可见失败(280100 段) |
| 删名片不级联图片孤儿 | 级联删除(记录+清理策略) |
| isv 配置键两义 | 拆 enabled/notifyUrl 两键,V1 不启用 |

## 错误码段(280100–280150,双语,登记 error.md 由实现轮落)

280101 名片不存在 / 280102 名片已存在(一人一卡) / 280103 名片已停用 / 280104 留资内容非法(姓名/电话/留言缺失或格式错) / 280105 相册图片不存在 / 280106 无权操作该名片(非本人) / 280107 留资不存在 / 280108 留资状态不可操作 / 280109 分享令牌无效 / 280110 超过留资频次限制(同访客短时多次,防刷)

## 划界(本期不做)

- **ISV 白标平台对接**(unionId 换取/openapi 推送)——扩展点保留,凭证与商务合作另议(开放问题 1 关联);
- **微信授权强制**(unionId 必填)——V1 匿名留资优先;
- **留资自动转进线/自动分配**——V1 人工一键转(开放问题 3);
- **名片访问统计/PV 埋点**——visitor 表已留 last_visit_time,报表随营销分析 V2;
- **名片模板/主题市场**——V1 单版式(实现轮 huashu-design 定稿);
- **IM 即时聊天**——老系统即无,留言板已是留资形态,不扩;
- **企业微信名片夹同步**——宿主集成域(C4 凭证后)。

## 实现期核对清单(拍板后写码逐条销)

1. web-mp 匿名路由白名单机制实读(cases 页同款,免登 token 豁免面)——名片页挂同名单;
2. hash 查重列与 AES 密文列姿势对齐 addon-market 进线报名既有实现(零新机制);
3. CardNoticeCreatedEvent 消费=通知中心内核写入姿势(对齐 Plan/事件通知既有先例);
4. share_token 生成与重置幂等(重置不换卡 id);
5. OSS 上传走既有基建(相册图片,showImg 契约);
6. 冒烟:留资幂等回显/重复频次闸/已读守卫/名片停用前台 410/匿名路由越权面(C 端员工语义性拒,第 49 次 RBAC 惯例);Web 三方向初稿先于名片页写码。

## 开放问题(待拍板,可整体「按推荐」)

1. **前台形态**:推荐 **去 ISV 化自托管**(web-mp 匿名名片页,零外部依赖,凭证清单解耦);备选延续 ISV 白标(需商务凭证,PRD 冻结至凭证到位);
2. **访客身份**:推荐 V1 匿名留资(unionId 可选回填);备选强制微信授权(转化可追踪但流失率高);
3. **留资转进线**:推荐 V1 人工一键转;备选自动经承接桥(撞单策略联动,依赖获客链 V2 排期);
4. **通知通道**:推荐 V1 Web 通知中心+徽章(内核能力,零凭证);备选企微/钉钉推送(随 C4 宿主凭证);
5. **名片与进线承接优先级**:若拍板「先做获客链 V2 承接自动化」,本子域让位顺延(建议先名片——体量小、独立性强、可单独交付)。
