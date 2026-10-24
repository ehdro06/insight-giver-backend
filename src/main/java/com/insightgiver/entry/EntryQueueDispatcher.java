package com.insightgiver.entry;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Moves entry ids from Redis to the {@code @Async} embedding workers. When every worker is busy
 * the pool's caller-runs policy makes this thread do the work itself, which stops it popping, so
 * a backlog waits in Redis instead of piling up in memory.
 */
@Component
class EntryQueueDispatcher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(EntryQueueDispatcher.class);
    private static final Duration ERROR_BACKOFF = Duration.ofSeconds(5);

    private final EntryQueue queue;
    private final EntryEmbeddingWorker worker;

    private volatile boolean running;
    private Thread thread;

    EntryQueueDispatcher(EntryQueue queue, EntryEmbeddingWorker worker) {
        this.queue = queue;
        this.worker = worker;
    }

    @Override
    public void start() {
        running = true;
        thread = Thread.ofPlatform().name("entry-queue-dispatcher").start(this::dispatch);
    }

    @Override
    public void stop() {
        // No interrupt: abandoning a blocking pop mid-flight can drop the id it was about to return.
        // The loop notices within one poll timeout.
        running = false;
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void dispatch() {
        while (running) {
            try {
                Optional<UUID> entryId = queue.poll();
                entryId.ifPresent(worker::embed);
            } catch (RuntimeException e) {
                if (!running) {
                    break;
                }
                log.warn("Polling the entry queue failed; retrying in {}", ERROR_BACKOFF, e);
                pause();
            }
        }
    }

    private void pause() {
        try {
            Thread.sleep(ERROR_BACKOFF);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
