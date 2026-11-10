package com.example.document.ocr;

import com.example.document.autoconfigure.DocumentProcessingProperties;
import java.util.Map;
import org.springframework.util.StringUtils;

public class PaddleOcrService extends PythonWorkerOcrService {
    public PaddleOcrService(DocumentProcessingProperties properties) {
        super(properties);
    }

    @Override
    protected String engineName() {
        return "PaddleOCR";
    }

    @Override
    protected String workerResource() {
        return "/paddleocr_worker.py";
    }

    @Override
    protected String configuredScriptPath(DocumentProcessingProperties.Ocr ocr) {
        return ocr.getPaddleScriptPath();
    }

    @Override
    protected void configureEnvironment(Map<String, String> env, DocumentProcessingProperties.Ocr ocr) {
        putIfText(env, "PADDLE_PDX_CACHE_HOME", ocr.getPaddleCacheDir());
        putIfText(env, "PADDLE_PDX_MODEL_SOURCE", ocr.getPaddleModelSource());
        env.put("PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK", String.valueOf(ocr.isPaddleDisableModelSourceCheck()));
        env.put("PADDLE_PDX_CPU_NUM_THREADS", String.valueOf(Math.max(1, ocr.getPaddleCpuThreads())));
        env.put("DOCUMENT_OCR_PADDLE_LANGUAGE", StringUtils.hasText(ocr.getPaddleLanguage()) ? ocr.getPaddleLanguage() : "ch");
        putIfText(env, "DOCUMENT_OCR_PADDLE_VERSION", ocr.getPaddleVersion());
        env.put("DOCUMENT_OCR_ENABLE_ROTATION_RETRY", String.valueOf(ocr.isEnableRotationRetry()));
    }
}
