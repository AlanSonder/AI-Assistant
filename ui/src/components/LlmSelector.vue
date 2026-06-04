<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import axios from 'axios'

const API_BASE = '/api/v1'

interface ProviderInfo {
  baseUrl: string
  models: string[]
}

interface ProvidersData {
  activeProvider: string
  defaultModel: string
  thinkingEnabled: boolean
  providers: Record<string, ProviderInfo>
}

const loading = ref(false)
const providersData = ref<ProvidersData | null>(null)
const selectedProvider = ref('')
const selectedModel = ref('')
const thinkingEnabled = ref(false)

/** 提供商选项（显示名 + 值） */
const providerOptions = computed(() => {
  if (!providersData.value) return []
  return Object.keys(providersData.value.providers).map(name => {
    const label = name === 'local' ? '🖥️ 本地' : name === 'deepseek' ? '☁️ DeepSeek' : name
    return { label, value: name }
  })
})

/** 当前提供商的模型选项 */
const modelOptions = computed(() => {
  if (!providersData.value) return []
  const provider = providersData.value.providers[selectedProvider.value]
  if (!provider) return []
  return provider.models.map(m => ({ label: m, value: m }))
})

/** 是否显示模型选择器（多模型时显示） */
const showModelSelector = computed(() => modelOptions.value.length > 1)

/** 是否显示思考模式开关（仅 DeepSeek 显示） */
const showThinkingToggle = computed(() => selectedProvider.value === 'deepseek')

/** 状态标签文本 */
const statusLabel = computed(() => {
  if (!providersData.value) return '加载中...'
  const p = selectedProvider.value
  if (p === 'local') return '本地 LLM'
  if (p === 'deepseek') return 'DeepSeek'
  return p
})

/** 状态标签类型 */
const statusType = computed(() => {
  const p = selectedProvider.value
  if (p === 'local') return 'success'
  if (p === 'deepseek') return ''
  return 'info'
})

async function fetchProviders() {
  loading.value = true
  try {
    const res = await axios.get(`${API_BASE}/llm/providers`)
    if (res.data.code === 200) {
      providersData.value = res.data.data
      selectedProvider.value = res.data.data.activeProvider
      selectedModel.value = res.data.data.defaultModel
      thinkingEnabled.value = res.data.data.thinkingEnabled ?? false
    }
  } catch (e) {
    console.error('获取LLM提供商列表失败:', e)
    ElMessage.warning('无法获取LLM提供商列表')
  } finally {
    loading.value = false
  }
}

async function switchProvider(provider: string) {
  if (!providersData.value) return
  const providerInfo = providersData.value.providers[provider]
  if (!providerInfo) return

  // 切换提供商时自动选第一个模型
  const firstModel = providerInfo.models[0] || ''
  selectedModel.value = firstModel

  try {
    await axios.post(`${API_BASE}/llm/providers/activate`, {
      provider,
      model: firstModel,
    })
    ElMessage.success(`已切换到 ${provider === 'local' ? '本地' : 'DeepSeek'} 模型`)
  } catch (e) {
    console.error('切换提供商失败:', e)
    ElMessage.error('切换失败')
  }
}

/** 根据模型名查找所属的 provider */
function findProviderByModel(model: string): string | null {
  if (!providersData.value) return null
  for (const [name, info] of Object.entries(providersData.value.providers)) {
    if (info.models.includes(model)) return name
  }
  return null
}

async function switchModel(model: string) {
  selectedModel.value = model

  // 找到该模型所属的 provider，必要时一并切换
  const owner = findProviderByModel(model)
  const needSwitchProvider = owner && owner !== selectedProvider.value
  if (needSwitchProvider) {
    selectedProvider.value = owner
  }

  try {
    await axios.post(`${API_BASE}/llm/providers/activate`, {
      provider: needSwitchProvider ? owner : undefined,
      model,
    })
    if (needSwitchProvider) {
      ElMessage.success(`已切换到 ${owner === 'local' ? '本地' : 'DeepSeek'} 模型`)
    }
  } catch (e) {
    console.error('切换模型失败:', e)
    ElMessage.error('切换模型失败')
  }
}

async function toggleThinking(enabled: boolean) {
  thinkingEnabled.value = enabled
  try {
    await axios.post(`${API_BASE}/llm/thinking`, { enabled })
  } catch (e) {
    console.error('切换思考模式失败:', e)
    ElMessage.error('切换思考模式失败')
    thinkingEnabled.value = !enabled  // 回滚
  }
}

onMounted(() => {
  fetchProviders()
})
</script>

<template>
  <div class="llm-selector">
    <!-- 状态标签（只读展示） -->
    <el-tag :type="statusType" effect="light" size="small" class="status-tag">
      {{ statusLabel }}
    </el-tag>

    <!-- 提供商选择 -->
    <el-select
      v-model="selectedProvider"
      @change="switchProvider"
      size="small"
      class="provider-select"
      :loading="loading"
      popper-class="llm-selector-popper"
    >
      <el-option
        v-for="opt in providerOptions"
        :key="opt.value"
        :label="opt.label"
        :value="opt.value"
      />
    </el-select>

    <!-- 模型选择（多模型时显示） -->
    <el-select
      v-if="showModelSelector"
      v-model="selectedModel"
      @change="switchModel"
      size="small"
      class="model-select"
    >
      <el-option
        v-for="opt in modelOptions"
        :key="opt.value"
        :label="opt.label"
        :value="opt.value"
      />
    </el-select>

    <!-- 思考模式开关（仅 DeepSeek 显示） -->
    <el-switch
      v-if="showThinkingToggle"
      v-model="thinkingEnabled"
      @change="toggleThinking"
      size="small"
      active-text="思考"
      class="thinking-switch"
    />
  </div>
</template>

<style scoped>
.llm-selector {
  display: flex;
  align-items: center;
  gap: 8px;
}

.status-tag {
  font-size: 12px;
}

.provider-select {
  width: 130px;
}

.model-select {
  width: 170px;
}

.thinking-switch {
  margin-left: 4px;
}

/* 确保下拉选项不被截断 */
.provider-select :deep(.el-input__wrapper),
.model-select :deep(.el-input__wrapper) {
  padding: 0 8px;
}
</style>
