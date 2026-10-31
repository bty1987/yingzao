# -*- coding: utf-8 -*-
"""第 108 次运行:A1 段尾补残留清理(卫生缺口修复)。
A1 部分退原单不置终态(设计如此),114 轮累积活单;HHMMSS 秒级撞历史残留即 270006。
在 A1 段尾(「A1 净退款单自动创建」断言后、营销获客段前)插入清理块,
并给段首加同款清理(防前轮崩在段中留残)。"""

p = 'docs/scripts/smoke/smoke_all.py'
t = open(p, encoding='utf-8').read()

cleanup = '''
    # A1 段残留清理(第 108 次:部分退原单恒活单,跨轮累积 HHMMSS 秒撞 270006——段首段尾双清理)
    def _a1_cleanup():
        conn = db_conn()
        try:
            with conn.cursor() as cur:
                cur.execute("delete from payment_bill where biz_addon like 'a1test%'")
                cur.execute("delete from payment_bill where remark like 'A1 部分退%' or remark like 'A1 净退%'")
            conn.commit()
        finally:
            conn.close()

    _a1_cleanup()
'''

# 段首:在 a1_stamp 计算前插入清理
anchor_head = '''    print("\\n== A1 部分退款与增减净退款 ==")
    import time as _t81
'''
assert t.count(anchor_head) == 1, 'head anchor: %d' % t.count(anchor_head)
t = t.replace(anchor_head,
              '    print("\\n== A1 部分退款与增减净退款 ==")\n' + cleanup + '    import time as _t81\n', 1)

# 段尾:在营销获客段 print 前插入段尾清理
anchor_tail = '''    # ========= 营销获客(addon-market 第 27 次扩展)========='''
assert t.count(anchor_tail) == 1, 'tail anchor: %d' % t.count(anchor_tail)
t = t.replace(anchor_tail, '    _a1_cleanup()\n\n' + anchor_tail, 1)

open(p, 'w', encoding='utf-8', newline='\n').write(t)
import ast
ast.parse(t)
print('A1 cleanup patched')
