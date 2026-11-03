<template>
  <view class="page">
    <view v-if="!logged" class="empty">
      <view class="yz-display" style="font-size: 36rpx; margin-bottom: 16rpx">客户确认</view>
      <view class="tip" style="margin-bottom: 40rpx">登录后可核验并确认您的主材销售单</view>
      <view class="btn-primary go" @click="goLogin">手机验证码登录</view>
    </view>
    <template v-else>
      <view v-if="!loading && rows.length === 0" class="empty">暂无待核验单据</view>
      <view v-for="s in rows" :key="s.id" class="card ticket">
        <view class="tk-head">
          <text class="tk-name">{{ s.code }}</text>
          <text class="stamp" :class="s.customer_confirmed === 1 ? 'st-ink' : 'st-seal'">
            {{ s.customer_confirmed === 1 ? '讫' : '候' }}
          </text>
        </view>
        <view class="tk-sub">{{ s.orderNo }} · {{ s.houseLabel }}</view>
        <view v-for="(it, i) in s.items" :key="i" class="item-row">
          <text class="item-name">{{ it.goods_name }}<template v-if="it.color_name"> · {{ it.color_name }}</template><template v-if="it.format_name"> · {{ it.format_name }}</template></text>
          <text class="item-amt">¥{{ yuan(it.total) }}</text>
        </view>
        <view class="tk-total">
          <text class="tip">合计({{ s.items.length }} 项)</text>
          <text class="amount yz-display">¥{{ yuan(s.total) }}</text>
        </view>
        <view class="tk-foot">
          <text v-if="s.customer_confirmed === 1" class="tip">已确认 {{ short(s.confirmed_at) }}</text>
          <view v-else class="btn-mini go-confirm" @click="askConfirm(s)">确认无误</view>
        </view>
      </view>
    </template>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app'
import { fetchSalesConfirmations, confirmSalesOrder } from '../../api'
import { getToken } from '../../api/http'

// 客户确认中心(R21,docs/15):验证码登录=实名锚,登录态自助确认主材销售单
// (免报码,替代「发码→员工代录」旧链);状态方章:已确认墨印「讫」/待确认朱印「候」;
// 驳回 V1 不做(有异议电话联系项目经理),重复确认后端 300012 幂等闸。
const rows = ref([])
const loading = ref(false)
const logged = ref(false)

const yuan = (li) => ((li || 0) / 1000).toLocaleString('zh-CN', { maximumFractionDigits: 2 })
const short = (t) => (t ? String(t).replace('T', ' ').slice(0, 16) : '')

async function load() {
  if (!getToken()) {
    logged.value = false
    return
  }
  logged.value = true
  loading.value = true
  try {
    const res = await fetchSalesConfirmations()
    rows.value = res.data || []
  } catch (e) {
    uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

function askConfirm(s) {
  uni.showModal({
    title: '确认销售单',
    content: '确认 ' + s.code + ' 的主材明细与金额(¥' + yuan(s.total) + ')无误?确认后将通知项目经理。',
    success: async (res) => {
      if (!res.confirm) return
      try {
        await confirmSalesOrder(s.id)
        uni.showToast({ title: '已确认', icon: 'none' })
        await load()
      } catch (e) {
        uni.showToast({ title: e.message || '确认失败', icon: 'none' })
      }
    }
  })
}

function goLogin() {
  uni.navigateTo({ url: '/pages/login/index?redirect=/pages/confirm/index' })
}
onShow(load)
onPullDownRefresh(async () => {
  await load()
  uni.stopPullDownRefresh()
})
</script>

<style scoped>
.page { padding: 24rpx 0 60rpx; }
.ticket { margin: 0 24rpx 24rpx; border: 1rpx solid var(--yz-line); background: var(--yz-paper); }
.tk-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14rpx 20rpx;
  background: var(--yz-paper-deep);
  border-bottom: 1rpx solid var(--yz-line);
}
.tk-name { font-size: 26rpx; color: var(--yz-ink); }
.stamp {
  width: 52rpx;
  height: 52rpx;
  line-height: 48rpx;
  text-align: center;
  font-size: 26rpx;
  border: 2rpx solid;
  box-sizing: border-box;
}
.st-ink { color: var(--yz-ink); border-color: var(--yz-ink); }
.st-seal { color: var(--yz-seal); border-color: var(--yz-seal); }
.tk-sub { padding: 12rpx 20rpx 0; font-size: 22rpx; color: var(--yz-ink-50); }
.item-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 10rpx 20rpx 0;
}
.item-name { font-size: 24rpx; color: var(--yz-ink); }
.item-amt { font-size: 24rpx; color: var(--yz-ink-62); }
.tk-total {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin: 16rpx 20rpx 0;
  padding-top: 12rpx;
  border-top: 1rpx solid var(--yz-line);
}
.amount { font-size: 30rpx; }
.tk-foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding: 14rpx 20rpx 18rpx;
  gap: 12rpx;
}
.go-confirm {
  color: var(--yz-paper);
  background: var(--yz-ink);
  padding: 8rpx 28rpx;
  border: 0;
}
.tip { color: var(--yz-ink-50); font-size: 22rpx; }
.empty { text-align: center; padding: 120rpx 40rpx; color: var(--yz-ink-50); }
</style>
