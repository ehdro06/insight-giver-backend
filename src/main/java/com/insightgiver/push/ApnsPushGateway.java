package com.insightgiver.push;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.insightgiver.account.ApnsEnvironment;
import com.insightgiver.account.Device;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends iOS pushes straight to APNs over HTTP/2. Insight pushes are background pushes: the app's
 * native handler stores them for the widget, nothing is shown, so APNs requires push type
 * {@code background} and priority 5.
 */
final class ApnsPushGateway implements PushGateway {

    static final URI PRODUCTION = URI.create("https://api.push.apple.com");
    static final URI SANDBOX = URI.create("https://api.sandbox.push.apple.com");

    private static final Logger log = LoggerFactory.getLogger(ApnsPushGateway.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Pattern APPLE_ID = Pattern.compile("[A-Z0-9]{10}");
    /** Reasons meaning the token no longer reaches this app on this host. */
    private static final Set<String> INVALID_TOKEN_REASONS = Set.of("BadDeviceToken", "Unregistered", "DeviceTokenNotForTopic");

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorResponse(String reason) {
    }

    private final HttpClient http;
    private final ApnsAuthToken authToken;
    private final String topic;
    private final Duration ttl;
    private final Map<ApnsEnvironment, URI> hosts;
    private final JsonMapper json;
    private final Clock clock;

    static ApnsPushGateway create(PushProperties.Ios properties, Duration ttl, JsonMapper json, Clock clock)
            throws IOException {
        String teamId = required(properties.teamId(), "APNS_TEAM_ID");
        String keyId = required(properties.keyId(), "APNS_KEY_ID");
        String topic = required(properties.topic(), "APNS_TOPIC");
        if (!APPLE_ID.matcher(teamId).matches() || !APPLE_ID.matcher(keyId).matches()) {
            throw new IllegalStateException("APNS_TEAM_ID and APNS_KEY_ID are 10 characters of A-Z and 0-9");
        }
        String pem = properties.privateKey() != null && !properties.privateKey().isBlank()
                ? properties.privateKey()
                : Files.readString(Path.of(required(properties.privateKeyFile(), "APNS_PRIVATE_KEY or APNS_PRIVATE_KEY_FILE")));
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        return new ApnsPushGateway(http, new ApnsAuthToken(teamId, keyId, ApnsAuthToken.readPrivateKey(pem), clock),
                topic, ttl, Map.of(ApnsEnvironment.PRODUCTION, PRODUCTION, ApnsEnvironment.SANDBOX, SANDBOX), json, clock);
    }

    ApnsPushGateway(HttpClient http, ApnsAuthToken authToken, String topic, Duration ttl,
            Map<ApnsEnvironment, URI> hosts, JsonMapper json, Clock clock) {
        this.http = http;
        this.authToken = authToken;
        this.topic = topic;
        this.ttl = ttl;
        this.hosts = hosts;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Result send(Device device, Map<String, String> data) {
        ApnsEnvironment environment = device.apnsEnvironment() == null ? ApnsEnvironment.PRODUCTION : device.apnsEnvironment();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("aps", Map.of("content-available", 1));
        payload.putAll(data);

        HttpRequest request = HttpRequest.newBuilder(hosts.get(environment).resolve("/3/device/" + device.pushToken()))
                .timeout(REQUEST_TIMEOUT)
                .header("authorization", "bearer " + authToken.current())
                .header("apns-topic", topic)
                .header("apns-push-type", "background")
                .header("apns-priority", "5")
                .header("apns-expiration", String.valueOf(clock.instant().plus(ttl).getEpochSecond()))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.warn("Push to device {} could not reach APNs", device.id(), e);
            return Result.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.FAILED;
        }

        if (response.statusCode() == 200) {
            return Result.SENT;
        }
        String reason = reason(response.body());
        if (response.statusCode() == 410 || INVALID_TOKEN_REASONS.contains(reason)) {
            log.info("APNs token of device {} is no longer valid ({})", device.id(), reason);
            return Result.INVALID_TOKEN;
        }
        if ("ExpiredProviderToken".equals(reason)) {
            authToken.invalidate();
        }
        log.warn("APNs rejected the push to device {}: HTTP {} {}", device.id(), response.statusCode(), reason);
        return Result.FAILED;
    }

    private String reason(String body) {
        try {
            ErrorResponse error = json.readValue(body, ErrorResponse.class);
            return error == null || error.reason() == null ? "" : error.reason();
        } catch (JacksonException e) {
            return "";
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "insight-giver.push.ios.provider=apns needs " + name + " (use provider=log to run without APNs)");
        }
        return value;
    }
}
