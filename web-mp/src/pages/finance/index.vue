<template>
  <view class="page">
    <view v-if="!logged" class="empty">
      <view class="yz-display" style="font-size: 36rpx; margin-bottom: 16rpx">资金流水</view>
      <view class="tip" style="margin-bottom: 40rpx">登录后可查看装修款与主材款收付票据</view>
      <view class="btn-primary go" @click="goLogin">手机验证码登录</view>
    </view>
    <template v-else>
      <view v-if="!loading && sections.length === 0" class="empty">名下暂无装修单款项</view>
      <view v-for="s in sections" :key="s.orderId" class="order-sec">
        <view class="sec-head">
          <view class="yz-display no">{{ s.orderNo }}</view>
          <view class="tip">{{ s.houseLabel }}</view>
        </view>

        <!-- 装修款:一笔一票(方向二票据卡) -->
        <view class="group-title yz-eyebrow">装修款</view>
        <view v-for="b in s.decoration" :key="'d' + b.billId" class="ticket">
          <view class="tk-head">
            <text class="tk-name">{{ b.typeName }}</text>
            <text class="stamp" :class="stampClass(b)">{{ stampChar(b) }}</text>
          </view>
          <view class="tk-body">
            <text class="amount yz-display">¥{{ yuan(b.amount) }}</text>
            <text class="tip">{{ statusText(b) }}</text>
          </view>
        </view>
        <view v-if="!s.decoration.length" class="tip pad">暂无装修款票据</view>

        <!-- 主材款:每张销售单一组 -->
        <template v-if="s.material.length">
          <view class="group-title yz-eyebrow">主材款
            <text class="go-confirm" @click="goConfirm">去核验确认 →</text>
          </view>
          <view v-for="g in s.material" :key="g.salesOrderId" class="mat-group">
            <view class="mat-head">
              <text class="mat-no">{{ g.salesNo }}</text>
            </view>
            <view v-for="b in g.bills" :key="'m' + b.billId" class="ticket slim">
              <view class="tk-head">
                <text class="tk-name">{{ b.typeName }}</text>
                <text class="stamp" :class="stampClass(b)">{{ stampChar(b) }}</text>
              </view>
              <view class="tk-body">
                <text class="amount yz-display">¥{{ yuan(b.amount) }}</text>
                <text class="tip">{{ statusText(b) }}</text>
              </view>
            </view>
          </view>
        </template>
      </view>

      <!-- 待付票脚注:线上支付渠道接入后开放(C2,凭证到位即插队) -->
      <view class="foot-tip">线上支付渠道接入后,可直接在线缴纳「候」票</view>
    </template>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app'
import { fetchFinance } from '../../api'
import { getToken } from '../../api/http'

// 资金流水(R20,拍板=方向二票据卡):一笔一票,状态方章——
// 收款票已收/已确认=墨印「讫」;待收=朱印「候」;退款票/已退款=赭金「退」。
// 金额厘→元前端换算;票据状态语义后端原样下发(direction/status 整数)。
const sections = ref([])
const loading = ref(false)
const logged = ref(false)

const yuan = (li) => (((li || 0) / 1000)).toLocaleString('zh-CN', { maximumFractionDigits: 2 })

function stampChar(b) {
  if (b.direction === 1 || b.status === 3) return '退'
  if (b.status === 0) return '候'
  return '讫'
}
function stampClass(b) {
  if (b.direction === 1 || b.status === 3) return 'st-ochre'
  if (b.status === 0) return 'st-seal'
  return 'st-ink'
}
function statusText(b) {
  if (b.direction === 1) return '退款票据'
  const map = { 0: '待付', 1: '已付', 2: '已付(已确认)', 3: '已退款' }
  return map[b.status] || ''
}

async function load() {
  if (!getToken()) {
    logged.value = false
    return
  }
  logged.value = true
  loading.value = true
  try {
    const res = await fetchFinance()
    sections.value = res.data || []
  } catch (e) {
    uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}
function goConfirm() {
  uni.navigateTo({ url: '/pages/confirm/index' })
}
function goLogin() {
  uni.navigateTo({ url: '/pages/login/index?redirect=/pages/finance/index' })
}
onShow(load)
onPullDownRefresh(async () => {
  await load()
  uni.stopPullDownRefresh()
})
</script>

<style scoped>
.page { padding: 24rpx 0 60rpx; }
.order-sec { margin-bottom: 28rpx; }
.sec-head {
  display: flex;
  align-items: baseline;
  gap: 16rpx;
  padding: 8rpx 24rpx;
}
.no { font-size: 32rpx; }
.group-title { padding: 16rpx 24rpx 8rpx; color: var(--yz-ink-50); }

/* 票据卡(方向二):票头纸深+状态方章 */
.ticket {
  margin: 12rpx 24rpx;
  border: 1rpx solid var(--yz-line);
  background: var(--yz-paper);
}
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
.st-ochre { color: #7a5e2a; border-color: #7a5e2a; }
.tk-body {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 16rpx 20rpx;
}
.amount { font-size: 34rpx; }
.ticket.slim { margin: 12rpx 24rpx 0 40rpx; }
.mat-group { margin-bottom: 8rpx; }
.mat-head { padding: 8rpx 40rpx 0; }
.mat-no { font-size: 24rpx; color: var(--yz-ink-50); }

.go-confirm {
  font-size: 22rpx;
  color: var(--yz-ink-50);
  font-weight: normal;
  margin-left: 16rpx;
}
.foot-tip {
  text-align: center;
  margin-top: 40rpx;
  font-size: 24rpx;
  color: var(--yz-ink-35);
}
.empty { text-align: center; padding: 120rpx 40rpx; color: var(--yz-ink-50); }
.tip { color: var(--yz-ink-50); font-size: 24rpx; }
.pad { padding: 8rpx 24rpx; }
</style>
