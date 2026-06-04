"""悬停选框 - 固定框，每次翻译实时截取框内当前屏幕内容"""

import tkinter as tk
import logging
import os
import time
from datetime import datetime
from PIL import Image
import mss

logger = logging.getLogger(__name__)


class SelectionFrame:
    """
    透明固定框：
    - 只有工具栏（关闭 + 尺寸 + 保存 + 翻译）和蓝色边框
    - 框体区域透明，可以看到下层屏幕内容
    - 拖拽工具栏移动，拖拽边缘/角落调整大小
    - 翻译时截取新位置/大小的屏幕内容
    - 保存时截取当前框内屏幕内容
    """

    BAR_HEIGHT = 30
    BORDER_WIDTH = 2
    BORDER_COLOR = "#409eff"
    BAR_BG = "#282837"
    HANDLE_SIZE = 8  # 拖拽手柄区域大小
    MIN_W = 80
    MIN_H = 40
    # 用于透明的颜色键
    TRANSPARENT_COLOR = "#010101"

    def __init__(self, region: tuple, save_dir: str,
                 on_close=None, on_translate=None):
        """
        Args:
            region: (x, y, width, height) 屏幕坐标
            save_dir: 保存目录路径
            on_close: 关闭回调
            on_translate: 翻译回调，接收 (region, screenshot) 参数
        """
        self.region = list(region)  # [x, y, w, h] 可变
        self.save_dir = save_dir
        self.on_close = on_close
        self.on_translate = on_translate

        self.window = None
        self._drag_data = {"x": 0, "y": 0}

    def show(self):
        """显示透明固定框"""
        x, y, w, h = self.region
        total_h = h + self.BAR_HEIGHT

        # 窗口整体上移 BAR_HEIGHT，使工具栏悬浮在选区顶部上方，框体精确对齐选区
        win_y = y - self.BAR_HEIGHT

        self.window = tk.Toplevel()
        self.window.overrideredirect(True)
        self.window.attributes("-topmost", True)
        self.window.geometry(f"{w}x{total_h}+{x}+{win_y}")

        # 设置透明色：TRANSPARENT_COLOR 的像素变为透明
        self.window.attributes("-transparentcolor", self.TRANSPARENT_COLOR)
        self.window.configure(bg=self.BORDER_COLOR)

        # 主容器
        outer = tk.Frame(self.window, bg=self.BORDER_COLOR)
        outer.pack(fill=tk.BOTH, expand=True)

        # ======================== 工具栏 ========================
        toolbar = tk.Frame(outer, bg=self.BAR_BG, height=self.BAR_HEIGHT)
        toolbar.pack(fill=tk.X)
        toolbar.pack_propagate(False)

        # 关闭按钮
        close_btn = tk.Label(toolbar, text="✕", bg=self.BAR_BG, fg="#ff4b4b",
                             font=("SansSerif", 11, "bold"), padx=8, cursor="hand2")
        close_btn.pack(side=tk.LEFT)
        close_btn.bind("<Button-1>", lambda e: self._dispose())
        close_btn.bind("<Enter>", lambda e: close_btn.configure(bg="#3a3a50"))
        close_btn.bind("<Leave>", lambda e: close_btn.configure(bg=self.BAR_BG))

        # 尺寸标签
        self._size_label = tk.Label(toolbar, text=f"{w} × {h}", bg=self.BAR_BG,
                                    fg="#aaaacc", font=("Consolas", 9))
        self._size_label.pack(side=tk.LEFT, padx=8)

        # 翻译按钮
        translate_btn = tk.Label(toolbar, text="翻译", bg=self.BAR_BG, fg="#409eff",
                                 font=("Microsoft YaHei", 10, "bold"), padx=10, cursor="hand2")
        translate_btn.pack(side=tk.RIGHT, padx=2)
        translate_btn.bind("<Button-1>", lambda e: self._do_translate())
        translate_btn.bind("<Enter>", lambda e: translate_btn.configure(bg="#3a3a50"))
        translate_btn.bind("<Leave>", lambda e: translate_btn.configure(bg=self.BAR_BG))

        # 保存按钮
        save_btn = tk.Label(toolbar, text="保存", bg=self.BAR_BG, fg="#50c878",
                            font=("Microsoft YaHei", 10, "bold"), padx=10, cursor="hand2")
        save_btn.pack(side=tk.RIGHT, padx=2)
        save_btn.bind("<Button-1>", lambda e: self._do_save())
        save_btn.bind("<Enter>", lambda e: save_btn.configure(bg="#3a3a50"))
        save_btn.bind("<Leave>", lambda e: save_btn.configure(bg=self.BAR_BG))

        # 拖拽移动
        for widget in [toolbar, close_btn]:
            widget.bind("<ButtonPress-1>", self._on_drag_start, add="+")
            widget.bind("<B1-Motion>", self._on_drag_motion, add="+")

        # ======================== 透明框体 ========================
        body = tk.Frame(outer, bg=self.TRANSPARENT_COLOR,
                        highlightbackground=self.BORDER_COLOR,
                        highlightthickness=self.BORDER_WIDTH)
        body.pack(fill=tk.BOTH, expand=True)

        # ======================== 拖拽调整大小手柄 ========================
        hs = self.HANDLE_SIZE
        # 4个角落手柄
        corners = [
            ("nw", {"x": 0, "y": 0, "width": hs, "height": hs}, "fleur"),
            ("ne", {"x": w - hs, "y": 0, "width": hs, "height": hs}, "fleur"),
            ("sw", {"x": 0, "y": total_h - hs, "width": hs, "height": hs}, "fleur"),
            ("se", {"x": w - hs, "y": total_h - hs, "width": hs, "height": hs}, "fleur"),
        ]
        # 4个边缘手柄
        edges = [
            ("n", {"x": hs, "y": 0, "width": w - 2 * hs, "height": hs}, "sb_v_double_arrow"),
            ("s", {"x": hs, "y": total_h - hs, "width": w - 2 * hs, "height": hs}, "sb_v_double_arrow"),
            ("w", {"x": 0, "y": hs, "width": hs, "height": total_h - 2 * hs}, "sb_h_double_arrow"),
            ("e", {"x": w - hs, "y": hs, "width": hs, "height": total_h - 2 * hs}, "sb_h_double_arrow"),
        ]
        for direction, pos, cursor in corners + edges:
            handle = tk.Frame(self.window, bg=self.BORDER_COLOR, cursor=cursor)
            handle.place(**pos)
            handle.bind("<ButtonPress-1>", lambda e, d=direction: self._on_resize_start(e, d))
            handle.bind("<B1-Motion>", self._on_resize_motion)
            handle.bind("<ButtonRelease-1>", self._on_resize_end)

        logger.info("固定框已打开: (%d, %d) %dx%d", x, y, w, h)

    def get_current_region(self) -> tuple:
        """获取框体当前的屏幕坐标和尺寸（考虑拖拽/调整大小后的位置）"""
        if not self.window:
            return tuple(self.region)
        win_x = self.window.winfo_rootx()
        win_y = self.window.winfo_rooty() + self.BAR_HEIGHT
        w = self.window.winfo_width()
        h = self.window.winfo_height() - self.BAR_HEIGHT
        return (win_x, win_y, max(w, 1), max(h, 1))

    def _capture_current_region(self) -> Image.Image | None:
        """实时截取框体当前区域的屏幕内容"""
        t_cap_start = time.time()
        region = self.get_current_region()
        x, y, w, h = region
        try:
            # 临时隐藏自身窗口，避免截到自己
            self.window.attributes("-topmost", False)
            self.window.withdraw()
            self.window.update()

            time.sleep(0.1)  # 等待窗口隐藏

            with mss.mss() as sct:
                monitor = {"left": x, "top": y, "width": w, "height": h}
                sct_img = sct.grab(monitor)
                screenshot = Image.frombytes("RGB", sct_img.size, sct_img.bgra, "raw", "BGRX")

            # 恢复窗口
            self.window.deiconify()
            self.window.attributes("-topmost", True)
            self.window.update()

            t_cap = time.time() - t_cap_start
            logger.info("截图完成: %dx%d pixels, 耗时 %.3fs", w, h, t_cap)

            return screenshot
        except Exception as e:
            logger.error("实时截图失败: %s", e)
            # 确保窗口恢复
            try:
                self.window.deiconify()
                self.window.attributes("-topmost", True)
            except Exception:
                pass
            return None

    def _on_drag_start(self, event):
        self._drag_data["x"] = event.x_root - self.window.winfo_x()
        self._drag_data["y"] = event.y_root - self.window.winfo_y()

    def _on_drag_motion(self, event):
        new_x = event.x_root - self._drag_data["x"]
        new_y = event.y_root - self._drag_data["y"]
        self.window.geometry(f"+{new_x}+{new_y}")

    # ======================== 拖拽调整大小 ========================

    def _on_resize_start(self, event, direction: str):
        """记录调整大小的起始状态"""
        self._resize_data = {
            "direction": direction,
            "start_x": event.x_root,
            "start_y": event.y_root,
            "start_w": self.window.winfo_width(),
            "start_h": self.window.winfo_height(),
            "start_win_x": self.window.winfo_x(),
            "start_win_y": self.window.winfo_y(),
        }

    def _on_resize_motion(self, event):
        """执行调整大小"""
        d = self._resize_data
        dx = event.x_root - d["start_x"]
        dy = event.y_root - d["start_y"]
        direction = d["direction"]

        new_w = d["start_w"]
        new_h = d["start_h"]
        new_x = d["start_win_x"]
        new_y = d["start_win_y"]

        # 计算新尺寸
        if "e" in direction:
            new_w = max(d["start_w"] + dx, self.MIN_W)
        if "w" in direction:
            delta = dx
            calc_w = max(d["start_w"] - delta, self.MIN_W)
            delta = d["start_w"] - calc_w
            new_w = calc_w
            new_x = d["start_win_x"] + delta
        if "s" in direction:
            new_h = max(d["start_h"] + dy, self.MIN_H + self.BAR_HEIGHT)
        if "n" in direction:
            delta = dy
            calc_h = max(d["start_h"] - delta, self.MIN_H + self.BAR_HEIGHT)
            delta = d["start_h"] - calc_h
            new_h = calc_h
            new_y = d["start_win_y"] + delta

        self.window.geometry(f"{new_w}x{new_h}+{new_x}+{new_y}")

    def _on_resize_end(self, event):
        """调整大小结束，更新尺寸标签"""
        if not self.window:
            return
        w = self.window.winfo_width()
        h = self.window.winfo_height() - self.BAR_HEIGHT
        self._size_label.configure(text=f"{w} \u00d7 {h}")
        # 重新布置手柄位置
        self._update_handle_positions()

    def _update_handle_positions(self):
        """调整大小后重新布置手柄位置"""
        if not self.window:
            return
        hs = self.HANDLE_SIZE
        w = self.window.winfo_width()
        h = self.window.winfo_height()
        # 查找所有手柄并重新放置
        for child in self.window.winfo_children():
            if isinstance(child, tk.Frame) and child.cget("cursor") in (
                "fleur", "sb_v_double_arrow", "sb_h_double_arrow"
            ):
                cursor = child.cget("cursor")
                # 根据当前位置推断手柄类型
                cur_x = child.winfo_x()
                cur_y = child.winfo_y()
                # 判断是左/中/右
                if cur_x < hs:
                    is_left = True
                elif cur_x + hs >= w:
                    is_left = False
                else:
                    is_left = None
                # 判断是上/中/下
                if cur_y < hs:
                    is_top = True
                elif cur_y + hs >= h:
                    is_top = False
                else:
                    is_top = None

                if cursor == "fleur":  # 角落
                    new_x = 0 if is_left else w - hs
                    new_y = 0 if is_top else h - hs
                    child.place(x=new_x, y=new_y, width=hs, height=hs)
                elif cursor == "sb_v_double_arrow":  # 上下边缘
                    new_y = 0 if is_top else h - hs
                    child.place(x=hs, y=new_y, width=w - 2 * hs, height=hs)
                elif cursor == "sb_h_double_arrow":  # 左右边缘
                    new_x = 0 if is_left else w - hs
                    child.place(x=new_x, y=hs, width=hs, height=h - 2 * hs)

    def _do_save(self):
        """实时截图并保存"""
        screenshot = self._capture_current_region()
        if screenshot is None:
            logger.error("截图保存失败: 无法截取屏幕内容")
            return

        try:
            os.makedirs(self.save_dir, exist_ok=True)
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S_%f")[:-3]
            filename = f"capture_{timestamp}.png"
            filepath = os.path.join(self.save_dir, filename)
            screenshot.save(filepath, "PNG")
            logger.info("截图已保存: %s", filepath)
        except Exception as e:
            logger.error("保存失败: %s", e)

    def _do_translate(self):
        """实时截取框内内容并触发翻译"""
        screenshot = self._capture_current_region()
        if screenshot is None:
            return

        region = self.get_current_region()
        if self.on_translate:
            self.on_translate(region, screenshot)

    def _dispose(self):
        """关闭选框"""
        if self.window:
            self.window.destroy()
            self.window = None
        if self.on_close:
            self.on_close()
        logger.info("固定框已关闭")

    def hide(self):
        if self.window:
            self.window.withdraw()

    def destroy(self):
        self._dispose()
