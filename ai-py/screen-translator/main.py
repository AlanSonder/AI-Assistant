"""
Screen Translator - 屏幕截图翻译工具

独立桌面应用，功能：
1. 系统托盘图标 + 全局热键触发截图
2. 全屏选区（类似 Snipaste）
3. 悬停选框（保存截图 / 翻译）
4. 翻译结果显示在覆盖层上

翻译流程（根据 Provider 自动选择）：
  - Vision 模型（local）：截图 → LLM 图片翻译 API（直接视觉翻译）
  - 非 Vision 模型（deepseek）：截图 → 本地 OCR 识别 → LLM 文字翻译 API

LLM 调用完全独立，无需 Spring Boot 后端。
"""

import tkinter as tk
import threading
import logging
import os
import sys
import yaml
import mss
import ctypes
from PIL import Image

from tray import SystemTray, HotkeyListener
from region_selector import RegionSelector
from selection_frame import SelectionFrame
from overlay import OverlayWindow
from api_client import ApiClient
from ocr_service import OcrService
from tesseract_service import TesseractService
from llm_client import LlmClient

# ======================== 日志配置 ========================
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%H:%M:%S",
    stream=sys.stdout
)
logger = logging.getLogger("main")

# ======================== 加载配置 ========================

def load_config() -> dict:
    """加载 config.yaml"""
    config_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.yaml")
    if not os.path.exists(config_path):
        logger.warning("配置文件不存在: %s, 使用默认配置", config_path)
        return {
            "backend": {"url": "http://localhost:9090"},
            "capture": {"save_dir": "./captures"},
            "translate": {"from": "auto", "to": "Chinese"},
            "hotkey": "ctrl+shift+f12",
            "ocr": {"lang": "ch", "confidence_threshold": 0.5}
        }
    with open(config_path, "r", encoding="utf-8") as f:
        config = yaml.safe_load(f)
        logger.info("配置已加载: %s", config_path)
        return config


# ======================== 主应用 ========================

class ScreenTranslatorApp:
    """屏幕翻译工具主控制器"""

    def __init__(self):
        self.config = load_config()
        self.backend_url = self.config["backend"]["url"]
        self.save_dir = self.config["capture"]["save_dir"]
        self.from_lang = self.config["translate"]["from"]
        self.to_lang = self.config["translate"]["to"]
        self.hotkey = self.config["hotkey"]

        # LLM 提供商配置
        llm_cfg = self.config.get("llm", {})
        self.llm_provider = llm_cfg.get("active_provider", "deepseek")
        self.llm_model = llm_cfg.get("default_model", "deepseek-v4-flash")
        self.thinking_enabled = llm_cfg.get("thinking_enabled", False)

        # 初始化 LLM 客户端（直接调用 API，无需后端）
        providers = llm_cfg.get("providers", {})
        self.llm_client = LlmClient(providers=providers)
        self.llm_client.active_provider = self.llm_provider
        self.llm_client.active_model = self.llm_model
        self.llm_client.thinking_enabled = self.thinking_enabled

        # OCR 配置
        ocr_cfg = self.config.get("ocr", {})
        ocr_engine = ocr_cfg.get("engine", "tesseract")
        ocr_lang = ocr_cfg.get("lang", "ch")
        ocr_threshold = ocr_cfg.get("confidence_threshold", 0.5)

        self.ocr_engine = ocr_engine
        if ocr_engine == "tesseract":
            self.ocr_service = TesseractService(lang=ocr_lang, confidence_threshold=ocr_threshold)
        else:
            self.ocr_service = OcrService(lang=ocr_lang, confidence_threshold=ocr_threshold)

        # 字体配置
        font_cfg = self.config.get("font", {})
        self.font_family = font_cfg.get("family", "Microsoft YaHei")
        self.font_size = font_cfg.get("size", 13)

        self.api_client = ApiClient(self.backend_url)
        self.capturing = False
        self.active_overlays: list[OverlayWindow] = []
        self.active_selection_frames: list[SelectionFrame] = []

        # 隐藏主 Tk 根窗口
        self.root = tk.Tk()
        self.root.withdraw()
        self.root.title("Screen Translator")

        # 系统托盘 + 热键
        self.tray = SystemTray(
            on_capture=self.trigger_capture,
            on_open_dir=self.open_save_dir,
            on_quit=self.quit_app,
            on_language_change=self._on_language_change,
            current_from_lang=self.from_lang,
            current_to_lang=self.to_lang,
            on_llm_change=self._on_llm_change,
            current_llm_provider=self.llm_provider,
            current_llm_model=self.llm_model,
            on_thinking_change=self._on_thinking_change,
            current_thinking_enabled=self.thinking_enabled,
            on_ocr_engine_change=self._on_ocr_engine_change,
            current_ocr_engine=self.ocr_engine,
            on_font_change=self._on_font_change,
            current_font_family=self.font_family,
            current_font_size=self.font_size,
            on_hotkey_change=self._on_hotkey_change,
            current_hotkey=self.hotkey
        )
        self.hotkey_listener = HotkeyListener(self.hotkey, self.trigger_capture)

    def start(self):
        """启动应用"""
        logger.info("=" * 50)
        logger.info("Screen Translator 启动中...")
        logger.info("后端: %s", self.backend_url)
        logger.info("热键: %s", self.hotkey)
        logger.info("保存目录: %s", self.save_dir)
        logger.info("翻译: %s → %s", self.from_lang, self.to_lang)
        logger.info("OCR: %s (仅 DeepSeek 模式使用)", self.ocr_engine)
        logger.info("=" * 50)

        # 检查后端连通性
        if self.api_client.check_health():
            logger.info("后端连接正常")
        else:
            logger.warning("后端健康检查失败，翻译将直接调用 LLM API")

        # 启动热键
        self.hotkey_listener.start()

        # 在后台线程运行系统托盘
        tray_thread = threading.Thread(target=self.tray.start, daemon=True, name="tray")
        tray_thread.start()

        # 启动 Tk 事件循环
        self.root.mainloop()

    # ======================== 截图主流程 ========================

    def trigger_capture(self):
        """触发截图选区流程"""
        if self.capturing:
            return
        self.capturing = True

        # 临时隐藏覆盖层
        for overlay in self.active_overlays:
            overlay.hide()
        for sf in self.active_selection_frames:
            sf.hide()

        # 延迟后在后台线程执行选区
        self.root.after(200, self._do_region_selection)

    def _do_region_selection(self):
        """执行区域选择（在 Tk 线程中，因为需要 tkinter）"""
        try:
            selector = RegionSelector()
            result = selector.show()  # 阻塞等待

            # 恢复覆盖层
            for overlay in self.active_overlays:
                overlay.show_again()

            if result is None:
                self.capturing = False
                return

            x, y, w, h = result
            logger.info("选区完成: (%d, %d) %dx%d", x, y, w, h)

            # 打开固定框（不截图，每次翻译时实时截取）
            self._open_selection_frame((x, y, w, h))

        except Exception as e:
            logger.error("选区流程异常: %s", e, exc_info=True)
            self.capturing = False

    def _capture_region(self, x: int, y: int, w: int, h: int) -> Image.Image | None:
        """使用 mss 截取指定区域"""
        try:
            with mss.mss() as sct:
                monitor = {"left": x, "top": y, "width": w, "height": h}
                sct_img = sct.grab(monitor)
                return Image.frombytes("RGB", sct_img.size, sct_img.bgra, "raw", "BGRX")
        except Exception as e:
            logger.error("截取区域失败: %s", e)
            return None

    def _open_selection_frame(self, region: tuple):
        """打开透明固定框"""
        selection_frame = None

        def on_close():
            self.capturing = False
            if selection_frame in self.active_selection_frames:
                self.active_selection_frames.remove(selection_frame)

        def on_translate(current_region, screenshot):
            self._do_translate(current_region, screenshot)

        selection_frame = SelectionFrame(
            region=region,
            save_dir=self.save_dir,
            on_close=on_close,
            on_translate=on_translate
        )
        self.active_selection_frames.append(selection_frame)
        selection_frame.show()

    # ======================== 翻译 ========================

    def _do_translate(self, region: tuple, screenshot: Image.Image):
        """
        执行翻译并流式显示到覆盖层。

        根据当前 LLM Provider 自动选择翻译路径：
        - Vision 模型（local）：截图 → LLM Vision API（直接视觉翻译）
        - 非 Vision 模型（deepseek）：截图 → 本地 OCR 识别 → LLM 文字翻译

        翻译结果通过流式方式实时追加到覆盖层。
        """
        import time
        t_start = time.time()
        w, h = screenshot.size

        # 判断当前 Provider 是否支持 Vision
        use_ocr = not self.llm_client.is_vision_enabled()

        mode_label = f"{self.llm_provider}/{self.llm_model}"
        if use_ocr:
            logger.info("========== 翻译开始 [%s] 截图:%dx%d OCR引擎:%s ==========",
                       mode_label, w, h, self.ocr_engine)
        else:
            logger.info("========== 翻译开始 [%s] 截图:%dx%d ==========",
                       mode_label, w, h)

        # 先创建覆盖层（空文本），后续流式追加
        self.root.after(0, lambda: self._show_streaming_overlay(region))

        def translate_worker():
            overlay = None
            try:
                # 等待 overlay 在主线程创建完成
                import time as _t
                _wait_start = _t.time()
                while overlay is None:
                    _t.sleep(0.02)
                    # 从 active_overlays 获取刚创建的 overlay
                    if self.active_overlays:
                        overlay = self.active_overlays[-1]
                    if _t.time() - _wait_start > 3:
                        raise RuntimeError("覆盖层创建超时")

                if use_ocr:
                    self._translate_via_ocr_stream(screenshot, overlay)
                else:
                    self._translate_via_vision_stream(screenshot, overlay)

                t_total = time.time() - t_start
                logger.info("========== 翻译总耗时: %.2fs ==========", t_total)

                # 更新标题栏耗时
                elapsed_ms = int(t_total * 1000)
                self.root.after(0, lambda: self._update_overlay_elapsed(overlay, elapsed_ms))

            except Exception as e:
                t_total = time.time() - t_start
                logger.error("翻译流程异常(%.2fs): %s", t_total, e, exc_info=True)

        threading.Thread(target=translate_worker, daemon=True, name="translate").start()

    def _show_streaming_overlay(self, region: tuple):
        """创建空的翻译覆盖层（流式模式）"""
        # 关闭已有的覆盖层
        for old in list(self.active_overlays):
            old.destroy()
        self.active_overlays.clear()

        overlay = OverlayWindow(
            region=region,
            translated_text="",
            elapsed_ms=0,
            on_close=lambda: self._on_overlay_close(overlay),
            font_family=self.font_family,
            font_size=self.font_size
        )
        self.active_overlays.append(overlay)
        overlay.show()

    def _on_overlay_close(self, overlay):
        """覆盖层关闭回调"""
        if overlay in self.active_overlays:
            self.active_overlays.remove(overlay)

    def _update_overlay_elapsed(self, overlay: OverlayWindow, elapsed_ms: int):
        """更新覆盖层标题栏耗时"""
        if overlay and overlay.window:
            title_text = f"翻译 · {elapsed_ms / 1000:.2f}s"
            # 递归查找标题 label（层级: window > main > inner > toolbar > drag_frame > title）
            def find_title_label(widget, depth=0):
                if depth > 6:
                    return None
                if hasattr(widget, 'cget'):
                    try:
                        text = widget.cget('text')
                        if text.startswith("翻译"):
                            return widget
                    except tk.TclError:
                        pass
                for child in widget.winfo_children():
                    result = find_title_label(child, depth + 1)
                    if result:
                        return result
                return None
            label = find_title_label(overlay.window)
            if label:
                label.configure(text=title_text)

    def _translate_via_vision_stream(self, screenshot: Image.Image, overlay: OverlayWindow):
        """
        Vision 模式流式翻译：截图直接发送到 LLM API，流式显示结果。
        """
        import time
        w, h = screenshot.size
        logger.info("-" * 40)
        logger.info("Vision 流式模式: %s/%s (%dx%d %s→%s)",
                   self.llm_provider, self.llm_model, w, h, self.from_lang, self.to_lang)
        t0 = time.time()

        for chunk in self.llm_client.translate_image_stream_gen(
            screenshot, from_lang=self.from_lang, to_lang=self.to_lang
        ):
            # 覆盖层已关闭则停止流式显示
            if overlay._destroyed:
                break
            # 在主线程追加文本到覆盖层
            self.root.after(0, lambda c=chunk: overlay.append_text(c))

        logger.info(">>> Vision 流式翻译总耗时(含预处理): %.2fs", time.time() - t0)

    def _translate_via_ocr_stream(self, screenshot: Image.Image, overlay: OverlayWindow):
        """
        OCR 模式流式翻译：本地 OCR 识别 + LLM 文字流式翻译。
        """
        import time
        w, h = screenshot.size

        if not self.ocr_service.is_available():
            logger.error("OCR 引擎不可用: %s", self.ocr_engine)
            raise RuntimeError(f"OCR 引擎({self.ocr_engine})不可用")

        # 步骤 1：本地 OCR 识别（非流式）
        logger.info("-" * 40)
        logger.info("步骤1/2: %s OCR识别 (%dx%d 图片)", self.ocr_engine.upper(), w, h)
        t0 = time.time()
        ocr_text = self.ocr_service.extract_text(
            screenshot, from_lang=self.from_lang
        )
        t_ocr = time.time() - t0
        ocr_chars = len(ocr_text.strip()) if ocr_text else 0

        if not ocr_text.strip():
            logger.info(">>> OCR 未识别到文字 (耗时: %.2fs)", t_ocr)
            return

        logger.info(">>> OCR 完成: %d 字符 (耗时: %.2fs)", ocr_chars, t_ocr)

        # 步骤 2：调用 LLM 文字翻译（流式）
        logger.info("步骤2/2: LLM文字翻译(流式) %s/%s (%d字符, %s→%s)",
                   self.llm_provider, self.llm_model, ocr_chars, self.from_lang, self.to_lang)
        t1 = time.time()

        for chunk in self.llm_client.translate_text_stream_gen(
            ocr_text, from_lang=self.from_lang, to_lang=self.to_lang
        ):
            # 覆盖层已关闭则停止流式显示
            if overlay._destroyed:
                break
            # 在主线程追加文本到覆盖层
            self.root.after(0, lambda c=chunk: overlay.append_text(c))

        t_trans = time.time() - t1
        logger.info(">>> 两步总计: OCR %.2fs + LLM流式翻译 %.2fs = %.2fs",
                   t_ocr, t_trans, t_ocr + t_trans)

    # ======================== 辅助 ========================

    def _on_language_change(self, from_lang: str, to_lang: str):
        """切换翻译语言，同步更新 OCR 识别语言"""
        self.from_lang = from_lang
        self.to_lang = to_lang
        # 同步 OCR 语言（映射 from_lang 到 PaddleOCR 模型语言）
        self.ocr_service.set_lang(from_lang)
        logger.info("翻译语言已更新: %s → %s（OCR 语言同步更新）", from_lang, to_lang)

    def _on_llm_change(self, provider: str, model: str):
        """切换 LLM 提供商和模型"""
        self.llm_provider = provider
        self.llm_model = model
        self.llm_client.set_provider(provider)
        self.llm_client.set_model(model)
        logger.info("LLM提供商已切换: %s / %s", provider, model)

    def _on_thinking_change(self, enabled: bool):
        """切换 DeepSeek 思考模式"""
        self.thinking_enabled = enabled
        self.llm_client.set_thinking(enabled)
        logger.info("Thinking思考模式已%s", "启用" if enabled else "关闭")

    def _on_ocr_engine_change(self, engine: str):
        """切换 OCR 引擎"""
        if engine == self.ocr_engine:
            return
        self.ocr_engine = engine
        ocr_cfg = self.config.get("ocr", {})
        ocr_lang = ocr_cfg.get("lang", "ch")
        ocr_threshold = ocr_cfg.get("confidence_threshold", 0.5)
        if engine == "tesseract":
            self.ocr_service = TesseractService(lang=ocr_lang, confidence_threshold=ocr_threshold)
        else:
            self.ocr_service = OcrService(lang=ocr_lang, confidence_threshold=ocr_threshold)
        logger.info("OCR 引擎已切换: %s", engine)

    def _on_font_change(self, font_family: str, font_size: int):
        """切换覆盖层字体"""
        self.font_family = font_family
        self.font_size = font_size
        logger.info("字体已切换: %s %d", font_family, font_size)

    def _on_hotkey_change(self, hotkey: str):
        """切换全局快捷键"""
        if self.hotkey_listener:
            success = self.hotkey_listener.change_hotkey(hotkey)
            if success:
                self.hotkey = hotkey
                logger.info("快捷键已切换: %s", hotkey)
            else:
                logger.error("快捷键切换失败: %s", hotkey)

    def quit_app(self):
        """退出应用"""
        logger.info("正在退出...")
        self.hotkey_listener.stop()

        # 关闭所有窗口
        for overlay in self.active_overlays:
            overlay.destroy()
        for sf in self.active_selection_frames:
            sf.destroy()

        self.tray.stop()
        self.root.after(0, self.root.destroy)

    def open_save_dir(self):
        """打开截图保存目录"""
        save_path = os.path.abspath(self.save_dir)
        if os.path.isdir(save_path):
            os.startfile(save_path)
        else:
            logger.warning("目录不存在: %s", save_path)

    # ======================== 生命周期 ========================


# ======================== 单实例检测 ========================

MUTEX_NAME = "Global\\ScreenTranslator_SingleInstance"
_mutex_handle = None


def acquire_single_instance_lock() -> bool:
    """
    使用 Windows 命名互斥量确保只有一个实例运行。
    返回 True 表示获取锁成功（当前是唯一实例），False 表示已有实例在运行。
    """
    global _mutex_handle
    kernel32 = ctypes.windll.kernel32
    ERROR_ALREADY_EXISTS = 183

    _mutex_handle = kernel32.CreateMutexW(None, True, MUTEX_NAME)
    if kernel32.GetLastError() == ERROR_ALREADY_EXISTS:
        kernel32.CloseHandle(_mutex_handle)
        _mutex_handle = None
        return False
    return True


def release_single_instance_lock():
    """释放互斥量"""
    global _mutex_handle
    if _mutex_handle:
        kernel32 = ctypes.windll.kernel32
        kernel32.ReleaseMutex(_mutex_handle)
        kernel32.CloseHandle(_mutex_handle)
        _mutex_handle = None


# ======================== 入口 ========================

def main():
    if not acquire_single_instance_lock():
        logger.warning("检测到已有实例在运行，退出")
        print("Screen Translator 已在运行中，请勿重复启动。")
        sys.exit(1)

    try:
        app = ScreenTranslatorApp()
        try:
            app.start()
        except KeyboardInterrupt:
            logger.info("收到退出信号")
            app.quit_app()
    finally:
        release_single_instance_lock()


if __name__ == "__main__":
    main()
