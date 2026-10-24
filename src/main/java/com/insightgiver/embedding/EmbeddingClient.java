package com.insightgiver.embedding;

/** Turns diary text into vectors stored in {@code entries.embedding}. */
public interface EmbeddingClient {

    /** Must match the {@code vector(...)} column in {@code V1__init.sql}. */
    int DIMENSIONS = 1024;

    /** Identifies the vector space. Recall only compares vectors produced by the same model. */
    String model();

    float[] embed(String text);
}
