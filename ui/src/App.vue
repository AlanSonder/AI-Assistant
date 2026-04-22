<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { DocumentCopy, Delete, MagicStick } from '@element-plus/icons-vue'
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

interface ImageTranslateResult {
  extractedText: string
  translatedText: string
  from: string
  to: string
  durationMs: number
}

interface AudioTranslateResult {
  recognizedText: string
  translatedText: string
  from: string
  to: string
  durationMs: number
  audioDurationMs: number
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
 * 文本翻译
 */
async function translateTextApi(params: TranslateRequest): Promise<ApiResponse<TranslateResult>> {
  const { data } = await api.post<ApiResponse<TranslateResult>>('/translate/text', params)
  return data
}

/**
 * 图片翻译
 */
async function translateImageApi(file: File, from: string, to: string): Promise<ApiResponse<ImageTranslateResult>> {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('from', from)
  formData.append('to', to)
  const { data } = await api.post<ApiResponse<ImageTranslateResult>>('/translate/image', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
  return data
}

/**
 * 语音翻译
 */
async function translateAudioApi(file: File, from: string, to: string): Promise<ApiResponse<AudioTranslateResult>> {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('from', from)
  formData.append('to', to)
  const { data } = await api.post<ApiResponse<AudioTranslateResult>>('/translate/audio', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
  return data
}

// ==================== 事件处理 ====================

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

  try {
    const from = fromLang.value === 'auto' ? 'auto' : fromLang.value

    if (activeTab.value === 'text') {
      const res = await translateTextApi({
        text: inputText.value,
        from,
        to: toLang.value,
        domain: domain.value,
        style: style.value,
      })

      if (res.code === 200) {
        resultText.value = res.data.translatedText
        duration.value = res.data.durationMs
        isCached.value = res.data.cached
        ElMessage.success(isCached.value ? '翻译完成（来自缓存）' : '翻译完成')
      } else {
        ElMessage.error(res.message || '翻译失败')
      }
    } else if (activeTab.value === 'image') {
      const res = await translateImageApi(imageFile.value!, from, toLang.value)
      if (res.code === 200) {
        extractedText.value = res.data.extractedText
        resultText.value = res.data.translatedText
        duration.value = res.data.durationMs
        ElMessage.success('图片翻译完成')
      } else {
        ElMessage.error(res.message || '图片翻译失败')
      }
    } else if (activeTab.value === 'audio') {
      const res = await translateAudioApi(audioFile.value!, from, toLang.value)
      if (res.code === 200) {
        recognizedText.value = res.data.recognizedText
        resultText.value = res.data.translatedText
        duration.value = res.data.durationMs
        ElMessage.success('语音翻译完成')
      } else {
        ElMessage.error(res.message || '语音翻译失败')
      }
    }
  } catch (err: any) {
    console.error('翻译错误:', err)
    ElMessage.error(err.response?.data?.message || '翻译服务异常，请检查后端是否启动')
  } finally {
    loading.value = false
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
            <el-icon class="el-icon--upload"><upload-filled /></el-icon>
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
            <el-icon class="el-icon--upload"><upload-filled /></el-icon>
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

      <!-- 中间结果展示（图片/语音） -->
      <el-card v-if="extractedText || recognizedText" class="intermediate-card" shadow="hover">
        <template #header>
          <span>{{ activeTab === 'image' ? 'OCR 识别结果' : '语音识别结果' }}</span>
        </template>
        <p class="intermediate-text">{{ activeTab === 'image' ? extractedText : recognizedText }}</p>
      </el-card>

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

.intermediate-card {
  border-radius: 8px;
}

.intermediate-text {
  margin: 0;
  font-size: 14px;
  line-height: 1.8;
  color: #606266;
  white-space: pre-wrap;
  word-break: break-word;
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
