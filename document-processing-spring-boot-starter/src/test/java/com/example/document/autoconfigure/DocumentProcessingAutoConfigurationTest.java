package com.example.document.autoconfigure;

import com.example.document.DocumentProcessingService;
import com.example.document.ocr.OcrService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentProcessingAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DocumentProcessingAutoConfiguration.class));

    @Test
    void createsDefaultBeans() {
        contextRunner.run(context -> assertThat(context)
                .hasSingleBean(OcrService.class)
                .hasSingleBean(DocumentProcessingService.class));
    }
}
