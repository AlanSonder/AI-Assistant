"""
唤醒词检测模块

使用 openWakeWord 实现常驻麦克风监听，检测到唤醒词后触发回调。
"""

import logging
import numpy as np
import sounddevice as sd
from openwakeword.model import Model

logger = logging.getLogger(__name__)


class WakeWordDetector:
    """唤醒词检测器，常驻监听麦克风，检测到唤醒词后触发回调"""

    def __init__(
        self,
        model_name: str = "hey_jarvis",
        threshold: float = 0.5,
        sample_rate: int = 16000,
        chunk_size: int = 1280,  # 80ms at 16kHz, openwakeword 要求的帧长
    ):
        self.threshold = threshold
        self.sample_rate = sample_rate
        self.chunk_size = chunk_size

        # 加载唤醒词模型
        logger.info(f"正在加载唤醒词模型: {model_name}")
        self.model = Model(
            wakeword_models=[model_name],
            inference_framework="onnx",
        )
        self._model_name = model_name
        logger.info("唤醒词模型加载完成")

    def listen(self, on_wake_callback) -> None:
        """
        开始常驻监听，阻塞式运行。
        检测到唤醒词后调用 on_wake_callback()。
        """
        logger.info(f"开始监听唤醒词 '{self._model_name}'...")

        with sd.InputStream(
            samplerate=self.sample_rate,
            channels=1,
            dtype="int16",
            blocksize=self.chunk_size,
        ) as stream:
            while True:
                audio_data, _ = stream.read(self.chunk_size)
                # 转换为 float32 并归一化
                audio_float = audio_data.astype(np.float32) / 32768.0
                audio_flat = audio_float.flatten()

                prediction = self.model.predict(audio_flat)

                # 检查所有模型的预测结果
                for wake_name, score in prediction.items():
                    if score > self.threshold:
                        logger.info(f"唤醒词检测成功! (score={score:.3f})")
                        self.model.reset()  # 重置状态，避免重复触发
                        on_wake_callback()
                        break

    def stop(self) -> None:
        """停止监听（当前阻塞式设计下由外部 Ctrl+C 触发）"""
        logger.info("唤醒词检测器已停止")
