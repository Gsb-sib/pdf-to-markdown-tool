package com.example.document.autoconfigure;

import com.example.document.DocumentProcessingService;
import com.example.document.ocr.OcrService;
import com.example.document.ocr.OnnxOcrService;
import com.example.document.ocr.OnnxRuntimeOcrService;
import com.example.document.ocr.PaddleOcrService;
import com.example.document.ocr.Tess4jOcrService;
import com.example.document.support.DefaultDocumentProcessingService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(DocumentProcessingProperties.class)
public class DocumentProcessingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "document.processing.ocr", name = "enabled", havingValue = "true", matchIfMissing = true)
    OcrService ocrService(DocumentProcessingProperties properties) {
        if ("onnxruntime".equalsIgnoreCase(properties.getOcr().getProvider())) {
            return new OnnxRuntimeOcrService(properties);
        }
        if ("onnx".equalsIgnoreCase(properties.getOcr().getProvider())) {
            return new OnnxOcrService(properties);
        }
        if ("paddle".equalsIgnoreCase(properties.getOcr().getProvider())) {
            return new PaddleOcrService(properties);
        }
        return new Tess4jOcrService(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    DocumentProcessingService documentProcessingService(DocumentProcessingProperties properties,
            ObjectProvider<OcrService> ocrServiceProvider) {
        return new DefaultDocumentProcessingService(properties, ocrServiceProvider.getIfAvailable());
    }
}
