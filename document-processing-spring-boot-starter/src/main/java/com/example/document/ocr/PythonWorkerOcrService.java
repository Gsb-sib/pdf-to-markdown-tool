package com.example.document.ocr;

import com.example.document.autoconfigure.DocumentProcessingProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.springframework.util.StringUtils;

abstract class PythonWorkerOcrService implements OcrService, AutoCloseable {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final DocumentProcessingProperties properties;
    private final Object poolLock = new Object();
    private final AtomicInteger startedWorkers = new AtomicInteger();
    private BlockingQueue<WorkerClient> workers;
    private Path workerScript;

    protected PythonWorkerOcrService(DocumentProcessingProperties properties) {
        this.properties = properties;
    }

    @Override
    public String recognize(Path imagePath) {
        return recognizeDetailed(imagePath).text();
    }

    @Override
    public OcrResult recognizeDetailed(Path imagePath) {
        Path workerImagePath = null;
        WorkerClient worker = null;
        try {
            workerImagePath = copyToAsciiWorkDir(imagePath);
            worker = borrowWorker();
            WorkerResponse response = worker.request(workerImagePath);
            if (!"ok".equals(response.status())) {
                throw new IllegalStateException(engineName() + " failed for image: " + imagePath + ". " + response.message());
            }
            return response.toOcrResult();
        } finally {
            if (worker != null) {
                returnWorker(worker);
            }
            if (workerImagePath != null) {
                try {
                    Files.deleteIfExists(workerImagePath);
                } catch (IOException ignored) {
                    // Best effort cleanup for copied OCR input images.
                }
            }
        }
    }

    @Override
    public String recognize(BufferedImage image) {
        return recognizeDetailed(image).text();
    }

    @Override
    public OcrResult recognizeDetailed(BufferedImage image) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("python-ocr-", ".png");
            ImageIO.write(image, "png", tempFile.toFile());
            return recognizeDetailed(tempFile);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to prepare image for " + engineName(), ex);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // Best effort cleanup for transient OCR images.
                }
            }
        }
    }

    @Override
    public boolean isThreadSafe() {
        return true;
    }

    @Override
    public void close() {
        synchronized (poolLock) {
            if (workers != null) {
                for (WorkerClient worker : workers) {
                    worker.close();
                }
                workers = null;
            }
        }
    }

    protected abstract String engineName();

    protected abstract String workerResource();

    protected abstract String configuredScriptPath(DocumentProcessingProperties.Ocr ocr);

    protected void configureEnvironment(Map<String, String> env, DocumentProcessingProperties.Ocr ocr) {
    }

    protected DocumentProcessingProperties properties() {
        return properties;
    }

    private WorkerClient borrowWorker() {
        ensurePoolStarted();
        WorkerClient existing = workers.poll();
        if (existing != null) {
            return existing;
        }
        if (startedWorkers.get() < Math.max(1, properties.getOcr().getWorkerPoolSize())) {
            return startWorker();
        }
        try {
            return workers.take();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + engineName() + " worker", ex);
        }
    }

    private void returnWorker(WorkerClient worker) {
        if (worker.isAlive()) {
            workers.offer(worker);
        } else {
            worker.close();
            workers.offer(startWorker());
        }
    }

    private void ensurePoolStarted() {
        if (workers != null) {
            return;
        }
        synchronized (poolLock) {
            if (workers != null) {
                return;
            }
            try {
                workerScript = resolveWorkerScript();
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to prepare " + engineName() + " worker script", ex);
            }
            int size = Math.max(1, properties.getOcr().getWorkerPoolSize());
            workers = new ArrayBlockingQueue<>(size);
            int initialSize = properties.getOcr().isEagerStartWorkerPool() ? size : 1;
            for (int i = 0; i < initialSize; i++) {
                workers.add(startWorker());
            }
        }
    }

    private WorkerClient startWorker() {
        try {
            startedWorkers.incrementAndGet();
            ProcessBuilder builder = new ProcessBuilder(command());
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            configureEnvironment(builder.environment(), properties.getOcr());
            Process process = builder.start();
            WorkerClient worker = new WorkerClient(process,
                    new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)),
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)));
            WorkerResponse ready = WorkerResponse.parse(worker.readJsonLineWithTimeout());
            if (!"ready".equals(ready.status())) {
                worker.close();
                throw new IllegalStateException(ready.message());
            }
            return worker;
        } catch (IOException ex) {
            startedWorkers.decrementAndGet();
            throw new IllegalStateException("Failed to start " + engineName() + " worker", ex);
        }
    }

    private Path copyToAsciiWorkDir(Path imagePath) {
        try {
            String configured = properties.getOcr().getOcrWorkDir();
            Path workDir = StringUtils.hasText(configured) ? Path.of(configured) : Path.of("C:/ocr-work");
            Files.createDirectories(workDir);
            Path target = workDir.resolve("ocr-" + System.nanoTime() + extension(imagePath));
            Files.copy(imagePath, target);
            return target;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to copy image into OCR work directory", ex);
        }
    }

    private String extension(Path imagePath) {
        String name = imagePath.getFileName() == null ? "" : imagePath.getFileName().toString();
        int index = name.lastIndexOf('.');
        if (index >= 0 && index < name.length() - 1) {
            String extension = name.substring(index);
            if (extension.matches("\\.[A-Za-z0-9]{1,12}")) {
                return extension;
            }
        }
        return ".png";
    }

    private List<String> command() {
        String pythonPath = properties.getOcr().getPythonPath();
        if (!StringUtils.hasText(pythonPath)) {
            pythonPath = ".venv-paddleocr/Scripts/python.exe";
        }
        List<String> command = new ArrayList<>();
        command.add(pythonPath);
        command.add(workerScript.toAbsolutePath().toString());
        return command;
    }

    private Path resolveWorkerScript() throws IOException {
        String configuredPath = configuredScriptPath(properties.getOcr());
        if (StringUtils.hasText(configuredPath)) {
            return Path.of(configuredPath);
        }
        String configuredWorkDir = properties.getOcr().getOcrWorkDir();
        Path scriptDir = (StringUtils.hasText(configuredWorkDir) ? Path.of(configuredWorkDir) : Path.of("C:/ocr-work"))
                .resolve("scripts");
        Files.createDirectories(scriptDir);
        Path tempScript = Files.createTempFile(scriptDir, engineName().replaceAll("[^A-Za-z0-9]", "-") + "-worker-", ".py");
        try (InputStream inputStream = PythonWorkerOcrService.class.getResourceAsStream(workerResource())) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing OCR worker resource: " + workerResource());
            }
            Files.write(tempScript, inputStream.readAllBytes());
        }
        tempScript.toFile().deleteOnExit();
        return tempScript;
    }

    protected void putIfText(Map<String, String> env, String key, String value) {
        if (StringUtils.hasText(value)) {
            env.put(key, value);
        }
    }

    private final class WorkerClient implements AutoCloseable {
        private final Process process;
        private final BufferedWriter writer;
        private final BufferedReader reader;
        private final AtomicBoolean closed = new AtomicBoolean();

        private WorkerClient(Process process, BufferedWriter writer, BufferedReader reader) {
            this.process = process;
            this.writer = writer;
            this.reader = reader;
        }

        private WorkerResponse request(Path imagePath) {
            try {
                writer.write(OBJECT_MAPPER.writeValueAsString(Map.of("imagePath", imagePath.toAbsolutePath().toString())));
                writer.write("\n");
                writer.flush();
                return WorkerResponse.parse(readJsonLineWithTimeout());
            } catch (IOException ex) {
                close();
                throw new IllegalStateException("Failed to communicate with " + engineName() + " worker", ex);
            }
        }

        private String readJsonLineWithTimeout() throws IOException {
            long timeoutMillis = Duration.ofSeconds(Math.max(1, properties.getOcr().getTimeoutSeconds())).toMillis();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (System.nanoTime() < deadline) {
                if (reader.ready()) {
                    String line = reader.readLine();
                    if (line == null) {
                        throw new IOException(engineName() + " worker stopped unexpectedly");
                    }
                    if (!line.startsWith("{")) {
                        continue;
                    }
                    return line;
                }
                if (!isAlive()) {
                    throw new IOException(engineName() + " worker exited with code " + process.exitValue());
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for " + engineName() + " worker", ex);
                }
            }
            close();
            throw new IOException(engineName() + " worker timed out after " + timeoutMillis + "ms");
        }

        private boolean isAlive() {
            return process.isAlive();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                writer.write("__quit__\n");
                writer.flush();
            } catch (IOException ignored) {
            }
        process.destroy();
            startedWorkers.decrementAndGet();
        }
    }

    private record WorkerResponse(String status, String text, String message, double score, double quality,
            String reason, int rotation, long durationMillis) {
        private static WorkerResponse parse(String json) {
            try {
                JsonNode node = OBJECT_MAPPER.readTree(json);
                return new WorkerResponse(text(node, "status"), text(node, "text"), text(node, "message"),
                        number(node, "score"), number(node, "quality"), text(node, "reason"),
                        (int) number(node, "rotation"), (long) number(node, "durationMillis"));
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("Invalid OCR worker response: " + json, ex);
            }
        }

        private OcrResult toOcrResult() {
            return new OcrResult(text, score, quality, reason == null || reason.isBlank() ? "ok" : reason, rotation,
                    durationMillis);
        }

        private static String text(JsonNode node, String key) {
            JsonNode value = node.get(key);
            return value == null || value.isNull() ? "" : value.asText();
        }

        private static double number(JsonNode node, String key) {
            JsonNode value = node.get(key);
            return value == null || value.isNull() ? 0.0 : value.asDouble();
        }
    }
}
