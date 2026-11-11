package com.example.pdfmarkdown;

import com.example.document.autoconfigure.DocumentProcessingProperties;
import com.example.document.ocr.OcrService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.BeansException;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PdfMarkdownService {
    private static final Logger log = LoggerFactory.getLogger(PdfMarkdownService.class);
    private final Path outputDir;
    private final DocumentProcessingProperties properties;
    private final OcrService ocr;
    private final ObjectMapper mapper;
    private final FormulaRecognitionService formulas;

    public PdfMarkdownService(@Value("${pdf-markdown.output-dir:./data/conversions}") String outputDir,
            DocumentProcessingProperties properties, @Lazy OcrService ocr, ObjectMapper mapper,
            FormulaRecognitionService formulas) {
        this.outputDir = Path.of(outputDir).toAbsolutePath().normalize();
        this.properties = properties;
        this.ocr = ocr;
        this.mapper = mapper;
        this.formulas = formulas;
    }

    public Conversion convert(MultipartFile file) {
        return convert(file, false);
    }

    public Conversion convert(MultipartFile file, boolean formulaEnhanced) {
        return convert(file, formulaEnhanced, false);
    }

    public Conversion convert(MultipartFile file, boolean formulaEnhanced, boolean exportFormulaImages) {
        return convert(file, formulaEnhanced, exportFormulaImages, false, true);
    }

    public Conversion convert(MultipartFile file, boolean formulaEnhanced, boolean exportFormulaImages,
            boolean formulaPlaceholders, boolean includeImages) {
        if (formulaEnhanced && formulaPlaceholders) throw badRequest("公式留空和公式增强不能同时开启。");
        if (file.isEmpty()) {
            throw badRequest("请选择一个非空的 PDF 文件。");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw badRequest("仅支持 PDF 文件。");
        }
        long started = System.nanoTime();
        String id = UUID.randomUUID().toString();
        Path jobDir = outputDir.resolve(id);
        Path input = jobDir.resolve("source.pdf");
        boolean completed = false;
        try {
            Files.createDirectories(jobDir.resolve("assets"));
            try (InputStream stream = file.getInputStream()) {
                Files.copy(stream, input);
            }
            int pageCount = validatePdf(input);
            DocumentProcessingProperties jobProperties = new DocumentProcessingProperties();
            // 每个任务使用独立的图片目录，不修改原知识库的共享配置。
            jobProperties.setImageOutputDir(jobDir.resolve("assets").toString());
            jobProperties.setImageUrlPrefix("/assets");
            jobProperties.setPdfScanImageRatioThreshold(properties.getPdfScanImageRatioThreshold());
            jobProperties.setPdfScanPageRatioThreshold(properties.getPdfScanPageRatioThreshold());
            jobProperties.setOcr(properties.getOcr());
            PdfMarkdownExtractor.Extraction result;
            try (var session = formulaEnhanced ? formulas.open(jobDir) : null) {
                result = new PdfMarkdownExtractor(jobProperties, ocr).extract(input, jobDir.resolve("assets"), session,
                        formulaEnhanced && exportFormulaImages, formulaPlaceholders, includeImages);
            }
            String markdown = result.markdown().replace("\r\n", "\n");
            for (Path image : result.images()) {
                String filename = image.getFileName().toString();
                markdown = markdown.replace("![](/assets/" + filename + ")", "![](assets/" + filename + ")");
            }
            if (markdown.isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "未提取到文字或图片，请检查 PDF 是否为空白或无法识别。");
            }
            UnicodeText repairedText = repairUnicode(markdown.stripTrailing() + "\n");
            UnicodeText repairedName = repairUnicode(originalName);
            markdown = repairedText.text();
            String filename = safeBaseName(repairedName.text()) + ".md";
            Path markdownFile = jobDir.resolve("document.md");
            Files.writeString(markdownFile, markdown, StandardCharsets.UTF_8);
            writeArchive(jobDir, filename, result.images());
            List<String> warnings = new ArrayList<>();
            if (formulaPlaceholders) warnings.add("已为 " + result.formulaCount()
                    + " 行明显的独立公式留空，标记含 PDF 页码；未加载公式模型。行内公式、复杂分式或 OCR 未正确识别的公式可能保留原文，请对照 PDF 手动补全。为保护正文，含普通单词或中文的行不会自动删除。");
            if (formulaEnhanced) warnings.add("公式增强检测到 " + result.formulaCount()
                    + " 个区域并尝试转换，待复核区域会保留原文。模型可能漏检或误识别，请对照"
                    + (exportFormulaImages ? " ZIP 中的公式原图" : "原 PDF") + "核验；未检测到不代表没有公式。");
            int unicodeMarkers = repairedText.markers() + repairedName.markers();
            if (unicodeMarkers > 0) {
                log.warn("PDF conversion {} contains {} invalid or unmapped Unicode characters", id, unicodeMarkers);
                warnings.add("提取文字或文件名包含 " + unicodeMarkers
                        + " 个异常 Unicode/无法映射字符，已以 � 标记，请对照原 PDF 核验。");
            }
            if (!result.images().isEmpty()) {
                warnings.add("文档包含图片。下载 ZIP 并完整解压，可保留 Markdown 中的图片引用。");
            }
            if (markdown.contains("[DIFFICULT_OCR_PAGE")) {
                warnings.add("部分扫描页识别质量较低或识别失败，内容中已保留标记，请对照原 PDF 核验。");
            }
            if (markdown.contains("[FORMULA_REVIEW_REQUIRED")) {
                warnings.add("部分公式检测框包含正文，已保留原始文字层并标记待复核；这些区域未自动替换为 LaTeX。");
            }
            Conversion conversion = new Conversion(id, filename, pageCount, result.usedOcr(),
                    result.images().size(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    markdown, List.copyOf(warnings), "/api/conversions/" + id + "/markdown",
                    "/api/conversions/" + id + "/archive", formulaEnhanced, result.formulaCount(), formulaPlaceholders, includeImages);
            mapper.writeValue(jobDir.resolve("result.json").toFile(), conversion);
            completed = true;
            return conversion;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (IllegalStateException | BeansException ex) {
            log.warn("PDF conversion failed: {}", id, ex);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "转换失败。若为扫描件，请检查 OCR 模型和配置；也可查看服务日志。", ex);
        } catch (IOException ex) {
            log.error("Failed to read, convert or save PDF: {}", id, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "文件读取、转换或保存失败，请查看服务日志中的具体原因。", ex);
        } finally {
            try {
                Files.deleteIfExists(input);
            } catch (IOException ex) {
                log.warn("Could not remove temporary PDF: {}", id, ex);
            }
            if (!completed) {
                removeFailedJob(jobDir);
            }
        }
    }

    private int validatePdf(Path input) {
        try (PDDocument document = Loader.loadPDF(input.toFile())) {
            int pages = document.getNumberOfPages();
            if (pages == 0) {
                throw badRequest("PDF 没有可转换的页面。");
            }
            if (!document.getCurrentAccessPermission().canExtractContent()) {
                throw badRequest("此 PDF 限制内容提取，请使用允许提取的 PDF。");
            }
            return pages;
        } catch (InvalidPasswordException ex) {
            throw badRequest("此 PDF 需要密码，请先解密后上传。");
        } catch (IOException ex) {
            throw badRequest("无法读取 PDF，文件可能已损坏或并非有效的 PDF。");
        }
    }

    private void writeArchive(Path jobDir, String markdownName, List<Path> images) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jobDir.resolve("document.zip")),
                StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(markdownName));
            Files.copy(jobDir.resolve("document.md"), zip);
            zip.closeEntry();
            for (Path image : images) {
                zip.putNextEntry(new ZipEntry("assets/" + image.getFileName()));
                Files.copy(image, zip);
                zip.closeEntry();
            }
        }
    }

    public Artifact artifact(String id, boolean archive) throws IOException {
        Path jobDir = jobDirectory(id);
        Path metadata = jobDir.resolve("result.json");
        if (!Files.isRegularFile(metadata)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "转换结果不存在，请重新上传文件。");
        }
        Conversion result = mapper.readValue(metadata.toFile(), Conversion.class);
        Path file = jobDir.resolve(archive ? "document.zip" : "document.md");
        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "下载文件不存在，请重新转换。");
        }
        String filename = archive ? result.filename().replaceFirst("\\.md$", ".zip") : result.filename();
        return new Artifact(file, filename);
    }

    private Path jobDirectory(String id) {
        try {
            if (!UUID.fromString(id).toString().equals(id)) {
                throw new IllegalArgumentException();
            }
            return outputDir.resolve(id);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "转换结果不存在。");
        }
    }

    private record UnicodeText(String text, int markers) {}

    private static UnicodeText repairUnicode(String text) {
        StringBuilder repaired = null;
        int markers = 0;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (Character.isHighSurrogate(current) && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                // 合法的 emoji、扩展汉字及数学字符必须完整保留。
                i++;
            } else if (Character.isSurrogate(current)) {
                // 孤立代理项无法编码为 UTF-8，显式标记而不是丢弃或静默转成问号。
                if (repaired == null) repaired = new StringBuilder(text);
                repaired.setCharAt(i, '\uFFFD');
                markers++;
            } else if (current == '\uFFFD') {
                // PDFBox 可能已为坏字体映射写入替代字符，也需要提示用户核验。
                markers++;
            }
        }
        return new UnicodeText(repaired == null ? text : repaired.toString(), markers);
    }

    private static String safeBaseName(String originalName) {
        String basename = originalName.replace('\\', '/');
        basename = basename.substring(basename.lastIndexOf('/') + 1);
        basename = basename.substring(0, basename.length() - 4)
                .replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]", "_").strip();
        basename = basename.replaceAll("^[. ]+|[. ]+$", "");
        if (basename.isBlank()) {
            return "document";
        }
        // 按 Unicode 码点截断，避免截断中文或其他补充字符。
        int count = Math.min(80, basename.codePointCount(0, basename.length()));
        return basename.substring(0, basename.offsetByCodePoints(0, count));
    }

    private void removeFailedJob(Path jobDir) {
        if (!jobDir.normalize().getParent().equals(outputDir)) {
            return;
        }
        if (!Files.exists(jobDir)) {
            return;
        }
        try (var paths = Files.walk(jobDir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ex) {
            log.warn("Could not clean failed conversion directory", ex);
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record Conversion(String id, String filename, int pageCount, boolean scannedPdf, int imageCount,
            long durationMillis, String markdown, List<String> warnings, String markdownUrl, String archiveUrl,
            boolean formulaEnhanced, int formulaCount, boolean formulaPlaceholders, boolean includeImages) {}

    public record Artifact(Path path, String filename) {}
}
