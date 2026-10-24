package com.insightgiver.entry;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param key            Redis list holding ids of entries waiting for an embedding
 * @param pollTimeout    how long one blocking pop waits; also bounds how long shutdown waits for the dispatcher
 * @param workerThreads  concurrent embedding calls
 * @param sweepInterval  how often stale PENDING entries are re-queued
 * @param staleAfter     how long a queued entry may go unprocessed before the sweeper re-queues it
 * @param retryBackoff   delay before the first retry of a failed embedding; doubles per attempt
 * @param maxRetryBackoff cap for the doubling
 * @param maxAttempts    failed attempts before an entry is marked FAILED
 */
@Validated
@ConfigurationProperties("insight-giver.queue")
public record QueueProperties(
        @NotBlank String key,
        @NotNull Duration pollTimeout,
        @Min(1) int workerThreads,
        @NotNull Duration sweepInterval,
        @NotNull Duration staleAfter,
        @NotNull Duration retryBackoff,
        @NotNull Duration maxRetryBackoff,
        @Min(1) int maxAttempts) {
}
