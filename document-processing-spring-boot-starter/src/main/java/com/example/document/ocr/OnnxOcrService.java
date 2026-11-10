package com.example.document.ocr;

import com.example.document.autoconfigure.DocumentProcessingProperties;
import java.util.Map;

public class OnnxOcrService extends PythonWorkerOcrService {
    public OnnxOcrService(DocumentProcessingProperties properties) {
        super(properties);
    }

    @Override
    protected String engineName() {
        return "ONNX OCR";
    }

    @Override
    protected String workerResource() {
        return "/onnxocr_worker.py";
    }

    @Override
    protected String configuredScriptPath(DocumentProcessingProperties.Ocr ocr) {
        return ocr.getOnnxScriptPath();
    }

    @Override
    protected void configureEnvironment(Map<String, String> env, DocumentProcessingProperties.Ocr ocr) {
        env.put("OMP_NUM_THREADS", String.valueOf(Math.max(1, ocr.getOnnxCpuThreads())));
        env.put("ORT_NUM_THREADS", String.valueOf(Math.max(1, ocr.getOnnxCpuThreads())));
        env.put("DOCUMENT_OCR_ENABLE_ROTATION_RETRY", String.valueOf(ocr.isEnableRotationRetry()));
    }
}
