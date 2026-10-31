// 扫码封装(docs/15 R12 移动端组件基建):uni.scanCode 的平台差异收敛。
// 小程序/APP:原生扫码,res.result 即码值;H5:无扫码能力,显式拒绝并提示
// (业务页 catch 后引导手动输入,施工域 R17~R19 扫工单码/详情页扫单号首个消费方)。
export function scanCode() {
  return new Promise((resolve, reject) => {
    // #ifdef H5
    uni.showToast({ title: '当前环境不支持扫码,请手动输入', icon: 'none' })
    reject(new Error('scan unsupported on H5'))
    // #endif
    // #ifndef H5
    uni.scanCode({
      success: (res) => resolve(res.result),
      fail: (err) => reject(new Error((err && err.errMsg) || '扫码取消或失败')),
    })
    // #endif
  })
}
