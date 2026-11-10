package com.example.document.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "document.processing")
public class DocumentProcessingProperties {
    private String imageOutputDir = "./document-images";
    private String imageUrlPrefix = "/document-images";
    private double pdfScanImageRatioThreshold = 0.60;
    private double pdfScanPageRatioThreshold = 0.50;
    private Ocr ocr = new Ocr();

    public String getImageOutputDir() {
        return imageOutputDir;
    }

    public void setImageOutputDir(String imageOutputDir) {
        this.imageOutputDir = imageOutputDir;
    }

    public String getImageUrlPrefix() {
        return imageUrlPrefix;
    }

    public void setImageUrlPrefix(String imageUrlPrefix) {
        this.imageUrlPrefix = imageUrlPrefix;
    }

    public double getPdfScanImageRatioThreshold() {
        return pdfScanImageRatioThreshold;
    }

    public void setPdfScanImageRatioThreshold(double pdfScanImageRatioThreshold) {
        this.pdfScanImageRatioThreshold = pdfScanImageRatioThreshold;
    }

    public double getPdfScanPageRatioThreshold() {
        return pdfScanPageRatioThreshold;
    }

    public void setPdfScanPageRatioThreshold(double pdfScanPageRatioThreshold) {
        this.pdfScanPageRatioThreshold = pdfScanPageRatioThreshold;
    }

    public Ocr getOcr() {
        return ocr;
    }

    public void setOcr(Ocr ocr) {
        this.ocr = ocr;
    }

    public static class Ocr {
        private boolean enabled = true;
        private String provider = "tesseract";
        private String language = "chi_sim+eng";
        private String paddleLanguage = "ch";
        private String paddleVersion = "PP-OCRv5";
        private String datapath;
        private String pythonPath;
        private String ocrWorkDir = "C:/ocr-work";
        private String onnxScriptPath;
        private int onnxCpuThreads = 4;
        private String onnxRuntimeDetectionModelPath = "C:/ocr-runtime/Lib/site-packages/rapidocr/models/PP-OCRv6_det_small.onnx";
        private String onnxRuntimeRecognitionModelPath = "C:/ocr-runtime/Lib/site-packages/rapidocr/models/PP-OCRv6_rec_small.onnx";
        private String onnxRuntimeDictionaryPath = "C:/ocr-runtime/Lib/site-packages/rapidocr/models/ppocr_keys_v1.txt";
        private double onnxRuntimeDetectionThreshold = 0.30;
        private double onnxRuntimeDetectionBoxThreshold = 0.50;
        private int onnxRuntimeDetectionMaxSide = 768;
        private int onnxRuntimeRecognitionMaxWidth = 960;
        private int onnxRuntimeMaxDetectionBoxes = 200;
        private int workerPoolSize = 2;
        private boolean eagerStartWorkerPool = false;
        private boolean enableRotationRetry = false;
        private int maxRenderedPageWidth = 1600;
        private double lowConfidenceThreshold = 0.68;
        private String paddleScriptPath;
        private String paddleCacheDir = "C:/paddlex-cache";
        private String paddleWorkDir = "C:/paddleocr-work";
        private String paddleModelSource = "modelscope";
        private boolean paddleDisableModelSourceCheck = true;
        private int paddleCpuThreads = 4;
        private int timeoutSeconds = 120;
        private int dpi = 160;
        private int parallelism = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        private boolean preprocess = true;
        private int binaryThreshold = 170;
        private int pageSegMode = 6;
        private String engineMode = "LSTM_ONLY";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getLanguage() {
            return language;
        }

        public void setLanguage(String language) {
            this.language = language;
        }

        public String getPaddleLanguage() {
            return paddleLanguage;
        }

        public void setPaddleLanguage(String paddleLanguage) {
            this.paddleLanguage = paddleLanguage;
        }

        public String getPaddleVersion() {
            return paddleVersion;
        }

        public void setPaddleVersion(String paddleVersion) {
            this.paddleVersion = paddleVersion;
        }

        public String getDatapath() {
            return datapath;
        }

        public void setDatapath(String datapath) {
            this.datapath = datapath;
        }

        public String getPythonPath() {
            return pythonPath;
        }

        public void setPythonPath(String pythonPath) {
            this.pythonPath = pythonPath;
        }

        public String getOcrWorkDir() {
            return ocrWorkDir;
        }

        public void setOcrWorkDir(String ocrWorkDir) {
            this.ocrWorkDir = ocrWorkDir;
        }

        public String getOnnxScriptPath() {
            return onnxScriptPath;
        }

        public void setOnnxScriptPath(String onnxScriptPath) {
            this.onnxScriptPath = onnxScriptPath;
        }

        public int getOnnxCpuThreads() {
            return onnxCpuThreads;
        }

        public void setOnnxCpuThreads(int onnxCpuThreads) {
            this.onnxCpuThreads = onnxCpuThreads;
        }

        public String getOnnxRuntimeDetectionModelPath() {
            return onnxRuntimeDetectionModelPath;
        }

        public void setOnnxRuntimeDetectionModelPath(String onnxRuntimeDetectionModelPath) {
            this.onnxRuntimeDetectionModelPath = onnxRuntimeDetectionModelPath;
        }

        public String getOnnxRuntimeRecognitionModelPath() {
            return onnxRuntimeRecognitionModelPath;
        }

        public void setOnnxRuntimeRecognitionModelPath(String onnxRuntimeRecognitionModelPath) {
            this.onnxRuntimeRecognitionModelPath = onnxRuntimeRecognitionModelPath;
        }

        public String getOnnxRuntimeDictionaryPath() {
            return onnxRuntimeDictionaryPath;
        }

        public void setOnnxRuntimeDictionaryPath(String onnxRuntimeDictionaryPath) {
            this.onnxRuntimeDictionaryPath = onnxRuntimeDictionaryPath;
        }

        public double getOnnxRuntimeDetectionThreshold() {
            return onnxRuntimeDetectionThreshold;
        }

        public void setOnnxRuntimeDetectionThreshold(double onnxRuntimeDetectionThreshold) {
            this.onnxRuntimeDetectionThreshold = onnxRuntimeDetectionThreshold;
        }

        public double getOnnxRuntimeDetectionBoxThreshold() {
            return onnxRuntimeDetectionBoxThreshold;
        }

        public void setOnnxRuntimeDetectionBoxThreshold(double onnxRuntimeDetectionBoxThreshold) {
            this.onnxRuntimeDetectionBoxThreshold = onnxRuntimeDetectionBoxThreshold;
        }

        public int getOnnxRuntimeDetectionMaxSide() {
            return onnxRuntimeDetectionMaxSide;
        }

        public void setOnnxRuntimeDetectionMaxSide(int onnxRuntimeDetectionMaxSide) {
            this.onnxRuntimeDetectionMaxSide = onnxRuntimeDetectionMaxSide;
        }

        public int getOnnxRuntimeRecognitionMaxWidth() {
            return onnxRuntimeRecognitionMaxWidth;
        }

        public void setOnnxRuntimeRecognitionMaxWidth(int onnxRuntimeRecognitionMaxWidth) {
            this.onnxRuntimeRecognitionMaxWidth = onnxRuntimeRecognitionMaxWidth;
        }

        public int getOnnxRuntimeMaxDetectionBoxes() {
            return onnxRuntimeMaxDetectionBoxes;
        }

        public void setOnnxRuntimeMaxDetectionBoxes(int onnxRuntimeMaxDetectionBoxes) {
            this.onnxRuntimeMaxDetectionBoxes = onnxRuntimeMaxDetectionBoxes;
        }

        public int getWorkerPoolSize() {
            return workerPoolSize;
        }

        public void setWorkerPoolSize(int workerPoolSize) {
            this.workerPoolSize = workerPoolSize;
        }

        public boolean isEagerStartWorkerPool() {
            return eagerStartWorkerPool;
        }

        public void setEagerStartWorkerPool(boolean eagerStartWorkerPool) {
            this.eagerStartWorkerPool = eagerStartWorkerPool;
        }

        public boolean isEnableRotationRetry() {
            return enableRotationRetry;
        }

        public void setEnableRotationRetry(boolean enableRotationRetry) {
            this.enableRotationRetry = enableRotationRetry;
        }

        public int getMaxRenderedPageWidth() {
            return maxRenderedPageWidth;
        }

        public void setMaxRenderedPageWidth(int maxRenderedPageWidth) {
            this.maxRenderedPageWidth = maxRenderedPageWidth;
        }

        public double getLowConfidenceThreshold() {
            return lowConfidenceThreshold;
        }

        public void setLowConfidenceThreshold(double lowConfidenceThreshold) {
            this.lowConfidenceThreshold = lowConfidenceThreshold;
        }

        public String getPaddleScriptPath() {
            return paddleScriptPath;
        }

        public void setPaddleScriptPath(String paddleScriptPath) {
            this.paddleScriptPath = paddleScriptPath;
        }

        public String getPaddleCacheDir() {
            return paddleCacheDir;
        }

        public void setPaddleCacheDir(String paddleCacheDir) {
            this.paddleCacheDir = paddleCacheDir;
        }

        public String getPaddleWorkDir() {
            return paddleWorkDir;
        }

        public void setPaddleWorkDir(String paddleWorkDir) {
            this.paddleWorkDir = paddleWorkDir;
        }

        public String getPaddleModelSource() {
            return paddleModelSource;
        }

        public void setPaddleModelSource(String paddleModelSource) {
            this.paddleModelSource = paddleModelSource;
        }

        public boolean isPaddleDisableModelSourceCheck() {
            return paddleDisableModelSourceCheck;
        }

        public void setPaddleDisableModelSourceCheck(boolean paddleDisableModelSourceCheck) {
            this.paddleDisableModelSourceCheck = paddleDisableModelSourceCheck;
        }

        public int getPaddleCpuThreads() {
            return paddleCpuThreads;
        }

        public void setPaddleCpuThreads(int paddleCpuThreads) {
            this.paddleCpuThreads = paddleCpuThreads;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public int getDpi() {
            return dpi;
        }

        public void setDpi(int dpi) {
            this.dpi = dpi;
        }

        public int getParallelism() {
            return parallelism;
        }

        public void setParallelism(int parallelism) {
            this.parallelism = parallelism;
        }

        public boolean isPreprocess() {
            return preprocess;
        }

        public void setPreprocess(boolean preprocess) {
            this.preprocess = preprocess;
        }

        public int getBinaryThreshold() {
            return binaryThreshold;
        }

        public void setBinaryThreshold(int binaryThreshold) {
            this.binaryThreshold = binaryThreshold;
        }

        public int getPageSegMode() {
            return pageSegMode;
        }

        public void setPageSegMode(int pageSegMode) {
            this.pageSegMode = pageSegMode;
        }

        public String getEngineMode() {
            return engineMode;
        }

        public void setEngineMode(String engineMode) {
            this.engineMode = engineMode;
        }
    }
}
