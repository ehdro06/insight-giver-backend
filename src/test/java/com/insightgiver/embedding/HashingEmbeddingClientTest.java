package com.insightgiver.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class HashingEmbeddingClientTest {

    private final HashingEmbeddingClient client = new HashingEmbeddingClient();

    @Test
    void producesDeterministicUnitVectors() {
        float[] first = client.embed("Ran along the river this morning");
        float[] second = client.embed("Ran along the river this morning");

        assertThat(first).hasSize(EmbeddingClient.DIMENSIONS).containsExactly(second);
        assertThat(dot(first, first)).isCloseTo(1.0, within(1e-5));
    }

    @Test
    void sharedWordingMeansCloserVectors() {
        float[] run = client.embed("Went running by the river, my head felt clear");
        float[] runAgain = client.embed("Running by the river again cleared my head");
        float[] work = client.embed("Deadline stress at work, could not sleep");

        assertThat(dot(run, runAgain)).isGreaterThan(dot(run, work));
    }

    @Test
    void textWithoutWordsStillGetsAUsableVector() {
        assertThat(dot(client.embed("..."), client.embed("..."))).isCloseTo(1.0, within(1e-5));
    }

    private static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}
