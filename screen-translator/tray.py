"""系统托盘 + 全局热键管理"""

import threading
import logging
import ctypes
import ctypes.wintypes
from PIL import Image, ImageDraw
import pystray
import keyboard

logger = logging.getLogger(__name__)

# Windows 常量
HOTKEY_ID = 1
VK_F12 = 0x7B
MOD_CONTROL = 0x0002
MOD_SHIFT = 0x0004


class HotkeyListener:
    """全局热键监听器（使用 keyboard 库）"""

    def __init__(self, hotkey: str, callback):
        """
        Args:
            hotkey: 热键字符串，如 "ctrl+shift+f12"
            callback: 热键触发时的回调函数
        """
        self.hotkey = hotkey
        self.callback = callback
        self._registered = False

    def start(self):
        """注册并启动热键监听"""
        try:
            keyboard.add_hotkey(self.hotkey, self.callback, suppress=False)
            self._registered = True
            logger.info("全局热键已注册: %s", self.hotkey)
        except Exception as e:
            logger.error("热键注册失败 (%s): %s", self.hotkey, e)

    def stop(self):
        """移除热键"""
        if self._registered:
            try:
                keyboard.remove_hotkey(self.hotkey)
                logger.info("全局热键已注销")
            except Exception:
                pass
            self._registered = False

    def change_hotkey(self, new_hotkey: str) -> bool:
        """
        更换热键。先注销旧热键，再注册新热键。

        Args:
            new_hotkey: 新热键字符串

        Returns:
            True 表示注册成功，False 表示失败
        """
        self.stop()
        self.hotkey = new_hotkey
        try:
            keyboard.add_hotkey(self.hotkey, self.callback, suppress=False)
            self._registered = True
            logger.info("全局热键已更换: %s", self.hotkey)
            return True
        except Exception as e:
            logger.error("热键注册失败 (%s): %s", self.hotkey, e)
            return False


class SystemTray:
    """系统托盘图标"""

    # 支持的源语言
    SOURCE_LANGUAGES = [
        ("auto", "自动检测"),
        ("Chinese", "中文"),
        ("English", "英文"),
        ("Japanese", "日文"),
        ("Korean", "韩文"),
        ("French", "法文"),
        ("German", "德文"),
        ("Spanish", "西班牙文"),
        ("Russian", "俄文"),
    ]

    # 支持的目标语言
    TARGET_LANGUAGES = [
        ("Chinese", "中文"),
        ("English", "英文"),
        ("Japanese", "日文"),
        ("Korean", "韩文"),
        ("French", "法文"),
        ("German", "德文"),
        ("Spanish", "西班牙文"),
        ("Russian", "俄文"),
    ]

    # OCR 引擎选项
    OCR_ENGINES = [
        ("tesseract", "Tesseract 5（快速）"),
        ("paddle", "PaddleOCR（高精度）"),
    ]

    # 字体选项
    FONT_FAMILIES = [
        ("Microsoft YaHei", "微软雅黑"),
        ("SimSun", "宋体"),
        ("SimHei", "黑体"),
        ("KaiTi", "楷体"),
        ("FangSong", "仿宋"),
        ("Consolas", "Consolas"),
    ]

    FONT_SIZES = [10, 11, 12, 13, 14, 16, 18, 20, 24]

    # 快捷键选项
    HOTKEYS = [
        ("ctrl+shift+f1", "Ctrl+Shift+F1"),
        ("ctrl+shift+f2", "Ctrl+Shift+F2"),
        ("ctrl+Q", "Ctrl+Q"),
        ("ctrl+alt+a", "Ctrl+Alt+A"),
        ("ctrl+alt+t", "Ctrl+Alt+T"),
    ]

    # LLM 提供商 + 模型（组合列表，每项 (provider, model, display_label)）
    LLM_OPTIONS = [
        ("local", "qwen",       "🖥️ 本地 - qwen"),
        ("local", "llama",      "🖥️ 本地 - llama"),
        ("deepseek", "deepseek-v4-pro",   "☁️ DeepSeek - Pro"),
        ("deepseek", "deepseek-v4-flash", "☁️ DeepSeek - Flash"),
    ]

    def __init__(self, on_capture, on_open_dir, on_quit,
                 on_language_change=None, current_from_lang="auto", current_to_lang="Chinese",
                 on_llm_change=None, current_llm_provider="local", current_llm_model="qwen",
                 on_thinking_change=None, current_thinking_enabled=False,
                 on_ocr_engine_change=None, current_ocr_engine="tesseract",
                 on_font_change=None, current_font_family="Microsoft YaHei", current_font_size=13,
                 on_hotkey_change=None, current_hotkey="ctrl+shift+f12"):
        """
        Args:
            on_capture: 截图翻译回调
            on_open_dir: 打开保存目录回调
            on_quit: 退出回调
            on_language_change: 语言切换回调 (from_lang, to_lang)
            current_from_lang: 当前源语言
            current_to_lang: 当前目标语言
            on_llm_change: LLM提供商切换回调 (provider, model)
            current_llm_provider: 当前LLM提供商
            current_llm_model: 当前LLM模型
            on_thinking_change: 思考模式切换回调 (enabled)
            current_thinking_enabled: 当前思考模式状态
            on_ocr_engine_change: OCR引擎切换回调 (engine)
            current_ocr_engine: 当前OCR引擎
            on_font_change: 字体切换回调 (font_family, font_size)
            current_font_family: 当前字体名称
            current_font_size: 当前字体大小
            on_hotkey_change: 快捷键切换回调 (hotkey)
            current_hotkey: 当前快捷键
        """
        self.on_capture = on_capture
        self.on_open_dir = on_open_dir
        self.on_quit = on_quit
        self.on_language_change = on_language_change
        self.current_from_lang = current_from_lang
        self.current_to_lang = current_to_lang
        self.on_llm_change = on_llm_change
        self.current_llm_provider = current_llm_provider
        self.current_llm_model = current_llm_model
        self.on_thinking_change = on_thinking_change
        self.current_thinking_enabled = current_thinking_enabled
        self.on_ocr_engine_change = on_ocr_engine_change
        self.current_ocr_engine = current_ocr_engine
        self.on_font_change = on_font_change
        self.current_font_family = current_font_family
        self.current_font_size = current_font_size
        self.on_hotkey_change = on_hotkey_change
        self.current_hotkey = current_hotkey
        self._icon = None

    def _create_icon_image(self) -> Image.Image:
        """创建托盘图标图片（蓝色圆角方块 + 白色 T）"""
        img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
        draw = ImageDraw.Draw(img)
        draw.rounded_rectangle([0, 0, 31, 31], radius=6, fill=(64, 158, 255))
        draw.text((8, 4), "T", fill="white", font=None)
        return img

    def _build_source_language_menu(self) -> pystray.Menu:
        """构建源语言选择子菜单"""
        items = []
        for lang_code, lang_name in self.SOURCE_LANGUAGES:
            def make_action(code):
                return lambda icon, item: self._on_from_language_change(code)

            def make_checked(code):
                return lambda item: self.current_from_lang == code

            items.append(
                pystray.MenuItem(
                    lang_name,
                    make_action(lang_code),
                    radio=True,
                    checked=make_checked(lang_code)
                )
            )
        return pystray.Menu(*items)

    def _build_target_language_menu(self) -> pystray.Menu:
        """构建目标语言选择子菜单"""
        items = []
        for lang_code, lang_name in self.TARGET_LANGUAGES:
            def make_action(code):
                return lambda icon, item: self._on_to_language_change(code)

            def make_checked(code):
                return lambda item: self.current_to_lang == code

            items.append(
                pystray.MenuItem(
                    lang_name,
                    make_action(lang_code),
                    radio=True,
                    checked=make_checked(lang_code)
                )
            )
        return pystray.Menu(*items)

    def _build_llm_menu(self) -> pystray.Menu:
        """构建 LLM 提供商+模型选择子菜单"""
        items = []
        for provider, model, label in self.LLM_OPTIONS:
            def make_action(p, m):
                return lambda icon, item: self._on_llm_change(p, m)

            def make_checked(p, m):
                return lambda item: self.current_llm_provider == p and self.current_llm_model == m

            items.append(
                pystray.MenuItem(
                    label,
                    make_action(provider, model),
                    radio=True,
                    checked=make_checked(provider, model)
                )
            )
        return pystray.Menu(*items)

    def _build_ocr_engine_menu(self) -> pystray.Menu:
        """构建 OCR 引擎选择子菜单"""
        items = []
        for engine, label in self.OCR_ENGINES:
            def make_action(e):
                return lambda icon, item: self._on_ocr_engine_change(e)

            def make_checked(e):
                return lambda item: self.current_ocr_engine == e

            items.append(
                pystray.MenuItem(
                    label,
                    make_action(engine),
                    radio=True,
                    checked=make_checked(engine)
                )
            )
        return pystray.Menu(*items)

    def _build_font_family_menu(self) -> pystray.Menu:
        """构建字体选择子菜单"""
        items = []
        for family, label in self.FONT_FAMILIES:
            def make_action(f):
                return lambda icon, item: self._on_font_family_change(f)

            def make_checked(f):
                return lambda item: self.current_font_family == f

            items.append(
                pystray.MenuItem(
                    label,
                    make_action(family),
                    radio=True,
                    checked=make_checked(family)
                )
            )
        return pystray.Menu(*items)

    def _build_font_size_menu(self) -> pystray.Menu:
        """构建字号选择子菜单"""
        items = []
        for size in self.FONT_SIZES:
            def make_action(s):
                return lambda icon, item: self._on_font_size_change(s)

            def make_checked(s):
                return lambda item: self.current_font_size == s

            items.append(
                pystray.MenuItem(
                    str(size),
                    make_action(size),
                    radio=True,
                    checked=make_checked(size)
                )
            )
        return pystray.Menu(*items)

    def _build_hotkey_menu(self) -> pystray.Menu:
        """构建快捷键选择子菜单"""
        items = []
        for hotkey, label in self.HOTKEYS:
            def make_action(h):
                return lambda icon, item: self._on_hotkey_change(h)

            def make_checked(h):
                return lambda item: self.current_hotkey == h

            items.append(
                pystray.MenuItem(
                    label,
                    make_action(hotkey),
                    radio=True,
                    checked=make_checked(hotkey)
                )
            )
        return pystray.Menu(*items)

    def start(self):
        """启动系统托盘（阻塞，需在单独线程中运行）"""
        image = self._create_icon_image()

        # 构建翻译语言子菜单
        lang_menu = pystray.Menu(
            pystray.MenuItem("源语言", self._build_source_language_menu()),
            pystray.MenuItem("目标语言", self._build_target_language_menu()),
        )

        # 构建字体子菜单
        font_menu = pystray.Menu(
            pystray.MenuItem("字体", self._build_font_family_menu()),
            pystray.MenuItem("字号", self._build_font_size_menu()),
        )

        menu = pystray.Menu(
            pystray.MenuItem("截图翻译", self._on_capture, default=True),
            pystray.MenuItem("翻译语言", lang_menu),
            pystray.MenuItem("字体", font_menu),
            pystray.MenuItem("快捷键", self._build_hotkey_menu()),
            pystray.MenuItem("LLM 模型", self._build_llm_menu()),
            pystray.MenuItem("OCR 引擎", self._build_ocr_engine_menu()),
            pystray.MenuItem(
                "思考模式",
                self._on_thinking_toggle,
                checked=lambda item: self.current_thinking_enabled
            ),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("打开保存目录", self._on_open_dir),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("退出", self._on_quit),
        )

        self._icon = pystray.Icon(
            "screen-translator",
            image,
            "AI 屏幕翻译",
            menu
        )
        logger.info("系统托盘图标已添加")
        self._icon.run()

    def stop(self):
        """停止托盘"""
        if self._icon:
            self._icon.stop()
            logger.info("系统托盘已移除")

    def show_notification(self, title: str, message: str):
        """显示托盘通知"""
        if self._icon:
            try:
                self._icon.notify(message, title)
            except Exception:
                pass
        logger.info("[%s] %s", title, message)

    def _on_capture(self, icon=None, item=None):
        self.on_capture()

    def _on_open_dir(self, icon=None, item=None):
        self.on_open_dir()

    def _on_quit(self, icon=None, item=None):
        self.on_quit()

    def _find_lang_name(self, lang_code: str, lang_list: list) -> str:
        """从语言列表中查找语言名称"""
        for code, name in lang_list:
            if code == lang_code:
                return name
        return lang_code

    def _on_from_language_change(self, from_lang: str):
        """切换源语言"""
        self.current_from_lang = from_lang
        lang_name = self._find_lang_name(from_lang, self.SOURCE_LANGUAGES)
        logger.info("源语言已切换: %s (%s)", lang_name, from_lang)
        self._notify_language_change()

    def _on_to_language_change(self, to_lang: str):
        """切换目标语言"""
        self.current_to_lang = to_lang
        lang_name = self._find_lang_name(to_lang, self.TARGET_LANGUAGES)
        logger.info("目标语言已切换: %s (%s)", lang_name, to_lang)
        self._notify_language_change()

    def _notify_language_change(self):
        """通知主应用语言已切换"""
        from_name = self._find_lang_name(self.current_from_lang, self.SOURCE_LANGUAGES)
        to_name = self._find_lang_name(self.current_to_lang, self.TARGET_LANGUAGES)
        if self._icon:
            self._icon.title = f"AI 屏幕翻译 {from_name}→{to_name}"
        if self.on_language_change:
            self.on_language_change(self.current_from_lang, self.current_to_lang)

    def _on_llm_change(self, provider: str, model: str):
        """切换 LLM 提供商和模型"""
        self.current_llm_provider = provider
        self.current_llm_model = model
        provider_display = "本地" if provider == "local" else "DeepSeek"
        logger.info("LLM已切换: %s / %s", provider_display, model)
        if self.on_llm_change:
            self.on_llm_change(provider, model)

    def _on_thinking_toggle(self, icon=None, item=None):
        """切换思考模式"""
        self.current_thinking_enabled = not self.current_thinking_enabled
        if self.on_thinking_change:
            self.on_thinking_change(self.current_thinking_enabled)
        logger.info("[Thinking] %s", "启用" if self.current_thinking_enabled else "关闭")

    def _on_ocr_engine_change(self, engine: str):
        """切换 OCR 引擎"""
        self.current_ocr_engine = engine
        engine_display = "Tesseract 5" if engine == "tesseract" else "PaddleOCR"
        logger.info("OCR引擎已切换: %s", engine_display)
        if self.on_ocr_engine_change:
            self.on_ocr_engine_change(engine)

    def _on_font_family_change(self, family: str):
        """切换字体"""
        self.current_font_family = family
        # 查找显示名称
        display_name = family
        for f, name in self.FONT_FAMILIES:
            if f == family:
                display_name = name
                break
        logger.info("字体已切换: %s (%s)", display_name, family)
        if self.on_font_change:
            self.on_font_change(self.current_font_family, self.current_font_size)

    def _on_font_size_change(self, size: int):
        """切换字号"""
        self.current_font_size = size
        logger.info("字号已切换: %d", size)
        if self.on_font_change:
            self.on_font_change(self.current_font_family, self.current_font_size)

    def _on_hotkey_change(self, hotkey: str):
        """切换快捷键"""
        if hotkey == self.current_hotkey:
            return
        self.current_hotkey = hotkey
        # 查找显示名称
        display_name = hotkey
        for h, name in self.HOTKEYS:
            if h == hotkey:
                display_name = name
                break
        logger.info("快捷键已切换: %s", display_name)
        if self.on_hotkey_change:
            self.on_hotkey_change(hotkey)
