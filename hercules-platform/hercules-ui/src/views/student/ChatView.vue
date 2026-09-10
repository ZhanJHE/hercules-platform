<script setup lang="ts">
/** 对话助手（阶段 D 贯通）：SSE 流式渲染 + meta 候选卡 + 确认制选课。 */
import { nextTick, onMounted, ref, watch } from 'vue'
import PageHeader from '@/components/common/PageHeader.vue'
import { useChatStore } from '@/stores/chat'
import { useEnrollmentStore } from '@/stores/enrollment'

const chat = useChatStore()
const enrollmentStore = useEnrollmentStore()

const input = ref('')
const listRef = ref<HTMLElement | null>(null)

onMounted(() => {
  chat.reset()
})

// 流式过程中自动滚动到底部
watch(
  () => chat.messages.map(m => m.content.length).join(','),
  () => {
    void nextTick(() => {
      listRef.value?.scrollTo({ top: listRef.value.scrollHeight })
    })
  },
)

function send(): void {
  void chat.send(input.value)
  input.value = ''
}

function stop(): void {
  chat.stop()
}

/** 候选卡片按钮：发起选课请求（触发冲突校验 + 待确认登记）。 */
function pickCourse(code: string): void {
  void chat.send(`帮我选 ${code}`)
}

/** 校验通过后的确认按钮：执行待确认请求。 */
function confirmPending(): void {
  void chat.send('确认')
  void enrollmentStore.fetchMine()
}
</script>

<template>
  <div class="chat-page">
    <PageHeader title="对话助手" subtitle="自然语言选课：推荐 → 冲突校验 → 确认执行" />

    <div ref="listRef" class="message-list">
      <el-empty v-if="chat.messages.length === 0" description="试着问：推荐几门 3 学分的课" />
      <div
        v-for="(message, index) in chat.messages"
        :key="index"
        class="row"
        :class="message.role"
      >
        <div class="bubble" :class="{ error: message.error }">
          <span>{{ message.content }}</span>
          <span v-if="message.streaming" class="cursor">▌</span>
        </div>

        <!-- 候选卡片（meta.candidates 存在时渲染在对应 assistant 消息之后） -->
        <div v-if="message.role === 'assistant' && message.meta?.candidates?.length" class="candidates">
          <div
            v-for="candidate in message.meta.candidates"
            :key="candidate.id"
            class="candidate"
          >
            <div class="c-info">
              <b>[{{ candidate.code }}] {{ candidate.name }}</b>
              <span class="c-credit">{{ candidate.credit }} 学分</span>
              <el-tag v-if="candidate.conflicted" size="small" type="danger" effect="plain">
                {{ candidate.conflicts.join('；') }}
              </el-tag>
            </div>
            <el-button
              v-if="!candidate.conflicted"
              size="small"
              type="primary"
              plain
              :disabled="chat.sending"
              @click="pickCourse(candidate.code)"
            >选课</el-button>
          </div>
        </div>

        <!-- 校验通过提示下的确认按钮 -->
        <div
          v-if="message.role === 'assistant' && message.meta?.passed"
          class="confirm-row"
        >
          <el-button type="primary" :disabled="chat.sending" @click="confirmPending">确认选课</el-button>
        </div>
      </div>
    </div>

    <div class="input-bar">
      <el-input
        v-model="input"
        placeholder="例如：推荐几门 3 学分的课 / 帮我选 CS101"
        :disabled="chat.sending"
        @keyup.enter="send"
      />
      <el-button v-if="!chat.sending" type="primary" :disabled="!input.trim()" @click="send">发送</el-button>
      <el-button v-else type="warning" @click="stop">停止</el-button>
    </div>
  </div>
</template>

<style scoped>
.chat-page {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 140px);
}
.message-list {
  flex: 1;
  overflow: auto;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 16px;
}
.row {
  display: flex;
  flex-direction: column;
  margin-bottom: 12px;
}
.row.user {
  align-items: flex-end;
}
.bubble {
  max-width: 76%;
  padding: 9px 13px;
  border-radius: 10px;
  white-space: pre-wrap;
  font-size: 14px;
  line-height: 1.6;
}
.row.user .bubble {
  background: #1677ff;
  color: #fff;
}
.row.assistant .bubble {
  background: #f6f8fa;
  border: 1px solid #e4e7ed;
}
.bubble.error {
  background: #fdf0ef;
  border-color: #f3c2c0;
  color: #c62828;
}
.cursor {
  animation: blink 1s infinite;
}
@keyframes blink {
  50% { opacity: 0; }
}
.candidates {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.candidate {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 8px 12px;
  background: #fff;
  max-width: 620px;
}
.c-info {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}
.c-credit {
  color: #57606a;
}
.confirm-row {
  margin-top: 8px;
}
.input-bar {
  display: flex;
  gap: 10px;
  margin-top: 12px;
}
</style>
