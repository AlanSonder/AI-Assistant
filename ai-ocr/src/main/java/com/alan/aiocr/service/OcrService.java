package com.alan.aiocr.service;

import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
public class OcrService {

    private final ITesseract tesseract;

    public OcrService() {
        tesseract = new Tesseract();
        tesseract.setPageSegMode(1);

        // 设置 tessdata 路径
        String tessdataPath = resolveTessdataPath();
        tesseract.setDatapath(tessdataPath);
        // 同时设置环境变量（供 JNA 原生库加载使用）
        System.setProperty("TESSDATA_PREFIX", tessdataPath);
        log.info("OCR引擎初始化完成, tessdata路径: {}", tessdataPath);
    }

    /**
     * 解析 tessdata 目录路径
     * 优先级：系统属性 > 环境变量 > 当前目录下的 tessdata
     */
    private String resolveTessdataPath() {
        // 1. 系统属性
        String prop = System.getProperty("TESSDATA_PREFIX");
        if (prop != null && !prop.isEmpty() && Files.isDirectory(Path.of(prop))) {
            return prop;
        }
        // 2. 环境变量
        String env = System.getenv("TESSDATA_PREFIX");
        if (env != null && !env.isEmpty() && Files.isDirectory(Path.of(env))) {
            return env;
        }
        // 3. 当前工作目录下的 tessdata
        Path cwdPath = Path.of(System.getProperty("user.dir"), "tessdata");
        if (Files.isDirectory(cwdPath)) {
            return cwdPath.toAbsolutePath().toString();
        }
        // 4. 上级目录（多模块 Maven 项目，子模块运行在模块目录下）
        Path parentPath = Path.of(System.getProperty("user.dir"), "..", "tessdata");
        if (Files.isDirectory(parentPath)) {
            return parentPath.toAbsolutePath().normalize().toString();
        }
        // 5. 回退到当前目录
        log.warn("未找到tessdata目录，使用当前目录");
        return System.getProperty("user.dir");
    }

    public String extractText(MultipartFile imageFile) {
        return extractText(imageFile, null);
    }

    /**
     * 提取文字，指定语言（如 "jpn", "chi_sim", "eng"）
     * 为 null/auto 时使用 eng+jpn+chi_sim 组合识别
     */
    public String extractText(MultipartFile imageFile, String language) {
        try {
            BufferedImage bufferedImage = ImageIO.read(imageFile.getInputStream());
            if (bufferedImage == null) {
                throw new OcrException("无法读取图片文件，请检查文件格式");
            }
            String lang = (language != null && !language.isEmpty() && !"auto".equalsIgnoreCase(language))
                    ? language : "eng+jpn+chi_sim";
            tesseract.setLanguage(lang);
            String result = tesseract.doOCR(bufferedImage);
            log.info("OCR提取完成(lang={}): 原文长度={}", lang, result.length());
            return result.trim();
        } catch (IOException e) {
            log.error("读取图片文件失败", e);
            throw new OcrException("读取图片文件失败: " + e.getMessage(), e);
        } catch (TesseractException e) {
            log.error("OCR识别失败", e);
            throw new OcrException("OCR识别失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将翻译 from 语言代码映射为 Tesseract OCR 语言代码
     */
    public static String toOcrLang(String fromLang) {
        if (fromLang == null || fromLang.isEmpty() || "auto".equalsIgnoreCase(fromLang)) {
            return null;  // 自动检测
        }
        return switch (fromLang.toLowerCase()) {
            case "chinese" -> "chi_sim";
            case "english" -> "eng";
            case "japanese" -> "jpn";
            case "korean" -> "kor";
            case "french" -> "fra";
            case "german" -> "deu";
            case "spanish" -> "spa";
            case "russian" -> "rus";
            default -> null;
        };
    }
}
