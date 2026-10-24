package com.insightgiver.insight;

import java.util.List;
import java.util.function.ToDoubleFunction;
import java.util.random.RandomGenerator;

/**
 * The "fuzzy memory" model: recall is cued by something recent, but like human memory it is
 * imprecise. The cue vector is blurred before the similarity search, and the memory is then
 * sampled from the nearest candidates instead of always taking the closest one, so the same
 * diary yields different (but still related) connections from day to day.
 */
final class FuzzyRecall {

    private FuzzyRecall() {
    }

    /**
     * Returns the cue nudged in a random direction and normalized again. The noise is scaled so
     * that its expected length is {@code blur} times the cue's, which keeps the result at a cosine
     * similarity of roughly {@code 1 / sqrt(1 + blur²)} to the original whatever the dimension.
     */
    static float[] blur(float[] cue, double blur, RandomGenerator random) {
        float[] unit = normalized(cue);
        double sigma = blur / Math.sqrt(unit.length);
        float[] blurred = new float[unit.length];
        for (int i = 0; i < unit.length; i++) {
            blurred[i] = (float) (unit[i] + random.nextGaussian() * sigma);
        }
        return normalized(blurred);
    }

    /**
     * Samples one candidate with probability proportional to {@code exp(similarity / temperature)}.
     * A temperature near 0 always returns the most similar candidate.
     */
    static <T> T pick(List<T> candidates, ToDoubleFunction<T> similarity, double temperature, RandomGenerator random) {
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("Nothing to pick from");
        }
        double best = candidates.stream().mapToDouble(similarity).max().orElseThrow();
        double[] weights = new double[candidates.size()];
        double total = 0;
        for (int i = 0; i < weights.length; i++) {
            // Shifted by the best similarity so exp() cannot overflow.
            weights[i] = Math.exp((similarity.applyAsDouble(candidates.get(i)) - best) / temperature);
            total += weights[i];
        }
        double target = random.nextDouble() * total;
        for (int i = 0; i < weights.length; i++) {
            target -= weights[i];
            if (target < 0) {
                return candidates.get(i);
            }
        }
        return candidates.getLast();
    }

    private static float[] normalized(float[] vector) {
        double sumOfSquares = 0;
        for (float value : vector) {
            sumOfSquares += value * value;
        }
        double norm = Math.sqrt(sumOfSquares);
        float[] result = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            result[i] = (float) (vector[i] / norm);
        }
        return result;
    }
}
