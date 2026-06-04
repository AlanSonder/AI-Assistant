"""
音频采集与端点检测模块 (Audio Capture + VAD)

唤醒词触发后，开始录音并使用 Silero VAD 检测语音端点，
当用户说完（静音超过阈值）自动停止录音。
"""

import logging
import numpy as np
import sounddevice as sd
import torch

logger = logging.getLogger(__name__)


class AudioCapture:
    """录音器，使用 VAD 自动检测语音端点"""

    def __init__(
        self,
        sample_rate: int = 16000,
        silence_threshold: float = 1.5,
        max_record_seconds: float = 30.0,
        frame_duration_ms: int = 30,
    ):
        self.sample_rate = sample_rate
        self.silence_threshold = silence_threshold
        self.max_record_seconds = max_record_seconds
        self.frame_duration_ms = frame_duration_ms
        self.frame_size = int(sample_rate * frame_duration_ms / 1000)

        # 加载 Silero VAD 模型
        logger.info("正在加载 Silero VAD 模型...")
        self.vad_model, self.utils = torch.hub.load(
            repo_or_dir="snakers4/silero-vad",
            model="silero_vad",
            trust_repo=True,
        )
        logger.info("VAD 模型加载完成")

    def record(self) -> np.ndarray:
        """
        开始录音，阻塞运行直到用户说完。
        返回录音数据的 numpy 数组 (float32, 16kHz, 单声道)。
        """
        logger.info("开始录音，请说话...")
        audio_buffer = []
        silence_duration = 0.0
        has_speech = False  # 是否检测到过语音

        with sd.InputStream(
            samplerate=self.sample_rate,
            channels=1,
            dtype="float32",
            blocksize=self.frame_size,
        ) as stream:
            while True:
                audio_chunk, _ = stream.read(self.frame_size)
                audio_flat = audio_chunk.flatten()
                audio_buffer.append(audio_flat.copy())

                # VAD 检测
                audio_tensor = torch.from_numpy(audio_flat)
                speech_prob = self.vad_model(audio_tensor, self.sample_rate).item()
                is_speech = speech_prob > 0.5

                if is_speech:
                    has_speech = True
                    silence_duration = 0.0
                else:
                    silence_duration += self.frame_duration_ms / 1000.0

                # 总录音时长
                total_duration = len(audio_buffer) * self.frame_duration_ms / 1000.0

                # 判断是否停止录音
                if has_speech and silence_duration >= self.silence_threshold:
                    logger.info(f"检测到静音 {silence_duration:.1f}s，停止录音")
                    break

                if total_duration >= self.max_record_seconds:
                    logger.warning(f"达到最大录音时长 {self.max_record_seconds}s，强制停止")
                    break

                # 如果还没检测到语音且已录音超过 5 秒，也停止（可能是误触发）
                if not has_speech and total_duration >= 5.0:
                    logger.warning("未检测到语音，停止录音")
                    break

        if not has_speech:
            logger.warning("本次录音未检测到语音")
            return np.array([], dtype=np.float32)

        audio_data = np.concatenate(audio_buffer)
        duration = len(audio_data) / self.sample_rate
        logger.info(f"录音完成，时长: {duration:.2f}s")
        return audio_data
