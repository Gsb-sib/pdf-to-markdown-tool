package com.example.pdfmarkdown;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 数学模型独立进程；每份文档只加载一次，关闭时释放模型内存。 */
@Service
final class FormulaRecognitionService {
    private static final Logger log = LoggerFactory.getLogger(FormulaRecognitionService.class);
    private final String python;
    private final String cache;
    private final int timeout;
    private final ObjectMapper mapper;
    private final Semaphore slot = new Semaphore(1);

    FormulaRecognitionService(@Value("${pdf-markdown.formula.python:}") String python,
            @Value("${pdf-markdown.formula.cache-dir:./data/formula-cache}") String cache,
            @Value("${pdf-markdown.formula.timeout-seconds:600}") int timeout, ObjectMapper mapper) {
        this.python = python;
        this.cache = cache;
        this.timeout = timeout;
        this.mapper = mapper;
    }

    Session open(Path job) throws IOException {
        if (python.isBlank()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "公式识别尚未配置。请安装公式依赖并设置 PDF_FORMULA_PYTHON，或取消公式增强。");
        if (!slot.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "另一份文档正在识别公式，请等待完成后重试。");
        Path script = job.resolve("formula_worker.py");
        try {
            try (InputStream input = new ClassPathResource("formula_worker.py").getInputStream()) {
                Files.copy(input, script, StandardCopyOption.REPLACE_EXISTING);
            }
            ProcessBuilder builder = new ProcessBuilder(python, "-u", script.toAbsolutePath().toString());
            builder.environment().put("PADDLE_PDX_CACHE_HOME", Path.of(cache).toAbsolutePath().toString());
            builder.environment().put("PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK", "True");
            builder.environment().putIfAbsent("PADDLE_PDX_MODEL_SOURCE", "modelscope");
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            builder.redirectError(job.resolve("formula.log").toFile());
            return new Session(builder.start(), script, job.resolve("formula-input.png"));
        } catch (IOException | RuntimeException ex) {
            slot.release();
            Files.deleteIfExists(script);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "无法启动公式识别，请检查 PDF_FORMULA_PYTHON 及 Python 依赖。", ex);
        }
    }

    final class Session implements AutoCloseable, PdfMarkdownExtractor.FormulaRecognizer {
        private final Process process;
        private final Path script;
        private final Path image;
        private final BufferedReader reader;
        private final BufferedWriter writer;
        private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "formula-response"); thread.setDaemon(true); return thread;
        });

        Session(Process process, Path script, Path image) {
            this.process = process; this.script = script; this.image = image;
            reader = process.inputReader(StandardCharsets.UTF_8);
            writer = process.outputWriter(StandardCharsets.UTF_8);
        }

        @Override public List<PdfMarkdownExtractor.Formula> recognize(BufferedImage page) throws IOException {
            ImageIO.write(page, "png", image.toFile());
            writer.write(mapper.writeValueAsString(Map.of("image", image.toAbsolutePath().toString())));
            writer.newLine(); writer.flush();
            Future<String> pending = executor.submit(() -> {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("__FORMULA_JSON__")) return line.substring(16);
                }
                throw new EOFException("公式进程提前退出");
            });
            try {
                String payload = pending.get(timeout, TimeUnit.SECONDS);
                var response = mapper.readTree(payload);
                Files.writeString(image.resolveSibling("formula-regions.json"), payload, StandardCharsets.UTF_8);
                if (response.has("error")) throw new IOException(response.get("error").asText());
                if (!response.path("regions").isArray()) throw new IOException("公式响应缺少区域数组");
                List<PdfMarkdownExtractor.Formula> result = new ArrayList<>();
                for (var region : response.get("regions")) {
                    var box = region.path("box");
                    if (box.size() != 4 || !region.path("latex").isTextual()) throw new IOException("公式区域格式错误");
                    double x1=box.get(0).asDouble(), y1=box.get(1).asDouble();
                    double x2=box.get(2).asDouble(), y2=box.get(3).asDouble();
                    if (!Double.isFinite(x1+y1+x2+y2) || x1<0 || y1<0 || x2<=x1 || y2<=y1
                            || x2>page.getWidth()+1 || y2>page.getHeight()+1) throw new IOException("公式区域超出页面范围");
                    String latex = region.get("latex").asText().strip();
                    if (latex.isBlank() || latex.indexOf('\0')>=0) throw new IOException("公式内容无效");
                    result.add(new PdfMarkdownExtractor.Formula(x1/page.getWidth(), y1/page.getHeight(),
                            x2/page.getWidth(), y2/page.getHeight(), latex));
                }
                return List.copyOf(result);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "公式识别被中断。", ex);
            } catch (ExecutionException | TimeoutException | IOException ex) {
                pending.cancel(true);
                // 失败任务目录随后会清理，先将模型诊断保留在服务日志中。
                log.warn("公式识别失败：{}", ex.toString());
                Path errorLog = image.resolveSibling("formula.log");
                try (var lines = Files.lines(errorLog, StandardCharsets.UTF_8)) {
                    var tail = new ArrayDeque<String>();
                    lines.forEach(line -> { if (tail.size() == 20) tail.removeFirst(); tail.addLast(line); });
                    log.warn("公式进程日志末尾：\n{}", String.join("\n", tail));
                } catch (IOException ignored) { }
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "公式识别失败或超时，请检查模型、依赖及服务日志；本次未生成结果。", ex);
            } finally {
                Files.deleteIfExists(image);
            }
        }

        @Override public void close() {
            // Windows 虚拟环境的 python.exe 可能是启动器，同时终止其推理子进程。
            process.descendants().forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
            process.destroy();
            if (process.isAlive()) process.destroyForcibly();
            executor.shutdownNow();
            try { reader.close(); writer.close(); } catch (IOException ignored) { }
            try { Files.deleteIfExists(image); Files.deleteIfExists(script); } catch (IOException ignored) { }
            slot.release();
        }
    }
}
