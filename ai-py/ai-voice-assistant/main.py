"""
语音助手主程序

串联唤醒词检测 -> 录音 -> ASR -> LLM -> TTS 的完整链路，
实现类 Siri 的语音交互体验。

使用方式:
    python main.py              # 使用默认 config.yaml
    python main.py -c my.yaml   # 指定配置文件
"""

import argparse
import logging
import signal
import sys
import os
import numpy as np
import sounddevice as sd
import yaml

from wake_word import WakeWordDetector
from audio_capture import AudioCapture
from asr import ASRService
from assistant import Assistant

# ─── 日志配置 ──────────────────────────────────────────────────────────────────

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("voice_assistant")


# ─── 配置加载 ──────────────────────────────────────────────────────────────────

def load_config(config_path: str) -> dict:
    """加载 YAML 配置文件"""
    if not os.path.exists(config_path):
        logger.error(f"配置文件不存在: {config_path}")
        sys.exit(1)

    with open(config_path, "r", encoding="utf-8") as f:
        config = yaml.safe_load(f)

    logger.info(f"配置文件加载成功: {config_path}")
    return config


# ─── 提示音 ────────────────────────────────────────────────────────────────────

def play_ding():
    """播放唤醒提示音（生成一个简单的正弦波 beep）"""
    sample_rate = 44100
    duration = 0.15  # 秒
    frequency = 880  # Hz (A5 音)

    t = np.linspace(0, duration, int(sample_rate * duration), False)
    # 生成带衰减的正弦波
    tone = np.sin(2 * np.pi * frequency * t) * np.exp(-3 * t)
    # 调整音量
    tone = tone * 0.3

    sd.play(tone, sample_rate)
    sd.wait()


# ─── 主类 ──────────────────────────────────────────────────────────────────────

class VoiceAssistant:
    """语音助手主类，整合所有模块"""

    def __init__(self, config: dict):
        self.config = config
        self._running = True

        # 注册信号处理器
        signal.signal(signal.SIGINT, self._signal_handler)

        # 初始化各模块
        self._init_modules()

    def _init_modules(self):
        """初始化所有子模块"""
        cfg = self.config

        # 唤醒词检测器
        wake_cfg = cfg.get("wake_word", {})
        self.wake_detector = WakeWordDetector(
            model_name=wake_cfg.get("model", "hey_jarvis"),
            threshold=wake_cfg.get("threshold", 0.5),
        )

        # 录音器
        audio_cfg = cfg.get("audio", {})
        self.audio_capture = AudioCapture(
            sample_rate=audio_cfg.get("sample_rate", 16000),
            silence_threshold=audio_cfg.get("silence_threshold", 1.5),
            max_record_seconds=audio_cfg.get("max_record_seconds", 30),
        )

        # 语音识别
        asr_cfg = cfg.get("asr", {})
        self.asr = ASRService(
            model_size=asr_cfg.get("model_size", "medium"),
            device=asr_cfg.get("device", "cpu"),
            compute_type=asr_cfg.get("compute_type", "int8"),
            language=asr_cfg.get("language", "zh"),
        )

        # AI 助手 (LLM + TTS)
        llm_cfg = cfg.get("llm", {})
        active_provider = llm_cfg.get("active_provider", "deepseek")
        provider_cfg = llm_cfg.get("providers", {}).get(active_provider, {})
        tts_cfg = cfg.get("tts", {})

        self.assistant = Assistant(
            llm_base_url=provider_cfg.get("base_url", ""),
            llm_api_key=provider_cfg.get("api_key", ""),
            llm_model=provider_cfg.get("model", "deepseek-v4-flash"),
            system_prompt=cfg.get("system_prompt", "你是一个智能语音助手。"),
            tts_voice=tts_cfg.get("voice", "zh-CN-XiaoxiaoNeural"),
            tts_rate=tts_cfg.get("rate", "+0%"),
            tts_volume=tts_cfg.get("volume", "+0%"),
        )

    def _signal_handler(self, signum, frame):
        """处理 Ctrl+C 退出"""
        logger.info("\n收到退出信号，正在关闭...")
        self._running = False
        sys.exit(0)

    def _on_wake(self):
        """唤醒词触发后的处理流程"""
        logger.info("=== 已唤醒，正在聆听 ===")

        # 播放提示音
        play_ding()

        # 录音
        audio_data = self.audio_capture.record()
        if audio_data.size == 0:
            logger.info("未检测到有效语音，回到待机状态")
            return

        # 语音识别
        logger.info("正在识别语音...")
        text = self.asr.transcribe(audio_data)
        if not text:
            logger.info("语音识别无结果，回到待机状态")
            return

        # 检查是否为退出指令
        if any(kw in text for kw in ["退出", "再见", "拜拜", "停止"]):
            logger.info("检测到退出指令")
            self.assistant.speak("好的，再见！")
            return

        # AI 回复
        logger.info("正在生成回复...")
        self.assistant.respond(text)

        logger.info("=== 回复完成，回到待机状态 ===\n")

    def run(self):
        """启动语音助手主循环"""
        logger.info("=" * 50)
        logger.info("  语音助手已启动")
        logger.info(f"  唤醒词: {self.config.get('wake_word', {}).get('model', 'hey_jarvis')}")
        logger.info("  按 Ctrl+C 退出")
        logger.info("=" * 50)
        logger.info("")

        # 启动唤醒词监听（阻塞式）
        self.wake_detector.listen(on_wake_callback=self._on_wake)


# ─── 入口 ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="AI 语音助手")
    parser.add_argument(
        "-c", "--config",
        default="config.yaml",
        help="配置文件路径 (默认: config.yaml)",
    )
    args = parser.parse_args()

    config = load_config(args.config)
    assistant = VoiceAssistant(config)
    assistant.run()


if __name__ == "__main__":
    main()
