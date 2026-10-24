package com.insightgiver.push;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Android pushes go through FCM, iOS pushes straight to APNs.
 *
 * @param ttl how long FCM and APNs keep trying to deliver; tomorrow's insight replaces it anyway
 */
@Validated
@ConfigurationProperties("insight-giver.push")
public record PushProperties(@NotNull Duration ttl, @Valid @NotNull Android android, @Valid @NotNull Ios ios) {

    /**
     * @param credentialsFile service-account JSON; Application Default Credentials when blank
     * @param priority        HIGH wakes dozing devices, but FCM deprioritizes apps whose high-priority
     *                        pushes never show a notification, so NORMAL is the safe default
     */
    public record Android(@NotNull Provider provider, String credentialsFile, @NotNull Priority priority) {

        public enum Provider {
            FCM,
            /** Logs pushes instead of sending them. */
            LOG
        }

        public enum Priority {
            NORMAL,
            HIGH
        }
    }

    /**
     * Token-based APNs authentication with a .p8 key from the Apple Developer portal.
     *
     * @param privateKey     the key's PEM contents; takes precedence over {@code privateKeyFile}
     * @param privateKeyFile path to the .p8 file
     * @param topic          the app's bundle id
     */
    public record Ios(@NotNull Provider provider, String teamId, String keyId, String privateKey, String privateKeyFile,
            String topic) {

        public enum Provider {
            APNS,
            /** Logs pushes instead of sending them. */
            LOG
        }
    }
}
