"""LLM 客户端 - 直接调用 OpenAI 兼容 API

替代 Spring Boot 后端翻译接口，使 Python 应用完全独立运行。

支持的提供商:
- deepseek: https://api.deepseek.com
- local: LM Studio / Ollama 等本地部署
"""

import json
import time
import logging
import requests
from io import BytesIO
from PIL import Image

logger = logging.getLogger(__name__)

# ======================== 默认配置 ========================

DEFAULT_PROVIDERS = {
    "deepseek": {
        "base_url": "https://api.deepseek.com",
        "api_key": "",
        "models": ["deepseek-v4-pro", "deepseek-v4-flash"],
        "vision_enabled": False,
        "thinking_enabled": False,
        "reasoning_effort": "high",
    },
    "local": {
        "base_url": "http://localhost:1234/v1",
        "api_key": "lm-studio",
        "models": ["qwen", "llama"],
        "vision_enabled": True,
    },
}

# 语言名映射（用于构建友好提示词）
_LANG_NAME_MAP = {
    "auto":     "自动检测到的文字",
    "chinese":  "中文",
    "english":  "英文",
    "japanese": "日文",
    "korean":   "韩文",
    "french":   "法文",
    "german":   "德文",
    "spanish":  "西班牙文",
    "russian":  "俄文",
}


class LlmClient:
    """直接调用 LLM API 的客户端"""

    def __init__(self, providers: dict = None):
        """
        Args:
            providers: 提供商配置字典，格式同 DEFAULT_PROVIDERS
        """
        self.providers = providers or DEFAULT_PROVIDERS
        self.active_provider = "deepseek"
        self.active_model = "deepseek-v4-flash"
        self.thinking_enabled = False
        self.reasoning_effort = "high"
        self._session = requests.Session()
        self._session.timeout = 60

    # ======================== 提供商管理 ========================

    def set_provider(self, name: str):
        """切换激活的提供商"""
        if name not in self.providers:
            raise ValueError(f"未知提供商: {name}, 可用: {list(self.providers.keys())}")
        self.active_provider = name
        logger.info("LLM 提供商已切换: %s", name)

    def set_model(self, model: str):
        """切换模型"""
        self.active_model = model
        logger.info("LLM 模型已切换: %s", model)

    def set_thinking(self, enabled: bool):
        """切换 DeepSeek 思考模式"""
        self.thinking_enabled = enabled
        logger.info("Thinking 模式: %s", "启用" if enabled else "关闭")

    def get_active_config(self) -> dict:
        """获取当前激活的提供商配置"""
        return self.providers.get(self.active_provider, {})

    def is_vision_enabled(self) -> bool:
        """当前提供商是否支持 Vision"""
        return self.get_active_config().get("vision_enabled", True)

    # ======================== 文字翻译 ========================

    def translate_text(self, text: str, from_lang: str = "auto",
                       to_lang: str = "Chinese") -> str:
        """
        纯文字翻译（使用当前激活的提供商和模型）。

        Args:
            text:      待翻译文字
            from_lang: 源语言
            to_lang:   目标语言

        Returns:
            翻译后的文本
        """
        config = self.get_active_config()
        system_prompt = f"你是一个翻译引擎。将以下{_lang_name(from_lang)}文本翻译为{_lang_name(to_lang)}。只输出译文，不要解释。"
        user_prompt = f"<text>\n{text}\n</text>"

        messages = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ]

        request_body = self._build_request_body(messages, stream=True)
        input_chars = len(text)

        t_total_start = time.time()
        t_req_start = time.time()

        parts = []
        first_token_elapsed = None
        token_count = 0

        url = f"{config['base_url']}/chat/completions"
        headers = _build_headers(config)
        body = dict(request_body)
        body["stream"] = True

        resp = self._session.post(url, json=body, headers=headers, stream=True, timeout=120)
        resp.raise_for_status()
        resp.encoding = "utf-8"

        t_connect = time.time() - t_req_start

        for line in resp.iter_lines(decode_unicode=True):
            if not line:
                continue
            line = line.strip()
            if not line.startswith("data:"):
                continue
            data_str = line[5:].strip()
            if data_str == "[DONE]":
                break
            try:
                data = json.loads(data_str)
                choices = data.get("choices", [])
                if choices:
                    delta = choices[0].get("delta", {})
                    content = delta.get("content", "")
                    if content:
                        if first_token_elapsed is None:
                            first_token_elapsed = time.time() - t_req_start
                        parts.append(content)
                        token_count += 1
            except (json.JSONDecodeError, KeyError, IndexError):
                continue

        result = "".join(parts).strip()

        t_total = time.time() - t_total_start
        t_api = time.time() - t_req_start
        t_stream = t_api - t_connect if first_token_elapsed else 0
        ttft = f"{first_token_elapsed:.2f}s" if first_token_elapsed is not None else "N/A"
        output_chars = len(result)

        logger.info(
            "LLM 文字翻译完成 | 输入:%d字 输出:%d字 耗时 %.2fs | "
            "连接:%.2fs TTFT:%s 流式:%.2fs API总计:%.2fs",
            input_chars, output_chars, t_total,
            t_connect, ttft, t_stream, t_api
        )
        return result

    def translate_text_stream(self, text: str, from_lang: str = "auto",
                              to_lang: str = "Chinese") -> str:
        """纯文字翻译（流式，结果与 translate_text 相同）"""
        # 当前实现：流式调用但聚合成完整结果，保持接口一致
        config = self.get_active_config()
        system_prompt = f"翻译以下文字：{_lang_name(from_lang)} → {_lang_name(to_lang)}，只输出译文。"
        user_prompt = f"<text>\n{text}\n</text>"

        messages = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ]

        request_body = self._build_request_body(messages, stream=True)

        t_start = time.time()
        parts = []
        first_token = None

        for chunk in self._call_chat_completions_stream(config, request_body):
            if chunk:
                if first_token is None:
                    first_token = time.time() - t_start
                parts.append(chunk)

        result = "".join(parts).strip()
        elapsed = time.time() - t_start
        ttft = f"TTFT={first_token:.2f}s" if first_token else "N/A"
        logger.info("LLM 文字翻译(流式)完成: %d 字符 (%s, %.2fs)",
                    len(result), ttft, elapsed)
        return result

    def translate_text_stream_gen(self, text: str, from_lang: str = "auto",
                                  to_lang: str = "Chinese"):
        """
        纯文字翻译（流式生成器），yield 每个文本块供调用方实时显示。

        Yields:
            str: 每次 LLM 返回的文本片段
        """
        config = self.get_active_config()
        system_prompt = f"翻译以下文字：{_lang_name(from_lang)} → {_lang_name(to_lang)}，只输出译文。"
        user_prompt = f"<text>\n{text}\n</text>"

        messages = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ]

        request_body = self._build_request_body(messages, stream=True)

        t_start = time.time()
        first_token = None
        total_chars = 0

        for chunk in self._call_chat_completions_stream(config, request_body):
            if chunk:
                if first_token is None:
                    first_token = time.time() - t_start
                    ttft = f"TTFT={first_token:.2f}s"
                    logger.info("LLM 文字翻译流式首token: %s", ttft)
                total_chars += len(chunk)
                yield chunk

        elapsed = time.time() - t_start
        logger.info("LLM 文字翻译流式完成: %d 字符 (%.2fs)", total_chars, elapsed)

    # ======================== 图片翻译（Vision 模式） ========================

    def translate_image(self, image: Image.Image, from_lang: str = "auto",
                        to_lang: str = "Chinese") -> str:
        """
        图片翻译（Vision 模式，仅支持 Vision 的 provider 可用）。

        Args:
            image:     PIL Image 对象
            from_lang: 源语言
            to_lang:   目标语言

        Returns:
            翻译后的文本
        """
        config = self.get_active_config()
        if not config.get("vision_enabled", True):
            raise RuntimeError(f"提供商 {self.active_provider} 不支持 Vision")

        t_total_start = time.time()

        # ========== 阶段1：图片预处理 ==========
        t_prep_start = time.time()
        w_orig, h_orig = image.size

        max_dim = 1024
        if max(w_orig, h_orig) > max_dim:
            scale = max_dim / max(w_orig, h_orig)
            image = image.resize((int(w_orig * scale), int(h_orig * scale)), Image.LANCZOS)
            w_new, h_new = image.size
        else:
            w_new, h_new = w_orig, h_orig

        buf = BytesIO()
        image.save(buf, format="JPEG", quality=80)
        jpeg_bytes = buf.getvalue()

        t_encode = time.time()
        base64_data = _encode_base64(jpeg_bytes)

        t_prep = time.time() - t_prep_start
        t_compress = t_encode - t_prep_start
        t_base64 = time.time() - t_encode
        jpeg_kb = len(jpeg_bytes) / 1024
        b64_kb = len(base64_data) / 1024

        logger.info(
            "Vision 图片预处理: %dx%d→%dx%d JPEG:%dKB(%.2fs) Base64:%dKB(%.2fs) 总计:%.2fs",
            w_orig, h_orig, w_new, h_new, int(jpeg_kb), t_compress,
            int(b64_kb), t_base64, t_prep
        )

        # ========== 阶段2：构建请求体 ==========
        t_build_start = time.time()

        system_prompt = (
            f"请提取图片中的{_lang_name(from_lang)}，并将其翻译为{_lang_name(to_lang)}。"
            f"只输出译文，不要解释。"
        )
        user_text = f"请提取图片中的{_lang_name(from_lang)}并翻译为{_lang_name(to_lang)}，只输出译文。"

        messages = [
            {"role": "system", "content": system_prompt},
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": user_text},
                    {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{base64_data}"}},
                ],
            },
        ]

        request_body = self._build_request_body(messages, stream=True, is_vision=True)
        t_build = time.time() - t_build_start

        # ========== 阶段3：API 调用（流式） ==========
        t_api_start = time.time()
        url = f"{config['base_url']}/chat/completions"
        headers = _build_headers(config)
        body = dict(request_body)
        body["stream"] = True

        resp = self._session.post(url, json=body, headers=headers, stream=True, timeout=180)
        resp.raise_for_status()
        resp.encoding = "utf-8"

        t_connect = time.time() - t_api_start

        parts = []
        first_token_elapsed = None

        for line in resp.iter_lines(decode_unicode=True):
            if not line:
                continue
            line = line.strip()
            if not line.startswith("data:"):
                continue
            data_str = line[5:].strip()
            if data_str == "[DONE]":
                break
            try:
                data = json.loads(data_str)
                choices = data.get("choices", [])
                if choices:
                    delta = choices[0].get("delta", {})
                    content = delta.get("content", "")
                    if content:
                        if first_token_elapsed is None:
                            first_token_elapsed = time.time() - t_api_start
                        parts.append(content)
            except (json.JSONDecodeError, KeyError, IndexError):
                continue

        raw = "".join(parts).strip()
        t_api = time.time() - t_api_start
        ttft = f"{first_token_elapsed:.2f}s" if first_token_elapsed is not None else "N/A"
        t_stream = t_api - t_connect

        # ========== 阶段4：处理响应 ==========
        t_parse_start = time.time()
        translated = raw  # 普通文本输出，无需 JSON 解析
        t_parse = time.time() - t_parse_start

        # ========== 汇总 ==========
        t_total = time.time() - t_total_start
        output_chars = len(translated)

        logger.info(
            "Vision 翻译完成 | 结果:%d字 | "
            "预处理:%.2fs 请求构建:%.2fs API总计:%.2fs(连接:%.2fs TTFT:%s 流式:%.2fs) 解析:%.2fs | "
            "总耗时:%.2fs",
            output_chars,
            t_prep, t_build, t_api, t_connect, ttft, t_stream, t_parse,
            t_total
        )
        return translated

    def translate_image_stream_gen(self, image: Image.Image, from_lang: str = "auto",
                                   to_lang: str = "Chinese"):
        """
        图片翻译（Vision 模式，流式生成器），yield 翻译文本块供调用方实时显示。

        Yields:
            str: 每次 LLM 返回的文本片段
        """
        config = self.get_active_config()
        if not config.get("vision_enabled", True):
            raise RuntimeError(f"提供商 {self.active_provider} 不支持 Vision")

        t_total_start = time.time()

        # 图片预处理
        w_orig, h_orig = image.size
        max_dim = 1024
        if max(w_orig, h_orig) > max_dim:
            scale = max_dim / max(w_orig, h_orig)
            image = image.resize((int(w_orig * scale), int(h_orig * scale)), Image.LANCZOS)

        buf = BytesIO()
        image.save(buf, format="JPEG", quality=80)




        
        jpeg_bytes = buf.getvalue()
        base64_data = _encode_base64(jpeg_bytes)

        t_prep = time.time() - t_total_start
        logger.info("Vision 流式: 图片预处理完成 (%.2fs)", t_prep)

        # 构建请求
        system_prompt = (
            f"请提取图片中的{_lang_name(from_lang)}，并将其翻译为{_lang_name(to_lang)}。"
            f"只输出{_lang_name(to_lang)}译文，不要解释，不要添加任何其他文字。"
        )
        user_text = f"请提取图片中的{_lang_name(from_lang)}并翻译为{_lang_name(to_lang)}，只输出译文。"

        messages = [
            {"role": "system", "content": system_prompt},
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": user_text},
                    {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{base64_data}"}},
                ],
            },
        ]

        request_body = self._build_request_body(messages, stream=True, is_vision=True)

        # 流式调用
        t_api_start = time.time()
        url = f"{config['base_url']}/chat/completions"
        headers = _build_headers(config)
        body = dict(request_body)
        body["stream"] = True

        resp = self._session.post(url, json=body, headers=headers, stream=True, timeout=180)
        resp.raise_for_status()
        resp.encoding = "utf-8"

        t_connect = time.time() - t_api_start
        logger.info("Vision 流式: API连接完成 (%.2fs)", t_connect)

        first_token = None
        total_chars = 0

        for line in resp.iter_lines(decode_unicode=True):
            if not line:
                continue
            line = line.strip()
            if not line.startswith("data:"):
                continue
            data_str = line[5:].strip()
            if data_str == "[DONE]":
                break
            try:
                data = json.loads(data_str)
                choices = data.get("choices", [])
                if choices:
                    delta = choices[0].get("delta", {})
                    content = delta.get("content", "")
                    if content:
                        if first_token is None:
                            first_token = time.time() - t_api_start
                            logger.info("Vision 流式: TTFT=%.2fs", first_token)
                        total_chars += len(content)
                        yield content
            except (json.JSONDecodeError, KeyError, IndexError):
                continue

        t_total = time.time() - t_total_start
        logger.info("Vision 流式翻译完成: %d字符, 总耗时=%.2fs", total_chars, t_total)

    # ======================== 内部：请求体构建 ========================

    def _build_request_body(self, messages: list, stream: bool = True,
                            is_vision: bool = False) -> dict:
        """构建 OpenAI 兼容请求体"""
        config = self.get_active_config()
        body = {
            "model": self.active_model,
            "messages": messages,
            "temperature": 0.3,
            "max_tokens": 4096,
            "top_p": 0.9,
            "stream": stream,
        }

        # DeepSeek 特有参数（仅非 Vision 请求时注入）
        if self.active_provider == "deepseek" and not is_vision:
            if self.thinking_enabled:
                body["thinking"] = {"type": "enabled"}
            if config.get("reasoning_effort"):
                body["reasoning_effort"] = config["reasoning_effort"]

        return body

    # ======================== 内部：HTTP 调用 ========================

    def _call_chat_completions(self, config: dict, body: dict) -> str:
        """同步调用 /chat/completions，聚合流式响应"""
        url = f"{config['base_url']}/chat/completions"
        headers = _build_headers(config)
        body["stream"] = False  # 同步模式

        resp = self._session.post(url, json=body, headers=headers, timeout=120)
        resp.raise_for_status()
        data = resp.json()

        choices = data.get("choices", [])
        if not choices:
            raise RuntimeError("LLM 返回空 choices")
        content = choices[0].get("message", {}).get("content", "")
        return content.strip()

    def _call_chat_completions_stream(self, config: dict, body: dict):
        """流式调用 /chat/completions，yield 文本块"""
        url = f"{config['base_url']}/chat/completions"
        headers = _build_headers(config)
        body["stream"] = True

        resp = self._session.post(url, json=body, headers=headers, stream=True, timeout=120)
        resp.raise_for_status()
        resp.encoding = "utf-8"

        for line in resp.iter_lines(decode_unicode=True):
            if not line:
                continue
            line = line.strip()
            if not line.startswith("data:"):
                continue
            data_str = line[5:].strip()
            if data_str == "[DONE]":
                break
            try:
                data = json.loads(data_str)
                choices = data.get("choices", [])
                if choices:
                    delta = choices[0].get("delta", {})
                    content = delta.get("content", "")
                    if content:
                        yield content
            except (json.JSONDecodeError, KeyError, IndexError):
                continue


# ======================== 工具函数 ========================

def _build_headers(config: dict) -> dict:
    """构建请求头"""
    headers = {"Content-Type": "application/json"}
    api_key = config.get("api_key", "")
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    return headers


def _lang_name(code: str) -> str:
    """将语言代码映射为中文名称"""
    if not code:
        return "自动检测"
    return _LANG_NAME_MAP.get(code.lower(), code)


def _encode_base64(data: bytes) -> str:
    """将字节数据编码为 Base64 字符串"""
    import base64
    return base64.b64encode(data).decode("utf-8")
