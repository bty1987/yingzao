# -*- coding: utf-8 -*-
"""第 106 次续:AGENTS.md 第 106 条目增补 B2 行业中性 PRD。"""
import io

P = r"D:\ERP\yingzao\AGENTS.md"
with io.open(P, "r", encoding="utf-8") as f:
    text = f.read()

old_title = "## 当前进度(2026-10-31 更新,第 106 次运行:D4 看板页三方向重出——纸墨体系内三诠释,待拍板)"
new_title = "## 当前进度(2026-10-31 更新,第 106 次运行:D4 看板页三方向重出(待拍板)+B2 配送域行业中性 PRD)"
assert old_title in text
text = text.replace(old_title, new_title, 1)

old_tail = "下一轮候选=看板页拍板→Home.vue 正式实现>B2 品牌跳级 PRD(按 10-30 拍板「做」,先出行业中性 PRD)>兜底=R12~R13 移动端组件基建或 D4 二轮;老系统零触碰,fl-yy 零触碰,生产库零访问;"
new_tail = "下一轮候选=看板页拍板→Home.vue 正式实现>B2 PRD 拍板→写码(300200–300250/V1104,可与看板实现并行)>兜底=R12~R13 移动端组件基建或 D4 二轮;fl-yy 零触碰,生产库零访问,冒烟零运行(纯设计+文档轮);\n- ✅ **B2 配送域行业中性 PRD 兑现(第 106 次同轮,纯文档轮,docs/11 B2「若要先出 PRD」拍板兑现,第 11 次总结溯源「配送四级(品牌跳级权限+按配送批次分成克隆结算行=方林玩法)」)**:①老机制只读考证实证(`SalesDeliveryServiceImpl.java:451-504` 递归连打+`:506-540` balanceSalesDeliveryItem 分成克隆):品牌×配送级配置表 salesdeliverystatusright(checked 免人工位=0 该级自动通过+crate 万分比),推进到达 crate≠0 的级即把该单该品牌预结基准行(TYPE_BALANCE)整行 SerializationUtils.clone 一条(type=TYPE_INIT/金额五列×crate/rate 快照/deliver_schedule=配送级批次锚),按(sid,bid,type,onetype,status)存在性幂等——「结算认领按配送进度分批」;②**行业中性判断:配送驱动结算是主材共性(分批供货分批结账),方林嫌疑只在厂商对账返点绑定与操作级切分**——拆两正交概念不抄实现:品牌配送流程模板(免人工位显式 auto_tran/auto_check/auto_confirm,**finish 恒人工防自答**;与 V1「越级显式报错」纪律相容=人不越级系统按配置代打+track auto_ 前缀留痕)+里程碑分批结算行 sales_settlement_batch(uk(org,settle_id,milestone)/rate 快照/金额逐列 HALF_UP/**末批=总额−已批补尾差**/Σ=10000 强守卫修老加总漂移);预结行(三段式)全额不动,分批行=认领计划视图,厂结/结算认领只回写 status 标记零资金轴改动;③老缺陷收敛表七条(同表混配两义拆两表/Σ 不校验/clone 整行连抄归属字段/免人工无留痕/deliver_schedule 魔法值/操作级非业务里程碑/克隆行无关联列);错误码 300200–300250 小段(oales 3001xx 后顺延)/迁移 V1104/端点归 /api/sales 既有 prefix;④7 项开放问题待拍板(可整体「按推荐」:落点 addon-sales V2 子域/finish 恒人工/分成挂四级操作级映射/分批=计划视图/预结未生成补扫/品牌级配置/Web 并入既有页);文档回写:docs/prd/addon-sales-delivery.md/docs/11 B2 划勾 PRD 已出/addon-sales PRD 第 45 行挂指针/rules/sales.md §12.1「未迁 ✅」解除项挂本 PRD;老系统只读零修改(Rhadamanthus sales/right 两域),"
assert old_tail in text
text = text.replace(old_tail, new_tail, 1)

with io.open(P, "w", encoding="utf-8", newline="\n") as f:
    f.write(text)
print("AGENTS.md B2 appended, len =", len(text))
