"""全屏区域选择器 - 类似 Snipaste 的截图选区体验"""

import tkinter as tk
import logging
from PIL import Image, ImageTk
import mss

logger = logging.getLogger(__name__)


class RegionSelector:
    """全屏选区窗口，用户拖拽框选区域，松开鼠标即完成"""

    def __init__(self):
        self.root = None
        self.canvas = None
        self.screenshot = None  # PIL Image
        self.tk_screenshot = None  # ImageTk reference
        self.result = None  # (x, y, w, h) 屏幕绝对坐标

        # 选区状态
        self._drag_start = None
        self._selection = None  # (x1, y1, x2, y2) 窗口内坐标

    def show(self) -> tuple | None:
        """
        显示全屏选区窗口，阻塞等待用户选区。

        Returns:
            (x, y, width, height) 屏幕绝对坐标，取消返回 None
        """
        self._take_screenshot()

        self.root = tk.Toplevel() if tk._default_root else tk.Tk()
        self.root.attributes("-topmost", True)
        self.root.overrideredirect(True)
        self.root.configure(cursor="cross")

        # 使用截图的实际尺寸（可能包含多显示器）
        img_w = self.screenshot.width
        img_h = self.screenshot.height

        # 获取虚拟桌面偏移（多显示器时可能为负坐标）
        with mss.mss() as sct:
            monitor = sct.monitors[0]
            desktop_x = monitor["left"]
            desktop_y = monitor["top"]

        # 手动设置窗口覆盖整个虚拟桌面
        self.root.geometry(f"{img_w}x{img_h}+{desktop_x}+{desktop_y}")

        # Canvas
        self.canvas = tk.Canvas(self.root, width=img_w, height=img_h,
                                highlightthickness=0)
        self.canvas.pack()

        # 事件绑定
        self.canvas.bind("<ButtonPress-1>", self._on_press)
        self.canvas.bind("<B1-Motion>", self._on_drag)
        self.canvas.bind("<ButtonRelease-1>", self._on_release)
        self.canvas.bind("<Motion>", self._on_move)
        self.root.bind("<Escape>", lambda e: self._cancel())

        # 首次绘制
        self._draw()

        # 阻塞等待
        self.root.grab_set()
        self.root.wait_window()

        return self.result

    def _take_screenshot(self):
        """使用 mss 截取全屏"""
        with mss.mss() as sct:
            monitor = sct.monitors[0]  # 所有显示器合并
            sct_img = sct.grab(monitor)
            self.screenshot = Image.frombytes("RGB", sct_img.size, sct_img.bgra, "raw", "BGRX")
            logger.info("全屏截图完成: %dx%d", self.screenshot.width, self.screenshot.height)

    def _draw(self):
        """绘制界面"""
        self.canvas.delete("all")

        # 1. 绘制截图底图
        self.tk_screenshot = ImageTk.PhotoImage(self.screenshot)
        self.canvas.create_image(0, 0, anchor=tk.NW, image=self.tk_screenshot)

        # 2. 半透明遮罩
        self.canvas.create_rectangle(
            0, 0, self.screenshot.width, self.screenshot.height,
            fill="black", stipple="gray50", tags="overlay"
        )

        # 3. 选区
        if self._selection:
            x1, y1, x2, y2 = self._selection
            # 选区内显示原图
            self.canvas.create_rectangle(x1, y1, x2, y2,
                                         fill="", outline="", tags="sel_clear")
            # 重绘选区内的截图
            sel_img = self.screenshot.crop((x1, y1, x2, y2))
            tk_sel = ImageTk.PhotoImage(sel_img)
            self.canvas.create_image(x1, y1, anchor=tk.NW, image=tk_sel, tags="sel_img")
            # 保持引用防止 GC
            self.canvas._sel_ref = tk_sel

            # 蓝色边框
            self.canvas.create_rectangle(x1, y1, x2, y2,
                                         outline="#409eff", width=2, tags="sel_border")

            # 角落把手
            handle_size = 4
            for cx, cy in [(x1, y1), (x2, y1), (x1, y2), (x2, y2)]:
                self.canvas.create_rectangle(
                    cx - handle_size, cy - handle_size,
                    cx + handle_size, cy + handle_size,
                    fill="#409eff", outline="", tags="sel_handle"
                )

            # 尺寸标签
            w, h = x2 - x1, y2 - y1
            label = f"{w} × {h}"
            label_y = y1 - 20 if y1 > 25 else y2 + 8
            self.canvas.create_text(x1 + 4, label_y, text=label, anchor=tk.NW,
                                    fill="white", font=("Consolas", 11), tags="sel_label")

        # 4. 顶部提示栏
        self.canvas.create_rectangle(0, 0, self.screenshot.width, 36,
                                     fill="black", stipple="gray75", tags="hint_bg")
        hint = "拖拽鼠标框选区域  |  Esc 取消" if not self._selection else "拖拽调整选区  |  松开鼠标完成  |  Esc 取消"
        self.canvas.create_text(16, 18, text=hint, anchor=tk.W,
                                fill="white", font=("Microsoft YaHei", 11), tags="hint_text")

    def _on_press(self, event):
        self._drag_start = (event.x, event.y)
        self._selection = None

    def _on_drag(self, event):
        if not self._drag_start:
            return
        x1 = min(self._drag_start[0], event.x)
        y1 = min(self._drag_start[1], event.y)
        x2 = max(self._drag_start[0], event.x)
        y2 = max(self._drag_start[1], event.y)

        if x2 - x1 > 5 and y2 - y1 > 5:
            self._selection = (x1, y1, x2, y2)
            self._draw()

    def _on_release(self, event):
        if self._selection:
            x1, y1, x2, y2 = self._selection
            # 转换为屏幕绝对坐标
            root_x = self.root.winfo_rootx()
            root_y = self.root.winfo_rooty()
            self.result = (root_x + x1, root_y + y1, x2 - x1, y2 - y1)
            logger.info("选区完成: %s", self.result)
            self.root.destroy()
        self._drag_start = None

    def _on_move(self, event):
        """鼠标移动时更新十字线（仅无选区时）"""
        if not self._selection:
            self._draw()
            # 十字线
            self.canvas.create_line(0, event.y, self.screenshot.width, event.y,
                                    fill="white", dash=(4, 4), tags="cross")
            self.canvas.create_line(event.x, 0, event.x, self.screenshot.height,
                                    fill="white", dash=(4, 4), tags="cross")
            # 坐标标签
            self.canvas.create_text(event.x + 12, event.y + 16,
                                    text=f"({event.x}, {event.y})",
                                    anchor=tk.NW, fill="#00ff00",
                                    font=("Consolas", 10), tags="cross")

    def _cancel(self):
        self.result = None
        self.root.destroy()
