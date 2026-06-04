"""OCR 服务 - 基于 PaddleOCR 的本地文字识别

使用本地 PaddleOCR 模型识别截图中的文字，
解决 DeepSeek 等 Provider 不支持 Vision（图片输入）的问题。
识别结果直接调用后端文字翻译接口完成翻译。
"""

import os
# 禁用 MKLDNN 以避免 PaddlePaddle 3.x 在 Windows + Python 3.13 上的 PIR 兼容性问题
os.environ.setdefault('PADDLE_PDX_ENABLE_MKLDNN_BYDEFAULT', 'False')

import time
import logging
from PIL import Image
import numpy as np

logger = logging.getLogger(__name__)


class OcrService:
    """
    本地 OCR 服务（PaddleOCR 后端）

    特性：
    - 懒加载：首次调用时才初始化模型，避免启动时卡顿
    - 语言映射：将通用语言名映射为 PaddleOCR 支持的模型语言代码
    - 置信度过滤：根据配置阈值过滤低置信度结果
    """

    # from_lang（后端语言名） -> PaddleOCR lang 代码
    _LANG_MAP = {
        "auto":    "japan",   # 自动 → 日文（默认）
        "chinese": "ch",   # 中文 → 中英混合
        "english": "en",
        "japanese": "japan",
        "korean":  "korean",
        "french":  "french",
        "german":  "german",
    }

    def __init__(self, lang: str = "ch",
                 confidence_threshold: float = 0.5):
        self._default_lang = lang
        self._confidence_threshold = confidence_threshold
        self._engine = None           # 懒加载
        self._current_lang = lang     # 当前已加载的语言

    # ======================== 懒加载初始化 ========================

    def _ensure_engine(self, lang: str = None):
        """
        确保 PaddleOCR 引擎已初始化。
        首次调用时加载模型，后续复用（除非语言变更）。
        """
        target_lang = lang or self._default_lang
        # 大小写不敏感查找映射
        target_lang_lower = target_lang.lower()
        for key, value in self._LANG_MAP.items():
            if key.lower() == target_lang_lower:
                target_lang = value
                break

        if self._engine is not None and target_lang == self._current_lang:
            return

        logger.info("初始化 PaddleOCR 引擎: lang=%s", target_lang)
        t_init = time.time()
        try:
            from paddleocr import PaddleOCR

            # 中英文使用 mobile 模型（速度优先），其他语言用默认 server 模型
            if target_lang == "ch":
                det_model = "PP-OCRv5_mobile_det"
                rec_model = "PP-OCRv5_mobile_rec"
            elif target_lang == "en":
                det_model = "PP-OCRv5_mobile_det"
                rec_model = "en_PP-OCRv5_mobile_rec"
            else:
                det_model = None
                rec_model = None

            self._engine = PaddleOCR(
                lang=target_lang if det_model is None else None,
                text_detection_model_name=det_model,
                text_recognition_model_name=rec_model,
                use_doc_orientation_classify=False,          # 截图不需要文档方向检测
                use_doc_unwarping=False,                      # 截图不需要文档展平
                use_textline_orientation=False,                # 屏幕文字以横排为主
            )
            self._current_lang = target_lang
            logger.info("PaddleOCR 引擎初始化完成 (%.2fs)", time.time() - t_init)
        except Exception as e:
            logger.error("PaddleOCR 引擎初始化失败: %s", e, exc_info=True)
            self._engine = None
            raise RuntimeError(f"PaddleOCR 初始化失败: {e}") from e

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
        self._ensure_engine(lang=from_lang)

        if self._engine is None:
            logger.warning("OCR 引擎未就绪，无法提取文字")
            return ""

        try:
            # 预处理：numpy 转换 + 可选降采样
            t_prep_start = time.time()
            img_array = np.array(image)
            h, w = img_array.shape[:2]
            w_orig, h_orig = w, h
            max_dim = 1500
            if w > max_dim:
                scale = max_dim / w
                new_w, new_h = int(w * scale), int(h * scale)
                from PIL import Image as PILImage
                image = image.resize((new_w, new_h), PILImage.LANCZOS)
                img_array = np.array(image)
                h, w = new_h, new_w
                logger.info("OCR 图片已降采样: %dx%d → %dx%d", w_orig, h_orig, w, h)
            t_prep = time.time() - t_prep_start

            # PaddleOCR 推理
            logger.info("OCR 推理中... (预处理:%.3fs, 图片:%dx%d)", t_prep, w, h)
            t_infer = time.time()

            # PaddleOCR v3 predict() 返回: list[OCRResult]
            # OCRResult 是 dict-like 对象:
            #   result["rec_texts"]  -> list[str]  识别文本
            #   result["rec_scores"] -> list[float] 置信度
            results = self._engine.ocr(img_array)

            t_ocr = time.time() - t_infer
            t_total = time.time() - t_prep_start

            if not results or len(results) == 0:
                logger.info("OCR 未识别到任何文字 (耗时: %.2fs)", t_total)
                return ""

            result = results[0]
            if "rec_texts" not in result or not result["rec_texts"]:
                logger.info("OCR 未识别到任何文字 (耗时: %.2fs)", t_total)
                return ""

            lines = []
            for text, score in zip(result["rec_texts"], result["rec_scores"]):
                if score >= self._confidence_threshold:
                    lines.append(text)
                    logger.info("  [%.3f] %s", score, text)

            extracted = "\n".join(lines)
            logger.info("PaddleOCR 完成: %d 行, %d 字符 | 预处理:%.3fs 推理:%.3fs 总计:%.2fs",
                        len(lines), len(extracted), t_prep, t_ocr, t_total)
            if extracted:
                logger.info("OCR 完整结果:\n%s", extracted)
            return extracted

        except Exception as e:
            logger.error("OCR 识别过程异常: %s", e, exc_info=True)
            return ""

    # ======================== 运行时语言切换 ========================

    def set_lang(self, lang: str):
        """
        设置 OCR 识别语言。
        下次调用 extract_text() 时会重新加载对应模型。
        """
        # 大小写不敏感映射
        lang_lower = lang.lower()
        for key, value in self._LANG_MAP.items():
            if key.lower() == lang_lower:
                mapped = value
                break
        else:
            mapped = lang
        if mapped != self._current_lang:
            logger.info("OCR 语言待更新: %s → %s", self._current_lang, mapped)
            self._default_lang = lang
            # 标记需要重新初始化
            self._engine = None

    def is_available(self) -> bool:
        """检测 PaddleOCR 是否已安装可用"""
        try:
            import paddleocr  # noqa: F401
            import paddle     # noqa: F401
            return True
        except ImportError:
            return False
