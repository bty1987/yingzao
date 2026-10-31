import { API_BASE } from '../config'

// 文件展示公共封装(F1 内核 file 模块配套,docs/15 R12):把 yz_file.id 换成本地可展示路径。
// 🔴移动端 <image> 发不了 Authorization 头——网络图回显必须经带票据的 uni.downloadFile
// 落本地 tempFilePath 再展示(小程序/H5 同构);R11 上传组件先例,R12 抽出共用。
const token = () => uni.getStorageSync('yz_token') || ''

/** 下载文件到本地临时路径,resolve(tempFilePath);失败 reject(Error)。 */
export function downloadForDisplay(fileId) {
  return new Promise((resolve, reject) => {
    uni.downloadFile({
      url: API_BASE + '/api/files/' + fileId + '/preview',
      header: { Authorization: 'Bearer ' + token() },
      success: (res) => {
        if (res.statusCode === 200 && res.tempFilePath) resolve(res.tempFilePath)
        else reject(new Error('文件下载失败 ' + res.statusCode))
      },
      fail: (err) => reject(new Error((err && err.errMsg) || '网络异常,文件下载失败')),
    })
  })
}
