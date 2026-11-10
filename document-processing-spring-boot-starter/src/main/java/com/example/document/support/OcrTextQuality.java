package com.example.document.support;

final class OcrTextQuality {
    private OcrTextQuality() {
    }

    static Assessment assess(String text) {
        if (text == null || text.isBlank()) {
            return new Assessment(true, "empty-result");
        }
        String compact = text.replaceAll("\\s+", "");
        if (compact.length() < 8) {
            return new Assessment(true, "too-little-text-handwriting-signature-or-stamp-possible");
        }

        int useful = 0;
        int suspicious = 0;
        for (int i = 0; i < compact.length(); i++) {
            char ch = compact.charAt(i);
            if (isUseful(ch)) {
                useful++;
            } else if (!isExpectedPunctuation(ch)) {
                suspicious++;
            }
        }

        double usefulRatio = useful / (double) compact.length();
        double suspiciousRatio = suspicious / (double) compact.length();
        if (usefulRatio < 0.35) {
            return new Assessment(true, "low-readable-character-ratio");
        }
        if (suspiciousRatio > 0.30) {
            return new Assessment(true, "many-unexpected-symbols-possible-garbled-text");
        }
        if (hasLongRepeatedNoise(compact)) {
            return new Assessment(true, "repeated-noise-possible-garbled-text");
        }
        return new Assessment(false, "ok");
    }

    private static boolean isUseful(char ch) {
        Character.UnicodeScript script = Character.UnicodeScript.of(ch);
        return Character.isLetterOrDigit(ch)
                || script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private static boolean isExpectedPunctuation(char ch) {
        return ",.;:!?()[]{}<>/-_+=%#@&*'\"`~|\\".indexOf(ch) >= 0
                || Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.GENERAL_PUNCTUATION;
    }

    private static boolean hasLongRepeatedNoise(String text) {
        int repeated = 1;
        for (int i = 1; i < text.length(); i++) {
            if (text.charAt(i) == text.charAt(i - 1) && !Character.isLetterOrDigit(text.charAt(i))) {
                repeated++;
                if (repeated >= 4) {
                    return true;
                }
            } else {
                repeated = 1;
            }
        }
        return false;
    }

    record Assessment(boolean difficult, String reason) {
    }
}
