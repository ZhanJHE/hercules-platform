import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// dev 代理 /api → 网关（8081）；可用 VITE_API_TARGET 覆盖（如直连应用 8080）
const apiTarget = process.env.VITE_API_TARGET ?? 'http://localhost:8081'

export default defineConfig({
  plugins: [
    vue(),
    AutoImport({ resolvers: [ElementPlusResolver()] }),
    Components({ resolvers: [ElementPlusResolver()] }),
  ],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: apiTarget, changeOrigin: true },
    },
  },
  build: {
    // element-plus / echarts 体积较大：拆分 vendor chunk，避免单包超限警告
    rollupOptions: {
      output: {
        manualChunks: {
          'vendor-element-plus': ['element-plus', '@element-plus/icons-vue'],
          'vendor-echarts': ['echarts'],
          'vendor-vue': ['vue', 'vue-router', 'pinia', 'axios'],
        },
      },
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.spec.ts'],
  },
})
