package com.example.pdfmarkdown;

import com.example.document.autoconfigure.DocumentProcessingProperties;
import com.example.document.ocr.OcrService;
import java.nio.file.*;
import java.util.List;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 核验接入模型后的区域替换与原文保留；数学模型准确率另行真实推理。 */
class FormulaOverlayTest {
    @TempDir Path directory;

    @Test void reconcilesVariableCaseOnlyWhenThePdfTextLayerIsUnambiguous() {
        assertThat(PdfMarkdownExtractor.preserveSourceCase("\\begin{array}{r}X=\\frac{-b}{2a}\\end{array}","x=-b2a"))
                .isEqualTo("\\begin{array}{r}x=\\frac{-b}{2a}\\end{array}");
        assertThat(PdfMarkdownExtractor.preserveSourceCase("X+x=2","Xx=2")).isEqualTo("X+x=2");
        assertThat(PdfMarkdownExtractor.preserveSourceCase("x_n=1","BROKEN")).isEqualTo("x_n=1");
        assertThat(PdfMarkdownExtractor.preserveSourceCase("X=1","𝑥=1")).isEqualTo("x=1");
        assertThat(PdfMarkdownExtractor.preserveSourceCase("\\mu+X=1","x=1")).isEqualTo("\\mu+x=1");
        assertThat(PdfMarkdownExtractor.preserveSourceCase("\\int_0^1 x\\,dx=1","x=1"))
                .isEqualTo("\\int_0^1 x\\,dx=1");
    }

    @Test void replacesOnlyTheFormulaRegionAndPreservesSurroundingBody() throws Exception {
        Path input = directory.resolve("input.pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage(); pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf,page)) {
                text(stream,"BEFORE BODY",40,740);
                text(stream,"BROKEN FORMULA",100,600);
                text(stream,"(1)",450,600);
                text(stream,"AFTER BODY",40,480);
            }
            pdf.save(input.toFile());
        }
        var ocr = mock(OcrService.class);
        Path assets = Files.createDirectory(directory.resolve("assets"));
        var result = new PdfMarkdownExtractor(new DocumentProcessingProperties(),ocr).extract(input,assets,
                image -> List.of(new PdfMarkdownExtractor.Formula(90.0/612,170.0/792,260.0/612,205.0/792,
                        "\\int_0^1 x^2\\,dx=\\frac{1}{3}")), true);
        assertThat(result.markdown()).containsSubsequence("BEFORE BODY","$$\n\\int_0^1 x^2\\,dx=\\frac{1}{3}\n$$","AFTER BODY")
                .contains("(1)","[公式原图](assets/").doesNotContain("BROKEN FORMULA");
        assertThat(result.formulaCount()).isEqualTo(1);
        assertThat(result.images()).hasSize(1).allMatch(Files::isRegularFile);
        verifyNoInteractions(ocr);
        var compact = new PdfMarkdownExtractor(new DocumentProcessingProperties(),ocr).extract(input,assets,
                image -> List.of(new PdfMarkdownExtractor.Formula(90.0/612,170.0/792,260.0/612,205.0/792,
                        "\\int_0^1 x^2\\,dx=\\frac{1}{3}")));
        assertThat(compact.images()).isEmpty();
        assertThat(compact.markdown()).contains("\\frac{1}{3}","BEFORE BODY","AFTER BODY").doesNotContain("公式原图");
    }

    @Test void noDetectedFormulaKeepsTheOriginalExtraction() throws Exception {
        Path input = directory.resolve("input.pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage(); pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf,page)) { text(stream,"UNCHANGED BODY",40,740); }
            pdf.save(input.toFile());
        }
        var extractor = new PdfMarkdownExtractor(new DocumentProcessingProperties(),mock(OcrService.class));
        Path assets = Files.createDirectory(directory.resolve("assets"));
        assertThat(extractor.extract(input,assets,image -> List.of()).markdown()).isEqualTo(extractor.extract(input,assets).markdown());
    }

    @Test void inlineFormulaStaysBetweenTheSurroundingWordsOnItsBaseline() throws Exception {
        Path input = directory.resolve("inline.pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage(); pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf,page)) {
                text(stream,"Let the value",40,600);
                text(stream,"BROKEN",125,600);
                text(stream,"denote state.",190,600);
                text(stream,"AFTER BODY",40,570);
            }
            pdf.save(input.toFile());
        }
        var extractor = new PdfMarkdownExtractor(new DocumentProcessingProperties(),mock(OcrService.class));
        Path assets = Files.createDirectory(directory.resolve("assets"));
        var result = extractor.extract(input,assets,image -> List.of(new PdfMarkdownExtractor.Formula(
                120.0/612,178.0/792,191.0/612,198.0/792,"x_{n}")), true);
        assertThat(result.markdown()).contains("Let the value $x_{n}$ denote state.")
                .containsSubsequence("denote state.","AFTER BODY","[公式原图]")
                .doesNotContain("BROKEN", "FORMULA");
    }

    @Test void suspiciousDetectionPreservesNativeBodyWordsAndMarksForReview() throws Exception {
        Path input = directory.resolve("body-detection.pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage(); pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf,page)) { text(stream,"years and x",125,600); }
            pdf.save(input.toFile());
        }
        var extractor = new PdfMarkdownExtractor(new DocumentProcessingProperties(),mock(OcrService.class));
        Path assets = Files.createDirectory(directory.resolve("assets"));
        var result = extractor.extract(input,assets,image -> List.of(new PdfMarkdownExtractor.Formula(
                120.0/612,178.0/792,205.0/612,198.0/792,"\\operatorname{WRONG}")), true);
        assertThat(result.markdown()).contains("years and x", "[FORMULA_REVIEW_REQUIRED", "[公式原图]")
                .doesNotContain("WRONG");
    }

    @Test void repeatedNumberedEquationsAreNotMistakenForTwoTextColumns() throws Exception {
        Path input = directory.resolve("numbered.pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage(); pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf,page)) {
                for (int row=0; row<4; row++) {
                    text(stream,"LABEL"+(row+1),40,700-row*80);
                    text(stream,"X",200,700-row*80);
                }
            }
            pdf.save(input.toFile());
        }
        var extractor = new PdfMarkdownExtractor(new DocumentProcessingProperties(),mock(OcrService.class));
        Path assets = Files.createDirectory(directory.resolve("assets"));
        var result = extractor.extract(input,assets,image -> java.util.stream.IntStream.range(0,4)
                .mapToObj(row -> new PdfMarkdownExtractor.Formula(195.0/612,(80.0+row*80)/792,
                        220.0/612,(98.0+row*80)/792,"X="+(row+1))).toList());
        assertThat(result.markdown()).containsSubsequence("LABEL1", "X=1", "LABEL2", "X=2", "LABEL3", "X=3", "LABEL4", "X=4");
    }

    @Test void glyphAnchoredNearTheTopOfADetectionIsNotLeftAsADuplicate() throws Exception {
        Path input = directory.resolve("top-anchor.pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage(); pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf,page)) {
                // 大型数学符号的 PDF 锚点可能落在检测框顶部，不能只用普通字母的中心估计。
                text(stream,"X",140,650);
                text(stream,"AFTER BODY",40,600);
            }
            pdf.save(input.toFile());
        }
        var extractor = new PdfMarkdownExtractor(new DocumentProcessingProperties(),mock(OcrService.class));
        Path assets = Files.createDirectory(directory.resolve("assets"));
        var result = extractor.extract(input,assets,image -> List.of(new PdfMarkdownExtractor.Formula(
                135.0/612,141.0/792,160.0/612,162.0/792,"\\int_0^1 f(t)\\,dt")));
        assertThat(result.markdown()).containsSubsequence("\\int_0^1", "AFTER BODY").doesNotContain("X");
    }

    @Test void multilineMathUsesAnEnvironmentThatCanBeNestedInMarkdownDelimiters() {
        var formula = new PdfMarkdownExtractor.Formula(0,0,1,1,
                "\\begin{align*}x&=a+b\\\\&=c\\end{align*}");
        assertThat(formula.latex()).isEqualTo("\\begin{aligned}x&=a+b\\\\&=c\\end{aligned}");
    }

    @Test void recoversNativeSubscriptsWithoutChangingOrdinaryWordsOrFootnotes() {
        var glyphs = List.of(new PdfMarkdownExtractor.Glyph("F",10,20,8,12,3),
                new PdfMarkdownExtractor.Glyph("n",18,23,4,8,2),
                new PdfMarkdownExtractor.Glyph("−",22,23,4,8,2),
                new PdfMarkdownExtractor.Glyph("1",26,23,4,8,2),
                new PdfMarkdownExtractor.Glyph("a",40,20,6,12,3),
                new PdfMarkdownExtractor.Glyph("2",46,16,4,8,2));
        var result = PdfMarkdownExtractor.restoreScripts(glyphs);
        assertThat(result).extracting(PdfMarkdownExtractor.Glyph::text).containsExactly("$F_{n-1}$", "a", "2");
        assertThat(PdfMarkdownExtractor.restoreScripts(List.of(
                new PdfMarkdownExtractor.Glyph("Hello",0,20,30,12,3),
                new PdfMarkdownExtractor.Glyph("world",33,20,30,12,3))))
                .extracting(PdfMarkdownExtractor.Glyph::text).containsExactly("Hello", "world");
    }

    @Test void restoresSubscriptFromPdfGeometryWhenTheModelMissesIt() throws Exception {
        Path input = directory.resolve("missed-subscript.pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage(); pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf,page)) {
                text(stream,"Let",40,600);
                text(stream,"F",65,600);
                stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),8);
                stream.newLineAtOffset(72,597); stream.showText("n-1"); stream.endText();
                text(stream,"denote state.",88,600);
            }
            pdf.save(input.toFile());
        }
        Path assets = Files.createDirectory(directory.resolve("assets"));
        var result = new PdfMarkdownExtractor(new DocumentProcessingProperties(),mock(OcrService.class))
                .extract(input,assets,image -> List.of());
        assertThat(result.markdown()).contains("Let $F_{n-1}$ denote state.");
        assertThat(result.images()).isEmpty();
    }

    private static void text(PDPageContentStream stream,String text,float x,float y) throws Exception {
        stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
        stream.newLineAtOffset(x,y); stream.showText(text); stream.endText();
    }
}
