package com.insightgiver.push;

import java.io.IOException;
import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class PushConfig {

    @Bean
    PushGateway pushGateway(PushProperties properties, Clock clock) throws IOException {
        return switch (properties.provider()) {
            case FCM -> new FcmPushGateway(properties.fcm(), clock);
            case LOG -> new LoggingPushGateway();
        };
    }
}
