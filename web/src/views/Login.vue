<template>
  <div class="login-page">
    <div class="frame">
      <div class="brand">
        <div class="brand-row">
          <h1 class="yz-display">营造</h1>
          <span class="yz-seal-stamp">法式</span>
        </div>
        <p class="tagline yz-display">现代装修企业的营造法式</p>
        <p class="latin">YINGZAO · THE OPEN-SOURCE ERP FOR RENOVATION</p>
      </div>

      <div class="divider" />

      <el-form class="form" :model="form" @keyup.enter="onLogin">
        <el-form-item>
          <el-input v-model="form.username" placeholder="用户名" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" placeholder="密码" show-password />
        </el-form-item>
        <button type="button" class="submit yz-display" :disabled="loading" @click="onLogin">
          {{ loading ? '…' : '登录' }}
        </button>
        <p class="hint">初始账号 admin / yingzao123</p>
      </el-form>
    </div>
    <footer class="page-foot yz-display">料例既定 · 规矩先立 ——《营造法式》</footer>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login } from '../api'

const router = useRouter()
const form = reactive({ username: '', password: '' })
const loading = ref(false)

async function onLogin() {
  if (!form.username || !form.password) return ElMessage.warning('请输入用户名与密码')
  loading.value = true
  try {
    const res = await login(form)
    localStorage.setItem('yz_token', res.data.token)
    localStorage.setItem('yz_user_id', String(res.data.userId))
    localStorage.setItem('yz_display_name', res.data.displayName)
    localStorage.setItem('yz_roles', JSON.stringify(res.data.roles || []))
    router.push('/')
  } catch (e) {
    ElMessage.error(e.message || '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
/* 古籍扉页构图:双线版框围合,宋体大字+朱印,8px 模数 */
.login-page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: var(--yz-paper);
  padding: 64px 24px;
  box-sizing: border-box;
}
.frame {
  width: 440px;
  max-width: 100%;
  padding: 56px 48px 48px;
  border: 1px solid var(--yz-line);
  outline: 1px solid var(--yz-line);
  outline-offset: 4px; /* 双线版框 */
  background: var(--yz-paper);
  text-align: center;
}
.brand-row {
  display: flex;
  align-items: flex-start;
  justify-content: center;
  gap: 12px;
}
.brand-row h1 {
  margin: 0;
  font-size: 64px;
  line-height: 1;
  letter-spacing: 16px;
  margin-right: -16px; /* 抵消末字间距,保持视觉居中 */
  font-weight: 600;
}
.tagline {
  margin: 24px 0 8px;
  font-size: 15px;
  letter-spacing: 4px;
  color: var(--yz-ink-70);
}
.latin {
  margin: 0 0 40px;
  font-size: 10px;
  letter-spacing: 3px;
  color: var(--yz-ink-50);
}
.divider {
  height: 1px;
  background: var(--yz-line);
  margin: 0 0 40px;
}
.form :deep(.el-input__wrapper) {
  background: transparent;
  box-shadow: none;
  border-bottom: 1px solid var(--yz-line-strong);
  border-radius: 0;
  padding-left: 0;
}
.form :deep(.el-input__inner) { font-size: 15px; }
.submit {
  width: 100%;
  margin-top: 24px;
  padding: 12px 0;
  background: var(--yz-ink);
  color: var(--yz-paper);
  border: none;
  font-size: 17px;
  letter-spacing: 12px;
  text-indent: 12px; /* 视觉居中补偿 */
  cursor: pointer;
}
.submit:hover:not(:disabled) { background: var(--yz-seal-deep); }
.submit:disabled { opacity: 0.6; cursor: default; }
.hint { margin: 16px 0 0; font-size: 12px; color: var(--yz-ink-50); }
.page-foot {
  margin-top: 48px;
  font-size: 12px;
  letter-spacing: 2px;
  color: var(--yz-ink-50);
}
</style>
