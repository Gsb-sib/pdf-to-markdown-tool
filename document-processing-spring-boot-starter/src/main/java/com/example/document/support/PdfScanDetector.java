package com.example.document.support;

import java.io.IOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;

final class PdfScanDetector {
    private final double imageRatioThreshold;
    private final double pageRatioThreshold;

    PdfScanDetector(double imageRatioThreshold, double pageRatioThreshold) {
        this.imageRatioThreshold = imageRatioThreshold;
        this.pageRatioThreshold = pageRatioThreshold;
    }

    boolean isScanned(PDDocument document) {
        int pageCount = document.getNumberOfPages();
        if (pageCount == 0) {
            return false;
        }
        int imageHeavyPages = 0;
        for (int i = 0; i < pageCount; i++) {
            PDPage page = document.getPage(i);
            double imageCoverage = imageCoverage(page);
            String pageText = extractPageText(document, i + 1);
            if (imageCoverage >= imageRatioThreshold && pageText.strip().length() < 80) {
                imageHeavyPages++;
            }
        }
        return imageHeavyPages / (double) pageCount >= pageRatioThreshold;
    }

    private double imageCoverage(PDPage page) {
        try {
            ImageCoverageGraphicsStreamEngine engine = new ImageCoverageGraphicsStreamEngine(page);
            engine.processPage(page);
            PDRectangle mediaBox = page.getMediaBox();
            double pageArea = mediaBox.getWidth() * mediaBox.getHeight();
            if (pageArea <= 0) {
                return 0;
            }
            return Math.min(1, engine.getImageArea() / pageArea);
        } catch (IOException ex) {
            return 0;
        }
    }

    private String extractPageText(PDDocument document, int pageNumber) {
        try {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(pageNumber);
            stripper.setEndPage(pageNumber);
            return stripper.getText(document);
        } catch (IOException ex) {
            return "";
        }
    }
}
