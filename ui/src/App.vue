<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { DocumentCopy, Delete, MagicStick, CircleCheck, UploadFilled } from '@element-plus/icons-vue'
import axios from 'axios'

// ==================== 类型定义 ====================

interface TranslateRequest {
  text: string
  from: string
  to: string
  domain?: string
  style?: string
  contextText?: string
}

interface ApiResponse<T> {
  code: number
  message: string
  data: T
  timestamp: number
}

interface TranslateResult {
  originalText: string
  translatedText: string
  from: string
  to: string
  domain: string
  style: string
  durationMs: number
  cached: boolean
}

// ==================== 常量 ====================

const API_BASE = '/api/v1'

const LANGUAGE_OPTIONS = [
  { label: '自动检测', value: 'auto' },
  { label: '中文', value: 'zh' },
  { label: '英文', value: 'en' },
  { label: '日文', value: 'ja' },
]

const DOMAIN_OPTIONS = [
  { label: '通用', value: 'general' },
  { label: '技术', value: 'tech' },
  { label: '医学', value: 'medical' },
  { label: '法律', value: 'legal' },
  { label: '商业', value: 'business' },
  { label: '文学', value: 'literary' },
]

const STYLE_OPTIONS = [
  { label: '中性', value: 'neutral' },
  { label: '正式', value: 'formal' },
  { label: '口语', value: 'casual' },
  { label: '学术', value: 'academic' },
]

// ==================== 状态 ====================

const inputText = ref('')
const fromLang = ref('auto')
const toLang = ref('en')
const domain = ref('general')
const style = ref('neutral')
const resultText = ref('')
const loading = ref(false)
const duration = ref(0)
const isCached = ref(false)

// 文件上传相关
const imageFile = ref<File | null>(null)
const audioFile = ref<File | null>(null)
const extractedText = ref('')
const recognizedText = ref('')
const activeTab = ref('text')

// ==================== API 封装 ====================

const api = axios.create({
  baseURL: API_BASE,
  timeout: 120000,
})

/**
 * 文本翻译（非流式）
 */
async function translateTextApi(params: TranslateRequest): Promise<ApiResponse<TranslateResult>> {
  const { data } = await api.post<ApiResponse<TranslateResult>>('/translate/text', params)
  return data
}

/**
 * 文本翻译（流式）
 */
async function translateTextStream(
  params: TranslateRequest,
  onChunk: (text: string) => void,
  onDone: () => void,
  onError: (error: Error) => void
) {
  try {
    const response = await fetch(`${API_BASE}/translate/text/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(params),
    })

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`)
    }

    const reader = response.body?.getReader()
    if (!reader) {
      throw new Error('无法读取响应流')
    }

    const decoder = new TextDecoder()
    let buffer = ''
    let fullText = ''
    let currentEvent = ''
    let currentData = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const rawLine of lines) {
        const line = rawLine.trim()
        
        // 跳过空行，遇到空行表示一条消息结束
        if (line === '') {
          // 处理已收集的消息
          if (currentEvent && currentData) {
            if (currentEvent === 'message') {
              try {
                const data = JSON.parse(currentData)
                if (data.text) {
                  fullText += data.text
                  onChunk(fullText)
                }
              } catch (e) {
                console.warn('解析 SSE 数据失败:', e)
              }
            } else if (currentEvent === 'done') {
              onDone()
              return
            }
          }
          currentEvent = ''
          currentData = ''
          continue
        }

        // 解析 event: 行
        if (line.startsWith('event:')) {
          currentEvent = line.substring(6).trim()
        }
        // 解析 data: 行
        else if (line.startsWith('data:')) {
          currentData = line.substring(5).trim()
        }
      }
    }

    // 处理最后剩余的数据
    if (currentEvent && currentData) {
      if (currentEvent === 'message') {
        try {
          const data = JSON.parse(currentData)
          if (data.text) {
            fullText += data.text
            onChunk(fullText)
          }
        } catch (e) {
          console.warn('解析 SSE 数据失败:', e)
        }
      } else if (currentEvent === 'done') {
        onDone()
      }
    }
  } catch (error) {
    onError(error as Error)
  }
}

// ==================== 事件处理 ====================

/**
 * 图片翻译（流式）
 */
async function translateImageStream(
  file: File,
  from: string,
  to: string,
  onChunk: (text: string) => void,
  onDone: () => void,
  onError: (error: Error) => void
) {
  try {
    const formData = new FormData()
    formData.append('file', file)
    formData.append('from', from)
    formData.append('to', to)

    const response = await fetch(`${API_BASE}/translate/image/stream`, {
      method: 'POST',
      body: formData,
    })

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`)
    }

    const reader = response.body?.getReader()
    if (!reader) {
      throw new Error('无法读取响应流')
    }

    const decoder = new TextDecoder()
    let buffer = ''
    let fullText = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const rawLine of lines) {
        const line = rawLine.trim()
        
        // 跳过空行
        if (line === '') continue
        
        // 解析 data: 行
        if (line.startsWith('data:')) {
          const dataStr = line.substring(5).trim()
          
          // 处理结束标记
          if (dataStr === '[DONE]') {
            onDone()
            return
          }
          
          try {
            const json = JSON.parse(dataStr)
            // 提取 content 字段
            const choices = json.choices
            if (choices && choices.length > 0) {
              const delta = choices[0].delta
              if (delta && delta.content) {
                fullText += delta.content
                onChunk(fullText)
              }
              // 检查是否完成
              if (choices[0].finish_reason === 'stop') {
                onDone()
                return
              }
            }
          } catch (e) {
            console.warn('解析 SSE 数据失败:', e, dataStr)
          }
        }
      }
    }

    onDone()
  } catch (error) {
    onError(error as Error)
  }
}

/**
 * 执行翻译
 */
async function handleTranslate() {
  if (activeTab.value === 'text') {
    if (!inputText.value.trim()) {
      ElMessage.warning('请输入要翻译的内容')
      return
    }
  } else if (activeTab.value === 'image') {
    if (!imageFile.value) {
      ElMessage.warning('请选择要翻译的图片')
      return
    }
  } else if (activeTab.value === 'audio') {
    if (!audioFile.value) {
      ElMessage.warning('请选择要翻译的音频')
      return
    }
  }

  loading.value = true
  resultText.value = ''
  extractedText.value = ''
  recognizedText.value = ''
  duration.value = 0
  isCached.value = false

  const startTime = Date.now()

  try {
    const from = fromLang.value === 'auto' ? 'auto' : fromLang.value

    if (activeTab.value === 'text') {
      // 文本翻译 - 使用流式输出
      const request: TranslateRequest = {
        text: inputText.value,
        from,
        to: toLang.value,
        domain: domain.value,
        style: style.value,
      }

      await translateTextStream(
        request,
        // onChunk: 实时更新翻译结果
        (text: string) => {
          resultText.value = text
        },
        // onDone: 翻译完成
        () => {
          duration.value = Date.now() - startTime
          loading.value = false
          ElMessage.success('翻译完成')
        },
        // onError: 错误处理
        (error: Error) => {
          console.error('流式翻译错误:', error)
          loading.value = false
          ElMessage.error('翻译服务异常，请检查后端是否启动')
        }
      )
    } else if (activeTab.value === 'image') {
      // 图片翻译 - 使用流式输出
      await translateImageStream(
        imageFile.value!,
        from,
        toLang.value,
        // onChunk: 实时更新翻译结果
        (text: string) => {
          resultText.value = text
        },
        // onDone: 翻译完成
        () => {
          duration.value = Date.now() - startTime
          loading.value = false
          ElMessage.success('图片翻译完成')
        },
        // onError: 错误处理
        (error: Error) => {
          console.error('图片翻译错误:', error)
          loading.value = false
          ElMessage.error('图片翻译服务异常')
        }
      )
    } else if (activeTab.value === 'audio') {
      // 语音翻译保持非流式
      const formData = new FormData()
      formData.append('file', audioFile.value!)
      formData.append('from', from)
      formData.append('to', toLang.value)
      const { data } = await api.post('/translate/audio', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
      if (data.code === 200) {
        recognizedText.value = data.data.recognizedText
        resultText.value = data.data.translatedText
        duration.value = data.data.durationMs
        ElMessage.success('语音翻译完成')
      } else {
        ElMessage.error(data.message || '语音翻译失败')
      }
    }
  } catch (err: any) {
    console.error('翻译错误:', err)
    ElMessage.error(err.response?.data?.message || '翻译服务异常，请检查后端是否启动')
  } finally {
    if (activeTab.value !== 'text') {
      loading.value = false
    }
  }
}

/**
 * 清空输入和结果
 */
function handleClear() {
  inputText.value = ''
  imageFile.value = null
  audioFile.value = null
  resultText.value = ''
  extractedText.value = ''
  recognizedText.value = ''
  duration.value = 0
  isCached.value = false
  ElMessage.info('已清空')
}

/**
 * 复制翻译结果
 */
async function handleCopy() {
  if (!resultText.value) {
    ElMessage.warning('没有可复制的内容')
    return
  }
  try {
    await navigator.clipboard.writeText(resultText.value)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.error('复制失败')
  }
}

/**
 * 图片选择变化
 */
function handleImageChange(file: any) {
  imageFile.value = file.raw
}

/**
 * 音频选择变化
 */
function handleAudioChange(file: any) {
  audioFile.value = file.raw
}
</script>

<template>
  <div class="translate-page">
    <!-- 顶部标题栏 -->
    <header class="page-header">
      <div class="header-content">
        <h1 class="title">
          <el-icon size="28" color="#409EFF"><MagicStick /></el-icon>
          <span>AI 翻译助手</span>
        </h1>
        <div class="status">
          <el-tag type="success" effect="light" size="small">
            <el-icon><CircleCheck /></el-icon>
            LM Studio 已连接
          </el-tag>
        </div>
      </div>
    </header>

    <!-- 主内容区 -->
    <main class="main-content">
      <!-- 输入模式切换 -->
      <el-tabs v-model="activeTab" type="border-card" class="input-tabs">
        <!-- 文本翻译 -->
        <el-tab-pane label="文本翻译" name="text">
          <el-input
            v-model="inputText"
            type="textarea"
            :rows="6"
            placeholder="请输入要翻译的内容"
            resize="vertical"
            maxlength="10000"
            show-word-limit
          />
        </el-tab-pane>

        <!-- 图片翻译 -->
        <el-tab-pane label="图片翻译" name="image">
          <el-upload
            drag
            action="#"
            :auto-upload="false"
            :on-change="handleImageChange"
            accept="image/*"
            class="upload-area"
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">
              拖拽图片到此处或 <em>点击上传</em>
            </div>
            <template #tip>
              <div class="el-upload__tip">
                支持 JPG/PNG/GIF/WEBP 格式，最大 50MB
              </div>
            </template>
          </el-upload>
        </el-tab-pane>

        <!-- 语音翻译 -->
        <el-tab-pane label="语音翻译" name="audio">
          <el-upload
            drag
            action="#"
            :auto-upload="false"
            :on-change="handleAudioChange"
            accept="audio/*"
            class="upload-area"
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">
              拖拽音频到此处或 <em>点击上传</em>
            </div>
            <template #tip>
              <div class="el-upload__tip">
                支持 MP3/WAV/M4A/OGG/FLAC 格式，最大 50MB
              </div>
            </template>
          </el-upload>
        </el-tab-pane>
      </el-tabs>

      <!-- 语言选择区 -->
      <el-card class="options-card" shadow="never">
        <div class="options-row">
          <div class="option-item">
            <span class="option-label">源语言</span>
            <el-select v-model="fromLang" placeholder="选择源语言" size="default">
              <el-option
                v-for="item in LANGUAGE_OPTIONS"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </div>

          <div class="option-item">
            <span class="option-label">目标语言</span>
            <el-select v-model="toLang" placeholder="选择目标语言" size="default">
              <el-option
                v-for="item in LANGUAGE_OPTIONS.filter(i => i.value !== 'auto')"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </div>

          <div class="option-item">
            <span class="option-label">领域</span>
            <el-select v-model="domain" placeholder="选择领域" size="default">
              <el-option
                v-for="item in DOMAIN_OPTIONS"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </div>

          <div class="option-item">
            <span class="option-label">风格</span>
            <el-select v-model="style" placeholder="选择风格" size="default">
              <el-option
                v-for="item in STYLE_OPTIONS"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </div>
        </div>
      </el-card>

      <!-- 操作按钮 -->
      <div class="action-bar">
        <el-button
        type="primary"
        size="large"
        :loading="loading"
        @click="handleTranslate"
      >
        <el-icon><MagicStick /></el-icon>
        翻译
      </el-button>
        <el-button
          size="large"
          @click="handleClear"
        >
          <el-icon><Delete /></el-icon>
          清空
        </el-button>
      </div>

      <!-- 翻译结果区 -->
      <el-card v-if="resultText" class="result-card" shadow="hover">
        <template #header>
          <div class="result-header">
            <span>翻译结果</span>
            <div class="result-meta">
              <el-tag v-if="isCached" type="warning" size="small">缓存</el-tag>
              <el-tag type="info" size="small">{{ duration }}ms</el-tag>
              <el-button
                type="primary"
                link
                size="small"
                @click="handleCopy"
              >
                <el-icon><DocumentCopy /></el-icon>
                复制
              </el-button>
            </div>
          </div>
        </template>
        <p class="result-text">{{ resultText }}</p>
      </el-card>
    </main>
  </div>
</template>

<style scoped>
.translate-page {
  min-height: 100vh;
  background: linear-gradient(135deg, #f5f7fa 0%, #e4e8ec 100%);
}

.page-header {
  background: #fff;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
  position: sticky;
  top: 0;
  z-index: 100;
}

.header-content {
  max-width: 800px;
  margin: 0 auto;
  padding: 16px 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.title {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
  color: #303133;
  display: flex;
  align-items: center;
  gap: 10px;
}

.status {
  display: flex;
  align-items: center;
}

.main-content {
  max-width: 800px;
  margin: 0 auto;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.input-tabs {
  background: #fff;
  border-radius: 8px;
  overflow: hidden;
}

.input-tabs :deep(.el-tabs__header) {
  margin: 0;
}

.input-tabs :deep(.el-tabs__content) {
  padding: 20px;
}

.upload-area {
  width: 100%;
}

.upload-area :deep(.el-upload) {
  width: 100%;
}

.upload-area :deep(.el-upload-dragger) {
  width: 100%;
  padding: 40px 20px;
}

.options-card {
  border-radius: 8px;
}

.options-card :deep(.el-card__body) {
  padding: 16px 20px;
}

.options-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.option-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.option-label {
  font-size: 13px;
  color: #606266;
  font-weight: 500;
}

.action-bar {
  display: flex;
  justify-content: center;
  gap: 16px;
  padding: 8px 0;
}

.result-card {
  border-radius: 8px;
  border-left: 4px solid #409EFF;
}

.result-card :deep(.el-card__header) {
  padding: 14px 20px;
  border-bottom: 1px solid #ebeef5;
}

.result-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
  color: #303133;
}

.result-meta {
  display: flex;
  align-items: center;
  gap: 8px;
}

.result-text {
  margin: 0;
  font-size: 15px;
  line-height: 1.8;
  color: #303133;
  white-space: pre-wrap;
  word-break: break-word;
}

/* 响应式适配 */
@media (max-width: 768px) {
  .options-row {
    grid-template-columns: repeat(2, 1fr);
  }

  .header-content {
    padding: 12px 16px;
  }

  .main-content {
    padding: 16px;
  }

  .title {
    font-size: 18px;
  }
}

@media (max-width: 480px) {
  .options-row {
    grid-template-columns: 1fr;
  }
}
</style>
