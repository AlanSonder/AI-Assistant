"""翻译覆盖层 - 在屏幕指定区域显示翻译结果"""

import tkinter as tk
import logging
import ctypes
import ctypes.wintypes

logger = logging.getLogger(__name__)

# Windows 常量
GWL_EXSTYLE = -20
WS_EX_LAYERED = 0x00080000
WS_EX_TRANSPARENT = 0x00000020


class OverlayWindow:
    """
    翻译覆盖层窗口：
    - 无边框，置顶
    - 顶部控制栏：拖拽区域 + 穿透锁定按钮 + 关闭按钮
    - 文本区域：自动换行 + 滚动
    - 支持右键切换点击穿透
    """

    BAR_HEIGHT = 28
    BAR_BG = "#1e1e2d"
    TEXT_BG = "#14141e"
    BORDER_COLOR = "#409eff"
    TEXT_COLOR = "#f0f0f0"

    def __init__(self, region: tuple, translated_text: str = "", elapsed_ms: int = 0, on_close=None,
                 font_family: str = "Microsoft YaHei", font_size: int = 13):
        """
        Args:
            region: (x, y, width, height) 屏幕坐标
            translated_text: 翻译后的文本（可为空，后续流式追加）
            elapsed_ms: 翻译耗时（毫秒）
            on_close: 关闭回调
            font_family: 字体名称
            font_size: 字体大小
        """
        self.region = region
        self.translated_text = translated_text
        self.elapsed_ms = elapsed_ms
        self.on_close = on_close
        self.click_through = False
        self.topmost = True
        self.font_family = font_family
        self.font_size = font_size

        self.window = None
        self._drag_data = {"x": 0, "y": 0}
        self._lock_label = None
        self._minimize_label = None
        self._destroyed = False

    def show(self):
        """显示覆盖层"""
        x, y, w, h = self.region
        total_h = h + self.BAR_HEIGHT

        self.window = tk.Toplevel()
        self.window.overrideredirect(True)
        self.window.attributes("-topmost", True)
        self.window.geometry(f"{w}x{total_h}+{x}+{y}")

        # 主容器
        main = tk.Frame(self.window, bg=self.BORDER_COLOR)
        main.pack(fill=tk.BOTH, expand=True)

        inner = tk.Frame(main, bg=self.TEXT_BG,
                         highlightbackground=self.BORDER_COLOR, highlightthickness=1)
        inner.pack(fill=tk.BOTH, expand=True, padx=1, pady=(0, 1))

        # ======================== 控制栏 ========================
        toolbar = tk.Frame(inner, bg=self.BAR_BG, height=self.BAR_HEIGHT)
        toolbar.pack(fill=tk.X)
        toolbar.pack_propagate(False)

        # 左侧：拖拽把手 + 标签（含耗时）
        drag_frame = tk.Frame(toolbar, bg=self.BAR_BG)
        drag_frame.pack(side=tk.LEFT, fill=tk.X, expand=True)

        grip = tk.Label(drag_frame, text="⋮⋮", bg=self.BAR_BG, fg="#646478",
                        font=("SansSerif", 9), padx=4)
        grip.pack(side=tk.LEFT)

        title_text = f"翻译 · {self.elapsed_ms / 1000:.2f}s" if self.elapsed_ms else "翻译"
        title = tk.Label(drag_frame, text=title_text, bg=self.BAR_BG, fg="#b4b4c8",
                         font=("Microsoft YaHei", 10))
        title.pack(side=tk.LEFT, padx=4)

        # 右侧：最小化按钮 + 锁定按钮 + 关闭按钮
        close_btn = tk.Label(toolbar, text="✕", bg=self.BAR_BG, fg="#ff5050",
                             font=("SansSerif", 12, "bold"), padx=8, cursor="hand2")
        close_btn.pack(side=tk.RIGHT)
        close_btn.bind("<Button-1>", lambda e: self._dispose())
        close_btn.bind("<Enter>", lambda e: close_btn.configure(bg="#3a3a50"))
        close_btn.bind("<Leave>", lambda e: close_btn.configure(bg=self.BAR_BG))

        self._lock_label = tk.Label(toolbar, text="🔓", bg=self.BAR_BG, fg="#64b4ff",
                                    font=("Segoe UI Emoji", 10), padx=6, cursor="hand2")
        self._lock_label.pack(side=tk.RIGHT)
        self._lock_label.bind("<Button-1>", lambda e: self._toggle_click_through())
        self._lock_label.bind("<Enter>", lambda e: self._lock_label.configure(bg="#3a3a50"))
        self._lock_label.bind("<Leave>", lambda e: self._lock_label.configure(bg=self.BAR_BG))

        self._minimize_label = tk.Label(toolbar, text="🗗", bg=self.BAR_BG, fg="#e0a040",
                                        font=("Segoe UI Emoji", 10), padx=6, cursor="hand2")
        self._minimize_label.pack(side=tk.RIGHT)
        self._minimize_label.bind("<Button-1>", lambda e: self._toggle_topmost())
        self._minimize_label.bind("<Enter>", lambda e: self._minimize_label.configure(bg="#3a3a50"))
        self._minimize_label.bind("<Leave>", lambda e: self._minimize_label.configure(bg=self.BAR_BG))

        # 拖拽 + 右键穿透
        for widget in [self._minimize_label, self._lock_label, drag_frame, grip, title]:
            widget.bind("<ButtonPress-1>", self._on_drag_start, add="+")
            widget.bind("<B1-Motion>", self._on_drag_motion, add="+")
            widget.bind("<Button-3>", lambda e: self._toggle_click_through())

        # 底部分隔线
        tk.Frame(inner, bg=self.BORDER_COLOR, height=1).pack(fill=tk.X)

        # ======================== 文本区域 ========================
        text_frame = tk.Frame(inner, bg=self.TEXT_BG)
        text_frame.pack(fill=tk.BOTH, expand=True)

        self.text_widget = tk.Text(
            text_frame,
            wrap=tk.WORD,
            bg=self.TEXT_BG,
            fg=self.TEXT_COLOR,
            font=(self.font_family, self.font_size),
            relief=tk.FLAT,
            padx=10, pady=10,
            insertbackground=self.TEXT_BG,  # 隐藏光标
            selectbackground="#409eff",
            selectforeground="white",
            state=tk.NORMAL,
            cursor="arrow"
        )
        self.text_widget.insert("1.0", self.translated_text)
        self.text_widget.configure(state=tk.DISABLED)

        scrollbar = tk.Scrollbar(text_frame, command=self.text_widget.yview,
                                 bg=self.TEXT_BG, troughcolor=self.TEXT_BG,
                                 width=4, relief=tk.FLAT)
        self.text_widget.configure(yscrollcommand=scrollbar.set)

        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)
        self.text_widget.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)

        # 文本区域右键也支持切换穿透
        self.text_widget.bind("<Button-3>", lambda e: self._toggle_click_through())

        logger.info("翻译覆盖层已显示: (%d, %d) %dx%d", x, y, w, h)

    def _on_drag_start(self, event):
        self._drag_data["x"] = event.x_root - self.window.winfo_x()
        self._drag_data["y"] = event.y_root - self.window.winfo_y()

    def _on_drag_motion(self, event):
        new_x = event.x_root - self._drag_data["x"]
        new_y = event.y_root - self._drag_data["y"]
        self.window.geometry(f"+{new_x}+{new_y}")

    def _toggle_click_through(self):
        """切换点击穿透状态"""
        try:
            hwnd = ctypes.windll.user32.FindWindowW(None, self.window.title())
            if not hwnd:
                # 通过窗口位置获取 HWND
                x = self.window.winfo_rootx()
                y = self.window.winfo_rooty()
                hwnd = ctypes.windll.user32.WindowFromPoint(ctypes.wintypes.POINT(x + 5, y + 5))

            if not hwnd:
                logger.warning("无法获取窗口句柄")
                return

            user32 = ctypes.windll.user32
            ex_style = user32.GetWindowLongW(hwnd, GWL_EXSTYLE)

            self.click_through = not self.click_through

            if self.click_through:
                # 开启穿透
                user32.SetWindowLongW(hwnd, GWL_EXSTYLE,
                                      ex_style | WS_EX_LAYERED | WS_EX_TRANSPARENT)
                self._lock_label.configure(text="🔒")
                logger.info("覆盖层点击穿透已启用")
            else:
                # 关闭穿透
                user32.SetWindowLongW(hwnd, GWL_EXSTYLE,
                                      ex_style & ~WS_EX_TRANSPARENT)
                self._lock_label.configure(text="🔓")
                logger.info("覆盖层点击穿透已关闭")

        except Exception as e:
            logger.warning("切换点击穿透失败: %s", e)

    def append_text(self, chunk: str):
        """增量追加翻译文本（流式更新用）"""
        if not chunk or self._destroyed or not self.text_widget:
            return
        try:
            self.text_widget.configure(state=tk.NORMAL)
            self.text_widget.insert(tk.END, chunk)
            self.text_widget.configure(state=tk.DISABLED)
            self.text_widget.see(tk.END)  # 自动滚动到最新内容
            self.translated_text += chunk
        except tk.TclError:
            # Widget 已被销毁，停止写入
            self._destroyed = True

    def update_text(self, text: str):
        """替换全部翻译文本"""
        self.translated_text = text
        if self.text_widget:
            self.text_widget.configure(state=tk.NORMAL)
            self.text_widget.delete("1.0", tk.END)
            self.text_widget.insert("1.0", text)
            self.text_widget.configure(state=tk.DISABLED)

    def _toggle_topmost(self):
        """切换置顶状态"""
        self.topmost = not self.topmost
        self.window.attributes("-topmost", self.topmost)
        if self.topmost:
            self._minimize_label.configure(text="🗗")
            logger.info("覆盖层置顶已恢复")
        else:
            self._minimize_label.configure(text="🗖")
            logger.info("覆盖层置顶已取消（最小化）")

    def _dispose(self):
        self._destroyed = True
        if self.window:
            self.window.destroy()
            self.window = None
        if self.on_close:
            self.on_close()
        logger.info("翻译覆盖层已关闭")

    def hide(self):
        if self.window:
            self.window.withdraw()

    def show_again(self):
        if self.window:
            self.window.deiconify()

    def destroy(self):
        self._dispose()
