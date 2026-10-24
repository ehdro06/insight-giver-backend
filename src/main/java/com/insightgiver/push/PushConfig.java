package com.insightgiver.push;

import java.io.IOException;
import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class PushConfig {

    @Bean
    PushGateway pushGateway(PushProperties properties, JsonMapper json, Clock clock) throws IOException {
        PushGateway android = switch (properties.android().provider()) {
            case FCM -> new FcmPushGateway(properties.android(), properties.ttl());
            case LOG -> new LoggingPushGateway();
        };
        PushGateway ios = switch (properties.ios().provider()) {
            case APNS -> ApnsPushGateway.create(properties.ios(), properties.ttl(), json, clock);
            case LOG -> new LoggingPushGateway();
        };
        return new PlatformPushGateway(android, ios);
    }
}
