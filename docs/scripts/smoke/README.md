# 冒烟脚本(可重复执行资产)

> 六次夜跑的 python urllib 冒烟散落在各晚执行记录里,本目录把它沉淀为一键回归资产。
> 2026-10-11 第 7 次运行起维护;改内核/插件后跑一遍,全绿才算完成。

## 前置

1. 本机起服务(指向 dev 库 192.168.5.21 独立库 yingzao,与老系统库隔离):

   ```bash
   mvn clean package -DskipTests   # 仓库根;改动只涉前端可跳过
   YINGZAO_DB_URL='jdbc:mysql://192.168.5.21:3306/yingzao?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai' \
   YINGZAO_DB_USER=root YINGZAO_DB_PASSWORD='Neusoft!' \
   java -jar yingzao-app/target/yingzao-app-*.jar
   ```

2. 依赖:python3 + pymysql(仅 `ensure_users.py` 直连 dev 库补测试账号用;接口调用全走 urllib)。

## 跑法

```bash
# 默认 BASE=http://127.0.0.1:8080,可用 YINGZAO_BASE 覆盖
python docs/scripts/smoke/smoke_all.py
```

输出逐步 ✓/✗ 与汇总 `N/N PASS`。任何一步 ✗ 退出码非 0。

## 覆盖链路(与历史夜跑冒烟同口径)

- 认证:双用户+admin 登录 / 错误密码 110001 / 无 token 401(含 /api/addons 去白名单回归);
- RBAC(第 8 次扩展):admin 旁路全量下发 / 无角色用户过滤为空 / 非管理员建角色拒绝 122003 / 建角色+菜单·功能点·成员授权+详情回显 / 同编码拒绝 122004 / 授权后按人可见 / 删除角色后回落为空;
- 客源:中文登记 → 手动分配(指定 example_c)→ 重复分配幂等拒绝 210003;
- 机会:列表含中文客源名与责任人 → 确认 0→3 → 跳步完成拒绝 210005 → 完成 3→4;
- 通知:分配后责任人未读数 +1(中文标题含客源名)→ 已读清零;
- 审批:example_b 提请假条 → example_c 待办可见 → 非处理人操作拒绝 121002 → 通过后业务单回写、发起人收办结通知 → 重复办结拒绝 121001;
- 多级审批(第 8 次扩展):days≥3 缺二级审批人拒绝 290006 → L1(example_c)通过后插件派生 L2(admin)并改挂 → L2 处理人待办可见/非处理人拒绝 121002 → L2 通过后业务单回写通过;
- 转交×多级串联(第 9 次扩展):非处理人转交拒绝 121002 → L1 转交后待办迁移、转交后处理人通过仍触发派生 L2 → L2 二次转交后待办迁移 → 两级走完业务单回写通过(转交只改 assignee 不改状态,派生链与处理人无关);
- 客户/楼盘档案(第 17 次扩展):城市→城区→楼盘→房屋(门牌密文+get-or-create 幂等)→客户(手机查重/多号码默认切换/reveal 留痕/账户脱敏)→客源转化联动主档;
- 基础数据(第 18 次扩展):品类树守卫链/品牌查重/商品三路查重/SKU·价格查重/套餐双守卫/型号/定材包引用行快照锁;
- 款项三件套(第 19 次扩展):类型字典(协议键+模板校验)→ 建单守卫(金额恒正/重复拒绝/类型校验)→ 顺序门控 → 收款守卫(渠道合计平衡/金额恒正/渠道白名单)→ 收据双人留痕 → 确认终态 → 作废;
- 装修单(第 20 次扩展):登记守卫族(档案契约校验 250004/250005/在途查重 250003/定金恒正 250007)→ 显式迁移表白名单(0完工/废单后预签 250002)→ 定金收款事件回程车升定金 → 预签 → 确认线(大改变体 0→21→2/跳步倒退 250014)→ 量房(重复申请 250017/成功联动工程线开工)→ 签约守卫(未收款 250009/总额恒正 250010)+比例模板拆款线+重签循环不重建 → 决算(未收齐 250016)→ 完工 → 终止审批(重复提交 250013/审批未清重签 250012/驳回零改动/通过联动归属失效+未收作废)→ 废单转归属;通知回程车+总线对账零滞留;
- 退款统一走内核审批(第 19 次扩展):提交带原款项状态快照/重复退款拒绝 270012 → 通过后原款项终态+退款单执行+PaymentRefunded 事件消费 → 驳回不改原款项状态;
- 事件总线三件套(第 19 次扩展):消费确认(status=1)/示范消费器通知留痕/坏载荷重试转死信(retry_count+last_error 可见)/死信 requeue 再消费/非管理员 123001;
- 预付款科目化台账(第 19 次扩展):懒预置三科目/科目查重/收入·划扣·提现/金额恒正/余额不足 270017/**两并发扣减只成功一次不超支**/对账红线 余额==Σ带符号流水(pymysql 直查)。

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `YINGZAO_BASE` | `http://127.0.0.1:8080` | 被测服务地址 |
| `YINGZAO_DB_HOST` / `YINGZAO_DB_PORT` | `192.168.5.21` / `3306` | ensure_users 直连库(仅 dev) |
| `YINGZAO_DB_NAME` / `YINGZAO_DB_USER` / `YINGZAO_DB_PASSWORD` | `yingzao` / `root` / `Neusoft!` | 同上 |

款项/事件总线段(第 19 次扩展)要求服务以**快事件总线参数**启动,否则死信链要等满生产退避:

```bash
YINGZAO_EVENTS_BACKOFFSECONDS=1 YINGZAO_EVENTS_BACKOFFCAPSECONDS=5 YINGZAO_EVENTS_MAXRETRIES=2 YINGZAO_EVENTS_POLLINTERVALMILLIS=2000 YINGZAO_EVENTS_POLLINITIALDELAYMILLIS=3000 java -jar yingzao-app/target/yingzao-app-*.jar
```

🔴 冒烟请求含中文必须走 python urllib(UTF-8),Git Bash 的 curl 发中文 = GBK 炸 JSON。
