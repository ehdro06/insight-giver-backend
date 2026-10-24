package com.insightgiver.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

class FuzzyRecallTest {

    private final Random random = new Random(42);

    @Test
    void blurKeepsTheCueRecognizable() {
        float[] cue = new float[1024];
        cue[7] = 3f; // deliberately not unit length

        float[] blurred = FuzzyRecall.blur(cue, 0.5, random);

        assertThat(dot(blurred, blurred)).isCloseTo(1.0, within(1e-4));
        // Cosine to the original unit cue: 1 / sqrt(1 + 0.5²) ≈ 0.894
        assertThat((double) blurred[7]).isCloseTo(0.894, within(0.02));
    }

    @Test
    void blurWithoutNoiseOnlyNormalizes() {
        float[] blurred = FuzzyRecall.blur(new float[] {3f, 4f}, 0, random);

        assertThat(blurred).containsExactly(0.6f, 0.8f);
    }

    @Test
    void coldTemperatureAlwaysPicksTheClosestMemory() {
        List<Double> similarities = List.of(0.2, 0.9, 0.5);

        for (int i = 0; i < 100; i++) {
            assertThat(FuzzyRecall.pick(similarities, Double::doubleValue, 0.001, random)).isEqualTo(0.9);
        }
    }

    @Test
    void warmTemperatureFavoursCloserMemoriesButNotOnly() {
        List<Double> similarities = List.of(0.80, 0.75, 0.40);
        Map<Double, Integer> counts = new HashMap<>();

        for (int i = 0; i < 10_000; i++) {
            counts.merge(FuzzyRecall.pick(similarities, Double::doubleValue, 0.08, random), 1, Integer::sum);
        }

        assertThat(counts.get(0.80)).isGreaterThan(counts.get(0.75));
        assertThat(counts.get(0.75)).isGreaterThan(1_000);
        assertThat(counts.getOrDefault(0.40, 0)).isLessThan(100);
    }

    @Test
    void picksTheOnlyCandidate() {
        assertThat(FuzzyRecall.pick(List.of("only"), s -> 0.1, 0.08, random)).isEqualTo("only");
    }

    private static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}
