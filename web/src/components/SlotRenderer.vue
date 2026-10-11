<template>
  <div class="slot-placeholder">
    <p><strong>{{ mount.component }}</strong>(插件组件占位)</p>
    <p class="hint">
      槽位 <code>{{ mount.slot }}</code> 由插件声明、后端下发。正式运行时此处渲染插件前端包提供的同名组件
      (defineAsyncComponent 动态注册);当前插件前端包尚未发布,显示占位。capability =
      <code>{{ mount.capability }}</code>(渲染前须通过功能权限校验)。
    </p>
  </div>
</template>

<script setup>
// 槽位渲染器:按后端下发的 mount 声明渲染插件组件。
// 正式版:组件来自插件前端包(构建产物携带组件清单),loader 用 defineAsyncComponent 注册后按 component 名解析;
// spike 阶段插件前端包未发布,渲染占位卡片。
defineProps({ mount: { type: Object, required: true } })
</script>

<style scoped>
.slot-placeholder { color: var(--yz-ink-70); }
.hint { font-size: 12px; color: var(--yz-ink-50); line-height: 1.8; }
code { background: var(--yz-paper-deep); padding: 0 4px; border-radius: 3px; }
</style>
