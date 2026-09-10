<script setup lang="ts">
/** 演示工具面板（业务组件）：冲突模拟 / L1 失效两个答辩演示按钮组。 */
import { ref } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { getAccessToken } from '@/api/tokenBox'

const courseId = ref(1)
const courseName = ref('程序设计基础(演示冲突)')
const evictKey = ref('course:1')
const busy = ref(false)
const lastResult = ref('')

async function debugPost(path: string, body: Record<string, unknown>): Promise<void> {
  busy.value = true
  try {
    const resp = await axios.post(`/api/v1/debug/${path}`, body, {
      headers: { Authorization: `Bearer ${getAccessToken() ?? ''}` },
      timeout: 20000,
    })
    lastResult.value = JSON.stringify(resp.data.data, null, 2)
    ElMessage.success('执行成功，请观察驾驶舱计数与详情数据')
  } catch (error) {
    const message = (error as { response?: { data?: { message?: string } } }).response?.data?.message
    lastResult.value = `执行失败：${message ?? String(error)}`
    ElMessage.error(lastResult.value)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="demo-panel">
    <el-divider content-position="left">模拟双节点并发写冲突（LWW 合并演示）</el-divider>
    <el-space wrap>
      <el-input-number v-model="courseId" :min="1" />
      <el-input v-model="courseName" style="width: 240px" />
      <el-button type="primary" :loading="busy" @click="debugPost('simulate-conflict', { courseId, courseName })">
        simulate-conflict
      </el-button>
    </el-space>

    <el-divider content-position="left">失效本机 L1（L2 回填演示）</el-divider>
    <el-space wrap>
      <el-input v-model="evictKey" style="width: 240px" />
      <el-button type="warning" :loading="busy" @click="debugPost('evict-local', { key: evictKey })">
        evict-local
      </el-button>
    </el-space>

    <el-divider content-position="left">最近一次执行结果</el-divider>
    <pre class="result">{{ lastResult || '（尚未执行）' }}</pre>
  </div>
</template>

<style scoped>
.result {
  background: #f6f8fa;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  padding: 10px 14px;
  font-size: 13px;
  max-height: 260px;
  overflow: auto;
}
</style>
