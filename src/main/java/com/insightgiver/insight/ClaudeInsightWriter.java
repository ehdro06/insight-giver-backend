package com.insightgiver.insight;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.stream.Collectors;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.beta.AnthropicBeta;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.BetaTextBlock;
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive;
import com.anthropic.models.beta.messages.MessageCreateParams;

/** Has Claude word the insight in the voice of an observant friend. */
final class ClaudeInsightWriter implements InsightWriter, AutoCloseable {

    static final String SYSTEM_PROMPT = """
            You are the quiet companion inside Insight Giver, a private journal. The person drops \
            unstructured thoughts into it and doesn't expect replies. Once a day you send them one short \
            note that connects something they wrote recently with an older entry it brings to mind.

            Write like an observant friend rather than an assistant or a therapist: casual, warm and \
            specific. Name the echo, contrast or change you notice, borrowing their own words where it \
            helps. Don't give advice, diagnose, moralize or ask them to do anything, and skip greetings, \
            sign-offs, emoji and hashtags.

            Write in the language the entries are written in. Keep the note under 280 characters and \
            reply with the note only.""";

    private final AnthropicClient client;
    private final ClaudeProperties properties;

    ClaudeInsightWriter(ClaudeProperties properties) {
        // fromEnv() also picks up ANTHROPIC_AUTH_TOKEN, profiles and workload identity federation.
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder().fromEnv().timeout(properties.timeout());
        if (properties.apiKey() != null && !properties.apiKey().isBlank()) {
            builder.apiKey(properties.apiKey());
        }
        this.client = builder.build();
        this.properties = properties;
    }

    @Override
    public String name() {
        return "claude/" + properties.model();
    }

    @Override
    public String write(Request request) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(properties.model())
                .maxTokens(properties.maxTokens())
                .system(SYSTEM_PROMPT)
                .thinking(BetaThinkingConfigAdaptive.builder().build())
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.of(properties.effort())).build())
                // If a safety classifier declines the request, the API re-runs it on Anthropic's
                // recommended fallback model instead of returning the refusal.
                .fallbacksDefault()
                .addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
                .addUserMessage(userMessage(request))
                .build();

        BetaMessage message;
        try {
            message = client.beta().messages().create(params);
        } catch (RateLimitException | InternalServerException | AnthropicIoException e) {
            // Already retried by the SDK; the next hourly run tries again.
            throw new InsightWriterException("Claude is unavailable right now", e);
        } catch (AnthropicServiceException e) {
            throw new InsightWriterException("Claude rejected the request with HTTP " + e.statusCode(), e);
        }

        if (message.stopReason().filter(BetaStopReason.REFUSAL::equals).isPresent()) {
            throw new InsightWriterException("Claude declined to write this insight"
                    + message.stopDetails().map(details -> " (" + details.category() + ")").orElse(""));
        }
        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(BetaTextBlock::text)
                .collect(Collectors.joining())
                .strip();
        if (text.isEmpty()) {
            throw new InsightWriterException("Claude returned no text (stop reason: "
                    + message.stopReason().map(Object::toString).orElse("none") + ")");
        }
        return unquote(text);
    }

    @Override
    public void close() {
        client.close();
    }

    static String userMessage(Request request) {
        return """
                Recent entry (%s):
                <recent_entry>
                %s
                </recent_entry>

                Older entry (%s):
                <older_entry>
                %s
                </older_entry>""".formatted(
                when(request.recent(), request), request.recent().content().strip(),
                when(request.recalled(), request), request.recalled().content().strip());
    }

    private static String when(Memory memory, Request request) {
        LocalDate date = memory.writtenAt().atZoneSameInstant(request.timeZone()).toLocalDate();
        long days = ChronoUnit.DAYS.between(date, request.today());
        String ago = days <= 0 ? "today" : days == 1 ? "yesterday" : days + " days ago";
        return date + ", " + ago;
    }

    /** Models sometimes wrap a requested "note only" reply in quotes. */
    private static String unquote(String text) {
        if (text.length() >= 2 && "\"“".indexOf(text.charAt(0)) >= 0
                && "\"”".indexOf(text.charAt(text.length() - 1)) >= 0) {
            return text.substring(1, text.length() - 1).strip();
        }
        return text;
    }
}
