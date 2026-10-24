package com.insightgiver.push;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("insight-giver.push")
public record PushProperties(@NotNull Provider provider, @Valid @NotNull Fcm fcm) {

    public enum Provider {
        FCM,
        /** Logs pushes instead of sending them. */
        LOG
    }

    /**
     * @param credentialsFile  service-account JSON; Application Default Credentials when blank
     * @param ttl              how long FCM/APNs keep trying to deliver; tomorrow's insight replaces it anyway
     * @param androidPriority  HIGH wakes dozing devices, but FCM deprioritizes apps whose high-priority
     *                         pushes never show a notification, so NORMAL is the safe default
     */
    public record Fcm(String credentialsFile, @NotNull Duration ttl, @NotNull AndroidPriority androidPriority) {

        public enum AndroidPriority {
            NORMAL,
            HIGH
        }
    }
}
