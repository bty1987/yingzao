# -*- coding: utf-8 -*-
"""第 108 次运行:修冒烟 file 段两处断言自身错误。
1) C 端主体访问 /api/files 在 RBAC 层即拒(122006 需授权 c-customer),先于业务守卫 125008——两道防线现行为正确;
2) fid2/fid3 软删断言前漏删除调用,补「上传人删除 fid2」。"""

p = 'docs/scripts/smoke/smoke_all.py'
t = open(p, encoding='utf-8').read()

old1 = '''    st, res = upload_bytes(png, c_token, "c端拒.png", "image/png")
    step("C 端主体上传拒 125008", (res or {}).get("code") == 125008, res)'''
new1 = '''    st, res = upload_bytes(png, c_token, "c端拒.png", "image/png")
    # C 端 principal 对员工端点在 RBAC 层即拒(122006),先于 file 业务守卫 125008——两道防线
    step("C 端主体上传拒 122006(RBAC 层)", (res or {}).get("code") == 122006, res)'''
assert t.count(old1) == 1
t = t.replace(old1, new1, 1)

old2 = '''    conn = db_conn()
    try:
        with conn.cursor() as cur:
            cur.execute("select count(*) from yz_file where id in (%s, %s) and status = 1", (fid2, fid3))
            _f_del = cur.fetchone()[0]'''
new2 = '''    api_ok("上传人删除 fid2", "POST", "/api/files/%s/delete" % fid2, tok_admin)
    conn = db_conn()
    try:
        with conn.cursor() as cur:
            cur.execute("select count(*) from yz_file where id in (%s, %s) and status = 1", (fid2, fid3))
            _f_del = cur.fetchone()[0]'''
assert t.count(old2) == 1
t = t.replace(old2, new2, 1)

# fid3 没删除仍是 status=0——断言改为「fid2 软删后查得 1 行 status=1」单行口径
old3 = '''    step("删除走软删留痕(status=1)", _f_del == 2, _f_del)'''
new3 = '''    step("删除走软删留痕(fid2 status=1)", _f_del == 1, _f_del)'''
assert t.count(old3) == 1
t = t.replace(old3, new3, 1)

open(p, 'w', encoding='utf-8', newline='\n').write(t)
import ast
ast.parse(t)
print('smoke file segment fixed')
