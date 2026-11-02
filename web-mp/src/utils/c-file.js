import { API_BASE } from '../config'
import { getToken } from '../api/http'

// C 端文件展示公共封装(R20 相册配套):把相册 yz_file.id 换成本地可展示路径。
// 🔴<image> 发不了 Authorization 头——网络图回显必须经带票据的 uni.downloadFile
// 落本地 tempFilePath 再展示(与员工端 utils/yz-file.js 同姿势);票据键=c_token。
// 字节流端点 /api/c/files/{id} 归属白名单校验在后端(不在本人相册集=360004)。
const token = () => getToken() || ''

/** 下载相册图到本地临时路径,resolve(tempFilePath);失败 reject(Error)。 */
export function downloadAlbumImage(fileId) {
  return new Promise((resolve, reject) => {
    uni.downloadFile({
      url: API_BASE + '/api/c/files/' + fileId,
      header: { Authorization: 'Bearer ' + token() },
      success: (res) => {
        if (res.statusCode === 200 && res.tempFilePath) resolve(res.tempFilePath)
        else reject(new Error('文件下载失败 ' + res.statusCode))
      },
      fail: (err) => reject(new Error((err && err.errMsg) || '网络异常,文件下载失败')),
    })
  })
}
