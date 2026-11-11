package com.example.pdfmarkdown;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/** 不加载模型的保守占位：只替换独立数学行，混有正文或表格时保留原文。 */
final class FormulaPlaceholders {
    private FormulaPlaceholders() { }

    static boolean isStandaloneFormula(String source) {
        String text = Normalizer.normalize(source.strip(), Normalizer.Form.NFKC);
        if (text.isEmpty() || text.startsWith("[") || text.startsWith("|")) return false;
        if (!text.matches(".*[=∑∫√≤≥≠∏].*")) return false;
        // 普通单词、中文和未知字形不删除；单行等式中的变量才可自动留空。
        if (text.matches(".*[A-Za-z]{3,}.*")) return false;
        return text.codePoints().allMatch(cp -> Character.isWhitespace(cp)
                || (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z') || Character.isDigit(cp)
                || "+-=*/^_(){}[].,<>:;!%|\\".indexOf(cp) >= 0
                || Character.getType(cp) == Character.MATH_SYMBOL
                || (cp >= 0x370 && cp <= 0x3ff));
    }

    static String marker(int page, int number) {
        return "〔待填写公式 p" + page + "-" + number + "〕";
    }

    record Text(String value, int count) { }

    static Text fromOcr(String source, int page) {
        String[] lines = source.split("\\R", -1);
        int count = 0;
        for (int index = 0; index < lines.length; index++) {
            if (isStandaloneFormula(lines[index])) lines[index] = marker(page, ++count);
        }
        return new Text(String.join("\n", lines), count);
    }

    record Native(List<PdfMarkdownExtractor.Glyph> glyphs, int count) { }

    static Native fromNative(List<PdfMarkdownExtractor.Glyph> source, PdfMarkdownExtractor.Grid grid, int page) {
        List<PdfMarkdownExtractor.Glyph> result = new ArrayList<>(source);
        int count = 0;
        for (var line : PdfMarkdownExtractor.lines(source, false)) {
            // 不把表格里的数学值或正文表项当成待删除公式。
            if (grid != null && line.y() >= grid.ys().get(0)
                    && line.y() <= grid.ys().get(grid.ys().size() - 1)) continue;
            if (!isStandaloneFormula(line.text())) continue;
            result.removeAll(line.glyphs());
            result.add(new PdfMarkdownExtractor.Glyph(marker(page, ++count), line.left(), line.y(),
                    line.right() - line.left(), line.size(), 3));
        }
        return new Native(List.copyOf(result), count);
    }
}
