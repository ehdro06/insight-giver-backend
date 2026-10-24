package com.insightgiver.embedding;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("insight-giver.embedding")
public record EmbeddingProperties(@NotNull Provider provider, @Valid @NotNull Voyage voyage) {

    public enum Provider {
        VOYAGE,
        /** Deterministic word hashing: offline and free, but with no real semantics. */
        HASHING
    }

    public record Voyage(String apiKey, @NotBlank String model, @NotNull URI baseUrl, @NotNull Duration timeout) {
    }
}
