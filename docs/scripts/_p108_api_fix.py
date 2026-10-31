# -*- coding: utf-8 -*-
"""第 108 次运行:修正 web/src/api/index.js 尾部被 bash 转义写坏的一行,补齐 file api 函数。"""
import io

p = 'web/src/api/index.js'
t = io.open(p, encoding='utf-8').read()
# 去掉写坏的尾部(bash 转义产物)
marker = '// 文件存储(F1 内核 file 模块:上传走 YzUpload 组件直传,这里只做回显与删除)'
idx = t.find(marker)
assert idx >= 0, 'marker not found'
t = t[:idx]
t += marker + '\n'
t += "export const listFiles = (params) => http.get('/api/files', { params })\n"
t += 'export const deleteFile = (id) => http.post(`/api/files/${id}/delete`)\n'
io.open(p, 'w', encoding='utf-8', newline='\n').write(t)
print('fixed')
