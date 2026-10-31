<template>
  <view class="ygrid" :style="{ '--cols': columns }">
    <view
      v-for="item in items"
      :key="item.key"
      class="gcell"
      :class="{ disabled: item.disabled }"
      @tap="onTap(item)"
    >
      <view v-if="item.icon" class="icon"><text class="icon-text yz-display">{{ item.icon }}</text></view>
      <text class="gtitle">{{ item.title }}</text>
      <text v-if="item.sub" class="gsub">{{ item.sub }}</text>
    </view>
  </view>
</template>

<script setup>
// 宫格(docs/15 R12 移动端组件基建,工作台配置化宫格 R14 的基础件):
// items = [{ key, icon, title, sub?, disabled? }];icon 传单字(宋体方章式墨线框,纸墨体系延伸),
// 或不传只留标题。格子无底色=整页一纸,列间以间距分界(R14 配置化渲染时按 GET /api/addons
// 槽位下发 items)。禁用格置灰且不触发 tap。
const props = defineProps({
  items: { type: Array, default: () => [] },
  columns: { type: Number, default: 4 },
})
const emit = defineEmits(['tap'])

function onTap(item) {
  if (item.disabled) return
  emit('tap', item)
}
</script>

<style scoped>
.ygrid { display: flex; flex-wrap: wrap; }
.gcell {
  width: calc(100% / var(--cols));
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 24rpx 8rpx;
  box-sizing: border-box;
}
.icon {
  width: 88rpx;
  height: 88rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1rpx solid var(--yz-line-strong);
  border-radius: 4rpx;
  background: var(--yz-paper);
}
.icon-text {
  font-size: 40rpx;
  color: var(--yz-ink);
  line-height: 1;
}
.gtitle { font-size: 26rpx; color: var(--yz-ink); margin-top: 14rpx; }
.gsub { font-size: 20rpx; color: var(--yz-ink-35); margin-top: 4rpx; }
.gcell:active { background: var(--yz-paper-deep); }
.gcell.disabled { opacity: 0.35; }
</style>
