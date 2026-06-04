"""
语音识别模块 (ASR)

使用 faster-whisper 实现本地语音识别，将录音转为文字。
"""

import logging
import numpy as np
from faster_whisper import WhisperModel

logger = logging.getLogger(__name__)


class ASRService:
    """语音识别服务，基于 faster-whisper"""

    def __init__(
        self,
        model_size: str = "medium",
        device: str = "cpu",
        compute_type: str = "int8",
        language: str = "zh",
    ):
        self.language = language
        logger.info(f"正在加载 Whisper 模型: {model_size} (device={device}, compute={compute_type})")
        self.model = WhisperModel(
            model_size,
            device=device,
            compute_type=compute_type,
        )
        logger.info("Whisper 模型加载完成")

    def transcribe(self, audio_data: np.ndarray, sample_rate: int = 16000) -> str:
        """
        将音频 numpy 数组转为文字。

        Args:
            audio_data: float32 numpy 数组，单声道音频
            sample_rate: 采样率（默认 16kHz）

        Returns:
            识别出的文字字符串，识别失败返回空字符串
        """
        if audio_data.size == 0:
            logger.warning("输入音频为空，跳过识别")
            return ""

        try:
            # faster-whisper 支持直接传入 numpy 数组
            segments, info = self.model.transcribe(
                audio_data,
                language=self.language if self.language else None,
                beam_size=5,
                vad_filter=True,  # 内置 VAD 过滤
            )

            # 拼接所有 segment 的文字
            text_parts = []
            for segment in segments:
                text_parts.append(segment.text.strip())

            text = "".join(text_parts)
            logger.info(f"识别结果: {text}")
            return text

        except Exception as e:
            logger.error(f"语音识别失败: {e}", exc_info=True)
            return ""
