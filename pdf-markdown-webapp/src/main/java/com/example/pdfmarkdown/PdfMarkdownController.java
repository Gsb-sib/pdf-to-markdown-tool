package com.example.pdfmarkdown;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/conversions")
public class PdfMarkdownController {
    private final PdfMarkdownService service;

    public PdfMarkdownController(PdfMarkdownService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PdfMarkdownService.Conversion convert(@RequestParam("file") MultipartFile file,
            @RequestParam(value = "formulaEnhanced", defaultValue = "false") boolean formulaEnhanced,
            @RequestParam(value = "exportFormulaImages", defaultValue = "false") boolean exportFormulaImages,
            @RequestParam(value = "formulaPlaceholders", defaultValue = "false") boolean formulaPlaceholders,
            @RequestParam(value = "includeImages", defaultValue = "true") boolean includeImages) {
        return service.convert(file, formulaEnhanced, exportFormulaImages, formulaPlaceholders, includeImages);
    }

    @GetMapping("/{id}/markdown")
    public ResponseEntity<Resource> markdown(@PathVariable("id") String id) throws IOException {
        return download(id, false);
    }

    @GetMapping("/{id}/archive")
    public ResponseEntity<Resource> archive(@PathVariable("id") String id) throws IOException {
        return download(id, true);
    }

    private ResponseEntity<Resource> download(String id, boolean archive) throws IOException {
        var artifact = service.artifact(id, archive);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(artifact.filename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(archive ? "application/zip" : "text/markdown;charset=UTF-8"))
                .contentLength(Files.size(artifact.path()))
                .body(new FileSystemResource(artifact.path()));
    }
}
