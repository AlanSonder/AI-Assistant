# AI-Assistant 项目深度分析文档

## 一、项目概述

**AI-Assistant** 是一个基于 **Spring Boot 3.5.13** 构建的模块化 AI 助手应用，采用 **Maven 多模块架构**设计。项目集成了大语言模型（LLM）、光学字符识别（OCR）、智能翻译等核心 AI 能力，通过本地化的 LLM 服务（如 LM Studio）提供智能对话、文本翻译、图片翻译、语音翻译等功能。

| 属性                 | 说明                 |
| ------------------ | ------------------ |
| **项目名称**           | ai-assistant       |
| **版本**             | 0.0.1-SNAPSHOT     |
| **构建工具**           | Maven 3.x（Wrapper） |
| **Java 版本**        | 17                 |
| **Spring Boot 版本** | 3.5.13             |
| **Spring AI 版本**   | 1.1.4              |
| **服务端口**           | 9090               |

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
│ • WebClient  │ • OpenAI API │ • Tesseract  │ • 文本翻译    │
│ • 全局异常处理│ • 对话服务   │ • 图片文字提取│ • 图片翻译    │
│ • 统一响应体 │ • 重试机制   │              │ • 语音翻译    │
│              │ • 配置外部化 │              │ • 缓存机制    │
└──────────────┴──────────────┴──────────────┴──────────────┘
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
| `ai-llm`        | 大语言模型调用封装、对话服务、OpenAI 兼容 API 交互       | ai-ocr, ai-translator, ai-agent |
| `ai-ocr`        | 基于 Tesseract 的光学字符识别服务                | ai-translator                   |
| `ai-translator` | 多模态翻译服务（文本/图片/语音）、缓存管理                | ai-app                          |
| `ai-agent`      | 智能体框架（当前为预留模块）                        | ai-app                          |
| `ai-app`        | 应用启动入口、配置聚合、依赖组装                      | -                               |

***

## 三、核心模块详解

### 3.1 ai-common（公共模块）

**包结构**: `com.alan.aicommon`

#### 3.1.1 统一响应体（ApiResponse）

位于 `dto/ApiResponse.java`，采用泛型设计，提供标准化的 API 返回格式：

```java
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
- **TranslationException**: 翻译业务异常，支持错误码（errorCode）

#### 3.1.4 WebClient 配置

提供响应式 HTTP 客户端 `WebClient` Bean，用于异步 HTTP 调用。

***

### 3.2 ai-llm（大语言模型模块）

**包结构**: `com.alan.aillm`

#### 3.2.1 配置类（LlmConfig）

通过 `@ConfigurationProperties(prefix = "ai.llm")` 绑定配置：

| 配置项           | 默认值                        | 说明                      |
| ------------- | -------------------------- | ----------------------- |
| `base-url`    | `http://localhost:1234/v1` | LLM API 基础地址（LM Studio） |
| `model`       | `qwen`                     | 默认模型名称                  |
| `api-key`     | `""`                       | API 密钥                  |
| `timeout`     | 30000ms                    | 连接/读取超时                 |
| `max-retries` | 3                          | 最大重试次数                  |
| `temperature` | 0.3                        | 采样温度                    |
| `topP`        | 0.9                        | 核采样参数（YAML 中使用 `topP`）  |
| `max-tokens`  | 4096                       | 最大生成令牌数                 |
| `stream`      | false                      | 是否流式输出                  |

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
```

#### 3.2.2 对话服务（LlmService）

核心服务类，提供与 OpenAI 兼容 API 的交互能力：

**核心方法**:

- `chat(String systemPrompt, String userPrompt)` - 单轮对话
- `chat(List<Message> messages)` - 多消息对话
- `chatWithHistory(String systemPrompt, String userPrompt, List<Message> history)` - 带历史记录的对话

**技术特性**:

- 使用 `RestTemplate` 进行同步 HTTP 调用
- 支持 **Spring Retry** 重试机制（`@Retryable`），对 `ResourceAccessException` 和 `HttpServerErrorException` 自动重试
- 指数退避策略：`delay = 1000ms`，`multiplier = 2`
- 完善的日志记录（请求耗时、模型名称、异常详情）
- 响应内容安全校验（空响应、空 choices、空内容检查）

**请求/响应 DTO**:

- `ChatRequest`: 兼容 OpenAI Chat Completions API 的请求体（含 temperature/topP/maxTokens/stream）
- `ChatResponse`: 解析 OpenAI 标准响应格式（含 choices/usage/delta 等字段）

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

***

### 3.4 ai-translator（智能翻译模块）

**包结构**: `com.alan.aitranslator`

#### 3.4.1 配置类（TranslatorConfig）

通过 `@ConfigurationProperties(prefix = "ai.translator")` 绑定配置：

| 配置项                  | 默认值     | 说明           |
| -------------------- | ------- | ------------ |
| `max-text-length`    | 10000   | 最大翻译文本长度     |
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
    max-text-length: 10000
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
   - 前一段摘要作为下一段的上下文参考
3. **领域适配**: 支持 5 种专业领域
   - `general` - 通用领域
   - `tech` - 技术领域（计算机、软件工程）
   - `medical` - 医学领域
   - `legal` - 法律领域
   - `business` - 商业领域
   - `literary` - 文学领域
4. **风格适配**: 支持 3 种翻译风格
   - `neutral` - 中性风格
   - `formal` - 正式书面语
   - `casual` - 口语化风格
   - `academic` - 学术严谨风格
5. **系统提示词工程**: 通过精心设计的 system prompt 约束 LLM 输出，确保翻译质量

#### 3.4.3 图片翻译服务（ImageTranslateService）

**流程**: 图片上传 → OCR 文字提取 → 文本翻译 → 返回结果

**接口**: `POST /api/v1/translate/image` (multipart/form-data)

**参数**:

- `file` - 图片文件（JPG/PNG/GIF/WEBP，最大 50MB）
- `from` - 源语言
- `to` - 目标语言

#### 3.4.4 语音翻译服务（AudioTranslateService）

**流程**: 音频上传 → ASR 语音识别 → 文本翻译 → 返回结果

**接口**: `POST /api/v1/translate/audio` (multipart/form-data)

**参数**:

- `file` - 音频文件（MP3/WAV/M4A/OGG/FLAC，最大 50MB）
- `from` - 源语言
- `to` - 目标语言

**技术细节**:

- 临时文件管理：上传后创建临时文件，处理完成后自动清理
- 音频时长计算：通过 `javax.sound.sampled` 获取音频时长
- ASR 服务：调用兼容 OpenAI 的 `/v1/audio/transcriptions` API

#### 3.4.5 ASR 服务（AsrService）

调用 OpenAI Whisper 兼容 API 进行语音识别：

- 模型：`whisper-1`
- 响应格式：`text`
- 支持语言参数指定

#### 3.4.6 文本预处理（TextPreprocessor）

翻译前的文本清洗工具：

- 去除 HTML 标签
- 合并多余空格
- 合并多余换行（超过 2 个换行合并为 2 个）

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
@SpringBootApplication
@EnableRetry
@ComponentScan(basePackages = {"com.alan.aiassistant", "com.alan.aicommon", 
    "com.alan.aitranslator", "com.alan.aillm", "com.alan.aiocr", "com.alan.aiagent"})
```

**关键注解**:

- `@EnableRetry`: 启用 Spring Retry 重试机制
- `@ComponentScan`: 显式扫描所有模块的包路径（解决多模块包扫描问题）

#### 3.6.2 应用配置（application.yml）

```yaml
spring:
  application:
    name: ai-assistant
  datasource:
    url: jdbc:postgresql://localhost:5432/ai_assistant
    username: postgres
    password: 123456
  ai:
    openai:
      api-key: ${OPENAI_API_KEY:sk-lm-zmU7SpEW:2wED8QUaU6JFaPKTbs6t}
      base-url: http://localhost:1234/v1
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
    max-text-length: 10000
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

## 四、API 接口清单

### 4.1 翻译接口（v1 版本）

| 方法   | 路径                        | 说明   | 请求类型      |
| ---- | ------------------------- | ---- | --------- |
| POST | `/api/v1/translate/text`  | 文本翻译 | JSON      |
| POST | `/api/v1/translate/image` | 图片翻译 | Multipart |
| POST | `/api/v1/translate/audio` | 语音翻译 | Multipart |

### 4.2 请求/响应示例

#### 文本翻译

**请求**:

```json
{
  "text": "Hello, world!",
  "from": "en",
  "to": "zh",
  "domain": "general",
  "style": "neutral",
  "contextText": "可选的上下文内容"
}
```

**响应**:

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "originalText": "Hello, world!",
    "translatedText": "你好，世界！",
    "from": "en",
    "to": "zh",
    "domain": "general",
    "style": "neutral",
    "durationMs": 1250,
    "cached": false
  },
  "timestamp": 1713763200000
}
```

#### 图片翻译

**请求** (multipart/form-data):

- `file` - 图片文件
- `from` - 源语言代码
- `to` - 目标语言代码

**响应**:

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "extractedText": "图片中提取的原文",
    "translatedText": "翻译后的文本",
    "from": "en",
    "to": "zh",
    "durationMs": 3250
  },
  "timestamp": 1713763200000
}
```

#### 语音翻译

**请求** (multipart/form-data):

- `file` - 音频文件
- `from` - 源语言代码
- `to` - 目标语言代码

**响应**:

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "recognizedText": "语音识别结果",
    "translatedText": "翻译后的文本",
    "from": "en",
    "to": "zh",
    "durationMs": 5250,
    "audioDurationMs": 15000
  },
  "timestamp": 1713763200000
}
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

| 技术                | 版本     | 用途            |
| ----------------- | ------ | ------------- |
| Spring Boot       | 3.5.13 | 应用框架          |
| Spring AI         | 1.1.4  | AI 抽象框架（向量存储） |
| Spring Retry      | -      | 重试机制          |
| Spring Validation | -      | 参数校验（JSR-303） |
| Lombok            | -      | 代码简化          |
| Tess4J            | 5.8.0  | OCR 引擎        |
| Caffeine          | -      | 本地缓存          |
| PostgreSQL        | -      | 关系型数据库        |
| pgvector          | -      | 向量扩展          |

### 5.2 设计模式应用

| 模式        | 应用位置               | 说明                       |
| --------- | ------------------ | ------------------------ |
| **模板方法**  | `LlmService`       | 对话流程标准化（构建请求→调用API→提取内容） |
| **策略模式**  | `TranslateService` | 不同领域/风格的翻译策略通过 prompt 切换 |
| **建造者模式** | DTO 类              | `@Builder` 注解生成流式构建器     |
| **单例模式**  | `OcrService`       | Tesseract 引擎单例管理         |
| **工厂模式**  | `ApiResponse`      | 静态工厂方法创建响应对象             |

### 5.3 异常处理策略

项目采用**分层异常处理**架构：

1. **模块自定义异常**: 各模块定义自己的异常类型（`LlmException`, `TranslationException`, `OcrException`, `AsrException`）
2. **异常转换**: 底层异常在模块边界处转换为业务异常
3. **统一拦截**: `GlobalExceptionHandler` 集中处理所有异常，统一返回格式
4. **日志分级**: WARN 处理客户端错误，ERROR 处理服务端错误

***

## 六、数据流分析

### 6.1 文本翻译流程

```
用户请求
    │
    ▼
┌─────────────┐
│ TranslateController │
│  @Valid 参数校验     │
│  路径: /api/v1/translate/text │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ TranslateService │
│ • 文本预处理      │
│ • 缓存查询        │
│ • 长文本分段      │
│ • 领域/风格适配   │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ LlmService   │
│ • 构建请求体   │
│ • HTTP 调用   │
│ • 重试机制    │
│ • 响应解析    │
└──────┬──────┘
       │
       ▼
   LLM API
       │
       ▼
  返回翻译结果
```

### 6.2 图片翻译流程

```
用户上传图片
    │
    ▼
┌─────────────┐
│ ImageTranslateService │
│ • 图片格式/大小校验    │
│  路径: /api/v1/translate/image │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ OcrService   │
│ • ImageIO 读取│
│ • Tesseract  │
│   文字提取   │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ TranslateService │
│ • 文本翻译       │
└──────┬──────┘
       │
       ▼
  返回 {extractedText, translatedText}
```

### 6.3 语音翻译流程

```
用户上传音频
    │
    ▼
┌─────────────┐
│ AudioTranslateService │
│ • 音频格式/大小校验    │
│ • 创建临时文件         │
│ • 计算音频时长         │
│  路径: /api/v1/translate/audio │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ AsrService   │
│ • 调用 Whisper API │
│ • 语音识别     │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ TranslateService │
│ • 文本翻译       │
└──────┬──────┘
       │
       ▼
  清理临时文件
       │
       ▼
  返回 {recognizedText, translatedText, audioDurationMs}
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

### 7.2 模块构建配置

- 所有模块均配置 `maven-compiler-plugin`，启用 Lombok 注解处理器
- `ai-app` 模块使用 `spring-boot-maven-plugin` 打包可执行 JAR
- 打包时排除 Lombok 依赖

### 7.3 运行环境要求

| 依赖         | 版本  | 说明                  |
| ---------- | --- | ------------------- |
| JDK        | 17+ | Java 运行时            |
| PostgreSQL | 14+ | 数据库，需安装 pgvector 扩展 |
| LM Studio  | -   | 本地 LLM 服务，端口 1234   |
| Tesseract  | -   | OCR 引擎（需安装语言包）      |

### 7.4 环境变量配置（生产环境推荐）

```bash
# LLM 配置
export LLM_BASE_URL=http://localhost:1234/v1
export LLM_MODEL=qwen
export LLM_API_KEY=your-secure-api-key
export LLM_TIMEOUT=60000

# OpenAI 配置（Spring AI 使用）
export OPENAI_API_KEY=your-secure-api-key

# 数据库配置
export DB_URL=jdbc:postgresql://localhost:5432/ai_assistant
export DB_USERNAME=postgres
export DB_PASSWORD=your-secure-password
```

***

## 八、扩展性分析

### 8.1 当前扩展点

1. **新领域翻译**: 在 `TranslateService.buildSystemPrompt()` 中添加新的 domain case
2. **新风格翻译**: 在 `TranslateService.buildSystemPrompt()` 中添加新的 style case
3. **新 OCR 语言**: 通过 `OcrService.extractText(file, language)` 指定 Tesseract 语言包
4. **缓存策略**: 调整 `TranslatorConfig` 中的缓存参数

### 8.2 架构优势

1. **模块化设计**: 各模块职责清晰，可独立演进
2. **依赖倒置**: `ai-app` 作为组装模块，业务模块之间通过接口解耦
3. **配置外部化**: 所有 AI 参数通过 `application.yml` 配置，支持环境变量覆盖，无需改代码
4. **异常隔离**: 各模块自定义异常，避免错误传播污染

### 8.3 待完善方向

1. **ai-agent 模块**: 当前为空，建议实现 ReAct/Plan-and-Execute 智能体框架
2. **向量存储**: 已引入 `spring-ai-starter-vector-store-pgvector`，但未在业务中使用
3. **流式输出**: `LlmService` 中 `stream=false` 写死，可扩展 SSE 流式接口
4. **多模态支持**: 可扩展视频翻译、文档翻译等能力
5. **用户管理**: 当前无认证授权机制
6. **限流熔断**: 建议引入 Sentinel/Resilience4j
7. **单元测试**: 当前测试覆盖不足，需补充 Service/Controller 层测试
8. **API 文档**: 建议引入 Swagger/OpenAPI 自动生成接口文档

***

## 九、代码质量评估

### 9.1 优点

- ✅ 统一的 API 响应格式，前后端交互规范
- ✅ 完善的全局异常处理，错误信息友好
- ✅ 日志记录详尽，便于问题排查
- ✅ 参数校验完善（`@Valid`, `@NotBlank`, `@Size`）
- ✅ 缓存机制提升性能
- ✅ 重试机制增强稳定性
- ✅ 临时文件自动清理，避免磁盘泄漏
- ✅ 配置外部化，支持环境变量注入
- ✅ 敏感配置默认支持环境变量覆盖

### 9.2 改进建议

- ⚠️ `application.yml` 中数据库密码仍有默认值，生产环境务必通过环境变量覆盖
- ⚠️ `AsrService` 中 ASR API URL 硬编码，建议提取到配置
- ⚠️ `OcrService` 中 Tesseract 数据路径未配置，可能依赖系统环境
- ⚠️ 缺少单元测试覆盖（仅有一个默认的 `AiAssistantApplicationTests`）
- ⚠️ 缺少 API 文档（如 Swagger/OpenAPI）

***

## 十、版本变更记录

### v0.0.1-SNAPSHOT (当前版本)

**新增功能**:

- 多模态翻译支持（文本/图片/语音）
- 翻译缓存机制（Caffeine）
- 长文本自动分段翻译
- 领域和风格适配（tech/medical/legal/business/literary/formal/casual/academic）
- 全局异常处理与统一响应格式
- Spring Retry 重试机制
- 配置外部化（支持环境变量）

**架构变更**:

- 清理重复模块（app、translator）
- API 路径规范化（`/api/v1/translate/*`）
- 模块依赖关系优化

***

## 十一、总结

AI-Assistant 是一个架构清晰、模块化的 Spring Boot AI 应用，通过 Maven 多模块方式组织了 LLM、OCR、翻译等核心能力。项目采用**本地化 LLM 方案**（LM Studio），降低了对外部云服务的依赖，适合私有化部署场景。

核心设计亮点：

1. **分层异常处理**: 模块异常 → 业务异常 → 统一拦截 → 标准化响应
2. **Prompt 工程**: 通过精细的系统提示词控制翻译质量
3. **多模态翻译**: 文本/图片/语音三种输入方式的统一翻译能力
4. **性能优化**: Caffeine 缓存 + 长文本分段处理
5. **配置安全**: 敏感配置支持环境变量覆盖

项目当前处于 **MVP 阶段**，核心功能已可用，后续可在智能体框架、向量检索、流式输出等方向持续演进。
