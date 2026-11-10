package com.example.pdfmarkdown;

import com.example.document.autoconfigure.DocumentProcessingProperties;
import com.example.document.ocr.OcrService;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证页面路由、顺序与内容保留；识别准确率由真实 HTTP OCR 评测验证。 */
class PdfMarkdownExtractorTest {
    @TempDir Path directory;
    private final OcrService ocr = mock(OcrService.class);

    @Test void mixedDocumentOnlyRecognizesItsScannedPageInOrder() throws Exception {
        when(ocr.recognizeDetailed(any(BufferedImage.class))).thenReturn(OcrService.OcrResult.plain("SCANNED THIRD"));
        try (var pdf = new PDDocument()) {
            page(pdf, "FIRST NATIVE"); page(pdf, "SECOND NATIVE"); imagePage(pdf, scan());
            var result = extract(pdf);
            assertThat(result.usedOcr()).isTrue();
            assertThat(result.markdown()).containsSubsequence("FIRST NATIVE", "SECOND NATIVE", "SCANNED THIRD");
            verify(ocr, times(1)).recognizeDetailed(any(BufferedImage.class));
        }
    }

    @Test void readsInterleavedColumnsInColumnOrder() throws Exception {
        try (var pdf = new PDDocument()) {
            PDPage page = new PDPage(); pdf.addPage(page);
            try (var s = new PDPageContentStream(pdf, page)) {
                for (int i=0;i<4;i++) { text(s, "LEFT " + i, 40, 750-i*35, 12); text(s, "RIGHT " + i, 330, 750-i*35, 12); }
            }
            assertThat(extract(pdf).markdown()).containsSubsequence("LEFT 0", "LEFT 1", "LEFT 2", "LEFT 3", "RIGHT 0", "RIGHT 1", "RIGHT 2", "RIGHT 3");
            verifyNoInteractions(ocr);
        }
    }

    @Test void preservesTextOutsideNativeTableAndEscapesCellPipes() throws Exception {
        try (var pdf = new PDDocument()) {
            PDPage page = new PDPage(); pdf.addPage(page);
            try (var s = new PDPageContentStream(pdf, page)) {
                text(s,"TABLE TITLE",40,780,22);
                text(s,"Item",50,730,12); text(s,"Value",250,730,12);
                text(s,"A|B",50,690,12); text(s,"123.45",250,690,12);
                text(s,"FOOTNOTE",40,610,12);
                for (int x : new int[]{40,240,440}) { s.moveTo(x,660); s.lineTo(x,750); }
                for (int y : new int[]{660,705,750}) { s.moveTo(40,y); s.lineTo(440,y); }
                s.stroke();
            }
            assertThat(extract(pdf).markdown()).containsSubsequence("TABLE TITLE", "| Item | Value |", "| --- | --- |", "| A\\|B | 123.45 |", "FOOTNOTE");
        }
    }

    @Test void clippingPathsAreNotMisclassifiedAsTables() throws Exception {
        try (var pdf = new PDDocument()) {
            PDPage page = page(pdf,"ONLY BODY");
            try (var s = new PDPageContentStream(pdf,page,PDPageContentStream.AppendMode.APPEND,true)) {
                for (int x : new int[]{40,240,440}) { s.moveTo(x,660); s.lineTo(x,750); }
                for (int y : new int[]{660,705,750}) { s.moveTo(40,y); s.lineTo(440,y); }
                s.clip();
            }
            assertThat(extract(pdf).markdown()).contains("ONLY BODY").doesNotContain("| ---");
        }
    }

    @Test void headingsUseFontSizeAndPreserveBody() throws Exception {
        try (var pdf = new PDDocument()) {
            PDPage page = new PDPage(); pdf.addPage(page);
            try (var s = new PDPageContentStream(pdf,page)) {
                text(s,"HEADING",40,760,24); text(s,"Body one",40,710,12); text(s,"Body two",40,680,12);
            }
            assertThat(extract(pdf).markdown()).startsWith("# HEADING\n").contains("Body one", "Body two");
        }
    }

    @Test void blankPagesDoNotCallOcr() throws Exception {
        try (var pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            assertThat(extract(pdf).markdown()).isEmpty();
            verifyNoInteractions(ocr);
        }
    }

    @Test void overlappingTextUsedForBoldDoesNotDuplicateCharacters() throws Exception {
        try (var pdf = new PDDocument()) {
            PDPage page = new PDPage(); pdf.addPage(page);
            try (var s = new PDPageContentStream(pdf,page)) {
                text(s,"OVERPRINT",40,760,12);text(s,"OVERPRINT",40,760,12);
            }
            assertThat(extract(pdf).markdown()).isEqualTo("OVERPRINT");
        }
    }

    @Test void vectorOutlinePagesCanUseOcrWithoutEmbeddedImages() throws Exception {
        when(ocr.recognizeDetailed(any(BufferedImage.class))).thenReturn(OcrService.OcrResult.plain("OUTLINED WORDS"));
        try (var pdf = new PDDocument()) {
            PDPage page = new PDPage(); pdf.addPage(page);
            try (var s = new PDPageContentStream(pdf,page)) { s.addRect(40,650,200,30); s.fill(); }
            assertThat(extract(pdf).markdown()).contains("OUTLINED WORDS");
            verify(ocr,times(1)).recognizeDetailed(any(BufferedImage.class));
        }
    }

    @Test void failedOcrPreservesTheOriginalScanAndDiagnostic() throws Exception {
        when(ocr.recognizeDetailed(any(BufferedImage.class))).thenThrow(new IllegalStateException("offline"));
        try (var pdf = new PDDocument()) {
            imagePage(pdf,scan());
            var result = extract(pdf);
            assertThat(result.markdown()).contains("DIFFICULT_OCR_PAGE", "![](assets/");
            assertThat(result.images()).hasSize(1);
            assertThat(result.images().get(0)).isRegularFile();
        }
    }

    @Test void rasterTableDoesNotDropHeaderAndFooter() throws Exception {
        BufferedImage image = new BufferedImage(600,800,BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics(); g.setColor(Color.WHITE);g.fillRect(0,0,600,800);g.setColor(Color.BLACK);
        for(int x:new int[]{40,300,560})g.drawLine(x,200,x,400);
        for(int y:new int[]{200,300,400})g.drawLine(40,y,560,y);
        g.drawString("CELL",60,250);g.drawString("CELL",320,250);g.drawString("CELL",60,350);g.drawString("CELL",320,350);
        g.setColor(Color.RED);g.fillRect(80,80,50,12);g.setColor(Color.BLUE);g.fillRect(80,600,50,12);g.dispose();
        when(ocr.recognizeDetailed(any(BufferedImage.class))).thenAnswer(call -> {
            BufferedImage im=call.getArgument(0);boolean red=false,blue=false;
            for(int y=0;y<im.getHeight();y+=2)for(int x=0;x<im.getWidth();x+=2){
                var color=new Color(im.getRGB(x,y));
                red|=color.getRed()>200 && color.getBlue()<80;
                blue|=color.getBlue()>200 && color.getRed()<80;
            }
            return OcrService.OcrResult.plain(red?"HEADER":blue?"FOOTER":"CELL");
        });
        try(var pdf=new PDDocument()){
            imagePage(pdf,image);
            assertThat(extract(pdf).markdown()).containsSubsequence("HEADER", "| CELL | CELL |", "FOOTER");
        }
    }

    private PdfMarkdownExtractor.Extraction extract(PDDocument pdf) throws Exception {
        Path input=directory.resolve("source.pdf"), assets=directory.resolve("assets"); Files.createDirectories(assets);pdf.save(input.toFile());
        var properties = new DocumentProcessingProperties(); properties.getOcr().setDpi(72);
        return new PdfMarkdownExtractor(properties,ocr).extract(input,assets);
    }
    private static PDPage page(PDDocument pdf,String words) throws Exception {
        PDPage page=new PDPage();pdf.addPage(page);try(var s=new PDPageContentStream(pdf,page)){text(s,words,40,740,12);}return page;
    }
    private static void text(PDPageContentStream s,String value,float x,float y,float size) throws Exception {
        s.beginText();s.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),size);s.newLineAtOffset(x,y);s.showText(value);s.endText();
    }
    private static BufferedImage scan(){
        BufferedImage im=new BufferedImage(600,800,BufferedImage.TYPE_INT_RGB);var g=im.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,600,800);g.setColor(Color.BLACK);g.drawString("SCANNED",40,80);g.dispose();return im;
    }
    private static void imagePage(PDDocument pdf,BufferedImage image) throws Exception {
        var page=new PDPage();pdf.addPage(page);try(var s=new PDPageContentStream(pdf,page)){s.drawImage(LosslessFactory.createFromImage(pdf,image),0,0,page.getMediaBox().getWidth(),page.getMediaBox().getHeight());}
    }
}
