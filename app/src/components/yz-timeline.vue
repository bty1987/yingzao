<template>
  <view class="ytl">
    <view v-for="(item, i) in items" :key="i" class="row">
      <view class="rail">
        <view class="dot" :class="toneClass(item.tone)" />
        <view v-if="i < items.length - 1" class="line" />
      </view>
      <view class="body">
        <view class="head">
          <text class="title" :class="{ 'title-seal': item.tone === 'seal' }">{{ item.title }}</text>
          <text v-if="item.time" class="time">{{ item.time }}</text>
        </view>
        <text v-if="item.desc" class="desc">{{ item.desc }}</text>
        <slot name="item" :item="item" :index="i" />
      </view>
    </view>
  </view>
</template>

<script setup>
// 时间轴(docs/15 R12 移动端组件基建):纵向墨线+节点圆点,施工节点/审批流转/单据留痕展示用。
// item = { title, time, desc, tone };tone:ink(默认墨点)/ seal(朱点=异常/驳回/超期)
// / moss(苔绿=完成)/ ochre(赭金=预警);当前节点把 dot 传 current 加实心强调。
const props = defineProps({
  items: { type: Array, default: () => [] },
})

function toneClass(tone) {
  return tone && tone !== 'ink' ? 'dot-' + tone : ''
}
</script>

<style scoped>
.ytl { padding: 8rpx 0; }
.row { display: flex; }
.rail { display: flex; flex-direction: column; align-items: center; width: 40rpx; flex-shrink: 0; }
.dot {
  width: 18rpx;
  height: 18rpx;
  border-radius: 50%;
  border: 3rpx solid var(--yz-ink-70);
  background: var(--yz-paper);
  margin-top: 8rpx;
  box-sizing: border-box;
}
.dot.dot-seal { border-color: var(--yz-seal); background: var(--yz-seal); }
.dot.dot-moss { border-color: var(--yz-moss); background: var(--yz-moss); }
.dot.dot-ochre { border-color: var(--yz-ochre); background: var(--yz-ochre); }
.line { width: 1rpx; flex: 1; background: var(--yz-line); margin: 4rpx 0; }
.body { flex: 1; padding: 0 0 32rpx 16rpx; min-width: 0; }
.row:last-child .body { padding-bottom: 8rpx; }
.head { display: flex; align-items: baseline; justify-content: space-between; }
.title { font-size: 28rpx; color: var(--yz-ink); font-weight: 600; }
.title-seal { color: var(--yz-seal); }
.time { font-size: 22rpx; color: var(--yz-ink-35); margin-left: 16rpx; flex-shrink: 0; }
.desc { display: block; font-size: 24rpx; color: var(--yz-ink-50); margin-top: 6rpx; line-height: 1.6; }
</style>
