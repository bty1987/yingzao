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
- 转交×多级串联(第 9 次扩展):非处理人转交拒绝 121002 → L1 转交后待办迁移、转交后处理人通过仍触发派生 L2 → L2 二次转交后待办迁移 → 两级走完业务单回写通过(转交只改 assignee 不改状态,派生链与处理人无关)。

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `YINGZAO_BASE` | `http://127.0.0.1:8080` | 被测服务地址 |
| `YINGZAO_DB_HOST` / `YINGZAO_DB_PORT` | `192.168.5.21` / `3306` | ensure_users 直连库(仅 dev) |
| `YINGZAO_DB_NAME` / `YINGZAO_DB_USER` / `YINGZAO_DB_PASSWORD` | `yingzao` / `root` / `Neusoft!` | 同上 |

🔴 冒烟请求含中文必须走 python urllib(UTF-8),Git Bash 的 curl 发中文 = GBK 炸 JSON。
