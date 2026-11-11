package com.example.pdfmarkdown;

import com.example.document.autoconfigure.DocumentProcessingProperties;
import com.example.document.ocr.OcrService;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/** 独立工具的逐页提取器，不改变知识库使用的原转换实现。 */
final class PdfMarkdownExtractor {
    private final DocumentProcessingProperties properties;
    private final OcrService ocr;

    PdfMarkdownExtractor(DocumentProcessingProperties properties, OcrService ocr) {
        this.properties = properties;
        this.ocr = ocr;
    }

    record Extraction(String markdown, List<Path> images, boolean usedOcr, int formulaCount) { }

    record Formula(double left, double top, double right, double bottom, String latex) {
        Formula {
            // Markdown 的数学定界符内应使用 aligned，不能再嵌套独立的 align* 环境。
            if (latex.contains("\\begin{align*}") && latex.contains("\\end{align*}")) {
                latex = latex.replace("\\begin{align*}", "\\begin{aligned}")
                        .replace("\\end{align*}", "\\end{aligned}");
            }
        }
    }
    @FunctionalInterface interface FormulaRecognizer {
        List<Formula> recognize(BufferedImage page) throws IOException;
    }

    Extraction extract(Path source, Path assets) throws IOException {
        return extract(source, assets, null);
    }

    Extraction extract(Path source, Path assets, FormulaRecognizer formulas) throws IOException {
        return extract(source, assets, formulas, false);
    }

    Extraction extract(Path source, Path assets, FormulaRecognizer formulas, boolean exportFormulaImages) throws IOException {
        return extract(source, assets, formulas, exportFormulaImages, false, true);
    }

    Extraction extract(Path source, Path assets, FormulaRecognizer formulas, boolean exportFormulaImages,
            boolean formulaPlaceholders, boolean includeImages) throws IOException {
        StringBuilder result = new StringBuilder();
        List<Path> images = new ArrayList<>();
        boolean usedOcr = false;
        int formulaCount = 0;
        try (PDDocument document = Loader.loadPDF(source.toFile())) {
            PDFRenderer renderer = new PDFRenderer(document);
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                PDPage page = document.getPage(index);
                GlyphCollector collector = new GlyphCollector(index + 1);
                String nativeText = collector.getText(document);
                PageGraphics graphics = new PageGraphics(page);
                graphics.processPage(page);
                double pageArea = page.getMediaBox().getWidth() * page.getMediaBox().getHeight();
                // 保留可靠文字层；仅当前页缺少有效文字时使用 OCR。
                boolean scan = nativeText.isBlank() || (nativeText.strip().length() < 80
                        && graphics.imageArea / Math.max(1, pageArea) > 0.6);
                BufferedImage formulaPage = null;
                List<Formula> regions = List.of();
                if (formulas != null) {
                    // 数学模型独立于普通 OCR，识别失败时禁止静默降级成破损公式。
                    formulaPage = renderer.renderImageWithDPI(index, 200, ImageType.RGB);
                    regions = formulas.recognize(formulaPage).stream()
                            .sorted(Comparator.comparingDouble(Formula::top).thenComparingDouble(Formula::left)).toList();
                    formulaCount += regions.size();
                }
                if (scan) {
                    try {
                        double width = page.getCropBox().getWidth();
                        int maxWidth = properties.getOcr().getMaxRenderedPageWidth();
                        float dpi = properties.getOcr().getDpi();
                        if (maxWidth > 0) dpi = (float) Math.min(dpi, maxWidth * 72.0 / width);
                        BufferedImage image = renderer.renderImageWithDPI(index, dpi, ImageType.RGB);
                        // 真正空白的页不调用 OCR；向量轮廓文字也能进入识别。
                        if (tightCrop(image) == null) continue;
                        usedOcr = true;
                        String extracted = regions.isEmpty() ? readScan(image, index + 1)
                                : scanWithFormulas(image, regions, assets, images, index + 1, exportFormulaImages);
                        if (formulaPlaceholders) {
                            var placeholders = FormulaPlaceholders.fromOcr(extracted, index + 1);
                            extracted = placeholders.value();
                            formulaCount += placeholders.count();
                        }
                        result.append(extracted).append("\n\n");
                        if (includeImages && extracted.contains("[DIFFICULT_OCR_PAGE")) saveImages(graphics, assets, images, result);
                    } catch (Exception ex) {
                        if (!regions.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(
                                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                                "扫描页公式或周边文字处理失败，请查看日志后重试。",ex);
                        result.append("[DIFFICULT_OCR_PAGE page=").append(index + 1)
                                .append(", reason=ocr-or-render-failed-")
                                .append(ex.getClass().getSimpleName()).append("]\n\n");
                        if (includeImages) saveImages(graphics, assets, images, result);
                    }
                } else {
                    if (regions.isEmpty()) {
                        List<Glyph> textGlyphs = formulas == null ? collector.glyphs : restoreScripts(collector.glyphs);
                        if (formulaPlaceholders) {
                            var placeholders = FormulaPlaceholders.fromNative(textGlyphs, graphics.grid(), index + 1);
                            textGlyphs = placeholders.glyphs();
                            formulaCount += placeholders.count();
                        }
                        result.append(layout(textGlyphs, graphics.grid()));
                    }
                    else {
                        if (page.getRotation() != 0) throw new IOException("请先将旋转的文字页恢复为正向，再使用公式增强。");
                        List<Glyph> replaced = new ArrayList<>(collector.glyphs);
                        var replacements = new java.util.LinkedHashMap<String, String>();
                        StringBuilder references = new StringBuilder();
                        double width = page.getCropBox().getWidth(), height = page.getCropBox().getHeight();
                        double size = collector.glyphs.stream().mapToDouble(Glyph::size).sorted().skip(collector.glyphs.size()/2).findFirst().orElse(12);
                        for (Formula formula : regions) {
                            // 用字形中心判断是否落在公式框内，避免误删框旁的正文或公式编号。
                            List<Glyph> originalFormula = replaced.stream().filter(g -> g.x+g.width/2 >= formula.left*width-1
                                    && g.x+g.width/2 <= formula.right*width+1
                                    && g.y >= formula.top*height-1
                                    && g.y-g.size*.35 <= formula.bottom*height+1).toList();
                            String nativeSymbols = String.join("",originalFormula.stream().map(Glyph::text).toList());
                            if (containsBodyWords(nativeSymbols)) {
                                // 检测框混入正文时，保留可靠文字层，避免模型将英文单词改成数学符号。
                                references.append("[FORMULA_REVIEW_REQUIRED page=").append(index+1)
                                        .append(", reason=contains-body-text]\n")
                                        .append(formulaReference(formulaPage, formula, assets, images, exportFormulaImages)).append("\n\n");
                                continue;
                            }
                            replaced.removeAll(originalFormula);
                            Formula reconciled = new Formula(formula.left,formula.top,formula.right,formula.bottom,
                                    preserveSourceCase(formula.latex,nativeSymbols));
                            double estimated = formulaBaseline(originalFormula, (formula.top+formula.bottom)*height/2);
                            double baseline = replaced.stream().filter(g -> g.y>=formula.top*height-1
                                    && g.y<=formula.bottom*height+size*.25)
                                    .min(Comparator.comparingDouble(g -> Math.abs(g.y-estimated)))
                                    .map(Glyph::y).orElse(estimated);
                            // 先用占位字形参与原排版，避免 LaTeX 换行影响阅读顺序和分栏判断。
                            boolean inline = replaced.stream().filter(g -> Math.abs(g.y-baseline)<=Math.max(2,size*.25))
                                    .mapToLong(g -> g.text.codePoints().filter(Character::isLetter).count()).sum()>3;
                            String token = "FORMULA"+UUID.randomUUID().toString().replace("-", "");
                            String latex = inline ? "$"+reconciled.latex+"$" : "\n$$\n"+reconciled.latex+"\n$$\n";
                            replacements.put(token, latex);
                            if (exportFormulaImages) references.append(formulaReference(formulaPage, reconciled,
                                    assets, images, true)).append("\n\n");
                            replaced.add(new Glyph(token, formula.left*width, baseline,
                                    (formula.right-formula.left)*width, size, 3));
                        }
                        String body = layout(restoreScripts(replaced), graphics.grid());
                        for (var entry : replacements.entrySet()) body = body.replace(entry.getKey(), entry.getValue());
                        result.append(body).append("\n\n").append(references);
                    }
                    result.append("\n\n");
                    if (includeImages) saveImages(graphics, assets, images, result);
                }
            }
        }
        return new Extraction(result.toString().stripTrailing(), List.copyOf(images), usedOcr, formulaCount);
    }

    /** 仅补文字层中位置、字号均明确的简单上下标，不猜测分式或扫描文字。 */
    static List<Glyph> restoreScripts(List<Glyph> glyphs) {
        List<Glyph> remaining = new ArrayList<>(glyphs);
        List<Glyph> result = new ArrayList<>();
        for (Glyph base : glyphs.stream().sorted(Comparator.comparingDouble(Glyph::x)).toList()) {
            if (!remaining.contains(base) || base.text.codePointCount(0, base.text.length()) != 1) continue;
            String symbol = Normalizer.normalize(base.text, Normalizer.Form.NFKC);
            if (!symbol.matches("[A-Za-z]")) continue;
            List<Glyph> scripts = remaining.stream().filter(g -> g != base && g.size < base.size*.9
                    && g.size >= base.size*.45 && g.x >= base.right()-base.size*.2
                    && g.x <= base.right()+base.size*2.5
                    && Math.abs(g.y-base.y) >= base.size*.15 && Math.abs(g.y-base.y) <= base.size*.8
                    && Normalizer.normalize(g.text, Normalizer.Form.NFKC).matches("[A-Za-z0-9+−()\\-]"))
                    .sorted(Comparator.comparingDouble(Glyph::x)).toList();
            List<Glyph> used = new ArrayList<>();
            StringBuilder sub = new StringBuilder(), sup = new StringBuilder();
            for (boolean below : new boolean[] {true, false}) {
                double right = base.right();
                double baseline = Double.NaN;
                for (Glyph g : scripts) {
                    if ((g.y>base.y) != below || g.x-right > base.size*.3
                            || (!Double.isNaN(baseline) && Math.abs(g.y-baseline)>base.size*.12)) continue;
                    (below ? sub : sup).append(Normalizer.normalize(g.text, Normalizer.Form.NFKC).replace('−','-'));
                    used.add(g); right = g.right(); baseline = g.y;
                }
            }
            if (used.isEmpty()) continue;
            // 普通正文中的字母后接缩小数字也可能是脚注；仅数学字形或下标才自动转换。
            int cp = base.text.codePointAt(0);
            if (sub.isEmpty() && cp < 0x1D400) continue;
            String name = Character.getName(cp);
            if (name != null && name.contains("DOUBLE-STRUCK")) symbol = "\\mathbb{"+symbol+"}";
            else if (name != null && name.contains("SCRIPT")) symbol = "\\mathcal{"+symbol+"}";
            String latex = "$"+symbol+(sub.isEmpty()?"":"_{"+sub+"}")+(sup.isEmpty()?"":"^{"+sup+"}")+"$";
            remaining.remove(base); remaining.removeAll(used);
            double right = Math.max(base.right(),used.stream().mapToDouble(Glyph::right).max().orElse(base.right()));
            result.add(new Glyph(latex,base.x,base.y,right-base.x,base.size,base.space));
        }
        result.addAll(remaining);
        return result;
    }

    static String preserveSourceCase(String latex, String source) {
        // 仅当文字层明确只出现同一大小写时校正单字母变量；不凭模型或标准答案猜测。
        String symbols = Normalizer.normalize(source, Normalizer.Form.NFKC).replaceAll("[A-Za-z]{3,}", "");
        StringBuilder result = new StringBuilder();
        var tokens = java.util.regex.Pattern.compile("\\\\begin\\{array}\\{[^}]*}|\\\\(?:begin|end)\\{[^}]*}|\\\\[A-Za-z]+|\\\\.|[A-Za-z]|[^A-Za-z\\\\]+").matcher(latex);
        while (tokens.find()) {
            String token = tokens.group();
            if (token.length()==1 && Character.isLetter(token.charAt(0))) {
                char current = token.charAt(0);
                char opposite = Character.isLowerCase(current) ? Character.toUpperCase(current) : Character.toLowerCase(current);
                if (symbols.indexOf(current)<0 && symbols.indexOf(opposite)>=0) token = String.valueOf(opposite);
            }
            result.append(token);
        }
        return result.toString();
    }

    private static double formulaBaseline(List<Glyph> glyphs, double fallback) {
        if (glyphs.isEmpty()) return fallback;
        // 正文字号的主基线通常包含最多字形；上下标与分式不应单独决定插入高度。
        var counts = new java.util.HashMap<Long, Integer>();
        for (Glyph glyph : glyphs) counts.merge(Math.round(glyph.y*2), 1, Integer::sum);
        return counts.entrySet().stream().sorted(java.util.Map.Entry.<Long,Integer>comparingByValue().reversed()
                .thenComparingDouble(e -> Math.abs(e.getKey()/2.0-fallback)))
                .findFirst().map(e -> e.getKey()/2.0).orElse(fallback);
    }

    private static boolean containsBodyWords(String source) {
        String text = Normalizer.normalize(source, Normalizer.Form.NFKC);
        return java.util.regex.Pattern.compile("(?i)(?<![a-z])(?:the|and|for|say|years?|where|when|then|with|that|such|from|given|period|process)(?![a-z])")
                .matcher(text).find();
    }

    private static String formulaReference(BufferedImage image, Formula formula, Path assets, List<Path> images,
            boolean export) throws IOException {
        if (!export) return "";
        int x = Math.max(0, (int)(formula.left*image.getWidth())-2);
        int y = Math.max(0, (int)(formula.top*image.getHeight())-2);
        int right = Math.min(image.getWidth(), (int)Math.ceil(formula.right*image.getWidth())+2);
        int bottom = Math.min(image.getHeight(), (int)Math.ceil(formula.bottom*image.getHeight())+2);
        Path target = assets.resolve("formula-"+UUID.randomUUID()+".png");
        ImageIO.write(image.getSubimage(x,y,right-x,bottom-y), "png", target.toFile());
        images.add(target);
        return "[公式原图](assets/"+target.getFileName()+")";
    }

    private String scanWithFormulas(BufferedImage image, List<Formula> regions, Path assets,
            List<Path> images, int page, boolean exportFormulaImages) throws IOException {
        StringBuilder out = new StringBuilder();
        int cursor = 0, index = 0;
        while (index < regions.size()) {
            List<Formula> band = new ArrayList<>();
            Formula first = regions.get(index++);
            double bottom = first.bottom;
            band.add(first);
            while (index < regions.size() && regions.get(index).top <= bottom) {
                Formula next = regions.get(index++); band.add(next); bottom = Math.max(bottom,next.bottom);
            }
            int top = Math.max(cursor,(int)Math.floor(first.top*image.getHeight())-2);
            int end = Math.min(image.getHeight(),(int)Math.ceil(bottom*image.getHeight())+2);
            appendScanRegion(out,image,0,cursor,image.getWidth(),top-cursor,page);
            band.sort(Comparator.comparingDouble(Formula::left));
            int left = 0;
            for (Formula formula : band) {
                int x = Math.max(left,(int)Math.floor(formula.left*image.getWidth())-2);
                appendScanRegion(out,image,left,top,x-left,end-top,page);
                out.append("$$\n").append(formula.latex).append("\n$$\n\n");
                if (exportFormulaImages) out.append(formulaReference(image,formula,assets,images,true)).append("\n\n");
                left = Math.max(left,Math.min(image.getWidth(),(int)Math.ceil(formula.right*image.getWidth())+2));
            }
            appendScanRegion(out,image,left,top,image.getWidth()-left,end-top,page);
            cursor = end;
        }
        appendScanRegion(out,image,0,cursor,image.getWidth(),image.getHeight()-cursor,page);
        return out.toString().stripTrailing();
    }

    private static void saveImages(PageGraphics graphics, Path assets, List<Path> images,
            StringBuilder result) throws IOException {
        for (PDImage image : graphics.images) {
            Path target = assets.resolve(UUID.randomUUID() + ".png");
            if (!ImageIO.write(image.getImage(), "png", target.toFile())) {
                throw new IOException("无法编码 PDF 图片");
            }
            images.add(target);
            result.append("\n![](assets/").append(target.getFileName()).append(")\n");
        }
    }

    private String readScan(BufferedImage image, int page) {
        image = deskew(image);
        Grid grid = imageGrid(image);
        if (grid != null) return scanTableWithSurroundings(image, grid, page);
        OcrService.OcrResult best = ocr.recognizeDetailed(image);
        if (image.getWidth() > image.getHeight() || best.lowConfidence(0.8) || best.likelyGarbled()) {
            for (int degrees : new int[] {90, 270, 180}) {
                OcrService.OcrResult candidate = ocr.recognizeDetailed(rotate(image, degrees));
                if (rank(candidate) > rank(best)) best = candidate;
            }
        }
        String text = best.text() == null ? "" : best.text();
        if (best.likelyGarbled() || best.lowConfidence(properties.getOcr().getLowConfidenceThreshold())) {
            return "[DIFFICULT_OCR_PAGE page=" + page + ", reason=low-confidence-or-empty]\n" + text;
        }
        return text;
    }

    private String scanTableWithSurroundings(BufferedImage image, Grid grid, int page) {
        int top = Math.max(0, grid.ys.get(0).intValue() - 5);
        int bottom = Math.min(image.getHeight(), grid.ys.get(grid.ys.size() - 1).intValue() + 6);
        int left = Math.max(0, grid.xs.get(0).intValue() - 5);
        int right = Math.min(image.getWidth(), grid.xs.get(grid.xs.size() - 1).intValue() + 6);
        StringBuilder out = new StringBuilder();
        appendScanRegion(out, image, 0, 0, image.getWidth(), top, page);
        appendScanRegion(out, image, 0, top, left, bottom - top, page);
        out.append(scanTable(image, grid)).append("\n\n");
        appendScanRegion(out, image, right, top, image.getWidth() - right, bottom - top, page);
        appendScanRegion(out, image, 0, bottom, image.getWidth(), image.getHeight() - bottom, page);
        return out.toString().stripTrailing();
    }

    private void appendScanRegion(StringBuilder out, BufferedImage image, int x, int y, int w, int h, int page) {
        if (w < 2 || h < 2) return;
        BufferedImage region = image.getSubimage(x, y, w, h);
        if (tightCrop(region) != null) out.append(readScan(region, page)).append("\n\n");
    }

    // 水平投影搜索校正小角度倾斜，只在投影集中度明显提高时旋转。
    static BufferedImage deskew(BufferedImage image) {
        double scale = Math.min(1, 900.0 / Math.max(image.getWidth(), image.getHeight()));
        int width = Math.max(1, (int) (image.getWidth() * scale));
        int height = Math.max(1, (int) (image.getHeight() * scale));
        List<int[]> pixels = new ArrayList<>();
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            if (dark(image, Math.min(image.getWidth()-1, (int)(x / scale)),
                    Math.min(image.getHeight()-1, (int)(y / scale)))) pixels.add(new int[] {x, y});
        }
        if (pixels.size() < 50) return image;
        double best = 0, baseline = 0, angle = 0;
        for (int step = -28; step <= 28; step++) {
            double degrees = step * .25, slope = Math.tan(Math.toRadians(degrees));
            int[] histogram = new int[height + width + 4];
            for (int[] p : pixels) histogram[(int) Math.round(p[1] + slope * p[0]) + width / 2 + 1]++;
            double score = 0;
            for (int count : histogram) score += (double) count * count;
            if (step == 0) baseline = score;
            if (score > best) { best = score; angle = degrees; }
        }
        return Math.abs(angle) >= .5 && best > baseline * 1.15 ? rotate(image, angle) : image;
    }

    private static double rank(OcrService.OcrResult value) {
        return value.score() + (value.likelyGarbled() ? -2 : 1)
                + Math.min(0.1, (value.text() == null ? 0 : value.text().length()) / 10000.0);
    }

    private String scanTable(BufferedImage image, Grid grid) {
        List<List<String>> rows = new ArrayList<>();
        for (int r = 0; r < grid.ys.size() - 1; r++) {
            List<String> cells = new ArrayList<>();
            for (int col = 0; col < grid.xs.size() - 1; col++) {
                int x = (int) Math.ceil(grid.xs.get(col)) + 4;
                int y = (int) Math.ceil(grid.ys.get(r)) + 4;
                int right = (int) Math.floor(grid.xs.get(col + 1)) - 4;
                int bottom = (int) Math.floor(grid.ys.get(r + 1)) - 4;
                BufferedImage crop = tightCrop(image.getSubimage(x, y, Math.max(1, right - x), Math.max(1, bottom - y)));
                if (crop == null) cells.add("");
                else if (crop.getWidth() > crop.getHeight() * 5 && crop.getHeight() < image.getHeight() * .004) {
                    cells.add("—");
                } else {
                    BufferedImage padded = new BufferedImage(crop.getWidth() + 16, crop.getHeight() + 12, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = padded.createGraphics();
                    g.setColor(Color.WHITE); g.fillRect(0, 0, padded.getWidth(), padded.getHeight());
                    g.drawImage(crop, 8, 6, null); g.dispose();
                    cells.add(ocr.recognizeDetailed(padded).text().strip());
                }
            }
            rows.add(cells);
        }
        return markdownTable(rows);
    }

    private static BufferedImage tightCrop(BufferedImage image) {
        int left = image.getWidth(), top = image.getHeight(), right = -1, bottom = -1;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            if (dark(image, x, y)) { left = Math.min(left, x); right = Math.max(right, x); top = Math.min(top, y); bottom = Math.max(bottom, y); }
        }
        return right < left ? null : image.getSubimage(left, top, right - left + 1, bottom - top + 1);
    }

    private static boolean dark(BufferedImage image, int x, int y) {
        int rgb = image.getRGB(x, y);
        return (((rgb >> 16) & 255) + ((rgb >> 8) & 255) + (rgb & 255)) < 420;
    }

    private static Grid imageGrid(BufferedImage image) {
        List<Double> horizontal = new ArrayList<>();
        // 表格边框必须形成贯穿多行的网格，普通文本行不能触发。
        for (int y = 0; y < image.getHeight(); y++) {
            int longest = 0, run = 0;
            for (int x = 0; x < image.getWidth(); x++) {
                run = dark(image, x, y) ? run + 1 : 0; longest = Math.max(longest, run);
            }
            if (longest > image.getWidth() * .45) horizontal.add((double) y);
        }
        horizontal = clusters(horizontal, 5);
        if (horizontal.size() < 3) return null;
        int top = horizontal.get(0).intValue(), bottom = horizontal.get(horizontal.size() - 1).intValue();
        List<Double> vertical = new ArrayList<>();
        for (int x = 0; x < image.getWidth(); x++) {
            int count = 0;
            for (int y = top; y <= bottom; y++) if (dark(image, x, y)) count++;
            if (count > (bottom - top) * .85) vertical.add((double) x);
        }
        vertical = clusters(vertical, 5);
        return vertical.size() < 3 ? null : new Grid(vertical, horizontal);
    }

    static BufferedImage rotate(BufferedImage source, double degrees) {
        double radians = Math.toRadians(degrees), sin = Math.abs(Math.sin(radians)), cos = Math.abs(Math.cos(radians));
        int w = (int) Math.ceil(source.getWidth() * cos + source.getHeight() * sin);
        int h = (int) Math.ceil(source.getWidth() * sin + source.getHeight() * cos);
        BufferedImage target = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.translate(w / 2.0, h / 2.0); g.rotate(radians);
        g.drawImage(source, -source.getWidth() / 2, -source.getHeight() / 2, null); g.dispose();
        return target;
    }

    record Glyph(String text, double x, double y, double width, double size, double space) {
        double right() { return x + width; }
    }

    private static final class GlyphCollector extends PDFTextStripper {
        final List<Glyph> glyphs = new ArrayList<>();
        GlyphCollector(int page) throws IOException { setStartPage(page); setEndPage(page); }
        @Override protected void writeString(String text, List<TextPosition> positions) throws IOException {
            // 使用 PDFBox 已去除重叠字形的结果，避免模拟粗体或重复文字层产生双份正文。
            for (TextPosition p : positions) glyphs.add(new Glyph(p.getUnicode(), p.getXDirAdj(), p.getYDirAdj(),
                    p.getWidthDirAdj(), p.getFontSizeInPt(), p.getWidthOfSpace()));
            super.writeString(text, positions);
        }
    }

    record Line(List<Glyph> glyphs, double y) {
        double left() { return glyphs.get(0).x; }
        double right() { return glyphs.get(glyphs.size() - 1).right(); }
        double size() { return glyphs.stream().mapToDouble(Glyph::size).max().orElse(12); }
        String text() {
            StringBuilder s = new StringBuilder(); Glyph previous = null;
            for (Glyph glyph : glyphs) {
                boolean mathBoundary = previous != null && !previous.text.isEmpty() && !glyph.text.isEmpty() && ((isMath(previous.text)
                        && Character.isLetter(glyph.text.codePointAt(0))) || (isMath(glyph.text)
                        && Character.isLetter(previous.text.codePointBefore(previous.text.length()))));
                if (previous != null && !previous.text.endsWith(" ") && !glyph.text.startsWith(" ")
                        && (glyph.x - previous.right() > Math.max(1.0, previous.space * .45)
                        || mathBoundary)) s.append(' ');
                s.append(glyph.text); previous = glyph;
            }
            return s.toString().strip();
        }
        private static boolean isMath(String text) {
            return text.startsWith("$") || text.matches("FORMULA[0-9a-f]{32}");
        }
    }

    static List<Line> lines(List<Glyph> glyphs, boolean splitWideGaps) {
        List<Glyph> sorted = glyphs.stream().sorted(Comparator.comparingDouble(Glyph::y).thenComparingDouble(Glyph::x)).toList();
        List<List<Glyph>> rows = new ArrayList<>();
        for (Glyph glyph : sorted) {
            List<Glyph> row = rows.isEmpty() ? null : rows.get(rows.size() - 1);
            if (row == null || Math.abs(row.get(0).y - glyph.y) > Math.max(2.0, glyph.size * .25)) {
                row = new ArrayList<>(); rows.add(row);
            }
            row.add(glyph);
        }
        List<Line> result = new ArrayList<>();
        for (List<Glyph> row : rows) {
            row.sort(Comparator.comparingDouble(Glyph::x));
            List<Glyph> segment = new ArrayList<>(); Glyph previous = null;
            for (Glyph glyph : row) {
                if (splitWideGaps && previous != null && !glyph.text.startsWith("FORMULA")
                        && !previous.text.startsWith("FORMULA")
                        && glyph.x - previous.right() > Math.max(20, glyph.size * 1.8)) {
                    result.add(new Line(List.copyOf(segment), segment.get(0).y)); segment.clear();
                }
                segment.add(glyph); previous = glyph;
            }
            if (!segment.isEmpty()) result.add(new Line(List.copyOf(segment), segment.get(0).y));
        }
        return result;
    }

    static String layout(List<Glyph> glyphs, Grid grid) {
        StringBuilder output = new StringBuilder();
        List<Glyph> outside = new ArrayList<>(glyphs);
        if (grid != null) {
            List<List<String>> rows = new ArrayList<>();
            for (int r = 0; r < grid.ys.size() - 1; r++) {
                List<String> row = new ArrayList<>();
                for (int col = 0; col < grid.xs.size() - 1; col++) {
                    double x1 = grid.xs.get(col), x2 = grid.xs.get(col + 1);
                    double y1 = grid.ys.get(r), y2 = grid.ys.get(r + 1);
                    List<Glyph> cell = glyphs.stream().filter(g -> g.x + g.width / 2 >= x1 && g.x + g.width / 2 < x2
                            && g.y >= y1 && g.y < y2).toList();
                    outside.removeAll(cell);
                    row.add(String.join(" ", lines(cell, false).stream().map(Line::text).toList()));
                }
                rows.add(row);
            }
            double top = grid.ys.get(0);
            appendLines(output, lines(outside.stream().filter(g -> g.y < top).toList(), true));
            output.append(markdownTable(rows)).append("\n\n");
            outside.removeIf(g -> g.y < top);
        }
        appendLines(output, lines(outside, true));
        return output.toString().stripTrailing();
    }

    private static void appendLines(StringBuilder out, List<Line> input) {
        if (input.isEmpty()) return;
        List<Line> ordered = new ArrayList<>();
        // 重复出现的宽空隙构成栏间通道；整行标题在分栏前后单独处理。
        List<Double> cuts = new ArrayList<>();
        for (int i = 0; i < input.size() - 1; i++) {
            Line a = input.get(i), b = input.get(i + 1);
            if (Math.abs(a.y - b.y) < 3 && b.left() - a.right() > 25) cuts.add((a.right() + b.left()) / 2);
        }
        double cut = cuts.size() >= 3 ? cuts.stream().mapToDouble(Double::doubleValue).average().orElse(-1) : -1;
        if (cut > 0) {
            List<Line> band = new ArrayList<>();
            for (Line line : input) {
                if (line.left() < cut && line.right() > cut) {
                    appendColumnBand(ordered, band, cut); band.clear(); ordered.add(line);
                } else band.add(line);
            }
            appendColumnBand(ordered, band, cut);
        } else ordered.addAll(input);
        double[] sizes = input.stream().mapToDouble(Line::size).sorted().toArray();
        double body = sizes[(sizes.length - 1) / 2];
        for (Line line : ordered) {
            String value = line.text();
            if (value.isBlank()) continue;
            if (line.size() > body * 1.3 && value.length() < 90) out.append(line.size() > body * 1.6 ? "# " : "## ");
            out.append(value).append('\n');
        }
    }

    private static void appendColumnBand(List<Line> result, List<Line> band, double cut) {
        band.stream().filter(l -> l.left() < cut).sorted(Comparator.comparingDouble(Line::y)).forEach(result::add);
        band.stream().filter(l -> l.left() >= cut).sorted(Comparator.comparingDouble(Line::y)).forEach(result::add);
    }

    private static String markdownTable(List<List<String>> rows) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            out.append("| ").append(String.join(" | ", rows.get(i).stream().map(s -> s.replace("|", "\\|").replace('\n', ' ')).toList())).append(" |\n");
            if (i == 0) out.append("| ").append(String.join(" | ", java.util.Collections.nCopies(rows.get(i).size(), "---"))).append(" |\n");
        }
        return out.toString().stripTrailing();
    }

    record Grid(List<Double> xs, List<Double> ys) { }

    private static List<Double> clusters(List<Double> values, double tolerance) {
        List<Double> sorted = values.stream().sorted().toList(), result = new ArrayList<>();
        double sum = 0, previous = -1; int count = 0;
        for (double value : sorted) {
            if (count > 0 && value - previous > tolerance) { result.add(sum / count); sum = 0; count = 0; }
            sum += value; count++; previous = value;
        }
        if (count > 0) result.add(sum / count);
        return result;
    }

    private static final class PageGraphics extends PDFGraphicsStreamEngine {
        final List<PDImage> images = new ArrayList<>();
        final List<Segment> paths = new ArrayList<>(), painted = new ArrayList<>();
        double imageArea; Point2D current = new Point2D.Double(), start = current;
        PageGraphics(PDPage page) { super(page); }
        Grid grid() {
            if (getPage().getRotation() != 0) return null;
            List<Segment> vertical = painted.stream().filter(s -> Math.abs(s.x1-s.x2)<1 && Math.abs(s.y1-s.y2)>20).toList();
            List<Segment> horizontal = painted.stream().filter(s -> Math.abs(s.y1-s.y2)<1 && Math.abs(s.x1-s.x2)>20).toList();
            List<Double> x = clusters(vertical.stream().map(s -> s.x1).toList(), 2);
            List<Double> y = clusters(horizontal.stream().map(s -> s.y1).toList(), 2);
            if (x.size() < 3 || y.size() < 3) return null;
            // 仅接受实际绘制、完整相交的矩形网格，防止将装饰线或剪裁路径误当表格。
            double left=x.get(0), right=x.get(x.size()-1), top=y.get(0), bottom=y.get(y.size()-1);
            boolean fullVertical = x.stream().allMatch(px -> vertical.stream().anyMatch(s -> Math.abs(s.x1-px)<2
                    && Math.min(s.y1,s.y2)<=top+2 && Math.max(s.y1,s.y2)>=bottom-2));
            boolean fullHorizontal = y.stream().allMatch(py -> horizontal.stream().anyMatch(s -> Math.abs(s.y1-py)<2
                    && Math.min(s.x1,s.x2)<=left+2 && Math.max(s.x1,s.x2)>=right-2));
            return fullVertical && fullHorizontal ? new Grid(x,y) : null;
        }
        @Override public void drawImage(PDImage image) {
            images.add(image);
            var m = getGraphicsState().getCurrentTransformationMatrix();
            imageArea += Math.abs(m.getScaleX() * m.getScaleY() - m.getShearX() * m.getShearY());
        }
        @Override public void appendRectangle(Point2D a, Point2D b, Point2D c, Point2D d) {
            moveTo((float)a.getX(), (float)a.getY()); lineTo((float)b.getX(), (float)b.getY());
            lineTo((float)c.getX(), (float)c.getY()); lineTo((float)d.getX(), (float)d.getY()); closePath();
        }
        @Override public void moveTo(float x, float y) { current = new Point2D.Double(x, y); start = current; }
        @Override public void lineTo(float x, float y) {
            var crop = getPage().getCropBox();
            paths.add(new Segment(current.getX()-crop.getLowerLeftX(), crop.getUpperRightY()-current.getY(),
                    x-crop.getLowerLeftX(), crop.getUpperRightY()-y));
            current = new Point2D.Double(x, y);
        }
        @Override public Point2D getCurrentPoint() { return current; }
        @Override public void closePath() { lineTo((float)start.getX(), (float)start.getY()); }
        @Override public void curveTo(float a, float b, float c, float d, float e, float f) { current = new Point2D.Double(e, f); }
        @Override public void clip(int windingRule) { }
        @Override public void endPath() { paths.clear(); }
        @Override public void strokePath() { painted.addAll(paths); paths.clear(); }
        @Override public void fillPath(int windingRule) { paths.clear(); }
        @Override public void fillAndStrokePath(int windingRule) { strokePath(); }
        @Override public void shadingFill(COSName name) { }
    }

    private record Segment(double x1, double y1, double x2, double y2) { }
}
