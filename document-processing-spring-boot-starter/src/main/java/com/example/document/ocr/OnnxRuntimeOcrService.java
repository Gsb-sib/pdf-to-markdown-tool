package com.example.document.ocr;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.example.document.autoconfigure.DocumentProcessingProperties;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

public class OnnxRuntimeOcrService implements OcrService, AutoCloseable {
    private static final int CHANNELS = 3;
    private static final int REC_HEIGHT = 48;
    private static final int REC_MIN_WIDTH = 48;
    private static final int DET_MIN_COMPONENT_AREA = 12;
    private static final int FALLBACK_MIN_LINE_HEIGHT = 8;
    private static final int FALLBACK_MIN_DARK_PIXELS = 8;
    private static final double DET_BOX_EXPAND_RATIO = 0.18;
    private static final double DET_LINE_Y_THRESHOLD = 12.0;

    private final OrtEnvironment environment;
    private final OrtSession detectionSession;
    private final OrtSession recognitionSession;
    private final String detectionInputName;
    private final String recognitionInputName;
    private final double detectionThreshold;
    private final double detectionBoxThreshold;
    private final int detectionMaxSide;
    private final int recognitionMaxWidth;
    private final int maxDetectionBoxes;
    private final List<String> characters;

    public OnnxRuntimeOcrService(DocumentProcessingProperties properties) {
        DocumentProcessingProperties.Ocr ocr = properties.getOcr();
        try {
            this.environment = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setIntraOpNumThreads(Math.max(1, ocr.getOnnxCpuThreads()));
            options.setInterOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            this.detectionSession = environment.createSession(ocr.getOnnxRuntimeDetectionModelPath(), options);
            this.recognitionSession = environment.createSession(ocr.getOnnxRuntimeRecognitionModelPath(), options);
            this.detectionInputName = detectionSession.getInputNames().iterator().next();
            this.recognitionInputName = recognitionSession.getInputNames().iterator().next();
            this.detectionThreshold = ocr.getOnnxRuntimeDetectionThreshold();
            this.detectionBoxThreshold = ocr.getOnnxRuntimeDetectionBoxThreshold();
            this.detectionMaxSide = Math.max(320, ocr.getOnnxRuntimeDetectionMaxSide());
            this.recognitionMaxWidth = Math.max(REC_MIN_WIDTH, ocr.getOnnxRuntimeRecognitionMaxWidth());
            this.maxDetectionBoxes = ocr.getOnnxRuntimeMaxDetectionBoxes();
            this.characters = loadCharacters(recognitionSession, Path.of(ocr.getOnnxRuntimeDictionaryPath()));
        } catch (OrtException ex) {
            throw new IllegalStateException("Failed to initialize ONNX Runtime OCR", ex);
        }
    }

    @Override
    public String recognize(Path imagePath) {
        return recognizeDetailed(imagePath).text();
    }

    @Override
    public OcrResult recognizeDetailed(Path imagePath) {
        try {
            BufferedImage image = ImageIO.read(imagePath.toFile());
            if (image == null) {
                throw new IllegalStateException("Failed to read image for ONNX Runtime OCR: " + imagePath);
            }
            return recognizeDetailed(image);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read image for ONNX Runtime OCR: " + imagePath, ex);
        }
    }

    @Override
    public String recognize(BufferedImage image) {
        return recognizeDetailed(image).text();
    }

    @Override
    public OcrResult recognizeDetailed(BufferedImage image) {
        long started = System.nanoTime();
        try {
            List<TextBox> boxes = detectTextBoxes(image);
            if (boxes.isEmpty()) {
                boxes = fallbackTextLines(image);
                if (boxes.isEmpty()) {
                    DecodeResult fallback = recognizeLine(image);
                    long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                    return new OcrResult(fallback.text(), fallback.score(), fallback.score(), "det-empty-fallback-rec", 0,
                            duration);
                }
            }

            StringBuilder text = new StringBuilder();
            double scoreSum = 0.0;
            int scoreCount = 0;
            for (TextBox box : boxes) {
                BufferedImage crop = crop(image, box);
                DecodeResult line = recognizeLine(crop);
                if (!line.text().isBlank()) {
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(line.text());
                    scoreSum += line.score();
                    scoreCount++;
                }
            }
            long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            double score = scoreCount == 0 ? 0.0 : scoreSum / scoreCount;
            return new OcrResult(text.toString(), score, score, "ok", 0, duration);
        } catch (OrtException ex) {
            throw new IllegalStateException("ONNX Runtime OCR inference failed", ex);
        }
    }

    @Override
    public boolean isThreadSafe() {
        return true;
    }

    @Override
    public void close() throws Exception {
        detectionSession.close();
        recognitionSession.close();
    }

    private List<TextBox> detectTextBoxes(BufferedImage image) throws OrtException {
        DetPreprocessedImage preprocessed = preprocessDetection(image);
        try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(preprocessed.data()),
                new long[] {1, CHANNELS, preprocessed.height(), preprocessed.width()});
                OrtSession.Result result = detectionSession.run(Map.of(detectionInputName, input))) {
            float[][] probabilityMap = detectionMap(result.get(0).getValue());
            List<TextBox> components = connectedTextBoxes(probabilityMap, preprocessed, image.getWidth(), image.getHeight());
            return mergeAndSortBoxes(components);
        }
    }

    private DetPreprocessedImage preprocessDetection(BufferedImage source) {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        double scale = Math.min(1.0, detectionMaxSide / (double) Math.max(sourceWidth, sourceHeight));
        int resizedWidth = Math.max(32, roundToMultipleOf32((int) Math.round(sourceWidth * scale)));
        int resizedHeight = Math.max(32, roundToMultipleOf32((int) Math.round(sourceHeight * scale)));
        BufferedImage resized = resize(source, resizedWidth, resizedHeight);

        float[] input = new float[CHANNELS * resizedHeight * resizedWidth];
        int planeSize = resizedHeight * resizedWidth;
        for (int y = 0; y < resizedHeight; y++) {
            for (int x = 0; x < resizedWidth; x++) {
                int rgb = resized.getRGB(x, y);
                int offset = y * resizedWidth + x;
                input[offset] = (((rgb >> 16) & 0xff) / 255.0f - 0.5f) / 0.5f;
                input[planeSize + offset] = (((rgb >> 8) & 0xff) / 255.0f - 0.5f) / 0.5f;
                input[planeSize * 2 + offset] = ((rgb & 0xff) / 255.0f - 0.5f) / 0.5f;
            }
        }
        return new DetPreprocessedImage(input, resizedWidth, resizedHeight);
    }

    private DecodeResult recognizeLine(BufferedImage image) throws OrtException {
        RecPreprocessedImage preprocessed = preprocessRecognition(image);
        try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(preprocessed.data()),
                new long[] {1, CHANNELS, REC_HEIGHT, preprocessed.width()});
                OrtSession.Result result = recognitionSession.run(Map.of(recognitionInputName, input))) {
            return decode(result.get(0).getValue());
        }
    }

    private RecPreprocessedImage preprocessRecognition(BufferedImage source) {
        int paddedWidth = recognitionWidth(source);
        int resizedWidth = Math.max(1,
                Math.min(paddedWidth, (int) Math.ceil(REC_HEIGHT * (source.getWidth() / (double) source.getHeight()))));
        BufferedImage resized = new BufferedImage(paddedWidth, REC_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resized.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        graphics.drawImage(source, 0, 0, resizedWidth, REC_HEIGHT, null);
        graphics.dispose();

        float[] input = new float[CHANNELS * REC_HEIGHT * paddedWidth];
        int planeSize = REC_HEIGHT * paddedWidth;
        for (int y = 0; y < REC_HEIGHT; y++) {
            for (int x = 0; x < resizedWidth; x++) {
                int rgb = resized.getRGB(x, y);
                int offset = y * paddedWidth + x;
                input[offset] = (((rgb >> 16) & 0xff) / 255.0f - 0.5f) / 0.5f;
                input[planeSize + offset] = (((rgb >> 8) & 0xff) / 255.0f - 0.5f) / 0.5f;
                input[planeSize * 2 + offset] = ((rgb & 0xff) / 255.0f - 0.5f) / 0.5f;
            }
        }
        return new RecPreprocessedImage(input, paddedWidth);
    }

    private int recognitionWidth(BufferedImage source) {
        int width = (int) Math.ceil(REC_HEIGHT * (source.getWidth() / (double) source.getHeight()));
        width = Math.max(REC_MIN_WIDTH, Math.min(recognitionMaxWidth, width));
        return ((width + 31) / 32) * 32;
    }

    private float[][] detectionMap(Object value) {
        if (value instanceof float[][][][] output) {
            return output[0][0];
        }
        throw new IllegalStateException("Unsupported ONNX Runtime detection output type: " + value.getClass().getName());
    }

    private List<TextBox> connectedTextBoxes(float[][] map, DetPreprocessedImage preprocessed, int originalWidth,
            int originalHeight) {
        int height = map.length;
        int width = map[0].length;
        boolean[][] visited = new boolean[height][width];
        List<TextBox> boxes = new ArrayList<>();
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (visited[y][x] || map[y][x] <= detectionThreshold) {
                    continue;
                }
                int minX = x;
                int maxX = x;
                int minY = y;
                int maxY = y;
                int count = 0;
                double scoreSum = 0.0;
                Queue<Point> queue = new ArrayDeque<>();
                queue.add(new Point(x, y));
                visited[y][x] = true;
                while (!queue.isEmpty()) {
                    Point point = queue.remove();
                    count++;
                    scoreSum += map[point.y()][point.x()];
                    minX = Math.min(minX, point.x());
                    maxX = Math.max(maxX, point.x());
                    minY = Math.min(minY, point.y());
                    maxY = Math.max(maxY, point.y());
                    for (int[] direction : directions) {
                        int nextX = point.x() + direction[0];
                        int nextY = point.y() + direction[1];
                        if (nextX < 0 || nextY < 0 || nextX >= width || nextY >= height || visited[nextY][nextX]
                                || map[nextY][nextX] <= detectionThreshold) {
                            continue;
                        }
                        visited[nextY][nextX] = true;
                        queue.add(new Point(nextX, nextY));
                    }
                }
                double score = scoreSum / count;
                if (count < DET_MIN_COMPONENT_AREA || score < detectionBoxThreshold) {
                    continue;
                }
                double scaleX = originalWidth / (double) width;
                double scaleY = originalHeight / (double) height;
                TextBox box = expand(new TextBox(minX * scaleX, minY * scaleY, (maxX + 1) * scaleX,
                        (maxY + 1) * scaleY, score), originalWidth, originalHeight);
                if (box.width() > 3 && box.height() > 3) {
                    boxes.add(box);
                }
            }
        }
        boxes.sort(Comparator.comparingDouble(TextBox::score).reversed());
        if (boxes.size() > maxDetectionBoxes) {
            boxes = new ArrayList<>(boxes.subList(0, maxDetectionBoxes));
        }
        return boxes;
    }

    private List<TextBox> fallbackTextLines(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] darkByRow = new int[height];
        for (int y = 0; y < height; y++) {
            int dark = 0;
            for (int x = 0; x < width; x += 2) {
                if (isDark(image.getRGB(x, y))) {
                    dark++;
                }
            }
            darkByRow[y] = dark;
        }
        List<TextBox> lines = new ArrayList<>();
        int y = 0;
        while (y < height) {
            while (y < height && darkByRow[y] < FALLBACK_MIN_DARK_PIXELS) {
                y++;
            }
            int startY = y;
            while (y < height && darkByRow[y] >= FALLBACK_MIN_DARK_PIXELS) {
                y++;
            }
            int endY = y;
            if (endY - startY < FALLBACK_MIN_LINE_HEIGHT) {
                continue;
            }
            int minX = width;
            int maxX = 0;
            for (int row = startY; row < endY; row++) {
                for (int x = 0; x < width; x++) {
                    if (isDark(image.getRGB(x, row))) {
                        minX = Math.min(minX, x);
                        maxX = Math.max(maxX, x);
                    }
                }
            }
            if (maxX > minX) {
                TextBox box = expand(new TextBox(minX, startY, maxX + 1, endY, 0.35), width, height);
                if (box.width() > 10 && box.height() > 5) {
                    lines.add(box);
                }
            }
        }
        if (lines.size() > maxDetectionBoxes) {
            return new ArrayList<>(lines.subList(0, maxDetectionBoxes));
        }
        return lines;
    }

    private boolean isDark(int rgb) {
        int r = (rgb >> 16) & 0xff;
        int g = (rgb >> 8) & 0xff;
        int b = rgb & 0xff;
        return (r * 0.299 + g * 0.587 + b * 0.114) < 185;
    }

    private List<TextBox> mergeAndSortBoxes(List<TextBox> boxes) {
        boxes.sort(Comparator.comparingDouble(TextBox::centerY).thenComparingDouble(TextBox::x1));
        List<TextBox> merged = new ArrayList<>();
        for (TextBox box : boxes) {
            boolean didMerge = false;
            for (int i = 0; i < merged.size(); i++) {
                TextBox existing = merged.get(i);
                double verticalOverlap = Math.min(existing.y2(), box.y2()) - Math.max(existing.y1(), box.y1());
                double minHeight = Math.min(existing.height(), box.height());
                double horizontalGap = Math.max(0.0, Math.max(existing.x1(), box.x1()) - Math.min(existing.x2(), box.x2()));
                if (verticalOverlap / Math.max(1.0, minHeight) > 0.35 && horizontalGap < Math.max(existing.height(), box.height()) * 3.0) {
                    merged.set(i, existing.merge(box));
                    didMerge = true;
                    break;
                }
            }
            if (!didMerge) {
                merged.add(box);
            }
        }
        merged.sort((a, b) -> {
            if (Math.abs(a.y1() - b.y1()) > DET_LINE_Y_THRESHOLD) {
                return Double.compare(a.y1(), b.y1());
            }
            return Double.compare(a.x1(), b.x1());
        });
        return merged;
    }

    private TextBox expand(TextBox box, int maxWidth, int maxHeight) {
        double dx = box.width() * DET_BOX_EXPAND_RATIO;
        double dy = box.height() * DET_BOX_EXPAND_RATIO;
        return new TextBox(Math.max(0, box.x1() - dx), Math.max(0, box.y1() - dy), Math.min(maxWidth, box.x2() + dx),
                Math.min(maxHeight, box.y2() + dy), box.score());
    }

    private BufferedImage crop(BufferedImage image, TextBox box) {
        int x = clamp((int) Math.floor(box.x1()), 0, image.getWidth() - 1);
        int y = clamp((int) Math.floor(box.y1()), 0, image.getHeight() - 1);
        int right = clamp((int) Math.ceil(box.x2()), x + 1, image.getWidth());
        int bottom = clamp((int) Math.ceil(box.y2()), y + 1, image.getHeight());
        return image.getSubimage(x, y, right - x, bottom - y);
    }

    private DecodeResult decode(Object value) {
        float[][][] output = castRecognitionOutput(value);
        float[][] sequence = output[0];
        StringBuilder text = new StringBuilder();
        double scoreSum = 0.0;
        int scoreCount = 0;
        int previous = -1;
        for (float[] step : sequence) {
            int bestIndex = 0;
            float bestScore = -Float.MAX_VALUE;
            double denominator = 0.0;
            float max = -Float.MAX_VALUE;
            for (float score : step) {
                max = Math.max(max, score);
            }
            for (float score : step) {
                denominator += Math.exp(score - max);
            }
            for (int c = 0; c < step.length; c++) {
                if (step[c] > bestScore) {
                    bestScore = step[c];
                    bestIndex = c;
                }
            }
            if (bestIndex != 0 && bestIndex != previous && bestIndex < characters.size()) {
                text.append(characters.get(bestIndex));
                scoreSum += confidence(bestScore, max, denominator);
                scoreCount++;
            }
            previous = bestIndex;
        }
        return new DecodeResult(text.toString(), scoreCount == 0 ? 0.0 : scoreSum / scoreCount);
    }

    private double confidence(float bestScore, float max, double denominator) {
        if (bestScore >= 0.0f && bestScore <= 1.0f) {
            return bestScore;
        }
        return Math.exp(bestScore - max) / denominator;
    }

    private float[][][] castRecognitionOutput(Object value) {
        if (value instanceof float[][][] output) {
            return output;
        }
        throw new IllegalStateException("Unsupported ONNX Runtime recognition output type: " + value.getClass().getName());
    }

    private List<String> loadCharacters(OrtSession session, Path dictionaryPath) throws OrtException {
        String embeddedCharacters = session.getMetadata().getCustomMetadataValue("character").orElse(null);
        if (embeddedCharacters != null && !embeddedCharacters.isBlank()) {
            List<String> list = new ArrayList<>();
            list.add("blank");
            list.addAll(embeddedCharacters.lines().filter(line -> !line.isEmpty()).toList());
            return list;
        }
        try {
            List<String> list = new ArrayList<>();
            list.add("blank");
            list.addAll(Files.readAllLines(dictionaryPath));
            list.add(" ");
            return list;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load OCR dictionary: " + dictionaryPath, ex);
        }
    }

    private BufferedImage resize(BufferedImage source, int width, int height) {
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resized.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return resized;
    }

    private int roundToMultipleOf32(int value) {
        return Math.max(32, (int) Math.round(value / 32.0) * 32);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record DecodeResult(String text, double score) {
    }

    private record DetPreprocessedImage(float[] data, int width, int height) {
    }

    private record RecPreprocessedImage(float[] data, int width) {
    }

    private record Point(int x, int y) {
    }

    private record TextBox(double x1, double y1, double x2, double y2, double score) {
        double width() {
            return x2 - x1;
        }

        double height() {
            return y2 - y1;
        }

        double centerY() {
            return (y1 + y2) / 2.0;
        }

        TextBox merge(TextBox other) {
            double mergedScore = Math.max(score, other.score());
            return new TextBox(Math.min(x1, other.x1()), Math.min(y1, other.y1()), Math.max(x2, other.x2()),
                    Math.max(y2, other.y2()), mergedScore);
        }
    }
}
