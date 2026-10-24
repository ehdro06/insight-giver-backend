package com.insightgiver.insight;

import java.util.Random;
import java.util.random.RandomGenerator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class InsightConfig {

    @Bean
    InsightWriter insightWriter(InsightProperties properties, ClaudeProperties claude) {
        return switch (properties.writer()) {
            case CLAUDE -> new ClaudeInsightWriter(claude);
            case TEMPLATE -> new TemplateInsightWriter();
        };
    }

    /** {@link Random} rather than a faster generator because runs share it across threads. */
    @Bean
    RandomGenerator insightRandom() {
        return new Random();
    }
}
