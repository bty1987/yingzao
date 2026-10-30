<template>
  <view class="page">
    <!-- 加载态 -->
    <view v-if="loading" class="state">
      <view class="yz-eyebrow">LOADING</view>
      <view class="state-t yz-display">正在展帖</view>
    </view>

    <!-- 失效态(280109 令牌无效 / 280103 已停用 / 网络失败) -->
    <view v-else-if="invalid" class="state">
      <view class="yz-seal yz-display">名</view>
      <view class="state-t yz-display">此帖暂时无法展示</view>
      <view class="tip">{{ invalidMsg }}</view>
      <view class="yz-eyebrow foot-eyebrow">YINGZAO · VISITING CARD</view>
    </view>

    <!-- 名帖:双框帖面 + 动作行 + 作品画册 + 留言笺 -->
    <block v-else>
      <view class="frame">
        <view class="frame-in">
          <view class="yz-eyebrow head-eyebrow">YINGZAO · VISITING CARD</view>

          <view class="who">
            <image v-if="card.userPhoto && !avatarBroken" :src="card.userPhoto" mode="aspectFill"
              class="avatar" @error="avatarBroken = true" />
            <view v-else class="yz-seal avatar-seal yz-display">{{ surname }}</view>
            <view class="who-t">
              <view class="yz-display name">{{ card.userName }}</view>
              <view v-if="duty" class="duty">{{ duty }}</view>
            </view>
          </view>

          <view v-if="card.info" class="info">{{ card.info }}</view>

          <view class="rows">
            <view v-if="card.phone" class="row">
              <text class="k">电话</text>
              <text class="v">{{ card.phone }}</text>
            </view>
            <view v-if="card.wechat" class="row">
              <text class="k">微信</text>
              <text class="v">{{ card.wechat }}</text>
            </view>
            <view v-if="card.address" class="row">
              <text class="k">地址</text>
              <text class="v">{{ card.address }}</text>
            </view>
          </view>
        </view>
      </view>

      <!-- 动作行:拨号/复制微信,访客三秒内可联系(装饰退后) -->
      <view v-if="card.phone || card.wechat" class="acts">
        <view v-if="card.phone" class="btn-ghost act" @click="call">拨打电话</view>
        <view v-if="card.wechat" class="btn-ghost act" @click="copyWechat">复制微信号</view>
      </view>

      <!-- 作品画册:图即结构,无卡片边框 -->
      <view v-if="photoUrls.length" class="sec">
        <view class="sec-h">
          <text class="yz-display sec-t">作 品</text>
          <view class="sec-line"></view>
        </view>
        <scroll-view scroll-x class="gallery" :show-scrollbar="false">
          <image v-for="(p, i) in card.photos" :key="p.id" :src="p.url" mode="aspectFill"
            class="shot" @click="preview(i)" />
        </scroll-view>
      </view>

      <!-- 留言笺:帖尾落款处递帖见礼 -->
      <view class="sec">
        <view class="sec-h">
          <text class="yz-display sec-t">留 言</text>
          <view class="sec-line"></view>
        </view>
        <view v-if="done" class="done">
          <text class="done-seal yz-display">讫</text>
          <view class="done-t">留言已收到,{{ card.userName }}会尽快与您联系</view>
          <view class="done-again" @click="done = false">换个号码再写一条</view>
        </view>
        <view v-else class="form">
          <view class="field">
            <text class="label">称呼</text>
            <input v-model="name" maxlength="64" placeholder="怎么称呼您" />
          </view>
          <view class="field">
            <text class="label">手机</text>
            <input v-model="phone" type="number" maxlength="11" placeholder="方便联系您的手机号" />
          </view>
          <view class="field area">
            <text class="label">想说</text>
            <textarea v-model="comment" maxlength="500" :auto-height="true" class="ta"
              placeholder="房屋情况、意向或任何想聊的(选填)" />
          </view>
          <view class="btn-primary go" :class="{ off: submitting }" @click="submit">
            {{ submitting ? '正在递帖…' : '递 名 帖' }}
          </view>
          <view class="tip center">留下称呼与手机,顾问会主动联系您</view>
        </view>
      </view>

      <view class="end">
        <view class="end-line"></view>
        <view class="yz-eyebrow">营造 · 家</view>
        <view class="end-line"></view>
      </view>
    </block>
  </view>
</template>

<script setup>
import { ref, computed } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { cardByToken, submitCardNotice } from '../../api'

// 匿名页:share_token 即路由锚(R6 契约 /api/c/cards/{token},内核白名单免登)。
const token = ref('')
const loading = ref(true)
const invalid = ref(false)
const invalidMsg = ref('')
const card = ref({})
const avatarBroken = ref(false)
const done = ref(false)
const name = ref('')
const phone = ref('')
const comment = ref('')
const submitting = ref(false)

const surname = computed(() => (card.value.userName || '名').slice(0, 1))
const duty = computed(() => [card.value.profession, card.value.industry].filter(Boolean).join(' · '))
const photoUrls = computed(() => (card.value.photos || []).map((p) => p.url))

onLoad((q) => {
  token.value = q.token || ''
  if (!token.value) {
    loading.value = false
    invalid.value = true
    invalidMsg.value = '链接不完整,请从分享卡片重新进入'
    return
  }
  load()
})

async function load() {
  loading.value = true
  try {
    const res = await cardByToken(token.value)
    card.value = res.data || {}
    if (card.value.userName) uni.setNavigationBarTitle({ title: card.value.userName + ' · 名片' })
  } catch (e) {
    invalid.value = true
    invalidMsg.value = e.message || '名片无法加载'
  } finally {
    loading.value = false
  }
}

function call() {
  uni.makePhoneCall({ phoneNumber: card.value.phone })
}

function copyWechat() {
  uni.setClipboardData({
    data: card.value.wechat,
    success: () => uni.showToast({ title: '微信号已复制', icon: 'none' })
  })
}

function preview(i) {
  uni.previewImage({ urls: photoUrls.value, current: i })
}

// 前端校验与后端守卫同口径:称呼必填≤64 / 手机 ^1\d{10}$ / 留言≤500;
// 同人同卡重复留资=后端幂等回显(duplicate=true),文案正向提示不吓访客。
async function submit() {
  if (submitting.value) return
  if (!name.value.trim()) return uni.showToast({ title: '请填写您的称呼', icon: 'none' })
  if (!/^1\d{10}$/.test(phone.value.trim())) {
    return uni.showToast({ title: '请输入正确手机号', icon: 'none' })
  }
  if (comment.value.length > 500) return uni.showToast({ title: '留言最多 500 字', icon: 'none' })
  submitting.value = true
  try {
    const res = await submitCardNotice(token.value, {
      name: name.value.trim(),
      phone: phone.value.trim(),
      comment: comment.value.trim()
    })
    done.value = true
    uni.showToast({
      title: res.data && res.data.duplicate ? '您已留过言,我们会尽快与您联系' : '留言已收到,我们会尽快与您联系',
      icon: 'none'
    })
  } catch (e) {
    uni.showToast({ title: e.message, icon: 'none' })
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.page { padding: 32rpx 32rpx 60rpx; }

/* ---- 帖面:外粗内细双框(C1) ---- */
.frame {
  border: 2rpx solid var(--yz-line-strong);
  padding: 8rpx;
  box-sizing: border-box;
}
.frame-in {
  border: 1rpx solid var(--yz-line);
  padding: 44rpx 36rpx 36rpx;
}
.head-eyebrow { text-align: center; margin-bottom: 40rpx; }

.who { display: flex; align-items: center; gap: 28rpx; }
.avatar { width: 128rpx; height: 128rpx; flex-shrink: 0; }
.avatar-seal { width: 128rpx; height: 128rpx; font-size: 56rpx; }
.who-t { display: flex; flex-direction: column; gap: 10rpx; min-width: 0; }
.name { font-size: 52rpx; line-height: 1.15; }
.duty { font-size: 24rpx; color: var(--yz-ink-50); letter-spacing: 2rpx; }

.info {
  margin-top: 32rpx; padding-top: 28rpx;
  border-top: 1rpx solid var(--yz-line);
  font-size: 26rpx; color: var(--yz-ink-70); line-height: 1.8;
}

.rows { margin-top: 24rpx; }
.row {
  display: flex; gap: 24rpx; padding: 18rpx 0;
  border-top: 1rpx solid var(--yz-line);
  font-size: 26rpx;
}
.row:first-child { border-top: none; }
.k { width: 88rpx; color: var(--yz-ink-35); flex-shrink: 0; }
.v { flex: 1; color: var(--yz-ink-70); word-break: break-all; }

/* ---- 动作行(C2) ---- */
.acts { display: flex; gap: 20rpx; margin: 28rpx 0 8rpx; }
.act { flex: 1; }

/* ---- 节标题:宋体 + 墨线 ---- */
.sec { margin-top: 44rpx; }
.sec-h { display: flex; align-items: center; gap: 24rpx; margin-bottom: 28rpx; }
.sec-t { font-size: 32rpx; letter-spacing: 6rpx; flex-shrink: 0; }
.sec-line { flex: 1; height: 1rpx; background: var(--yz-line); }

.gallery { white-space: nowrap; width: 100%; box-sizing: border-box; }
.shot {
  display: inline-block; width: 320rpx; height: 240rpx;
  margin-right: 20rpx; background: var(--yz-paper-deep);
}

/* ---- 留言笺:underline 字段(login 同款语言) ---- */
.form { display: flex; flex-direction: column; gap: 28rpx; }
.field {
  display: flex; align-items: center; gap: 16rpx;
  border-bottom: 1rpx solid var(--yz-line-strong); padding: 16rpx 4rpx;
}
.field.area { align-items: flex-start; }
.label { width: 88rpx; font-size: 26rpx; color: var(--yz-ink-70); flex-shrink: 0; }
.field input { flex: 1; min-width: 0; font-size: 30rpx; }
.ta { flex: 1; min-height: 120rpx; font-size: 28rpx; line-height: 1.7; width: 100%; }
.go { margin-top: 12rpx; }
.go.off { opacity: 0.55; }
.center { text-align: center; margin-top: 4rpx; }

/* ---- 留毕(讫) ---- */
.done {
  display: flex; flex-direction: column; align-items: center; gap: 20rpx;
  padding: 40rpx 0 24rpx;
}
.done-seal {
  width: 88rpx; height: 88rpx; font-size: 40rpx;
  background: var(--yz-seal); color: var(--yz-paper);
  display: flex; align-items: center; justify-content: center;
}
.done-t { font-size: 26rpx; color: var(--yz-ink-70); }
.done-again { font-size: 24rpx; color: var(--yz-ink-35); text-decoration: underline; }

/* ---- 落款 ---- */
.end {
  display: flex; align-items: center; justify-content: center; gap: 24rpx;
  margin-top: 64rpx;
}
.end-line { width: 96rpx; height: 1rpx; background: var(--yz-line); }

/* ---- 状态页 ---- */
.state {
  display: flex; flex-direction: column; align-items: center; gap: 24rpx;
  padding: 220rpx 0 120rpx;
}
.state-t { font-size: 36rpx; }
.foot-eyebrow { margin-top: 32rpx; }
</style>
