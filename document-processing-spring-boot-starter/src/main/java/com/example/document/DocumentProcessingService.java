package com.example.document;

import java.nio.file.Path;

public interface DocumentProcessingService {
    DocumentProcessResult recognizeImage(Path imagePath, OutputFormat format);

    DocumentProcessResult readWord(Path wordPath, OutputFormat format);

    DocumentProcessResult readPdf(Path pdfPath, OutputFormat format);
}
