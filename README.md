# AI-Assistant 项目深度分析文档

## 一、项目概述

**AI-Assistant** 是一个基于 **Spring Boot 3.5.13** 构建的模块化 AI 助手应用，采用 **Maven 多模块架构**设计。项目集成了大语言模型（LLM）、光学字符识别（OCR）、智能翻译等核心 AI 能力，通过本地化的 LLM 服务（如 LM Studio）提供智能对话、文本翻译、图片翻译（Vision）、语音翻译等功能。

项目同时包含一个基于 **Vue 3 + Element Plus + Vite** 的前端界面（`ui` 模块），支持流式（SSE）输出，提供良好的用户体验。

| 属性                 | 说明                 |
| ------------------ | ------------------ |
| **项目名称**           | ai-assistant       |
| **版本**             | 0.0.1-SNAPSHOT     |
| **构建工具**           | Maven 3.x（Wrapper） |
| **Java 版本**        | 17                 |
| **Spring Boot 版本** | 3.5.13             |
| **Spring AI 版本**   | 1.1.4              |
| **前端框架**           | Vue 3 + Vite + Element Plus |
| **服务端口**           | 9090（后端）/ 5173（前端开发） |

***

## 二、项目架构

### 2.1 模块依赖图

```
┌─────────────────────────────────────────────────────────────┐
│                        ai-app (启动入口)                      │
│                   Spring Boot Web 应用                        │
│                   端口: 9090                                  │
└──────────┬──────────────────────────────────────────────────┘
           │ 依赖所有子模块
           ▼
┌──────────────┬──────────────┬──────────────┬──────────────┐
│  ai-common   │   ai-llm     │   ai-ocr     │ ai-translator│
│  (公共模块)   │  (LLM调用)    │  (文字识别)   │  (翻译服务)   │
├──────────────┼──────────────┼──────────────┼──────────────┤
│ • WebClient  │ • 纯文本对话  │ • Tesseract  │ • 文本翻译    │
│ • 全局异常处理│ • Vision图片  │ • 图片文字提取│ • 图片翻译    │
│ • 统一响应体 │ • 流式输出    │              │ • 语音翻译    │
│              │ • 图片压缩    │              │ • 缓存机制    │
│              │ • 重试机制    │              │ • SSE流式    │
└──────────────┴──────────────┴──────────────┴──────────────┘
           ▲
┌──────────┴──────────────────────────────────────────────────┐
│                 ui (Vue.js 前端界面)                          │
│  Vue 3 + TypeScript + Element Plus + Vite + Axios           │
│  端口: 5173 (dev)，代理 /api 到后端 9090                      │
└─────────────────────────────────────────────────────────────┘
           ▲
┌──────────┴──────────────────────────────────────────────────┐
│                      ai-agent (智能体框架)                    │
│                   当前为占位模块，预留扩展                      │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 模块职责说明

| 模块              | 职责                                    | 被依赖方                            |
| --------------- | ------------------------------------- | ------------------------------- |
| `ai-common`     | 公共工具类、全局异常处理、统一 API 响应格式、WebClient 配置 | 所有模块                            |
| `ai-llm`        | 大语言模型调用封装（纯文本/Vision/流式）、图片压缩       | ai-translator, ai-agent         |
| `ai-ocr`        | 基于 Tesseract 的光学字符识别服务                | （当前未被 translator 直接使用）          |
| `ai-translator` | 多模态翻译服务（文本/图片/语音）、SSE 流式输出、缓存管理     | ai-app                          |
| `ai-agent`      | 智能体框架（当前为预留模块）                        | ai-app                          |
| `ai-app`        | 应用启动入口、配置聚合、依赖组装                      | -                               |
| `ui`            | Vue 3 前端界面，流式翻译交互                     | -                               |

***

## 三、核心模块详解

### 3.1 ai-common（公共模块）

**包结构**: `com.alan.aicommon`

#### 3.1.1 统一响应体（ApiResponse）

位于 `dto/ApiResponse.java`，采用泛型设计，提供标准化的 API 返回格式：

```json
{
  "code": 200,
  "message": "success",
  "data": { ... },
  "timestamp": 1713763200000
}
```

**工厂方法**:

- `success(T data)` - 成功响应
- `success(String message, T data)` - 带自定义消息的成功响应
- `error(int code, String message)` - 错误响应
- `badRequest(String message)` - 400 错误
- `serverError(String message)` - 500 错误

#### 3.1.2 全局异常处理（GlobalExceptionHandler）

基于 `@RestControllerAdvice` 的全局异常拦截器，覆盖以下异常类型：

| 异常类型                                      | HTTP 状态码 | 处理逻辑            |
| ----------------------------------------- | -------- | --------------- |
| `LlmException`                            | 500      | AI 模型调用失败       |
| `TranslationException`                    | 400      | 翻译业务异常          |
| `MethodArgumentNotValidException`         | 400      | 参数校验失败（JSR-303） |
| `HttpMessageNotReadableException`         | 400      | 请求体解析失败         |
| `MissingServletRequestParameterException` | 400      | 缺少必需参数          |
| `HttpRequestMethodNotSupportedException`  | 405      | 不支持的 HTTP 方法    |
| `NoResourceFoundException`                | 404      | 资源未找到           |
| `MaxUploadSizeExceededException`          | 413      | 文件上传超限          |
| `Exception`                               | 500      | 兜底异常处理          |

#### 3.1.3 自定义异常

- **LlmException**: LLM 调用过程中的运行时异常
- **TranslationException**: 翻译业务异常，支持错误码（`errorCode`）

#### 3.1.4 WebClient 配置

提供响应式 HTTP 客户端 `WebClient` Bean，用于异步 HTTP 调用。

***

### 3.2 ai-llm（大语言模型模块）

**包结构**: `com.alan.aillm`

`LlmService` 是整个项目的核心服务，统一提供以下四大能力：

1. **纯文本对话**（同步 + 流式）
2. **Vision 图片内容理解**（图片翻译、文字提取）
3. **图片压缩处理**（自动压缩大图用于 LLM 传输）
4. **流式输出**（SSE / Flux）

所有请求均调用相同的 `/chat/completions` 接口，区别仅在于 messages 的格式：
- 文本消息使用 `ChatRequest.Message`（含 system/user/assistant 角色）
- 含图片消息使用 `ChatRequest.VisionMessage`（包含 base64 图片数据）

#### 3.2.1 配置类（LlmConfig）

通过 `@ConfigurationProperties(prefix = "ai.llm")` 绑定配置：

| 配置项           | 默认值                        | 说明                      |
| ------------- | -------------------------- | ----------------------- |
| `base-url`    | `http://localhost:1234/v1` | LLM API 基础地址（兼容 OpenAI） |
| `model`       | `qwen`                     | 默认模型名称                  |
| `api-key`     | `""`                       | API 密钥                  |
| `timeout`     | 30000ms                    | 连接/读取超时                 |
| `max-retries` | 3                          | 最大重试次数                  |
| `temperature` | 0.3                        | 采样温度                    |
| `topP`        | 0.9                        | 核采样参数                   |
| `max-tokens`  | 4096                       | 最大生成令牌数                 |
| `stream`      | false                      | 文本请求是否流式（Vision 请求始终为 true） |

**配置示例**（支持环境变量覆盖）：

```yaml
ai:
  llm:
    base-url: ${LLM_BASE_URL:http://localhost:1234/v1}
    model: ${LLM_MODEL:qwen}
    api-key: ${LLM_API_KEY:your-api-key}
    timeout: ${LLM_TIMEOUT:60000}
    max-retries: ${LLM_MAX_RETRIES:3}
    temperature: ${LLM_TEMPERATURE:0.3}
    topP: ${LLM_TOP_P:0.9}
    max-tokens: ${LLM_MAX_TOKENS:4096}
    stream: ${LLM_STREAM:false}
```

#### 3.2.2 LlmService 核心方法

**纯文本对话**:

- `chat(String systemPrompt, String userPrompt)` - 单轮对话（同步）
- `chat(List<ChatRequest.Message> messages)` - 多消息对话（同步）
- `chatWithHistory(String systemPrompt, String userPrompt, List<ChatRequest.Message> history)` - 带历史记录的对话
- `chatStream(String systemPrompt, String userPrompt)` - 单轮流式对话，返回 `Flux<String>`
- `chatStream(List<ChatRequest.Message> messages)` - 多轮流式对话

**Vision 图片翻译**:

- `translateImage(byte[] imageBytes, String from, String to, String domain, String style)` - 图片翻译（同步），自动压缩图片后调用 Vision LLM
- `translateImageBase64(String base64Image, String from, String to, String domain, String style)` - 使用已有 Base64 图片翻译
- `translateImageStream(byte[] imageBytes, String from, String to, String domain, String style)` - 图片翻译（流式），返回 `Flux<String>`

**图片文字提取**:

- `extractTextFromImage(byte[] imageBytes)` - 纯 OCR 识别，使用 Vision LLM 提取图片中的文字（不翻译）

**技术特性**:

- 使用 `WebClient`（响应式）进行 HTTP 调用
- **Vision 请求自动强制流式**（`stream: true`），通过解析 SSE 行来提取最终结果
- 支持 **Spring Retry** 重试机制（`@Retryable`），对 `WebClientResponseException` 自动重试
- 指数退避策略：`delay = 1000ms`，`multiplier = 2`
- 完善的日志记录（请求耗时、模型名称、异常详情）
- 响应内容安全校验（空响应、空 choices、空内容检查）
- 图片自动压缩：大于 50KB 的图片自动缩放（最大边 1024px）并转 JPEG
- 流式解析同时支持 `content` 和 `reasoning`（推理内容）字段

#### 3.2.3 请求/响应 DTO

**ChatRequest** (位于 `dto/request/ChatRequest.java`):

- `Message` 内部类：纯文本消息（role + content），含 `system()` / `user()` / `assistant()` 工厂方法
- `VisionMessage` 内部类：多模态消息（role + List\<Content\>），含图片+文本混合内容
- `Content` 内部类：支持 `text` 和 `image_url` 两种类型

**ChatResponse** (位于 `dto/response/ChatResponse.java`):

- 解析 OpenAI 标准响应格式（含 `choices`/`usage`/`delta` 等字段）
- 支持非流式和流式两种模式的响应结构

***

### 3.3 ai-ocr（光学字符识别模块）

**包结构**: `com.alan.aiocr`

#### 3.3.1 OCR 服务（OcrService）

基于 **Tess4J**（Tesseract OCR 的 Java 封装）实现图片文字提取：

**依赖**: `net.sourceforge.tess4j:tess4j:5.8.0`

**核心方法**:

- `extractText(MultipartFile imageFile)` - 默认语言识别
- `extractText(MultipartFile imageFile, String language)` - 指定语言识别

**技术细节**:

- 页面分割模式设置为 `PSM_AUTO`（`setPageSegMode(1)`）
- 支持常见图片格式（通过 `ImageIO` 自动识别）
- 异常处理：图片读取失败、OCR 识别失败均有明确异常提示

> **注意**: 当前图片翻译功能已改用 LLM Vision 实现（通过 `LlmService.translateImage()`），
> OcrService 主要用于独立的文字识别需求，不再被 `ImageTranslateService` 直接调用。

#### 3.3.2 自定义异常

- **OcrException**: OCR 识别过程中的运行时异常

***

### 3.4 ai-translator（智能翻译模块）

**包结构**: `com.alan.aitranslator`

#### 3.4.1 配置类（TranslatorConfig）

通过 `@ConfigurationProperties(prefix = "ai.translator")` 绑定配置：

| 配置项                  | 默认值     | 说明           |
| -------------------- | ------- | ------------ |
| `max-text-length`    | 50000   | 最大翻译文本长度     |
| `max-segment-length` | 3000    | 长文本分段长度      |
| `enable-cache`       | true    | 是否启用翻译缓存     |
| `cache-size`         | 10000   | 缓存最大条目数      |
| `cache-ttl-minutes`  | 1440    | 缓存过期时间（24小时） |
| `default-domain`     | general | 默认翻译领域       |
| `default-style`      | neutral | 默认翻译风格       |

**配置示例**：

```yaml
ai:
  translator:
    max-text-length: 50000
    max-segment-length: 3000
    enable-cache: true
    cache-size: 10000
    cache-ttl-minutes: 1440
    default-domain: general
    default-style: neutral
```

#### 3.4.2 翻译服务（TranslateService）

基于 LLM 的高质量翻译引擎，支持多领域、多风格翻译：

**核心特性**:

1. **缓存机制**: 使用 **Caffeine Cache** 实现本地缓存
   - 缓存键：`{from}_{to}_{domain}_{style}_{length}_{hash}`
   - 统计功能：记录命中率
2. **长文本处理**: 超过 `maxSegmentLength` 的文本自动分段翻译
   - 按段落分割，保持上下文连贯性
   - 前一段内容作为下一段的上下文参考
3. **领域适配**: 支持 6 种领域（含通用）
   - `general` - 通用领域
   - `tech` - 技术领域（计算机、软件工程）
   - `medical` - 医学领域
   - `legal` - 法律领域
   - `business` - 商业领域
   - `literary` - 文学领域
4. **风格适配**: 支持 4 种翻译风格
   - `neutral` - 中性风格
   - `formal` - 正式书面语
   - `casual` - 口语化风格
   - `academic` - 学术严谨风格
5. **XML 标签隔离**: 使用 `<text>`/`<context>` XML 标签包裹待翻译内容和上下文，有效防止本地小模型的"提示词注入（Prompt Injection）"问题

#### 3.4.3 图片翻译服务（ImageTranslateService）

**流程**: 图片上传 → LLM Vision 识别并翻译 → 流式返回结果

> **注意**: 当前实现直接使用 LLM Vision 能力，不再经过 Tesseract OCR。

**方法**:

- `translateImageStream(MultipartFile imageFile, String from, String to)` - 流式图片翻译，返回 `Flux<String>`

**校验规则**:

- 文件类型必须为 `image/*`
- 文件大小限制 50MB
- 使用 `TranslatorConfig` 中的 `defaultDomain` 和 `defaultStyle`

#### 3.4.4 语音翻译服务（AudioTranslateService）

**流程**: 音频上传 → ASR 语音识别 → 文本翻译（流式）→ SSE 返回结果

**方法**:

- `translateAudioStream(MultipartFile audioFile, String from, String to, ObjectMapper objectMapper)` - 流式语音翻译，返回 `SseEmitter`

**SSE 事件类型**:

- `asr` - ASR 识别完成事件（含 `recognizedText` 和 `asrDuration`）
- `translation` - 翻译流式块（含 `text` 和 `done` 标志）
- `done` - 完成事件（含 `recognizedText`/`translatedText`/`totalDuration`/`audioDuration`）

**技术细节**:

- 临时文件管理：上传后创建临时文件，处理完成后自动清理
- 音频时长计算：通过 `javax.sound.sampled` 获取音频时长
- 流式翻译：使用 `llmService.chatStream()` 获取 Flux，通过 Rx 订阅发送 SSE 事件

#### 3.4.5 ASR 服务（AsrService）

调用 OpenAI Whisper 兼容 API 进行语音识别：

- 模型：`whisper-1`
- 响应格式：`text`
- 支持语言参数指定
- API 地址自动从 `LlmConfig.baseUrl` 拼接 `/audio/transcriptions`

#### 3.4.6 文本预处理（TextPreprocessor）

翻译前的文本清洗工具：

- 去除 HTML 标签
- 合并多余空格
- 合并多余换行（超过 2 个换行合并为 2 个）

#### 3.4.7 自定义异常

- **AsrException**: 语音识别过程中的运行时异常

***

### 3.5 ai-agent（智能体模块）

**包结构**: `com.alan.aiagent`

当前为**预留模块**，仅包含占位类 `AgentModuleInfo`，用于：

- 确保目录被 Git 跟踪
- 为后续智能体框架（Agent Framework）扩展预留空间

**未来可扩展方向**:

- ReAct 智能体模式
- 工具调用（Function Calling）编排
- 多智能体协作
- 记忆管理（Memory）

***

### 3.6 ai-app（应用启动模块）

**包结构**: `com.alan.aiassistant`

#### 3.6.1 启动类（AiAssistantApplication）

```java
@SpringBootApplication(exclude = {
    OpenAiAudioSpeechAutoConfiguration.class,
    OpenAiAudioTranscriptionAutoConfiguration.class,
    OpenAiChatAutoConfiguration.class,
    OpenAiEmbeddingAutoConfiguration.class,
    OpenAiImageAutoConfiguration.class,
    OpenAiModerationAutoConfiguration.class,
    PgVectorStoreAutoConfiguration.class
})
@EnableRetry
@ComponentScan(basePackages = {"com.alan.aiassistant", "com.alan.aicommon",
    "com.alan.aitranslator", "com.alan.aillm", "com.alan.aiocr", "com.alan.aiagent"})
public class AiAssistantApplication { ... }
```

**关键注解**:

- `@EnableRetry`: 启用 Spring Retry 重试机制
- `@ComponentScan`: 显式扫描所有模块的包路径（解决多模块包扫描问题）
- `@SpringBootApplication(exclude = {...})`: 排除 Spring AI 的自动配置，因为项目使用自定义的 LLM 调用实现

#### 3.6.2 应用配置（application.yml）

```yaml
spring:
  application:
    name: ai-assistant
  datasource:
    url: jdbc:postgresql://localhost:5432/ai_assistant
    username: postgres
    password: 123456
    driver-class-name: org.postgresql.Driver
  servlet:
    multipart:
      max-file-size: 50MB
      max-request-size: 50MB

ai:
  llm:
    base-url: ${LLM_BASE_URL:http://localhost:1234/v1}
    model: ${LLM_MODEL:qwen}
    api-key: ${LLM_API_KEY:sk-lm-zmU7SpEW:2wED8QUaU6JFaPKTbs6t}
    timeout: ${LLM_TIMEOUT:60000}
    max-retries: ${LLM_MAX_RETRIES:3}
    temperature: ${LLM_TEMPERATURE:0.3}
    topP: ${LLM_TOP_P:0.9}
    max-tokens: ${LLM_MAX_TOKENS:4096}
  translator:
    max-text-length: 50000
    max-segment-length: 3000
    enable-cache: true
    cache-size: 10000
    cache-ttl-minutes: 1440
    default-domain: general
    default-style: neutral

server:
  port: 9090
```

**基础设施**:

- **数据库**: PostgreSQL（用于向量存储 `pgvector`）
- **LLM 服务**: 本地 LM Studio（端口 1234）
- **文件上传**: 最大 50MB

**安全配置说明**:

- 所有敏感配置（API Key、密码）支持通过环境变量覆盖
- 格式：`${ENV_VAR:default_value}`，环境变量不存在时使用默认值
- 生产环境建议设置对应的环境变量，避免使用默认值

***

### 3.7 ui（Vue.js 前端模块）

**技术栈**: Vue 3 + TypeScript + Element Plus + Vite + Axios

#### 3.7.1 功能特性

- **三模式翻译**: 文本翻译 / 图片翻译 / 语音翻译，通过标签页切换
- **流式输出（SSE）**: 所有翻译均采用 SSE 流式输出，实时显示翻译进度
- **领域与风格选择**: 支持 6 种领域 × 4 种风格组合
- **文件拖拽上传**: 图片和音频支持拖拽上传
- **复制翻译结果**: 一键复制到剪贴板
- **响应式布局**: 适配桌面端和移动端

#### 3.7.2 技术细节

- Vite 开发服务器端口：`5173`
- 代理配置：`/api` → `http://localhost:9090`
- SSE 流式解析：手动实现 SSE 协议解析（`fetch` + `ReadableStream`）
- 时长展示：显示翻译耗时（毫秒）

***

## 四、API 接口清单

### 4.1 翻译接口（v1 版本，全部流式）

所有接口均返回 SSE（Server-Sent Events）流式响应。

| 方法   | 路径                             | 说明       | 请求类型         | 流式格式     |
| ---- | ------------------------------ | -------- | ------------ | -------- |
| POST | `/api/v1/translate/text/stream`  | 文本翻译（流式） | JSON         | SSE      |
| POST | `/api/v1/translate/image/stream` | 图片翻译（流式） | Multipart    | SSE (text/event-stream) |
| POST | `/api/v1/translate/audio/stream` | 语音翻译（流式） | Multipart    | SSE      |

### 4.2 请求/响应示例

#### 文本翻译（流式）

**请求**:

```json
{
  "text": "Hello, world!",
  "from": "English",
  "to": "Chinese",
  "domain": "general",
  "style": "neutral",
  "contextText": "可选的上下文内容"
}
```

**SSE 流式响应**:

```
event: message
data: {"text":"你","done":false}

event: message
data: {"text":"好，","done":false}

event: message
data: {"text":"世界！","done":false}

event: done
data: {"text":"","done":true}
```

#### 图片翻译（流式）

**请求** (multipart/form-data):

- `file` - 图片文件（JPG/PNG/GIF/WEBP，最大 50MB）
- `from` - 源语言代码
- `to` - 目标语言代码

**响应**: SSE 流（`text/event-stream`），每行为 `data: {"choices":[{"delta":{"content":"..."}}]}`

> **语言参数说明**: 前端使用明确的语言名称（如 `Chinese`、`English`、`Japanese`），
> 后端通过 `.toLowerCase()` 转换后传入 LLM（如 `chinese`、`english`、`japanese`）。

#### 语音翻译（流式）

**请求** (multipart/form-data):

- `file` - 音频文件（MP3/WAV/M4A/OGG/FLAC，最大 50MB）
- `from` - 源语言代码
- `to` - 目标语言代码

**SSE 流式响应**:

```
event: asr
data: {"type":"asr_result","recognizedText":"Hello world","asrDuration":1250}

event: translation
data: {"text":"你","done":false}

event: translation
data: {"text":"好世","done":false}

event: translation
data: {"text":"界","done":false}

event: done
data: {"type":"done","recognizedText":"Hello world","translatedText":"你好世界","totalDuration":5250,"audioDuration":15000}
```

### 4.3 错误响应示例

```json
{
  "code": 400,
  "message": "参数验证失败: 翻译文本不能为空; 源语言不能为空",
  "data": null,
  "timestamp": 1713763200000
}
```

***

## 五、技术栈分析

### 5.1 核心框架与库

| 技术                 | 版本     | 用途                |
| ------------------ | ------ | ----------------- |
| Spring Boot        | 3.5.13 | 应用框架              |
| Spring AI          | 1.1.4  | AI 抽象框架（向量存储，自动配置已排除） |
| Spring Retry       | -      | 重试机制              |
| Spring Validation  | -      | 参数校验（JSR-303）     |
| Spring WebFlux     | -      | 响应式 HTTP 客户端和流式处理  |
| Lombok             | -      | 代码简化              |
| Tess4J             | 5.8.0  | OCR 引擎            |
| Caffeine           | -      | 本地缓存              |
| PostgreSQL         | -      | 关系型数据库            |
| pgvector           | -      | 向量扩展              |
| Vue 3              | 3.5.32 | 前端框架              |
| Element Plus       | 2.13.7 | UI 组件库            |
| Vite               | 8.0.9  | 前端构建工具            |
| Axios              | 1.15.2 | HTTP 客户端          |
| Jackson            | -      | JSON 解析（后端流式 SSE 解析） |

### 5.2 设计模式应用

| 模式        | 应用位置               | 说明                       |
| --------- | ------------------ | ------------------------ |
| **模板方法**  | `LlmService`       | 对话/Vision 流程标准化（构建请求→调用API→解析响应） |
| **策略模式**  | `TranslateService` | 不同领域/风格的翻译策略通过 prompt 切换 |
| **建造者模式** | DTO 类              | `@Builder` 注解生成流式构建器     |
| **单例模式**  | `OcrService`       | Tesseract 引擎单例管理         |
| **工厂模式**  | `ApiResponse`      | 静态工厂方法创建响应对象             |
| **观察者模式** | 流式处理              | WebFlux Flux/SSE 事件订阅处理 |

### 5.3 异常处理策略

项目采用**分层异常处理**架构：

1. **模块自定义异常**: 各模块定义自己的异常类型（`LlmException`, `TranslationException`, `OcrException`, `AsrException`）
2. **异常转换**: 底层异常在模块边界处转换为业务异常
3. **统一拦截**: `GlobalExceptionHandler` 集中处理所有异常，统一返回格式
4. **日志分级**: WARN 处理客户端错误，ERROR 处理服务端错误

***

## 六、数据流分析

### 6.1 文本翻译流程（流式）

```
用户请求 (SSE)
    │
    ▼
┌─────────────────────┐
│ TranslateController │
│ POST /api/v1/translate/text/stream │
│ @Valid 参数校验      │
└──────┬──────────────┘
       │
       ▼
┌─────────────────────┐
│ TextPreprocessor    │
│ • 文本预处理          │
└──────┬──────────────┘
       │
       ▼
┌─────────────────────┐
│ LlmService.chat()   │
│ • 构建请求体          │
│ • WebClient 同步调用  │
│ • 解析响应            │
└──────┬──────────────┘
       │
       ▼
  分块发送 SSE 事件
  (10字符/块, 间隔50ms)
       │
       ▼
  发送 done 事件
```

### 6.2 图片翻译流程（流式）

```
用户上传图片
    │
    ▼
┌──────────────────────────┐
│ ImageTranslateService    │
│ • 图片格式/大小校验        │
│ POST /api/v1/translate/image/stream │
└──────┬───────────────────┘
       │
       ▼
┌──────────────────────────┐
│ LlmService.translateImageStream() │
│ • 图片自动压缩（>50KB→1024px）│
│ • Base64 编码              │
│ • 构建 Vision 请求体        │
│ • WebClient Flux 流式调用  │
│ • Vision LLM 识别+翻译     │
└──────┬───────────────────┘
       │
       ▼
  SSE 流式返回翻译结果
```

### 6.3 语音翻译流程（流式）

```
用户上传音频
    │
    ▼
┌──────────────────────────┐
│ AudioTranslateService    │
│ • 音频格式/大小校验        │
│ • 创建临时文件             │
│ • 计算音频时长             │
│ POST /api/v1/translate/audio/stream │
└──────┬───────────────────┘
       │
       ▼
┌──────────────────────────┐
│ AsrService.recognize()   │
│ • 调用 Whisper API        │
│ • 语音识别                │
└──────┬───────────────────┘
       │
       ▼ SSE event: asr
  发送识别结果事件
       │
       ▼
┌──────────────────────────┐
│ LlmService.chatStream()  │
│ • 文本翻译（流式 Flux）    │
└──────┬───────────────────┘
       │
       ▼ SSE event: translation (多次)
  流式发送翻译块
       │
       ▼ SSE event: done
  发送完成事件 + 清理临时文件
```

***

## 七、构建与部署

### 7.1 Maven 构建

```bash
# 使用 Maven Wrapper 构建
./mvnw clean package

# 运行应用
./mvnw spring-boot:run -pl ai-app
```

### 7.2 前端构建

```bash
# 进入 ui 目录
cd ui

# 安装依赖
npm install

# 开发模式（热重载）
npm run dev

# 生产构建
npm run build
```

### 7.3 一键启动

项目提供了 `start.bat` 脚本：

```batch
mvnw.cmd clean package
mvnw.cmd spring-boot:run -pl ai-app
```

### 7.4 模块构建配置

- 所有模块均配置 `maven-compiler-plugin`，启用 Lombok 注解处理器
- `ai-app` 模块使用 `spring-boot-maven-plugin` 打包可执行 JAR
- 打包时排除 Lombok 依赖

### 7.5 运行环境要求

| 依赖          | 版本  | 说明                          |
| ----------- | --- | --------------------------- |
| JDK         | 17+ | Java 运行时                    |
| Node.js     | 18+ | 前端构建                        |
| PostgreSQL  | 14+ | 数据库，需安装 pgvector 扩展（当前未使用） |
| LM Studio   | -   | 本地 LLM 服务，端口 1234           |
| Tesseract   | -   | OCR 引擎（安装语言包，可选）            |

### 7.6 环境变量配置（生产环境推荐）

```bash
# LLM 配置
export LLM_BASE_URL=http://localhost:1234/v1
export LLM_MODEL=qwen
export LLM_API_KEY=your-secure-api-key
export LLM_TIMEOUT=60000

# 数据库配置
export DB_URL=jdbc:postgresql://localhost:5432/ai_assistant
export DB_USERNAME=postgres
export DB_PASSWORD=your-secure-password
```

***

## 八、扩展性分析

### 8.1 当前扩展点

1. **新领域翻译**: 在 `TranslateService` 和 `LlmService.buildImageTranslatePrompt()` 中添加新的 domain case
2. **新风格翻译**: 添加新的 style case
3. **新 OCR 语言**: 通过 `OcrService.extractText(file, language)` 指定 Tesseract 语言包
4. **缓存策略**: 调整 `TranslatorConfig` 中的缓存参数
5. **前端多语言**: 扩展 `LANGUAGE_OPTIONS` 常量
6. **extra_body 参数**: 通过扩展 `LlmService` 透传额外 LLM 参数（如 `enable_thinking`，当前仅在测试类中实现）

### 8.2 架构优势

1. **模块化设计**: 各模块职责清晰，可独立演进
2. **依赖倒置**: `ai-app` 作为组装模块，业务模块之间通过接口解耦
3. **配置外部化**: 所有 AI 参数通过 `application.yml` 配置，支持环境变量覆盖
4. **异常隔离**: 各模块自定义异常，避免错误传播污染
5. **流式优先**: 所有翻译接口均采用 SSE 流式输出，用户体验好
6. **前后端分离**: Vue.js 独立开发，通过 Vite proxy 与后端通信

### 8.3 待完善方向

1. **ai-agent 模块**: 当前为空，建议实现 ReAct/Plan-and-Execute 智能体框架
2. **向量存储**: 已引入 `spring-ai-starter-vector-store-pgvector`，但排除自动配置，未在业务中使用
3. **传统 REST 端点**: 当前只有流式端点，可添加非流式 API 作为降级方案
4. **多模态支持**: 可扩展视频翻译、文档翻译等能力
5. **用户管理**: 当前无认证授权机制
6. **限流熔断**: 建议引入 Sentinel/Resilience4j
7. **单元测试**: 当前测试覆盖不足（仅一个默认测试），需补充 Service/Controller 层测试
8. **API 文档**: 建议引入 Swagger/OpenAPI 自动生成接口文档
9. **前端状态管理**: 可引入 Pinia 管理复杂状态

***

## 九、代码质量评估

### 9.1 优点

- ✅ 统一的 API 响应格式，前后端交互规范
- ✅ 完善的全局异常处理，错误信息友好
- ✅ 日志记录详尽，便于问题排查
- ✅ 参数校验完善（`@Valid`, `@NotBlank`, `@Size`）
- ✅ 缓存机制提升性能（Caffeine）
- ✅ 重试机制增强稳定性（Spring Retry）
- ✅ 临时文件自动清理，避免磁盘泄漏
- ✅ 配置外部化，支持环境变量注入
- ✅ 敏感配置默认支持环境变量覆盖
- ✅ 流式 SSE 输出，实时反馈翻译进度
- ✅ Vision LLM 直接处理图片翻译，无需 OCR 中间步骤
- ✅ 图片自动压缩优化，减少数据传输量
- ✅ XML 标签隔离防提示词注入
- ✅ Vue 3 前端实现完整，支持拖拽上传和流式显示

### 9.2 改进建议

- ⚠️ `application.yml` 中数据库密码和 API Key 仍有默认值，生产环境务必通过环境变量覆盖
- ⚠️ `AsrService` 中 API URL 从 `LlmConfig` 拼接待改进，建议使用独立配置
- ⚠️ `OcrService` 中 Tesseract 数据路径未配置，可能依赖系统环境
- ⚠️ 缺少单元测试覆盖（仅有一个默认的 `AiAssistantApplicationTests`）
- ⚠️ 缺少 API 文档（如 Swagger/OpenAPI）
- ⚠️ `TranslateController.text/stream` 实际上是同步调用后分块发送，并非真正的流式 LLM 调用
- ⚠️ `TranslateService.buildSystemPrompt()` 中 `domain` 和 `style` 参数虽传入但未在提示词中使用（4 参数仅 2 个 `%s` 占位符）
- ⚠️ `extra_body` 透传（如 `enable_thinking`）仅在测试类 `ChatCompletionsTest` 中实现，未集成到生产代码 `LlmService`

***

## 十、版本变更记录

### v0.0.1-SNAPSHOT (当前版本)

**新增功能**:

- 多模态翻译支持（文本/图片/语音）
- 所有翻译接口改为 SSE 流式输出
- LLM Vision 图片翻译（替代 OCR 流程）
- 图片自动压缩优化
- 翻译缓存机制（Caffeine）
- 长文本自动分段翻译
- 领域和风格适配（tech/medical/legal/business/literary/formal/casual/academic）
- 全局异常处理与统一响应格式
- Spring Retry 重试机制
- 配置外部化（支持环境变量）
- Vue 3 前端界面（Element Plus + Vite）
- XML 标签隔离防提示词注入
- `extra_body` 透传支持

**架构变更**:

- 清理重复模块（app、translator）
- API 路径规范化（`/api/v1/translate/*/stream`）
- 模块依赖关系优化
- `LlmService` 重构：从 `RestTemplate` 迁移到 `WebClient`，统一文本/Vision 调用
- 排除 Spring AI 自动配置（使用自定义 LLM 调用）
- 图片翻译从 OCR+翻译 流程改为 Vision LLM 直接处理

***

## 十一、总结

AI-Assistant 是一个架构清晰、模块化的 Spring Boot AI 应用，通过 Maven 多模块方式组织了 LLM、OCR、翻译等核心能力，并提供了 Vue 3 前端界面。项目采用**本地化 LLM 方案**（LM Studio），降低了对外部云服务的依赖，适合私有化部署场景。

核心设计亮点：

1. **统一 LLM 服务层**: `LlmService` 统一封装文本对话、Vision 识别、图片翻译、流式输出四大能力
2. **流式优先架构**: 所有翻译接口均采用 SSE 流式输出，提供实时反馈
3. **分层异常处理**: 模块异常 → 业务异常 → 统一拦截 → 标准化响应
4. **Prompt 工程**: 领域/风格适配 + XML 标签隔离 + Vision 系统提示词
5. **多模态翻译**: 文本/图片/语音三种输入方式的统一翻译能力
6. **性能优化**: Caffeine 缓存 + 长文本分段处理 + 图片自动压缩
7. **配置安全**: 敏感配置支持环境变量覆盖
8. **前后端分离**: Vue 3 独立前端，Vite 代理通信

项目当前处于 **MVP 阶段**，核心功能已可用，后续可在智能体框架、向量检索、非流式降级等方向持续演进。
