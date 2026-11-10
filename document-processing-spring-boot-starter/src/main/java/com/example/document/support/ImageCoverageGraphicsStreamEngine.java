package com.example.document.support;

import java.awt.geom.Point2D;
import java.io.IOException;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

final class ImageCoverageGraphicsStreamEngine extends PDFGraphicsStreamEngine {
    private double imageArea;

    ImageCoverageGraphicsStreamEngine(PDPage page) {
        super(page);
    }

    double getImageArea() {
        return imageArea;
    }

    @Override
    public void drawImage(PDImage pdImage) throws IOException {
        PDGraphicsState graphicsState = getGraphicsState();
        Matrix ctm = graphicsState.getCurrentTransformationMatrix();
        double width = Math.hypot(ctm.getScaleX(), ctm.getShearY());
        double height = Math.hypot(ctm.getShearX(), ctm.getScaleY());
        imageArea += Math.abs(width * height);
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
