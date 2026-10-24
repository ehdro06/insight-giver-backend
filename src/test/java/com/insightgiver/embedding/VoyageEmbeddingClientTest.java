package com.insightgiver.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class VoyageEmbeddingClientTest {

    private MockRestServiceServer server;
    private VoyageEmbeddingClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new VoyageEmbeddingClient(builder, new EmbeddingProperties.Voyage(
                "test-key", "voyage-4-lite", URI.create("https://api.voyageai.com/v1"), Duration.ofSeconds(5)));
    }

    @Test
    void requestsADocumentEmbeddingOfTheColumnSize() {
        server.expect(requestTo("https://api.voyageai.com/v1/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(content().json("""
                        {"input": ["hello"], "model": "voyage-4-lite", "input_type": "document", "output_dimension": 1024}
                        """))
                .andRespond(withSuccess(response(EmbeddingClient.DIMENSIONS), MediaType.APPLICATION_JSON));

        float[] embedding = client.embed("hello");

        assertThat(embedding).hasSize(EmbeddingClient.DIMENSIONS);
        assertThat(client.model()).isEqualTo("voyage/voyage-4-lite");
        server.verify();
    }

    @Test
    void rejectsVectorsOfTheWrongSize() {
        server.expect(requestTo("https://api.voyageai.com/v1/embeddings"))
                .andRespond(withSuccess(response(512), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.embed("hello"))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("512");
    }

    private static String response(int dimensions) {
        String vector = Collections.nCopies(dimensions, "0.01").stream().collect(Collectors.joining(","));
        return """
                {"object": "list", "data": [{"object": "embedding", "embedding": [%s], "index": 0}],
                 "model": "voyage-4-lite", "usage": {"total_tokens": 1}}
                """.formatted(vector);
    }
}
