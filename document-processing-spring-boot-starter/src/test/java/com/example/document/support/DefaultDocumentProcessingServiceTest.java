package com.example.document.support;

import com.example.document.DocumentProcessResult;
import com.example.document.OutputFormat;
import com.example.document.SourceType;
import com.example.document.autoconfigure.DocumentProcessingProperties;
import com.example.document.ocr.OcrService;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultDocumentProcessingServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void recognizesImageAsHtmlWithElementId() throws IOException {
        DefaultDocumentProcessingService service = serviceWithOcr("image ocr text");
        Path imagePath = tempDir.resolve("ocr.png");
        ImageIO.write(sampleImage(), "png", imagePath.toFile());

        DocumentProcessResult result = service.recognizeImage(imagePath, OutputFormat.HTML);

        assertThat(result.getSourceType()).isEqualTo(SourceType.IMAGE);
        assertThat(result.getFormat()).isEqualTo(OutputFormat.HTML);
        assertThat(result.getContent()).isEqualTo("<p id=\"doc-p-1\">image ocr text</p>");
        assertThat(result.getSavedImages()).isEmpty();
        assertThat(result.isScannedPdf()).isFalse();
    }

    @Test
    void recognizesImageAsMarkdown() throws IOException {
        DefaultDocumentProcessingService service = serviceWithOcr("image ocr text");
        Path imagePath = tempDir.resolve("ocr.png");
        ImageIO.write(sampleImage(), "png", imagePath.toFile());

        DocumentProcessResult result = service.recognizeImage(imagePath, OutputFormat.MARKDOWN);

        assertThat(result.getContent()).isEqualTo("image ocr text");
    }

    @Test
    void requiresOcrServiceForImageRecognition() throws IOException {
        DefaultDocumentProcessingService service = new DefaultDocumentProcessingService(properties(), null);
        Path imagePath = tempDir.resolve("ocr.png");
        ImageIO.write(sampleImage(), "png", imagePath.toFile());

        assertThatThrownBy(() -> service.recognizeImage(imagePath, OutputFormat.HTML))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OCR service is not available");
    }

    @Test
    void readsWordAsHtmlAndSavesEmbeddedImages() throws Exception {
        DefaultDocumentProcessingService service = serviceWithOcr("unused");
        Path wordPath = createWordDocument();

        DocumentProcessResult result = service.readWord(wordPath, OutputFormat.HTML);

        assertThat(result.getSourceType()).isEqualTo(SourceType.WORD);
        assertThat(result.getContent()).contains("<p id=\"doc-p-1\">Before image</p>");
        assertThat(result.getContent()).contains("<img id=\"doc-img-1\" src=\"/assets/");
        assertThat(result.getContent()).contains("After image</p>");
        assertThat(result.getSavedImages()).hasSize(1);
        assertThat(result.getSavedImages().get(0)).exists();
    }

    @Test
    void readsWordAsMarkdownAndSavesEmbeddedImages() throws Exception {
        DefaultDocumentProcessingService service = serviceWithOcr("unused");
        Path wordPath = createWordDocument();

        DocumentProcessResult result = service.readWord(wordPath, OutputFormat.MARKDOWN);

        assertThat(result.getContent()).contains("Before image");
        assertThat(result.getContent()).contains("![](/assets/");
        assertThat(result.getContent()).contains("After image");
        assertThat(result.getSavedImages()).hasSize(1);
        assertThat(result.getSavedImages().get(0)).exists();
    }

    @Test
    void readsWordTableAsHtmlTable() throws Exception {
        DefaultDocumentProcessingService service = serviceWithOcr("unused");
        Path wordPath = createWordTableDocument();

        DocumentProcessResult result = service.readWord(wordPath, OutputFormat.HTML);

        assertThat(result.getContent()).contains("<table id=\"doc-table-1\">");
        assertThat(result.getContent()).contains("<td>参数</td>");
        assertThat(result.getContent()).contains("<td>中文表格内容</td>");
    }

    @Test
    void readsWordTableAsMarkdownTable() throws Exception {
        DefaultDocumentProcessingService service = serviceWithOcr("unused");
        Path wordPath = createWordTableDocument();

        DocumentProcessResult result = service.readWord(wordPath, OutputFormat.MARKDOWN);

        assertThat(result.getContent()).contains("| 参数 | 值 |");
        assertThat(result.getContent()).contains("| --- | --- |");
        assertThat(result.getContent()).contains("| 名称 | 中文表格内容 |");
    }

    @Test
    void readsTextPdfAsHtmlAndDoesNotMarkAsScanned() throws IOException {
        DefaultDocumentProcessingService service = serviceWithOcr("unused");
        Path pdfPath = createTextPdf();

        DocumentProcessResult result = service.readPdf(pdfPath, OutputFormat.HTML);

        assertThat(result.getSourceType()).isEqualTo(SourceType.PDF);
        assertThat(result.isScannedPdf()).isFalse();
        assertThat(result.getContent()).contains("<p id=\"doc-p-1\">Hello PDF text</p>");
    }

    @Test
    void extractsImagesFromTextPdfAsMarkdown() throws IOException {
        DefaultDocumentProcessingService service = serviceWithOcr("unused");
        Path pdfPath = createTextPdfWithImage();

        DocumentProcessResult result = service.readPdf(pdfPath, OutputFormat.MARKDOWN);

        assertThat(result.isScannedPdf()).isFalse();
        assertThat(result.getContent()).contains("PDF with image");
        assertThat(result.getContent()).contains("![](/assets/");
        assertThat(result.getSavedImages()).hasSize(1);
        assertThat(result.getSavedImages().get(0)).exists();
    }

    @Test
    void readsScannedPdfWithOcr() throws IOException {
        DefaultDocumentProcessingService service = serviceWithOcr("scanned page text");
        Path pdfPath = createScannedPdf();

        DocumentProcessResult result = service.readPdf(pdfPath, OutputFormat.HTML);

        assertThat(result.isScannedPdf()).isTrue();
        assertThat(result.getContent()).isEqualTo("<p id=\"doc-p-1\">scanned page text</p>");
        assertThat(result.getSavedImages()).isEmpty();
    }

    @Test
    void scannedPdfRequiresOcrService() throws IOException {
        DefaultDocumentProcessingService service = new DefaultDocumentProcessingService(properties(), null);
        Path pdfPath = createScannedPdf();

        assertThatThrownBy(() -> service.readPdf(pdfPath, OutputFormat.MARKDOWN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OCR service is not available");
    }

    @Test
    void retriesRotationForLandscapeImagesEvenWhenInitialOcrLooksUsable() throws IOException {
        DefaultDocumentProcessingService service = new DefaultDocumentProcessingService(properties(), new LandscapeRotationOcrService());
        Path imagePath = tempDir.resolve("landscape-table.png");
        ImageIO.write(sampleLandscapeImage(), "png", imagePath.toFile());

        DocumentProcessResult result = service.recognizeImage(imagePath, OutputFormat.MARKDOWN);

        assertThat(result.getContent()).contains("rotation=90");
        assertThat(result.getContent()).contains("rotated table text with complete cells");
    }

    private DefaultDocumentProcessingService serviceWithOcr(String text) {
        return new DefaultDocumentProcessingService(properties(), new StubOcrService(text));
    }

    private DocumentProcessingProperties properties() {
        DocumentProcessingProperties properties = new DocumentProcessingProperties();
        properties.setImageOutputDir(tempDir.resolve("images").toString());
        properties.setImageUrlPrefix("/assets");
        properties.setPdfScanImageRatioThreshold(0.60);
        properties.setPdfScanPageRatioThreshold(0.50);
        properties.getOcr().setDpi(72);
        properties.getOcr().setEnableRotationRetry(true);
        return properties;
    }

    private Path createWordDocument() throws Exception {
        Path wordPath = tempDir.resolve("sample.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun before = paragraph.createRun();
            before.setText("Before image");
            XWPFRun imageRun = paragraph.createRun();
            byte[] imageBytes = sampleImageBytes();
            imageRun.addPicture(new ByteArrayInputStream(imageBytes), Document.PICTURE_TYPE_PNG,
                    "sample.png", Units.toEMU(40), Units.toEMU(40));
            XWPFRun after = paragraph.createRun();
            after.setText("After image");
            try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                document.write(outputStream);
                Files.write(wordPath, outputStream.toByteArray());
            }
        }
        return wordPath;
    }

    private Path createWordTableDocument() throws Exception {
        Path wordPath = tempDir.resolve("table.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("参数");
            table.getRow(0).getCell(1).setText("值");
            table.getRow(1).getCell(0).setText("名称");
            table.getRow(1).getCell(1).setText("中文表格内容");
            try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                document.write(outputStream);
                Files.write(wordPath, outputStream.toByteArray());
            }
        }
        return wordPath;
    }

    private Path createTextPdf() throws IOException {
        Path pdfPath = tempDir.resolve("text.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                contentStream.newLineAtOffset(72, 720);
                contentStream.showText("Hello PDF text");
                contentStream.endText();
            }
            document.save(pdfPath.toFile());
        }
        return pdfPath;
    }

    private Path createTextPdfWithImage() throws IOException {
        Path pdfPath = tempDir.resolve("text-image.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                contentStream.newLineAtOffset(72, 720);
                contentStream.showText("PDF with image");
                contentStream.endText();
                contentStream.drawImage(LosslessFactory.createFromImage(document, sampleImage()), 72, 600, 80, 80);
            }
            document.save(pdfPath.toFile());
        }
        return pdfPath;
    }

    private Path createScannedPdf() throws IOException {
        Path pdfPath = tempDir.resolve("scanned.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.drawImage(LosslessFactory.createFromImage(document, sampleImage()), 0, 0,
                        PDRectangle.LETTER.getWidth(), PDRectangle.LETTER.getHeight());
            }
            document.save(pdfPath.toFile());
        }
        return pdfPath;
    }

    private byte[] sampleImageBytes() throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ImageIO.write(sampleImage(), "png", outputStream);
            return outputStream.toByteArray();
        }
    }

    private BufferedImage sampleImage() {
        BufferedImage image = new BufferedImage(80, 80, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                image.setRGB(x, y, (x + y) % 2 == 0 ? Color.WHITE.getRGB() : Color.BLUE.getRGB());
            }
        }
        return image;
    }

    private BufferedImage sampleLandscapeImage() {
        BufferedImage image = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                image.setRGB(x, y, Color.WHITE.getRGB());
            }
        }
        return image;
    }

    private static final class StubOcrService implements OcrService {
        private final String text;

        private StubOcrService(String text) {
            this.text = text;
        }

        @Override
        public String recognize(Path imagePath) {
            return text;
        }

        @Override
        public String recognize(BufferedImage image) {
            return text;
        }
    }

    private static final class LandscapeRotationOcrService implements OcrService {
        @Override
        public String recognize(Path imagePath) {
            return "initial table text";
        }

        @Override
        public String recognize(BufferedImage image) {
            return recognizeDetailed(image).text();
        }

        @Override
        public OcrResult recognizeDetailed(BufferedImage image) {
            if (image.getWidth() < image.getHeight()) {
                return new OcrResult("rotated table text with complete cells", 0.99, 0.99, "ok", 0, 10);
            }
            return new OcrResult("initial table text", 0.72, 0.72, "ok", 0, 10);
        }
    }
}
