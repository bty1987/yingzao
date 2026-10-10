<template>
  <div class="login-wrap">
    <el-card class="login-card">
      <div class="brand">
        <h1>营造</h1>
        <p>现代装修企业的营造法式</p>
      </div>
      <el-form :model="form" @keyup.enter="onLogin">
        <el-form-item>
          <el-input v-model="form.username" placeholder="用户名" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" placeholder="密码" show-password />
        </el-form-item>
        <el-button type="primary" style="width: 100%" :loading="loading" @click="onLogin">登 录</el-button>
      </el-form>
    </el-card>
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
    localStorage.setItem('yz_display_name', res.data.displayName)
    router.push('/')
  } catch (e) {
    ElMessage.error(e.message || '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-wrap { display: flex; align-items: center; justify-content: center; height: 100vh; background: #f5f7fa; }
.login-card { width: 360px; }
.brand { text-align: center; margin-bottom: 24px; }
.brand h1 { margin: 0; letter-spacing: 8px; }
.brand p { margin: 8px 0 0; color: #909399; font-size: 12px; }
</style>
