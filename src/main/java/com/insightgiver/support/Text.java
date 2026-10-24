package com.insightgiver.support;

public final class Text {

    private Text() {
    }

    /**
     * Shortens {@code text} to at most {@code maxChars} chars, at a word break when there is one
     * near the end (text without spaces is cut anywhere), never between the halves of a
     * surrogate pair. Text that already fits is returned unchanged.
     */
    public static String clip(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        int cut = text.lastIndexOf(' ', maxChars);
        if (cut < maxChars * 2 / 3) {
            cut = maxChars;
        }
        if (cut > 0 && Character.isHighSurrogate(text.charAt(cut - 1))) {
            cut--;
        }
        return text.substring(0, cut).stripTrailing();
    }
}
