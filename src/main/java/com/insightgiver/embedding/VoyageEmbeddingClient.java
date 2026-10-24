package com.insightgiver.embedding;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Voyage AI embeddings (https://docs.voyageai.com/reference/embeddings-api). */
final class VoyageEmbeddingClient implements EmbeddingClient {

    record EmbeddingRequest(
            List<String> input,
            String model,
            @JsonProperty("input_type") String inputType,
            @JsonProperty("output_dimension") int outputDimension) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingResponse(List<Item> data) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Item(float[] embedding) {
        }
    }

    private final RestClient http;
    private final String model;

    static VoyageEmbeddingClient create(RestClient.Builder builder, EmbeddingProperties.Voyage properties) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        requestFactory.setReadTimeout(properties.timeout());
        return new VoyageEmbeddingClient(builder.requestFactory(requestFactory), properties);
    }

    VoyageEmbeddingClient(RestClient.Builder builder, EmbeddingProperties.Voyage properties) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "insight-giver.embedding.provider=voyage needs VOYAGE_API_KEY (use provider=hashing for offline development)");
        }
        this.http = builder
                .baseUrl(properties.baseUrl().toString())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .build();
        this.model = properties.model();
    }

    @Override
    public String model() {
        return "voyage/" + model;
    }

    @Override
    public float[] embed(String text) {
        // Entries are compared with each other (not with short search queries), so every text is a "document".
        EmbeddingResponse response = http.post()
                .uri("/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new EmbeddingRequest(List.of(text), model, "document", DIMENSIONS))
                .retrieve()
                .body(EmbeddingResponse.class);
        if (response == null || response.data() == null || response.data().isEmpty()) {
            throw new EmbeddingException("Voyage returned no embedding");
        }
        float[] embedding = response.data().getFirst().embedding();
        if (embedding == null || embedding.length != DIMENSIONS) {
            throw new EmbeddingException("Voyage returned " + (embedding == null ? 0 : embedding.length)
                    + " dimensions, expected " + DIMENSIONS);
        }
        return embedding;
    }
}
