package com.example.document.support;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

final class PdfImageExtractor extends PDFGraphicsStreamEngine {
    private final ImageStorage imageStorage;
    private final List<Path> savedImages = new ArrayList<>();

    PdfImageExtractor(PDPage page, ImageStorage imageStorage) {
        super(page);
        this.imageStorage = imageStorage;
    }

    List<Path> extract(PDPage page) throws IOException {
        processPage(page);
        return List.copyOf(savedImages);
    }

    @Override
    public void drawImage(PDImage pdImage) throws IOException {
        savedImages.add(imageStorage.save(pdImage.getImage()));
    }

    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
    }

    @Override
    public void clip(int windingRule) {
    }

    @Override
    public void moveTo(float x, float y) {
    }

    @Override
    public void lineTo(float x, float y) {
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
    }

    @Override
    public Point2D getCurrentPoint() {
        return new Point2D.Float(0, 0);
    }

    @Override
    public void closePath() {
    }

    @Override
    public void endPath() {
    }

    @Override
    public void strokePath() {
    }

    @Override
    public void fillPath(int windingRule) {
    }

    @Override
    public void fillAndStrokePath(int windingRule) {
    }

    @Override
    public void shadingFill(COSName shadingName) {
    }

    @Override
    public void showGlyph(Matrix textRenderingMatrix, org.apache.pdfbox.pdmodel.font.PDFont font,
            int code, Vector displacement) {
    }
}
