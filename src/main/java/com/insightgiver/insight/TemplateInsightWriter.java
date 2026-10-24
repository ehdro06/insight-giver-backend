package com.insightgiver.insight;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

import com.insightgiver.support.Text;

/** Fixed wording without any API call, for local development and tests. */
final class TemplateInsightWriter implements InsightWriter {

    private static final int EXCERPT_LENGTH = 90;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);

    @Override
    public String name() {
        return "template";
    }

    @Override
    public String write(Request request) {
        String when = request.recalled().writtenAt().atZoneSameInstant(request.timeZone()).format(DATE);
        return "On " + when + " you wrote “" + excerpt(request.recalled().content())
                + "”. It came back to me when you wrote “" + excerpt(request.recent().content()) + "”.";
    }

    private static String excerpt(String text) {
        String flat = text.strip().replaceAll("\\s+", " ");
        String clipped = Text.clip(flat, EXCERPT_LENGTH);
        return clipped.length() < flat.length() ? clipped + "…" : clipped;
    }
}
