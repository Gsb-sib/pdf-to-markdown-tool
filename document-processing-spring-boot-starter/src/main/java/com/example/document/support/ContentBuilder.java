package com.example.document.support;

import java.util.concurrent.atomic.AtomicInteger;

final class ContentBuilder {
    private final StringBuilder content = new StringBuilder();
    private final AtomicInteger textCounter = new AtomicInteger();
    private final AtomicInteger imageCounter = new AtomicInteger();
    private boolean hasText;

    void appendText(String text, boolean html) {
        if (text == null || text.isBlank()) {
            return;
        }
        hasText = true;
        if (html) {
            content.append("<p id=\"doc-p-")
                    .append(textCounter.incrementAndGet())
                    .append("\">")
                    .append(escapeHtml(text.strip()))
                    .append("</p>\n");
        } else {
            content.append(text.strip()).append("\n\n");
        }
    }

    void appendImage(String imageUrl, boolean html) {
        if (html) {
            content.append("<img id=\"doc-img-")
                    .append(imageCounter.incrementAndGet())
                    .append("\" src=\"")
                    .append(escapeHtml(imageUrl))
                    .append("\" />\n");
        } else {
            content.append("![](").append(escapeMarkdownUrl(imageUrl)).append(")\n\n");
        }
    }

    void appendTable(java.util.List<java.util.List<String>> rows, boolean html) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        boolean hasCellText = rows.stream().flatMap(java.util.List::stream)
                .anyMatch(value -> value != null && !value.isBlank());
        if (!hasCellText) {
            return;
        }
        hasText = true;
        if (html) {
            content.append("<table id=\"doc-table-").append(textCounter.incrementAndGet()).append("\">\n");
            for (java.util.List<String> row : rows) {
                content.append("<tr>");
                for (String cell : row) {
                    content.append("<td>").append(escapeHtml(cell == null ? "" : cell.strip())).append("</td>");
                }
                content.append("</tr>\n");
            }
            content.append("</table>\n");
        } else {
            int columns = rows.stream().mapToInt(java.util.List::size).max().orElse(0);
            java.util.List<String> header = rows.get(0);
            appendMarkdownTableRow(header, columns);
            java.util.List<String> separator = new java.util.ArrayList<>();
            for (int i = 0; i < columns; i++) {
                separator.add("---");
            }
            appendMarkdownTableRow(separator, columns);
            for (int i = 1; i < rows.size(); i++) {
                appendMarkdownTableRow(rows.get(i), columns);
            }
            content.append("\n");
        }
    }

    private void appendMarkdownTableRow(java.util.List<String> row, int columns) {
        content.append('|');
        for (int i = 0; i < columns; i++) {
            String cell = i < row.size() && row.get(i) != null ? row.get(i).strip() : "";
            content.append(' ').append(escapeMarkdownCell(cell)).append(' ').append('|');
        }
        content.append("\n");
    }

    String build() {
        return content.toString().stripTrailing();
    }

    boolean hasText() {
        return hasText;
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String escapeMarkdownUrl(String value) {
        return value.replace(" ", "%20").replace(")", "%29");
    }

    private static String escapeMarkdownCell(String value) {
        return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }
}
