package com.example.document.support;

import com.example.document.DocumentProcessResult;
import com.example.document.DocumentProcessingService;
import com.example.document.OutputFormat;
import com.example.document.SourceType;
import com.example.document.autoconfigure.DocumentProcessingProperties;
import com.example.document.ocr.OcrService;
import com.example.document.ocr.OcrService.OcrResult;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.IRunElement;
import org.apache.poi.xwpf.usermodel.XWPFSDT;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

public class DefaultDocumentProcessingService implements DocumentProcessingService {
    private static final double LANDSCAPE_ROTATION_RETRY_RATIO = 1.20;
    private final DocumentProcessingProperties properties;
    private final OcrService ocrService;
    private final ImageStorage imageStorage;

    public DefaultDocumentProcessingService(DocumentProcessingProperties properties, OcrService ocrService) {
        this.properties = properties;
        this.ocrService = ocrService;
        this.imageStorage = new ImageStorage(properties);
    }

    @Override
    public DocumentProcessResult recognizeImage(Path imagePath, OutputFormat format) {
        OcrService ocr = requireOcrService();
        ContentBuilder builder = new ContentBuilder();
        OcrResult result = recognizeBestWithRotation(ocr, imagePath);
        OcrTextQuality.Assessment assessment = OcrTextQuality.assess(result.text());
        if (assessment.difficult() || result.lowConfidence(properties.getOcr().getLowConfidenceThreshold())
                || result.rotation() != 0 || result.likelyGarbled()) {
            builder.appendText(difficultMarker(0, detailedReason(assessment, result), result.durationMillis(), result)
                    + difficultBody(result), format == OutputFormat.HTML);
        } else {
            builder.appendText(result.text(), format == OutputFormat.HTML);
        }
        return new DocumentProcessResult(SourceType.IMAGE, format, builder.build(), List.of(), false);
    }

    @Override
    public DocumentProcessResult readWord(Path wordPath, OutputFormat format) {
        if (isLegacyDoc(wordPath)) {
            return readLegacyWord(wordPath, format);
        }
        try (InputStream inputStream = Files.newInputStream(wordPath);
                XWPFDocument document = new XWPFDocument(inputStream)) {
            ContentBuilder builder = new ContentBuilder();
            List<Path> savedImages = new ArrayList<>();
            for (IBodyElement element : document.getBodyElements()) {
                appendBodyElement(element, builder, savedImages, format == OutputFormat.HTML);
            }
            appendExtractorFallback(document, builder, format == OutputFormat.HTML);
            appendDocxXmlFallback(wordPath, builder, format == OutputFormat.HTML);
            return new DocumentProcessResult(SourceType.WORD, format, builder.build(), savedImages, false);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read Word document: " + wordPath, ex);
        }
    }

    private DocumentProcessResult readLegacyWord(Path wordPath, OutputFormat format) {
        try (InputStream inputStream = Files.newInputStream(wordPath);
                HWPFDocument document = new HWPFDocument(inputStream);
                WordExtractor extractor = new WordExtractor(document)) {
            ContentBuilder builder = new ContentBuilder();
            for (String paragraph : extractor.getParagraphText()) {
                builder.appendText(cleanWordText(paragraph), format == OutputFormat.HTML);
            }
            return new DocumentProcessResult(SourceType.WORD, format, builder.build(), List.of(), false);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read legacy Word document: " + wordPath, ex);
        }
    }

    private boolean isLegacyDoc(Path wordPath) {
        String filename = wordPath.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (filename.endsWith(".doc") && !filename.endsWith(".docx")) {
            return true;
        }
        try (InputStream inputStream = Files.newInputStream(wordPath);
                PushbackInputStream pushback = new PushbackInputStream(inputStream, 8)) {
            byte[] header = pushback.readNBytes(8);
            return header.length >= 8
                    && (header[0] & 0xff) == 0xd0
                    && (header[1] & 0xff) == 0xcf
                    && (header[2] & 0xff) == 0x11
                    && (header[3] & 0xff) == 0xe0
                    && (header[4] & 0xff) == 0xa1
                    && (header[5] & 0xff) == 0xb1
                    && (header[6] & 0xff) == 0x1a
                    && (header[7] & 0xff) == 0xe1;
        } catch (IOException ex) {
            return false;
        }
    }

    @Override
    public DocumentProcessResult readPdf(Path pdfPath, OutputFormat format) {
        try {
            byte[] bytes = Files.readAllBytes(pdfPath);
            try (PDDocument document = Loader.loadPDF(bytes)) {
                boolean scannedPdf = new PdfScanDetector(properties.getPdfScanImageRatioThreshold(),
                        properties.getPdfScanPageRatioThreshold()).isScanned(document);
                List<Path> savedImages = new ArrayList<>();
                String content = scannedPdf ? readScannedPdf(bytes, document.getNumberOfPages(), format)
                        : readTextPdf(document, format, savedImages);
                return new DocumentProcessResult(SourceType.PDF, format, content, savedImages, scannedPdf);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read PDF document: " + pdfPath, ex);
        }
    }

    private String readTextPdf(PDDocument document, OutputFormat format, List<Path> savedImages) {
        ContentBuilder builder = new ContentBuilder();
        try {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
                int pageNumber = pageIndex + 1;
                stripper.setStartPage(pageNumber);
                stripper.setEndPage(pageNumber);
                for (String paragraph : stripper.getText(document).split("\\R{2,}")) {
                    builder.appendText(paragraph, format == OutputFormat.HTML);
                }
                List<Path> pageImages = new PdfImageExtractor(document.getPage(pageIndex), imageStorage)
                        .extract(document.getPage(pageIndex));
                for (Path imagePath : pageImages) {
                    savedImages.add(imagePath);
                    builder.appendImage(imageStorage.toUrl(imagePath), format == OutputFormat.HTML);
                }
            }
            return builder.build();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to extract PDF text", ex);
        }
    }

    private String readScannedPdf(byte[] pdfBytes, int pageCount, OutputFormat format) {
        OcrService ocr = requireOcrService();
        int parallelism = Math.max(1, Math.min(properties.getOcr().getParallelism(), pageCount));
        if (parallelism == 1 || !ocr.isThreadSafe()) {
            return readScannedPdfSequentially(pdfBytes, ocr, pageCount, format);
        }
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, ocrThreadFactory());
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < pageCount; i++) {
                int pageIndex = i;
                futures.add(executor.submit(ocrTask(pdfBytes, ocr, pageIndex)));
            }
            ContentBuilder builder = new ContentBuilder();
            for (Future<String> future : futures) {
                builder.appendText(future.get(), format == OutputFormat.HTML);
            }
            return builder.build();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while OCR processing scanned PDF", ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("Unexpected OCR executor failure", ex.getCause());
        } finally {
            executor.shutdownNow();
        }
    }

    private ThreadFactory ocrThreadFactory() {
        return new ThreadFactory() {
            private final java.util.concurrent.atomic.AtomicInteger index = new java.util.concurrent.atomic.AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "document-pdf-ocr-worker-" + index.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    private String readScannedPdfSequentially(byte[] pdfBytes, OcrService ocr, int pageCount, OutputFormat format) {
        ContentBuilder builder = new ContentBuilder();
        for (int i = 0; i < pageCount; i++) {
            builder.appendText(runOcrPage(pdfBytes, ocr, i), format == OutputFormat.HTML);
        }
        return builder.build();
    }

    private Callable<String> ocrTask(byte[] pdfBytes, OcrService ocr, int pageIndex) {
        return () -> runOcrPage(pdfBytes, ocr, pageIndex);
    }

    private String runOcrPage(byte[] pdfBytes, OcrService ocr, int pageIndex) {
        try {
            try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImageWithDPI(pageIndex, properties.getOcr().getDpi(), ImageType.GRAY);
            image = resizeForOcr(image);
            long started = System.nanoTime();
            OcrResult result = recognizeBestWithRotation(ocr, image);
            String text = result.text();
            long durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            OcrTextQuality.Assessment assessment = OcrTextQuality.assess(text);
            String detailReason = detailedReason(assessment, result);
            if (assessment.difficult() || result.lowConfidence(properties.getOcr().getLowConfidenceThreshold())
                    || result.rotation() != 0 || result.likelyGarbled() || !"ok".equalsIgnoreCase(result.reason())) {
                return difficultMarker(pageIndex, detailReason, durationMillis, result) + difficultBody(result);
            }
            return nullToBlank(text);
            }
        } catch (Throwable ex) {
            return difficultMarker(pageIndex, "ocr-or-render-failed-" + ex.getClass().getSimpleName(), -1,
                    new OcrResult("", 0.0, 0.0, "failed", 0, -1));
        }
    }

    private BufferedImage resizeForOcr(BufferedImage source) {
        int maxWidth = properties.getOcr().getMaxRenderedPageWidth();
        if (maxWidth <= 0 || source.getWidth() <= maxWidth) {
            return source;
        }
        int targetWidth = maxWidth;
        int targetHeight = Math.max(1, (int) Math.round(source.getHeight() * (targetWidth / (double) source.getWidth())));
        int imageType = source.getType() == 0 ? BufferedImage.TYPE_BYTE_GRAY : source.getType();
        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, imageType);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        graphics.dispose();
        return scaled;
    }

    private OcrResult recognizeBestWithRotation(OcrService ocr, Path imagePath) {
        try {
            BufferedImage image = javax.imageio.ImageIO.read(imagePath.toFile());
            if (image != null) {
                return recognizeBestWithRotation(ocr, image);
            }
        } catch (IOException ignored) {
            // Fall back to direct OCR path if Java image decoding cannot read it.
        }
        return ocr.recognizeDetailed(imagePath);
    }

    private OcrResult recognizeBestWithRotation(OcrService ocr, BufferedImage image) {
        OcrResult best = ocr.recognizeDetailed(image);
        if (!shouldRetryRotations(best, image)) {
            return best;
        }
        for (int degrees : List.of(90, 270)) {
            OcrResult candidate = withRotation(ocr.recognizeDetailed(rotate(image, degrees)), degrees);
            if (ocrRank(candidate) > ocrRank(best)) {
                best = candidate;
            }
        }
        return best;
    }

    private boolean shouldRetryRotations(OcrResult result, BufferedImage image) {
        OcrTextQuality.Assessment assessment = OcrTextQuality.assess(result.text());
        boolean landscapePage = image.getWidth() > image.getHeight() * LANDSCAPE_ROTATION_RETRY_RATIO;
        return properties.getOcr().isEnableRotationRetry()
                && (landscapePage
                || assessment.difficult()
                || result.lowConfidence(properties.getOcr().getLowConfidenceThreshold())
                || result.likelyGarbled()
                || result.rotation() != 0
                || !"ok".equalsIgnoreCase(result.reason()));
    }

    private double ocrRank(OcrResult result) {
        OcrTextQuality.Assessment assessment = OcrTextQuality.assess(result.text());
        String compact = nullToBlank(result.text()).replaceAll("\\s+", "");
        long useful = compact.chars().filter(ch -> Character.isLetterOrDigit(ch)
                || Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN).count();
        double usefulRatio = compact.isEmpty() ? 0.0 : useful / (double) compact.length();
        double rank = result.quality() + result.score() + usefulRatio;
        if (!assessment.difficult()) {
            rank += 2.0;
        }
        if (result.likelyGarbled()) {
            rank -= 2.0;
        }
        return rank;
    }

    private OcrResult withRotation(OcrResult result, int degrees) {
        return new OcrResult(result.text(), result.score(), result.quality(), result.reason(), degrees,
                result.durationMillis());
    }

    private BufferedImage rotate(BufferedImage source, int degrees) {
        double radians = Math.toRadians(degrees);
        int targetWidth = degrees % 180 == 0 ? source.getWidth() : source.getHeight();
        int targetHeight = degrees % 180 == 0 ? source.getHeight() : source.getWidth();
        BufferedImage rotated = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rotated.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        AffineTransform transform = new AffineTransform();
        transform.translate(targetWidth / 2.0, targetHeight / 2.0);
        transform.rotate(radians);
        transform.translate(-source.getWidth() / 2.0, -source.getHeight() / 2.0);
        graphics.drawImage(source, transform, null);
        graphics.dispose();
        return rotated;
    }

    private String detailedReason(OcrTextQuality.Assessment assessment, OcrResult result) {
        List<String> reasons = new ArrayList<>();
        if (assessment.difficult()) {
            reasons.add(assessment.reason());
        }
        if (result.lowConfidence(properties.getOcr().getLowConfidenceThreshold())) {
            reasons.add("low-confidence-score-" + String.format(java.util.Locale.ROOT, "%.3f", result.score()));
        }
        if (result.likelyGarbled()) {
            reasons.add("garbled-question-mark-heavy-result");
        }
        if (result.rotation() != 0) {
            reasons.add("rotated-table-or-landscape-page-best-at-" + result.rotation() + "deg");
        }
        if (result.reason() != null && !result.reason().isBlank() && !"ok".equalsIgnoreCase(result.reason())) {
            reasons.add(result.reason());
        }
        return reasons.isEmpty() ? "ok" : String.join(";", reasons);
    }

    private String difficultMarker(int pageIndex, String reason, long durationMillis, OcrResult result) {
        String duration = durationMillis >= 0 ? ", durationMillis=" + durationMillis : "";
        String metrics = ", score=" + String.format(java.util.Locale.ROOT, "%.3f", result.score())
                + ", quality=" + String.format(java.util.Locale.ROOT, "%.3f", result.quality())
                + ", rotation=" + result.rotation();
        return "[DIFFICULT_OCR_PAGE page=" + (pageIndex + 1) + ", reason=" + reason + duration + metrics
                + ", note=handwriting-signature-stamp-rotated-table-or-low-quality-image-may-need-manual-review]";
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private String difficultBody(OcrResult result) {
        if (result.likelyGarbled()) {
            return "\n[OCR_TEXT_SUPPRESSED reason=garbled-question-mark-heavy-result, note=original OCR text was not returned as normal content]";
        }
        return "\n" + nullToBlank(result.text());
    }

    private OcrService requireOcrService() {
        if (ocrService == null) {
            throw new IllegalStateException("OCR service is not available. Enable document.processing.ocr or provide an OcrService bean.");
        }
        return ocrService;
    }

    private void appendBodyElement(IBodyElement element, ContentBuilder builder, List<Path> savedImages, boolean html) {
        if (element instanceof XWPFParagraph paragraph) {
            appendParagraph(paragraph, builder, savedImages, html);
        } else if (element instanceof XWPFTable table) {
            appendTable(table, builder, savedImages, html);
        } else if (element instanceof XWPFSDT sdt) {
            builder.appendText(sdt.getContent().getText(), html);
        }
    }

    private void appendParagraph(XWPFParagraph paragraph, ContentBuilder builder, List<Path> savedImages, boolean html) {
        StringBuilder text = new StringBuilder();
        for (IRunElement runElement : paragraph.getIRuns()) {
            if (runElement instanceof XWPFRun run) {
                text.append(run.text());
                for (XWPFPicture picture : run.getEmbeddedPictures()) {
                    if (!text.isEmpty()) {
                        builder.appendText(text.toString(), html);
                        text.setLength(0);
                    }
                    XWPFPictureData pictureData = picture.getPictureData();
                    Path savedImage = imageStorage.save(pictureData);
                    savedImages.add(savedImage);
                    builder.appendImage(imageStorage.toUrl(savedImage), html);
                }
            } else if (runElement instanceof XWPFSDT sdt) {
                text.append(sdt.getContent().getText());
            }
        }
        if (text.isEmpty() && paragraph.getText() != null && !paragraph.getText().isBlank()) {
            text.append(paragraph.getText());
        }
        builder.appendText(text.toString(), html);
    }

    private void appendTable(XWPFTable table, ContentBuilder builder, List<Path> savedImages, boolean html) {
        List<List<String>> rows = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(cleanWordText(cell.getText()));
                for (IBodyElement element : cell.getBodyElements()) {
                    collectImagesOnly(element, builder, savedImages, html);
                }
            }
            rows.add(cells);
        }
        builder.appendTable(rows, html);
    }

    private void collectImagesOnly(IBodyElement element, ContentBuilder builder, List<Path> savedImages, boolean html) {
        if (element instanceof XWPFParagraph paragraph) {
            for (IRunElement runElement : paragraph.getIRuns()) {
                if (runElement instanceof XWPFRun run) {
                    for (XWPFPicture picture : run.getEmbeddedPictures()) {
                        XWPFPictureData pictureData = picture.getPictureData();
                        Path savedImage = imageStorage.save(pictureData);
                        savedImages.add(savedImage);
                        builder.appendImage(imageStorage.toUrl(savedImage), html);
                    }
                }
            }
        } else if (element instanceof XWPFTable table) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    for (IBodyElement nestedElement : cell.getBodyElements()) {
                        collectImagesOnly(nestedElement, builder, savedImages, html);
                    }
                }
            }
        }
    }

    private String cleanWordText(String text) {
        return text == null ? "" : text.replace('\u0007', ' ').replace('\r', ' ').strip();
    }

    private void appendExtractorFallback(XWPFDocument document, ContentBuilder builder, boolean html) {
        if (builder.hasText()) {
            return;
        }
        try (XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            for (String paragraph : extractor.getText().split("\\R+")) {
                builder.appendText(cleanWordText(paragraph), html);
            }
        } catch (Exception ex) {
            // Structured extraction already ran; ignore extractor-only failures.
        }
    }

    private void appendDocxXmlFallback(Path wordPath, ContentBuilder builder, boolean html) {
        if (builder.hasText()) {
            return;
        }
        try (ZipFile zipFile = new ZipFile(wordPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith("word/") || !name.endsWith(".xml")) {
                    continue;
                }
                String xml = new String(zipFile.getInputStream(entry).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                String text = extractWordTextNodes(xml);
                builder.appendText(text, html);
            }
        } catch (IOException ex) {
            // Structured extraction already ran; ignore raw XML fallback failures.
        }
    }

    private String extractWordTextNodes(String xml) {
        StringBuilder text = new StringBuilder();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("<w:t(?:\\s[^>]*)?>(.*?)</w:t>", java.util.regex.Pattern.DOTALL)
                .matcher(xml);
        while (matcher.find()) {
            String nodeText = unescapeXml(matcher.group(1));
            if (!nodeText.isBlank()) {
                if (!text.isEmpty()) {
                    text.append(' ');
                }
                text.append(nodeText.strip());
            }
        }
        return text.toString();
    }

    private String unescapeXml(String value) {
        return value.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }
}
