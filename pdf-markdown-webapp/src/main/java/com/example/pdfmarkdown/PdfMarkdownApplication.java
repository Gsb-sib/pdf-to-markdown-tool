package com.example.pdfmarkdown;

import com.example.document.autoconfigure.DocumentProcessingAutoConfiguration;
import com.example.document.autoconfigure.DocumentProcessingProperties;
import com.example.document.ocr.OcrService;
import com.example.document.ocr.Tess4jOcrService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

@SpringBootApplication(exclude = DocumentProcessingAutoConfiguration.class)
@EnableConfigurationProperties(DocumentProcessingProperties.class)
public class PdfMarkdownApplication {
    public static void main(String[] args) {
        SpringApplication.run(PdfMarkdownApplication.class, args);
    }

    // 仅处理扫描件时初始化 OCR，普通 PDF 转换不依赖模型文件。
    @Bean
    @Lazy
    OcrService pdfOcrService(DocumentProcessingProperties properties) {
        if (!properties.getOcr().isEnabled()) {
            throw new IllegalStateException("扫描件识别未启用，请启用 OCR 后重试。");
        }
        return switch (properties.getOcr().getProvider().toLowerCase(java.util.Locale.ROOT)) {
            case "onnxruntime" -> new AccurateOnnxOcrService(properties);
            case "tesseract" -> new Tess4jOcrService(properties);
            default -> throw new IllegalStateException("此工具支持 onnxruntime 或 tesseract OCR。");
        };
    }
}
