package com.insightgiver.embedding;

import java.util.Locale;

/**
 * Offline stand-in for a real embedding model: signed feature hashing of words and word pairs.
 * Entries that share wording land close together, which is enough to exercise the pipeline
 * locally and in tests. It understands nothing, so never use it in production.
 */
public final class HashingEmbeddingClient implements EmbeddingClient {

    @Override
    public String model() {
        return "hashing-v1";
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIMENSIONS];
        String previous = null;
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.isEmpty()) {
                continue;
            }
            add(vector, word, 1f);
            if (previous != null) {
                add(vector, previous + ' ' + word, 0.5f);
            }
            previous = word;
        }
        if (!normalize(vector)) {
            // Text without letters or digits: a zero vector has no cosine distance, so pin it somewhere.
            vector[Math.floorMod(mix(text.hashCode()), DIMENSIONS)] = 1f;
        }
        return vector;
    }

    private static void add(float[] vector, String feature, float weight) {
        int hash = mix(feature.hashCode());
        float sign = (hash & 0x8000_0000) == 0 ? 1f : -1f;
        vector[Math.floorMod(hash, DIMENSIONS)] += sign * weight;
    }

    private static boolean normalize(float[] vector) {
        double sumOfSquares = 0;
        for (float value : vector) {
            sumOfSquares += value * value;
        }
        if (sumOfSquares == 0) {
            return false;
        }
        float norm = (float) Math.sqrt(sumOfSquares);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
        return true;
    }

    /** MurmurHash3's finalizer, so similar strings spread across dimensions. */
    private static int mix(int hash) {
        hash ^= hash >>> 16;
        hash *= 0x85eb_ca6b;
        hash ^= hash >>> 13;
        hash *= 0xc2b2_ae35;
        hash ^= hash >>> 16;
        return hash;
    }
}
