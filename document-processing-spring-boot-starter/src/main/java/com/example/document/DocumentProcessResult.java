package com.example.document;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

public final class DocumentProcessResult {
    private final SourceType sourceType;
    private final OutputFormat format;
    private final String content;
    private final List<Path> savedImages;
    private final boolean scannedPdf;

    public DocumentProcessResult(SourceType sourceType, OutputFormat format, String content,
            List<Path> savedImages, boolean scannedPdf) {
        this.sourceType = sourceType;
        this.format = format;
        this.content = content;
        this.savedImages = savedImages == null ? List.of() : List.copyOf(savedImages);
        this.scannedPdf = scannedPdf;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public OutputFormat getFormat() {
        return format;
    }

    public String getContent() {
        return content;
    }

    public List<Path> getSavedImages() {
        return Collections.unmodifiableList(savedImages);
    }

    public boolean isScannedPdf() {
        return scannedPdf;
    }
}
