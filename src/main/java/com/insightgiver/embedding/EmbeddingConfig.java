package com.insightgiver.embedding;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class EmbeddingConfig {

    @Bean
    EmbeddingClient embeddingClient(EmbeddingProperties properties, RestClient.Builder restClientBuilder) {
        return switch (properties.provider()) {
            case VOYAGE -> VoyageEmbeddingClient.create(restClientBuilder, properties.voyage());
            case HASHING -> new HashingEmbeddingClient();
        };
    }
}
