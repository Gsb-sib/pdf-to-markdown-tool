package com.example.document.ocr;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

public interface OcrService {
    String recognize(Path imagePath);

    String recognize(BufferedImage image);

    default OcrResult recognizeDetailed(Path imagePath) {
        return OcrResult.plain(recognize(imagePath));
    }

    default OcrResult recognizeDetailed(BufferedImage image) {
        return OcrResult.plain(recognize(image));
    }

    default boolean isThreadSafe() {
        return false;
    }

    record OcrResult(String text, double score, double quality, String reason, int rotation, long durationMillis) {
        public static OcrResult plain(String text) {
            return new OcrResult(text, 1.0, 1.0, "ok", 0, -1);
        }

        public boolean lowConfidence(double threshold) {
            return score > 0.0 && score < threshold;
        }

        public boolean likelyGarbled() {
            if (text == null || text.isBlank()) {
                return true;
            }
            String compact = text.replaceAll("\\s+", "");
            long questionMarks = compact.chars().filter(ch -> ch == '?' || ch == '\uff1f').count();
            long useful = compact.chars().filter(ch -> Character.isLetterOrDigit(ch)
                    || Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN).count();
            double questionRatio = questionMarks / (double) compact.length();
            double usefulRatio = useful / (double) compact.length();
            return questionRatio > 0.25 || (compact.length() < 4 && quality < 0.70) || usefulRatio < 0.35;
        }
    }
}
