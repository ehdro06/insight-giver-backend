package com.insightgiver.insight;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param apiKey when blank, the SDK resolves credentials from the environment
 * @param effort low | medium | high | xhigh | max
 */
@Validated
@ConfigurationProperties("insight-giver.claude")
public record ClaudeProperties(
        String apiKey,
        @NotBlank String model,
        @NotBlank String effort,
        @Positive long maxTokens,
        @NotNull Duration timeout) {
}
