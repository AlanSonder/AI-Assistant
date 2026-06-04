"""
AI 助手模块 (LLM 对话 + TTS 语音合成)

整合 LLM 对话能力和 edge-tts 语音合成，实现文字 -> 对话 -> 语音回应的完整链路。
"""

import asyncio
import io
import logging
import sounddevice as sd
import soundfile as sf
import edge_tts
from openai import OpenAI

logger = logging.getLogger(__name__)


class Assistant:
    """AI 助手，整合 LLM 对话与 TTS 语音播报"""

    def __init__(
        self,
        llm_base_url: str,
        llm_api_key: str,
        llm_model: str,
        system_prompt: str,
        tts_voice: str = "zh-CN-XiaoxiaoNeural",
        tts_rate: str = "+0%",
        tts_volume: str = "+0%",
    ):
        self.llm_model = llm_model
        self.tts_voice = tts_voice
        self.tts_rate = tts_rate
        self.tts_volume = tts_volume

        # 初始化 LLM 客户端
        self.llm_client = OpenAI(base_url=llm_base_url, api_key=llm_api_key)

        # 对话历史
        self.messages = [
            {"role": "system", "content": system_prompt}
        ]

        logger.info(f"AI 助手初始化完成 (model={llm_model}, voice={tts_voice})")

    def chat(self, user_text: str) -> str:
        """
        发送文字给 LLM，获取回复。

        Args:
            user_text: 用户输入的文字

        Returns:
            LLM 的回复文字
        """
        self.messages.append({"role": "user", "content": user_text})

        try:
            response = self.llm_client.chat.completions.create(
                model=self.llm_model,
                messages=self.messages,
                temperature=0.7,
                max_tokens=1024,
            )
            reply = response.choices[0].message.content.strip()
            self.messages.append({"role": "assistant", "content": reply})

            # 限制对话历史长度，避免 token 溢出（保留 system + 最近 10 轮）
            if len(self.messages) > 21:  # system + 10*2
                self.messages = [self.messages[0]] + self.messages[-20:]

            logger.info(f"LLM 回复: {reply[:100]}...")
            return reply

        except Exception as e:
            logger.error(f"LLM 调用失败: {e}", exc_info=True)
            return "抱歉，我遇到了一些问题，请稍后再试。"

    async def _synthesize_speech(self, text: str) -> bytes:
        """
        使用 edge-tts 合成语音，返回音频字节。

        Args:
            text: 要合成的文字

        Returns:
            MP3 格式的音频字节
        """
        communicate = edge_tts.Communicate(
            text,
            voice=self.tts_voice,
            rate=self.tts_rate,
            volume=self.tts_volume,
        )

        audio_buffer = io.BytesIO()
        async for chunk in communicate.stream():
            if chunk["type"] == "audio":
                audio_buffer.write(chunk["data"])

        audio_buffer.seek(0)
        return audio_buffer.getvalue()

    def speak(self, text: str) -> None:
        """
        将文字合成为语音并播放。

        Args:
            text: 要播报的文字
        """
        if not text.strip():
            logger.warning("播报文字为空，跳过")
            return

        try:
            logger.info(f"正在合成语音...")
            audio_bytes = asyncio.run(self._synthesize_speech(text))

            # 解码 MP3 并播放
            audio_data, sample_rate = sf.read(io.BytesIO(audio_bytes))
            logger.info(f"正在播放语音 (时长: {len(audio_data)/sample_rate:.1f}s)...")
            sd.play(audio_data, sample_rate)
            sd.wait()  # 阻塞等待播放完成
            logger.info("语音播放完成")

        except Exception as e:
            logger.error(f"语音合成/播放失败: {e}", exc_info=True)

    def respond(self, user_text: str) -> None:
        """
        完整响应流程：接收用户文字 -> LLM 生成回复 -> TTS 播报。

        Args:
            user_text: 用户输入的文字
        """
        logger.info(f"用户说: {user_text}")

        # LLM 生成回复
        reply = self.chat(user_text)
        logger.info(f"助手回复: {reply}")

        # TTS 播报
        self.speak(reply)

    def reset(self) -> None:
        """重置对话历史（保留 system prompt）"""
        self.messages = [self.messages[0]]
        logger.info("对话历史已重置")
