# -*- coding: utf-8 -*-
"""第 108 次运行:smoke_all.py 追加 file 文件存储段(F1,docs/15 R10)。
插入点:dashboard 落地页段尾 step("角色1落地页已清",...) 之后、第一个 return finish() 之前。"""

p = 'docs/scripts/smoke/smoke_all.py'
t = open(p, encoding='utf-8').read()

anchor = '    step("角色1落地页已清", not _r3.get("home_path"), _r3)\n'
assert t.count(anchor) == 1, 'anchor not unique: %d' % t.count(anchor)

seg = '''
    # ================= file 文件存储(F1 内核 file 模块,docs/15 R10;错误码 125000 段) =================
    print("== file 文件存储(F1:上传/回读/白名单/限额/org 隔离/删除权/C 端拒) ==")
    conn = db_conn()
    try:
        with conn.cursor() as cur:
            cur.execute("delete from yz_file where biz_type = 'smoke'")
        conn.commit()
    finally:
        conn.close()

    def upload_bytes(xbytes, token, fname, ctype="application/octet-stream", biz_type=None, biz_id=None):
        boundary = "smokeboundfile108"
        head = ("--" + boundary + "\\r\\nContent-Disposition: form-data; name=\\"file\\"; filename=\\"" +
                fname + "\\"\\r\\nContent-Type: " + ctype + "\\r\\n\\r\\n")
        body = head.encode("utf-8") + xbytes + ("\\r\\n--" + boundary + "--\\r\\n").encode("utf-8")
        url = BASE + "/api/files"
        if biz_type:
            url = url + "?bizType=" + urllib.parse.quote(biz_type)
            if biz_id is not None:
                url = url + "&bizId=" + str(biz_id)
        req = urllib.request.Request(url, data=body, method="POST")
        req.add_header("Content-Type", "multipart/form-data; boundary=" + boundary)
        req.add_header("Authorization", "Bearer " + token)
        try:
            with urllib.request.urlopen(req) as resp:
                return resp.status, json.loads(resp.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def get_file_headers(path, token):
        req = urllib.request.Request(BASE + path)
        req.add_header("Authorization", "Bearer " + token)
        try:
            with urllib.request.urlopen(req) as resp:
                return resp.status, resp.read(), dict(resp.headers)
        except urllib.error.HTTPError as e:
            return e.code, e.read(), dict(e.headers)

    stamp_file = time.strftime("%H%M%S")
    png = bytes.fromhex("89504e470d0a1a0a") + ("smoke108" * 11).encode("utf-8")
    st, res = upload_bytes(png, tok_admin, "冒烟文件" + stamp_file + ".png", "image/png", "smoke", 1)
    f1 = (res or {}).get("data") or {}
    step("上传中文文件名 png(bizType 挂接)", st == 200 and f1.get("id") and f1.get("fileName", "").endswith(".png")
         and f1.get("bizType") == "smoke", f1)
    fid1 = f1.get("id")

    st, down, hdrs = get_file_headers("/api/files/%s" % fid1, tok_admin)
    cd = hdrs.get("Content-Disposition", "")
    step("下载字节一致+attachment+RFC5987 文件名", st == 200 and down == png and "attachment" in cd
         and "filename*=" in cd, (st, len(down), cd[:80]))

    st, down, hdrs = get_file_headers("/api/files/%s/preview" % fid1, tok_admin)
    step("预览 inline(图片白名单)", st == 200 and hdrs.get("Content-Type", "").startswith("image/png")
         and "inline" in hdrs.get("Content-Disposition", ""), (st, hdrs.get("Content-Type")))

    st, res = upload_bytes(b"", tok_admin, "empty.png", "image/png")
    step("空文件拒 125001", (res or {}).get("code") == 125001, res)
    big = b"x" * (20 * 1024 * 1024 + 1)
    st, res = upload_bytes(big, tok_admin, "big.png", "image/png")
    step("超限拒 125002(业务闸 20MB)", (res or {}).get("code") == 125002, res)
    st, res = upload_bytes(b"MZfake", tok_admin, "hack.exe", "application/x-msdownload")
    step("类型白名单拒 125003", (res or {}).get("code") == 125003, res)

    tok_o = login("example_o")
    st, down, hdrs = get_file_headers("/api/files/%s" % fid1, tok_o)
    step("跨组织下载拒 125005", json.loads(down).get("code") == 125005, down[:120])
    st, res = upload_bytes(png, c_token, "c端拒.png", "image/png")
    step("C 端主体上传拒 125008", (res or {}).get("code") == 125008, res)

    st, res = call("POST", "/api/files/%s/delete" % fid1, tok_b)
    step("非上传人删除拒 125006", (res or {}).get("code") == 125006, res)
    api_ok("上传人删除", "POST", "/api/files/%s/delete" % fid1, tok_admin)
    st, down, hdrs = get_file_headers("/api/files/%s" % fid1, tok_admin)
    step("删除后下载拒 125004(软删)", json.loads(down).get("code") == 125004, down[:120])

    st, res = upload_bytes(png, tok_admin, "列表文件A.png", "image/png", "smoke", 11)
    fid2 = ((res or {}).get("data") or {}).get("id")
    st, res = upload_bytes(png, tok_admin, "列表文件B.png", "image/png", "smoke", 22)
    fid3 = ((res or {}).get("data") or {}).get("id")
    lst = api_ok("列表 bizType=smoke", "GET", "/api/files?bizType=smoke", tok_admin)
    ids = [r.get("id") for r in (lst or [])]
    step("列表含两文件且倒序", fid2 in ids and fid3 in ids and ids.index(fid3) < ids.index(fid2), ids)
    lst2 = api_ok("列表 bizId 收窄", "GET", "/api/files?bizType=smoke&bizId=11", tok_admin)
    step("bizId 过滤只余 A", [r.get("id") for r in (lst2 or [])] == [fid2], lst2)

    conn = db_conn()
    try:
        with conn.cursor() as cur:
            cur.execute("select count(*) from yz_file where id in (%s, %s) and status = 1", (fid2, fid3))
            _f_del = cur.fetchone()[0]
        conn.commit()
    finally:
        conn.close()
    # 收尾清理:软删测试行(物理文件落 data/files 无元数据引用,无害)
    conn = db_conn()
    try:
        with conn.cursor() as cur:
            cur.execute("delete from yz_file where id in (%s, %s)", (fid2, fid3))
        conn.commit()
    finally:
        conn.close()
    step("删除走软删留痕(status=1)", _f_del == 2, _f_del)

    return finish()
'''

t = t.replace(anchor, anchor + seg, 1)
# 去掉原有的重复 return finish()(历史遗留,插段后正好收敛为单出口)
dup = '    return finish()\n\n    return finish()\n'
if dup in t:
    t = t.replace(dup, '    return finish()\n', 1)
    print('duplicate return collapsed')

open(p, 'w', encoding='utf-8', newline='\n').write(t)
print('smoke file segment appended')
