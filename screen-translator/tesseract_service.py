"""OCR 服务 - 基于 Tesseract 5 的本地文字识别

使用 Tesseract OCR 引擎识别截图中的文字，
解决 DeepSeek 等 Provider 不支持 Vision（图片输入）的问题。

相比 PaddleOCR：
- 速度更快（无模型加载开销）
- 安装体积小（~50MB vs ~2GB）
- 对清晰屏幕文字识别效果好
"""

import os
import time
import logging
import subprocess
from PIL import Image

logger = logging.getLogger(__name__)

# Tesseract 安装路径（Windows winget 安装默认路径）
_TESSERACT_PATHS = [
    r"C:\Program Files\Tesseract-OCR\tesseract.exe",
    r"C:\Program Files (x86)\Tesseract-OCR\tesseract.exe",
    os.path.expandvars(r"%LOCALAPPDATA%\Programs\Tesseract-OCR\tesseract.exe"),
]


def _find_tesseract() -> str | None:
    """查找 tesseract.exe 路径"""
    # 先检查环境变量 PATH
    import shutil
    found = shutil.which("tesseract")
    if found:
        return found
    # 再检查常见安装路径
    for p in _TESSERACT_PATHS:
        if os.path.isfile(p):
            return p
    return None


def _get_best_tessdata() -> str | None:
    """获取最优 tessdata 目录（语言包最多的那个）"""
    candidates = []

    # 1. 用户目录（优先：winget 安装时语言包可能安装在此处）
    user_tessdata = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Tesseract-OCR", "tessdata")
    if os.path.isdir(user_tessdata):
        candidates.append(user_tessdata)

    # 2. 标准安装路径
    for tesseract_path in _TESSERACT_PATHS:
        if os.path.isfile(tesseract_path):
            base = os.path.dirname(tesseract_path)
            candidate = os.path.join(base, "tessdata")
            if os.path.isdir(candidate):
                candidates.append(candidate)
            # 也检查上级目录
            alt = os.path.join(os.path.dirname(base), "tessdata")
            if os.path.isdir(alt):
                candidates.append(alt)
            break  # 只取找到的第一个 tesseract.exe

    # 3. 环境变量
    env_prefix = os.environ.get("TESSDATA_PREFIX", "")
    if env_prefix and os.path.isdir(env_prefix) and env_prefix not in candidates:
        candidates.append(env_prefix)

    if not candidates:
        return None

    # 返回 .traineddata 文件最多的目录
    def count_traineddata(path):
        try:
            return len([f for f in os.listdir(path) if f.endswith(".traineddata")])
        except Exception:
            return 0

    candidates.sort(key=count_traineddata, reverse=True)
    best = candidates[0]
    return best


class TesseractService:
    """
    本地 OCR 服务（Tesseract 5 后端）

    特性：
    - 通过 pytesseract 封装调用 Tesseract
    - 语言映射：将通用语言名映射为 Tesseract 语言代码
    - 无需模型预加载，调用时直接识别
    """

    # from_lang（后端语言名） -> Tesseract lang 代码
    _LANG_MAP = {
        "auto":    "jpn+eng",        # 自动 → 日英混合
        "chinese": "chi_sim",
        "english": "eng",
        "japanese": "jpn",
        "korean":  "kor",
        "french":  "fra",
        "german":  "deu",
        "spanish": "spa",
        "russian": "rus",
    }

    def __init__(self, lang: str = "chi_sim+eng",
                 confidence_threshold: float = 0.5):
        self._default_lang = lang
        self._confidence_threshold = confidence_threshold
        self._tesseract_path = _find_tesseract()

        if self._tesseract_path is None:
            logger.warning("未找到 Tesseract 安装，请运行: winget install UB-Mannheim.TesseractOCR")
        else:
            # 设置 TESSDATA_PREFIX 环境变量（如果未设置）
            if "TESSDATA_PREFIX" not in os.environ:
                tessdata = _get_best_tessdata()
                if tessdata:
                    os.environ["TESSDATA_PREFIX"] = tessdata
                    logger.info("TESSDATA_PREFIX 已设为: %s", tessdata)

            # 验证 pytesseract 可用
            try:
                import pytesseract
                pytesseract.pytesseract.tesseract_cmd = self._tesseract_path
                tesseract_version = pytesseract.get_tesseract_version()
                logger.info("Tesseract 已就绪: %s (v%s)", self._tesseract_path, tesseract_version)
            except ImportError:
                logger.warning("pytesseract 未安装，请运行: pip install pytesseract")
            except Exception as e:
                logger.warning("Tesseract 配置异常: %s", e)

    # ======================== 文字识别 ========================

    def extract_text(self, image: Image.Image, from_lang: str = None) -> str:
        """
        从 PIL Image 中提取文字。

        Args:
            image:     PIL Image 对象（RGB 格式）
            from_lang: 源语言（可选，为 None 时使用配置默认值）

        Returns:
            识别到的文字，多行以 \\n 分隔；未识别到内容时返回空字符串
        """
        if self._tesseract_path is None:
            logger.warning("Tesseract 未安装，无法提取文字")
            return ""

        try:
            # 确定目标语言
            target_lang = from_lang or self._default_lang
            # 大小写不敏感查找
            target_lang_lower = target_lang.lower()
            for key, value in self._LANG_MAP.items():
                if key.lower() == target_lang_lower:
                    target_lang = value
                    break

            import pytesseract

            # 预处理：转灰度 + 二值化（提升 OCR 准确率）
            t_prep_start = time.time()
            gray = image.convert("L")
            # 简单的对比度增强
            import numpy as np
            arr = np.array(gray)
            # 自适应阈值二值化
            threshold = 127
            binary = (arr > threshold).astype(np.uint8) * 255
            from PIL import Image as PILImage
            processed = PILImage.fromarray(binary)
            t_prep = time.time() - t_prep_start

            logger.info("Tesseract OCR 推理中... (lang=%s, 预处理 %.3fs)", target_lang, t_prep)
            t_ocr_start = time.time()

            # Tesseract 识别（PSM 6: 假设为统一的文字块）
            text = pytesseract.image_to_string(
                processed,
                lang=target_lang,
                config="--psm 6 -c tessedit_create_hocr=0"
            )

            t_ocr = time.time() - t_ocr_start
            elapsed = time.time() - t_prep_start

            # 清理结果
            lines = [line.strip() for line in text.splitlines() if line.strip()]
            result = "\n".join(lines)

            if result:
                logger.info("Tesseract OCR 完成: %d 行, %d 字符 | 预处理:%.3fs 推理:%.3fs 总计:%.2fs",
                            len(lines), len(result), t_prep, t_ocr, elapsed)
                logger.info("OCR 完整结果:\n%s", result)
            else:
                logger.info("Tesseract 未识别到文字 (耗时: %.2fs)", elapsed)

            return result

        except Exception as e:
            logger.error("Tesseract OCR 识别异常: %s", e, exc_info=True)
            return ""

    # ======================== 运行时语言切换 ========================

    def set_lang(self, lang: str):
        """
        设置 OCR 识别语言。
        下次调用 extract_text() 时会使用新语言。
        """
        # 大小写不敏感映射
        lang_lower = lang.lower()
        for key, value in self._LANG_MAP.items():
            if key.lower() == lang_lower:
                mapped = value
                break
        else:
            mapped = lang
        logger.info("Tesseract OCR 语言已更新: %s → %s", lang, mapped)
        self._default_lang = lang

    def is_available(self) -> bool:
        """检测 Tesseract 是否已安装可用"""
        if self._tesseract_path is None:
            return False
        try:
            import pytesseract  # noqa: F401
            pytesseract.get_tesseract_version()
            return True
        except Exception:
            return False
