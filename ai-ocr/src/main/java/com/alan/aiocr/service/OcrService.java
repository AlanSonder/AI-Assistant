package com.alan.aiocr.service;

import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;

@Slf4j
@Service
public class OcrService {

    private final ITesseract tesseract;

    public OcrService() {
        tesseract = new Tesseract();
        tesseract.setPageSegMode(1);
        log.info("OCR引擎初始化完成");
    }

    public String extractText(MultipartFile imageFile) {
        try {
            BufferedImage bufferedImage = ImageIO.read(imageFile.getInputStream());
            if (bufferedImage == null) {
                throw new OcrException("无法读取图片文件，请检查文件格式");
            }
            String result = tesseract.doOCR(bufferedImage);
            log.info("OCR提取完成: 原文长度={}", result.length());
            return result.trim();
        } catch (IOException e) {
            log.error("读取图片文件失败", e);
            throw new OcrException("读取图片文件失败: " + e.getMessage(), e);
        } catch (TesseractException e) {
            log.error("OCR识别失败", e);
            throw new OcrException("OCR识别失败: " + e.getMessage(), e);
        }
    }

    public String extractText(MultipartFile imageFile, String language) {
        try {
            BufferedImage bufferedImage = ImageIO.read(imageFile.getInputStream());
            if (bufferedImage == null) {
                throw new OcrException("无法读取图片文件，请检查文件格式");
            }
            tesseract.setLanguage(language);
            String result = tesseract.doOCR(bufferedImage);
            log.info("OCR提取完成({}): 原文长度={}", language, result.length());
            return result.trim();
        } catch (IOException e) {
            log.error("读取图片文件失败", e);
            throw new OcrException("读取图片文件失败: " + e.getMessage(), e);
        } catch (TesseractException e) {
            log.error("OCR识别失败", e);
            throw new OcrException("OCR识别失败: " + e.getMessage(), e);
        }
    }
}
