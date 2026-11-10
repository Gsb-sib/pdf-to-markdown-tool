package com.example.document.support;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;

final class ImageStorage {
    private final Path outputDir;
    private final String urlPrefix;

    ImageStorage(com.example.document.autoconfigure.DocumentProcessingProperties properties) {
        this.outputDir = Path.of(properties.getImageOutputDir()).toAbsolutePath().normalize();
        this.urlPrefix = normalizePrefix(properties.getImageUrlPrefix());
    }

    Path save(XWPFPictureData pictureData) {
        String extension = pictureData.suggestFileExtension();
        if (extension == null || extension.isBlank()) {
            extension = "bin";
        }
        return saveBytes(pictureData.getData(), extension);
    }

    Path save(BufferedImage image) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", outputStream);
            return saveBytes(outputStream.toByteArray(), "png");
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to encode image", ex);
        }
    }

    Path save(InputStream inputStream, String extension) {
        try {
            return saveBytes(inputStream.readAllBytes(), extension);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read image stream", ex);
        }
    }

    String toUrl(Path imagePath) {
        return urlPrefix + "/" + imagePath.getFileName();
    }

    private Path saveBytes(byte[] bytes, String extension) {
        try {
            Files.createDirectories(outputDir);
            String safeExtension = extension.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (safeExtension.isBlank()) {
                safeExtension = "bin";
            }
            Path target = outputDir.resolve(UUID.randomUUID() + "." + safeExtension);
            Files.write(target, bytes);
            return target;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to save embedded image", ex);
        }
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String normalized = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }
}
