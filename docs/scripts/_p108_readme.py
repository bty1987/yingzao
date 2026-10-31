# -*- coding: utf-8 -*-
"""第 108 次运行:README 回写(F1 文件存储兑现+冒烟 2617+第 18 插件口径)。"""

p = 'README.md'
t = open(p, encoding='utf-8').read()

# 1) 当前状态行:17→18 插件(第 104 次起 dashboard 已入)+ 冒烟 2456→2617 + V2 迭代描述更新
old = '| 当前状态 | 功能完备:17 个插件(15 业务域 + C 端门户 + 示范)全量 V1,回归冒烟 2456 步 + 插件静态规则校验 101 条全绿;进入 V2 迭代(五份子域 PRD 待拍板:定额/毛利线/外销/名片/PK 对赌;部署清单:企微钉钉/微信/短信凭证由使用方提供),欢迎 Watch/Star 关注进展 |'
new = '| 当前状态 | 功能完备:18 个插件(15 业务域 + 驾驶舱 + C 端门户 + 示范)全量 V1,V2 子域迭代中(定额/毛利线/外销/名片/PK 对赌/品牌配送分批均已落地),内核文件存储可插后端已就位;回归冒烟 2617 步 + 插件静态规则校验 101 条全绿;部署清单:企微钉钉/微信/短信凭证由使用方提供。欢迎 Watch/Star 关注进展 |'
assert t.count(old) == 1, 'status line'
t = t.replace(old, new, 1)

# 2) 行业全链条行:冒烟数
old2 = '十五个业务插件 + 客户端门户 + 示范插件 V1 已落地,回归冒烟 2400+ 步持续全绿;'
new2 = '十五个业务插件 + 驾驶舱 + 客户端门户 + 示范插件已落地,回归冒烟 2600+ 步持续全绿;'
assert t.count(old2) == 1, 'chain line'
t = t.replace(old2, new2, 1)

# 3) 内核能力行:文件标注可插后端(local 首发)
old3 = '插件框架(扩展点/领域事件/前端槽位)、组织账号、RBAC、字典、文件、通知、审计、i18n 等通用设施。**内核永不出现任何公司判断** |'
new3 = '插件框架(扩展点/领域事件/前端槽位)、组织账号、RBAC、字典、文件存储(FileStorageProvider 可插后端,local 首发)、通知、审批、i18n 等通用设施。**内核永不出现任何公司判断** |'
assert t.count(old3) == 1, 'core line'
t = t.replace(old3, new3, 1)

# 4) 仓库结构冒烟注释:1700+ → 2600+
old4 = '│   └── scripts/smoke/      # 一键冒烟回归(1700+ 步,可重复执行)'
new4 = '│   └── scripts/smoke/      # 一键冒烟回归(2600+ 步,可重复执行)'
assert t.count(old4) == 1, 'tree line'
t = t.replace(old4, new4, 1)

open(p, 'w', encoding='utf-8', newline='\n').write(t)
print('README patched')
