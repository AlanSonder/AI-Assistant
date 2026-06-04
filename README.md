# AI-Assistant

模块化 AI 助手应用，集成大语言模型（LLM）、OCR 文字识别、智能翻译等核心能力，支持多提供商（本地 / DeepSeek）灵活切换。同时包含一个 Vue 3 Web 前端和一个 Python 桌面截图翻译工具。

| 属性 | 说明 |
|------|------|
| 构建工具 | Maven 3.x（Wrapper）+ npm |
| Java / Spring Boot | 21 / 3.5.13 |
| Spring AI | 1.1.4（已排除自动配置，使用自定义 LLM 调用） |
| 前端 | Vue 3.5 + Element Plus 2.13 + Vite 8 + TypeScript |
| Python 桌面端 | tkinter + mss + Pillow + Tesseract/PaddleOCR |
| 服务端口 | 9090（后端）/ 5173（前端开发） |

## 项目结构

```
ai-app ─── 启动入口，依赖所有子模块
  ├── ai-common      公共模块（统一响应体、全局异常处理、WebClient）
  ├── ai-llm         LLM 多提供商调用封装（文本/Vision/流式）
  ├── ai-translator  多模态翻译（文本/图片/语音）+ SSE 流式
  └── ai-agent       智能体框架（预留模块）

ui ─── Vue 3 Web 前端（翻译界面 + LLM 提供商切换）

screen-translator ─── Python 桌面截图翻译工具（独立运行，无需后端）
```

---

## 快速开始

### 环境要求

- JDK 21+（项目使用 Maven Toolchains，无需修改全局 JAVA_HOME）、Node.js 18+、Maven 3.x
- PostgreSQL 14+（需安装 pgvector 扩展）
- LM Studio 或其他 OpenAI 兼容 LLM 服务（本地部署时）
- Tesseract OCR 引擎（可选，screen-translator 本地 OCR 使用）

### 后端启动

```bash
# 构建所有模块
.\mvnw.cmd install -DskipTests

# 启动 Spring Boot
.\mvnw.cmd spring-boot:run -pl ai-app
```

### 前端启动

```bash
cd ui
npm install
npm run dev
```

前端开发服务器在 `http://localhost:5173`，`/api` 请求自动代理到后端 `9090`。

### 一键管理（start.ps1）

项目提供 PowerShell 管理脚本，支持前后端启停、重启、状态查看、日志查看等：

```powershell
.\start.ps1 start          # 启动后端 + 前端
.\start.ps1 stop           # 停止后端 + 前端
.\start.ps1 restart        # 重启后端 + 前端
.\start.ps1 start-backend  # 仅启动后端
.\start.ps1 start-frontend # 仅启动前端
.\start.ps1 status         # 查看运行状态
.\start.ps1 build          # 构建后端
.\start.ps1 menu           # 交互式菜单
```

### screen-translator 启动

```bash
cd screen-translator
pip install -r requirements.txt
python main.py
```

详细配置见 [screen-translator](#screen-translator) 章节。

---

## 多 Provider 配置

项目支持同时配置多个 LLM 提供商，通过 `active-provider` 切换当前使用的提供商，每个提供商可配置多个模型。

### application.yml

```yaml
ai:
  llm:
    active-provider: ${LLM_ACTIVE_PROVIDER:local}
    default-model: ${LLM_DEFAULT_MODEL:qwen}
    timeout: ${LLM_TIMEOUT:60000}
    max-retries: ${LLM_MAX_RETRIES:3}
    temperature: ${LLM_TEMPERATURE:0.3}
    top-p: ${LLM_TOP_P:0.9}
    max-tokens: ${LLM_MAX_TOKENS:4096}
    providers:
      local:
        base-url: ${LLM_LOCAL_URL:http://localhost:1234/v1}
        api-key: ${LLM_LOCAL_KEY:sk-lm-local}
        models: [qwen, llama]
      deepseek:
        base-url: ${LLM_DEEPSEEK_URL:https://api.deepseek.com}
        api-key: ${LLM_DEEPSEEK_KEY:your-api-key}
        models: [deepseek-v4-pro, deepseek-v4-flash]
        vision-enabled: false
        thinking:
          enabled: ${LLM_DEEPSEEK_THINKING:false}
        reasoning-effort: ${LLM_DEEPSEEK_REASONING:high}
```

### 运行时切换

- **API 方式**：通过 `/api/v1/llm/providers/activate` 接口切换提供商和模型（见 API 文档）
- **前端方式**：页面右上角 `LlmSelector` 组件可直接切换提供商、模型，以及 DeepSeek 思考模式开关
- **自动匹配**：切换模型时自动定位其所属的 Provider 并切换

### DeepSeek 思考模式

当 `active-provider` 为 `deepseek` 时，可开启思考模式（`thinking`），模型会先输出推理过程再输出最终结果。`reasoning-effort` 控制推理深度（`low` / `medium` / `high`）。Vision 请求不支持思考模式参数，仅在纯文本场景生效。

---

## API 接口

### 翻译接口（SSE 流式）

| 方法 | 路径 | 说明 | 请求类型 |
|------|------|------|----------|
| POST | `/api/v1/translate/text/stream` | 文本翻译（流式） | JSON |
| POST | `/api/v1/translate/image/stream` | 图片翻译（流式） | Multipart |
| POST | `/api/v1/translate/audio/stream` | 语音翻译（流式） | Multipart |

**文本翻译请求示例**：

```json
{
  "text": "Hello, world!",
  "from": "English",
  "to": "Chinese",
  "domain": "general",
  "style": "neutral"
}
```

**文本翻译 SSE 响应**：

```
event: message
data: {"text":"你好，","done":false}

event: done
data: {"text":"","done":true}
```

**图片翻译**（`multipart/form-data`）：

- `file` — 图片文件（JPG/PNG/GIF/WEBP，最大 50MB）
- `from` — 源语言（如 `Japanese`）
- `to` — 目标语言（如 `Chinese`）

> 图片翻译依赖 Vision 能力，当前 Provider 需设置 `vision-enabled: true`（如 local）。

**语音翻译**（`multipart/form-data`）：

- `file` — 音频文件（MP3/WAV/M4A/OGG/FLAC，最大 50MB）
- `from` / `to` — 语言参数

SSE 事件类型：`asr`（语音识别完成）→ `translation`（翻译流式块）→ `done`（完成）

### LLM 管理接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/llm/providers` | 获取所有提供商及模型列表 |
| POST | `/api/v1/llm/providers/activate` | 切换提供商和/或模型 |
| POST | `/api/v1/llm/thinking` | 切换 DeepSeek 思考模式 |

**切换提供商请求**：

```json
{
  "provider": "deepseek",
  "model": "deepseek-v4-flash"
}
```

**切换思考模式请求**：

```json
{ "enabled": true }
```

### 错误响应格式

```json
{
  "code": 400,
  "message": "参数验证失败: 翻译文本不能为空",
  "data": null,
  "timestamp": 1713763200000
}
```

---

## screen-translator

独立的 Python 桌面截图翻译工具，**无需 Spring Boot 后端**，直接调用 LLM API。

### 功能

- 系统托盘图标 + 全局热键（默认 `Ctrl+Q`）触发截图
- 全屏选区（类似 Snipaste），悬停选框可保存截图或翻译
- 翻译结果实时流式显示在覆盖层上
- 支持运行时切换 LLM 提供商/模型、OCR 引擎、字体、语言

### 双模式翻译

根据当前 Provider 是否支持 Vision 自动选择：

| 模式 | 适用 Provider | 流程 |
|------|--------------|------|
| Vision 模式 | local | 截图 → LLM Vision API（直接视觉翻译） |
| OCR 模式 | deepseek 等非 Vision | 截图 → 本地 OCR 识别 → LLM 文字翻译 |

OCR 引擎支持 **Tesseract**（轻量快速，默认）和 **PaddleOCR**（精度更高）。

### config.yaml 关键配置

```yaml
llm:
  active_provider: "deepseek"       # local / deepseek
  default_model: "deepseek-v4-flash"

ocr:
  engine: "tesseract"               # tesseract / paddle
  lang: "ch"                        # 识别语言

translate:
  from: "Japanese"
  to: "Chinese"

hotkey: "ctrl+Q"                    # 全局热键
```

---

## 开发说明

### 模块构建

```bash
# 构建全部模块
.\mvnw.cmd clean install -DskipTests

# 仅构建指定模块及其依赖
.\mvnw.cmd install -DskipTests -pl ai-translator -am
```

### 目录结构

```
AI-Assistant/
├── ai-app/src/main/java/com/alan/aiassistant/
│   └── AiAssistantApplication.java        # 启动类
├── ai-common/src/main/java/com/alan/aicommon/
│   ├── config/WebClientConfig.java        # WebClient Bean
│   ├── dto/ApiResponse.java               # 统一响应体
│   └── exception/
│       ├── GlobalExceptionHandler.java    # 全局异常处理
│       ├── LlmException.java
│       └── TranslationException.java
├── ai-llm/src/main/java/com/alan/aillm/
│   ├── config/LlmConfig.java             # 多 Provider 配置
│   ├── controller/LlmController.java      # LLM 管理接口
│   ├── dto/request/ChatRequest.java       # 含 thinking/reasoningEffort
│   ├── dto/response/ChatResponse.java
│   └── service/LlmService.java            # 核心 LLM 服务
├── ai-translator/src/main/java/com/alan/aitranslator/
│   ├── config/TranslatorConfig.java       # 翻译配置
│   ├── controller/TranslateController.java
│   ├── service/
│   │   ├── TranslateService.java          # 文本翻译 + 缓存 + 分段
│   │   ├── ImageTranslateService.java     # 图片翻译（Vision/OCR 降级）
│   │   ├── AudioTranslateService.java     # 语音翻译
│   │   └── AsrService.java               # Whisper ASR
│   └── util/TextPreprocessor.java
├── ui/src/
│   ├── App.vue                            # 主页面（翻译 + SSE）
│   └── components/LlmSelector.vue         # Provider/Model 切换器
└── screen-translator/
    ├── main.py                            # 入口（Tk + 系统托盘）
    ├── llm_client.py                      # 独立 LLM 客户端
    ├── tesseract_service.py               # Tesseract OCR
    ├── ocr_service.py                     # PaddleOCR
    ├── overlay.py                         # 翻译结果覆盖层
    ├── region_selector.py                 # 全屏选区
    ├── selection_frame.py                 # 悬停选框
    ├── tray.py                            # 系统托盘 + 热键
    └── config.yaml                        # 配置文件
```

### 已知限制

- 文本翻译接口（`text/stream`）实际为同步调用 LLM 后分块发送 SSE，非真正的 LLM 流式输出
- `TranslateService.buildSystemPrompt()` 中 `domain` 和 `style` 参数传入但未在提示词模板中使用（仅 2 个 `%s` 占位符）
- `AsrService` 的 API 地址从当前激活 Provider 的 `baseUrl` 拼接，不支持独立配置
- 缺少单元测试覆盖和 API 文档（Swagger/OpenAPI）
- `extra_body` 透传仅在测试类中实现，未集成到 `LlmService`
