package com.example.pdfmarkdown;

import com.example.document.ocr.OcrService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PdfMarkdownIntegrationTest {
    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("pdf-markdown.output-dir", () -> tempDir.resolve("conversions").toString());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockBean OcrService pdfOcrService;

    @Test void textFirstAndPlaceholdersPreserveBodyWithoutModelsOrSymbolImages() throws Exception {
        byte[] sample = formulaTextPdf();
        var plainResponse = mvc.perform(multipart("/api/conversions").file(pdf("text-first.pdf", sample))
                .param("includeImages", "false")).andExpect(status().isOk()).andReturn();
        JsonNode plain = mapper.readTree(plainResponse.getResponse().getContentAsByteArray());
        assertThat(plain.get("markdown").asText()).contains("x = 1", "Body before the formula.", "where x = 1", "Body after the formula.");
        assertThat(plain.get("formulaEnhanced").asBoolean()).isFalse();
        assertThat(plain.get("imageCount").asInt()).isZero();
        var blankResponse = mvc.perform(multipart("/api/conversions").file(pdf("text-first.pdf", sample))
                .param("formulaPlaceholders", "true").param("includeImages", "false"))
                .andExpect(status().isOk()).andReturn();
        JsonNode blank = mapper.readTree(blankResponse.getResponse().getContentAsByteArray());
        assertThat(blank.get("markdown").asText()).containsSubsequence("Body before the formula.", "〔待填写公式 p1-1〕",
                "where x = 1", "Body after the formula.");
        assertThat(blank.get("markdown").asText()).doesNotContain("![](");
        assertThat(blank.get("formulaCount").asInt()).isEqualTo(1);
        assertThat(blank.get("formulaEnhanced").asBoolean()).isFalse();
        assertThat(blank.get("formulaPlaceholders").asBoolean()).isTrue();
        verifyNoInteractions(pdfOcrService);
        assertUnicodeDownloads(blank, "〔待填写公式 p1-1〕");
        Path job = tempDir.resolve("conversions").resolve(blank.get("id").asText());
        try (var assets = Files.list(job.resolve("assets"))) { assertThat(assets.count()).isZero(); }
    }

    @Test void scanPlaceholdersKeepChineseAndInlineMathButSkipImageExport() throws Exception {
        String text = "正文一：保持完整。\nx = 1\n正文含 x = 2，不删除。\n正文二：继续保留。";
        when(pdfOcrService.recognizeDetailed(any(BufferedImage.class))).thenReturn(OcrService.OcrResult.plain(text));
        var response = mvc.perform(multipart("/api/conversions").file(pdf("scan.pdf", scannedPdf()))
                .param("formulaPlaceholders", "true").param("includeImages", "false"))
                .andExpect(status().isOk()).andReturn();
        JsonNode json = mapper.readTree(response.getResponse().getContentAsByteArray());
        assertThat(json.get("markdown").asText()).isEqualTo("正文一：保持完整。\n〔待填写公式 p1-1〕\n正文含 x = 2，不删除。\n正文二：继续保留。\n");
        assertThat(json.get("formulaCount").asInt()).isEqualTo(1);
        assertThat(json.get("imageCount").asInt()).isZero();
        assertUnicodeDownloads(json, "正文二：继续保留。");
    }

    @Test void rejectsContradictoryFormulaModesBeforeStartingModel() throws Exception {
        mvc.perform(multipart("/api/conversions").file(pdf("text.pdf", formulaTextPdf()))
                .param("formulaEnhanced", "true").param("formulaPlaceholders", "true"))
                .andExpect(status().isBadRequest());
    }

    @Test void placeholderNumbersIncludePdfPageAndDoNotDropSurroundingText() throws Exception {
        try (var source = org.apache.pdfbox.Loader.loadPDF(formulaTextPdf());
                var document = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            document.importPage(source.getPage(0)); document.importPage(source.getPage(0));
            document.save(bytes);
            var response = mvc.perform(multipart("/api/conversions").file(pdf("two-pages.pdf", bytes.toByteArray()))
                    .param("formulaPlaceholders", "true").param("includeImages", "false"))
                    .andExpect(status().isOk()).andReturn();
            JsonNode json = mapper.readTree(response.getResponse().getContentAsByteArray());
            assertThat(json.get("formulaCount").asInt()).isEqualTo(2);
            assertThat(json.get("markdown").asText()).containsSubsequence("Body before the formula.",
                    "〔待填写公式 p1-1〕", "Body after the formula.", "Body before the formula.",
                    "〔待填写公式 p2-1〕", "Body after the formula.");
        }
    }

    @Test void placeholderModePreservesEquationsInsideNativeTables() throws Exception {
        try (var document = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            var page = new PDPage(); document.addPage(page);
            try (var stream = new PDPageContentStream(document, page)) {
                for (int row = 0; row < 2; row++) for (int col = 0; col < 2; col++) {
                    stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(50 + col * 200, 730 - row * 45);
                    stream.showText(row == 0 ? (col == 0 ? "Formula" : "Value") : (col == 0 ? "x = 1" : "y = 2"));
                    stream.endText();
                }
                for (int x : new int[]{40, 240, 440}) { stream.moveTo(x, 660); stream.lineTo(x, 750); }
                for (int y : new int[]{660, 705, 750}) { stream.moveTo(40, y); stream.lineTo(440, y); }
                stream.stroke();
            }
            document.save(bytes);
            var response = mvc.perform(multipart("/api/conversions").file(pdf("table.pdf", bytes.toByteArray()))
                    .param("formulaPlaceholders", "true").param("includeImages", "false"))
                    .andExpect(status().isOk()).andReturn();
            JsonNode json = mapper.readTree(response.getResponse().getContentAsByteArray());
            assertThat(json.get("formulaCount").asInt()).isZero();
            assertThat(json.get("markdown").asText()).contains("| x = 1 | y = 2 |");
        }
    }

    private static byte[] formulaTextPdf() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(); document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(60, 740);
                for (String line : new String[] {"Body before the formula.", "x = 1", "where x = 1", "Body after the formula."}) {
                    stream.showText(line); stream.newLineAtOffset(0, -30);
                }
                stream.endText();
                stream.drawImage(LosslessFactory.createFromImage(document, image()), 60, 500, 20, 20);
            }
            document.save(output); return output.toByteArray();
        }
    }

    @Test void formulaModeWithoutConfiguredPythonReturnsAnExplicitError() throws Exception {
        var result = mvc.perform(multipart("/api/conversions").file(pdf("text.pdf",textPdf(1,false)))
                .param("formulaEnhanced","true")).andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("PDF_FORMULA_PYTHON");
    }

    @Test
    void generatesRealMarkdownAndPortableArchiveWithChineseFilename() throws Exception {
        var result = mvc.perform(multipart("/api/conversions").file(pdf("项目说明.PDF", textPdf(1, true))))
                .andExpect(status().isOk()).andReturn();
        JsonNode json = mapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(json.get("filename").asText()).isEqualTo("项目说明.md");
        assertThat(json.get("pageCount").asInt()).isEqualTo(1);
        assertThat(json.get("imageCount").asInt()).isEqualTo(1);
        assertThat(json.get("scannedPdf").asBoolean()).isFalse();
        String markdown = json.get("markdown").asText();
        assertThat(markdown).contains("Hello PDF to Markdown", "![](assets/").doesNotContain("![](/assets/");

        var download = mvc.perform(get(json.get("markdownUrl").asText()))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(download.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(markdown);
        assertThat(download.getContentType()).isEqualTo("text/markdown;charset=UTF-8");
        assertThat(download.getHeader("Content-Disposition")).contains("attachment;", "filename*=UTF-8''");

        byte[] bytes = mvc.perform(get(json.get("archiveUrl").asText()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        Map<String, byte[]> entries = unzip(bytes);
        assertThat(new String(entries.get("项目说明.md"), StandardCharsets.UTF_8)).isEqualTo(markdown);
        assertThat(entries).hasSize(2);
        String image = entries.keySet().stream().filter(name -> name.startsWith("assets/")).findFirst().orElseThrow();
        assertThat(markdown).contains("![](" + image + ")");
        assertThat(entries.get(image)).isNotEmpty();

        Path job = tempDir.resolve("conversions").resolve(json.get("id").asText());
        assertThat(job.resolve("document.md")).hasContent(markdown);
        assertThat(job.resolve("source.pdf")).doesNotExist();
        assertThat(job.resolve("result.json")).isRegularFile();
    }

    @Test
    void preservesChineseOcrTextInDownloadedFile() throws Exception {
        String text = "扫描页面测试：这是中文识别结果，下载的 Markdown 文件应保留全部中文文字和标点。";
        when(pdfOcrService.recognizeDetailed(any(BufferedImage.class))).thenReturn(OcrService.OcrResult.plain(text));
        JsonNode json = convert("扫描件.pdf", scannedPdf());
        assertThat(json.get("scannedPdf").asBoolean()).isTrue();
        assertThat(json.get("markdown").asText()).contains(text);
        String downloaded = mvc.perform(get(json.get("markdownUrl").asText())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(downloaded).contains(text);
        verify(pdfOcrService).recognizeDetailed(any(BufferedImage.class));
    }

    @Test
    void savesMalformedPdfUnicodeWithVisibleMarkersAndPreservesSupplementaryCharacters() throws Exception {
        // 使用真实 PDF 的损坏 ToUnicode 映射复现，避免仅测试字符串替换。
        JsonNode json = convert("unicode.pdf", malformedUnicodePdf());
        assertUnicodeDownloads(json, "before \uFFFD middle \uFFFD valid \uD83D\uDE00 supplementary \uD840\uDC00 after");
        assertThat(json.get("warnings").toString()).contains("2", "Unicode", "核验");
    }

    @Test
    void savesMalformedOcrUnicodeWithoutLosingValidText() throws Exception {
        when(pdfOcrService.recognizeDetailed(any(BufferedImage.class))).thenReturn(OcrService.OcrResult.plain(
                "中文 before \uD800 middle \uDC00 after \uD83D\uDE00 \uD840\uDC00"));
        JsonNode json = convert("OCR.pdf", scannedPdf());
        assertUnicodeDownloads(json, "中文 before \uFFFD middle \uFFFD after \uD83D\uDE00 \uD840\uDC00");
        assertThat(json.get("warnings").toString()).contains("Unicode", "核验");
    }

    @Test
    void repairsMalformedFilenameAndKeepsValidSupplementaryFilename() throws Exception {
        JsonNode malformed = convert("bad\uD800name.pdf", textPdf(1, false));
        assertThat(malformed.get("filename").asText()).isEqualTo("bad\uFFFDname.md");
        assertUnicodeDownloads(malformed, "Hello PDF to Markdown");
        JsonNode valid = convert("报告\uD83D\uDE00\uD840\uDC00.pdf", textPdf(1, false));
        assertThat(valid.get("filename").asText()).isEqualTo("报告\uD83D\uDE00\uD840\uDC00.md");
        assertThat(valid.get("warnings").isEmpty()).isTrue();
        assertUnicodeDownloads(valid, "Hello PDF to Markdown");
    }

    private void assertUnicodeDownloads(JsonNode json, String expected) throws Exception {
        String markdown = json.get("markdown").asText();
        assertThat(markdown).contains(expected);
        var downloaded = mvc.perform(get(json.get("markdownUrl").asText())).andExpect(status().isOk())
                .andReturn().getResponse();
        assertThat(downloaded.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(markdown);
        // 严格解码，不能依靠读取时的自动替代掩盖损坏的 UTF-8 字节。
        assertThat(StandardCharsets.UTF_8.newDecoder().decode(
                java.nio.ByteBuffer.wrap(downloaded.getContentAsByteArray())).toString()).isEqualTo(markdown);
        byte[] archive = mvc.perform(get(json.get("archiveUrl").asText())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        Map<String, byte[]> entries = unzip(archive);
        assertThat(new String(entries.get(json.get("filename").asText()), StandardCharsets.UTF_8)).isEqualTo(markdown);
    }

    private static byte[] malformedUnicodePdf() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            StringBuilder cmap = new StringBuilder("/CIDInit /ProcSet findresource begin\n12 dict begin\n"
                    + "begincmap\n/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
                    + "/CMapName /BrokenUnicode def\n/CMapType 2 def\n"
                    + "1 begincodespacerange\n<00> <FF>\nendcodespacerange\n95 beginbfchar\n");
            for (int code = 32; code <= 126; code++) {
                String mapped = switch (code) {
                    case 'A' -> "D800";
                    case 'B' -> "DC00";
                    case 'C' -> "D83DDE00";
                    case 'D' -> "D840DC00";
                    default -> String.format(java.util.Locale.ROOT, "%04X", code);
                };
                cmap.append(String.format(java.util.Locale.ROOT, "<%02X> <%s>\n", code, mapped));
            }
            cmap.append("endbfchar\nendcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
            PDStream unicode = new PDStream(document, new ByteArrayInputStream(
                    cmap.toString().getBytes(StandardCharsets.US_ASCII)));
            font.getCOSObject().setItem(COSName.TO_UNICODE, unicode);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(font, 12);
                stream.newLineAtOffset(40, 740);
                stream.showText("before A middle B valid C supplementary D after");
                stream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    @Test
    void reportsFailedOcrPagesInsteadOfClaimingCompleteRecognition() throws Exception {
        when(pdfOcrService.recognizeDetailed(any(BufferedImage.class))).thenThrow(new IllegalStateException("test"));
        JsonNode json = convert("扫描件.pdf", scannedPdf());
        assertThat(json.get("warnings").toString()).contains("识别失败");
        assertThat(json.get("markdown").asText()).contains("DIFFICULT_OCR_PAGE");
    }

    @Test
    void rejectsEmptyWrongTypeAndCorruptedFiles() throws Exception {
        mvc.perform(multipart("/api/conversions").file(pdf("empty.pdf", new byte[0])))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/conversions").file(pdf("notes.txt", textPdf(1, false))))
                .andExpect(status().isBadRequest());
        var corrupted = mvc.perform(multipart("/api/conversions").file(pdf("broken.pdf", new byte[]{1, 2, 3})))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(mapper.readTree(corrupted.getResponse().getContentAsByteArray()).get("message").asText())
                .contains("无法读取 PDF");
        mvc.perform(multipart("/api/conversions")).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsPasswordProtectedPdf() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.protect(new StandardProtectionPolicy("owner", "secret", new AccessPermission()));
            document.save(buffer);
            var response = mvc.perform(multipart("/api/conversions").file(pdf("locked.pdf", buffer.toByteArray())))
                    .andExpect(status().isBadRequest()).andReturn().getResponse();
            assertThat(mapper.readTree(response.getContentAsByteArray()).get("message").asText()).contains("密码");
        }
    }

    @Test
    void convertsMoreThanTwoHundredPagesWithoutDroppingContent() throws Exception {
        JsonNode result = convert("long-document.pdf", textPdf(201, false));
        assertThat(result.get("pageCount").asInt()).isEqualTo(201);
        String markdown = result.get("markdown").asText();
        assertThat(markdown).contains("Hello PDF to Markdown - page 1\n", "Hello PDF to Markdown - page 201");
        String downloaded = mvc.perform(get(result.get("markdownUrl").asText())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(downloaded).isEqualTo(markdown);
    }

    @Test
    void isolatesResultsAndRejectsInvalidDownloadIds() throws Exception {
        JsonNode first = convert("../../第一份.pdf", textPdf(1, false));
        JsonNode second = convert("第二份.pdf", textPdf(1, false));
        assertThat(first.get("id").asText()).isNotEqualTo(second.get("id").asText());
        assertThat(first.get("filename").asText()).isEqualTo("第一份.md");
        mvc.perform(get(first.get("markdownUrl").asText())).andExpect(status().isOk());
        mvc.perform(get(second.get("markdownUrl").asText())).andExpect(status().isOk());
        mvc.perform(get("/api/conversions/not-a-uuid/markdown")).andExpect(status().isNotFound());
        mvc.perform(get("/api/conversions/" + UUID.randomUUID() + "/archive")).andExpect(status().isNotFound());
        mvc.perform(get("/missing-page")).andExpect(status().isNotFound());
    }

    private JsonNode convert(String filename, byte[] content) throws Exception {
        byte[] json = mvc.perform(multipart("/api/conversions").file(pdf(filename, content)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        return mapper.readTree(json);
    }

    private static MockMultipartFile pdf(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/pdf", content);
    }

    private static byte[] textPdf(int pages, boolean withImage) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(60, 740);
                    stream.showText("Hello PDF to Markdown - page " + (i + 1));
                    stream.endText();
                    if (withImage) {
                        stream.drawImage(LosslessFactory.createFromImage(document, image()), 60, 600, 40, 40);
                    }
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] scannedPdf() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.drawImage(LosslessFactory.createFromImage(document, image()), 0, 0,
                        page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static BufferedImage image() {
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 100, 100);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(20, 20, 60, 60);
        graphics.dispose();
        return image;
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }
}
