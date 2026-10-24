package com.insightgiver.push;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.Map;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.insightgiver.account.Device;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class FcmPushGateway implements PushGateway, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FcmPushGateway.class);
    /** A newer insight replaces an undelivered older one instead of queueing behind it. */
    private static final String COLLAPSE_KEY = "insight";

    private final FirebaseApp app;
    private final FirebaseMessaging messaging;
    private final PushProperties.Fcm properties;
    private final Clock clock;

    FcmPushGateway(PushProperties.Fcm properties, Clock clock) throws IOException {
        this.app = FirebaseApp.initializeApp(
                FirebaseOptions.builder().setCredentials(credentials(properties.credentialsFile())).build(),
                "insight-giver");
        this.messaging = FirebaseMessaging.getInstance(app);
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Result send(Device device, Map<String, String> data) {
        Message message = Message.builder()
                .setToken(device.pushToken())
                .putAllData(data)
                .setAndroidConfig(AndroidConfig.builder()
                        .setPriority(properties.androidPriority() == PushProperties.Fcm.AndroidPriority.HIGH
                                ? AndroidConfig.Priority.HIGH
                                : AndroidConfig.Priority.NORMAL)
                        .setTtl(properties.ttl().toMillis())
                        .setCollapseKey(COLLAPSE_KEY)
                        .build())
                .setApnsConfig(ApnsConfig.builder()
                        // A background (silent) push: content-available and nothing visible. APNs
                        // requires push type "background" and priority 5 for these.
                        .putHeader("apns-push-type", "background")
                        .putHeader("apns-priority", "5")
                        .putHeader("apns-expiration", String.valueOf(clock.instant().plus(properties.ttl()).getEpochSecond()))
                        .setAps(Aps.builder().setContentAvailable(true).build())
                        .build())
                .build();
        try {
            messaging.send(message);
            return Result.SENT;
        } catch (FirebaseMessagingException e) {
            MessagingErrorCode code = e.getMessagingErrorCode();
            if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.SENDER_ID_MISMATCH) {
                log.info("Push token of device {} is no longer valid ({})", device.id(), code);
                return Result.INVALID_TOKEN;
            }
            log.warn("Push to device {} failed ({})", device.id(), code, e);
            return Result.FAILED;
        }
    }

    @Override
    public void close() {
        app.delete();
    }

    private static GoogleCredentials credentials(String credentialsFile) throws IOException {
        if (credentialsFile == null || credentialsFile.isBlank()) {
            return GoogleCredentials.getApplicationDefault();
        }
        try (InputStream in = new FileInputStream(credentialsFile)) {
            return GoogleCredentials.fromStream(in);
        }
    }
}
