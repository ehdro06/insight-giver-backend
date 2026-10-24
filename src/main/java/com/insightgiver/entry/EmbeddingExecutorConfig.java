package com.insightgiver.entry;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableAsync
class EmbeddingExecutorConfig {

    /** Bounded so embedding traffic stays within the provider's rate limits. */
    @Bean(EntryEmbeddingWorker.EXECUTOR)
    ThreadPoolTaskExecutor embeddingExecutor(QueueProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("embedding-");
        executor.setCorePoolSize(properties.workerThreads());
        executor.setMaxPoolSize(properties.workerThreads());
        executor.setQueueCapacity(properties.workerThreads());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        return executor;
    }
}
