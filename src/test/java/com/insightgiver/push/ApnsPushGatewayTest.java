package com.insightgiver.push;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.insightgiver.TestFixtures.MutableClock;
import com.insightgiver.account.ApnsEnvironment;
import com.insightgiver.account.Device;
import com.insightgiver.account.Platform;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class ApnsPushGatewayTest {

    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");
    private static final String TOKEN = "ab".repeat(32);
    private static final Map<String, String> PAYLOAD = Map.of("type", "insight", "insightId", "i-1", "body", "Hello");

    private final JsonMapper json = JsonMapper.builder().build();
    private final MutableClock clock = new MutableClock(NOW);
    private FakeApns production;
    private FakeApns sandbox;
    private KeyPair keys;
    private ApnsPushGateway gateway;

    @BeforeEach
    void setUp() throws Exception {
        production = new FakeApns();
        sandbox = new FakeApns();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        keys = generator.generateKeyPair();
        gateway = new ApnsPushGateway(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
                new ApnsAuthToken("TEAM123456", "KEY1234567", keys.getPrivate(), clock),
                "com.insightgiver.app",
                Duration.ofHours(24),
                Map.of(ApnsEnvironment.PRODUCTION, production.uri(), ApnsEnvironment.SANDBOX, sandbox.uri()),
                json,
                clock);
    }

    @AfterEach
    void tearDown() {
        production.stop();
        sandbox.stop();
    }

    @Test
    void sendsABackgroundPushToTheTokensEnvironment() {
        assertThat(gateway.send(device(ApnsEnvironment.SANDBOX), PAYLOAD)).isEqualTo(PushGateway.Result.SENT);

        assertThat(production.requests()).isEmpty();
        FakeApns.Request request = sandbox.requests().getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/3/device/" + TOKEN);
        assertThat(request.header("apns-topic")).isEqualTo("com.insightgiver.app");
        assertThat(request.header("apns-push-type")).isEqualTo("background");
        assertThat(request.header("apns-priority")).isEqualTo("5");
        assertThat(request.header("apns-expiration")).isEqualTo(String.valueOf(NOW.plus(Duration.ofHours(24)).getEpochSecond()));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = json.readValue(request.body(), Map.class);
        assertThat(body.get("aps")).isEqualTo(Map.of("content-available", 1));
        assertThat(body).containsEntry("type", "insight").containsEntry("insightId", "i-1").containsEntry("body", "Hello");
    }

    @Test
    void sendsToProductionUnlessTheDeviceSaysSandbox() {
        gateway.send(device(null), PAYLOAD);
        gateway.send(device(ApnsEnvironment.PRODUCTION), PAYLOAD);

        assertThat(production.requests()).hasSize(2);
        assertThat(sandbox.requests()).isEmpty();
    }

    @Test
    void authenticatesWithAnEs256ProviderToken() throws Exception {
        gateway.send(device(ApnsEnvironment.PRODUCTION), PAYLOAD);

        String[] jwt = bearer(production.requests().getFirst()).split("\\.");
        assertThat(decode(jwt[0])).isEqualTo(Map.of("alg", "ES256", "kid", "KEY1234567"));
        assertThat(decode(jwt[1])).isEqualTo(Map.of("iss", "TEAM123456", "iat", (int) NOW.getEpochSecond()));
        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(keys.getPublic());
        verifier.update((jwt[0] + "." + jwt[1]).getBytes(UTF_8));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(jwt[2]))).isTrue();
    }

    @Test
    void reusesTheProviderTokenFor50Minutes() {
        gateway.send(device(null), PAYLOAD);
        clock.set(NOW.plus(Duration.ofMinutes(49)));
        gateway.send(device(null), PAYLOAD);
        clock.set(NOW.plus(Duration.ofMinutes(51)));
        gateway.send(device(null), PAYLOAD);

        List<String> tokens = production.requests().stream().map(ApnsPushGatewayTest::bearer).toList();
        assertThat(tokens.get(1)).isEqualTo(tokens.get(0));
        assertThat(tokens.get(2)).isNotEqualTo(tokens.get(1));
    }

    @Test
    void reportsTokensApnsNoLongerAccepts() {
        production.respond(410, "{\"reason\":\"Unregistered\",\"timestamp\":1761264000000}");
        assertThat(gateway.send(device(null), PAYLOAD)).isEqualTo(PushGateway.Result.INVALID_TOKEN);

        production.respond(400, "{\"reason\":\"BadDeviceToken\"}");
        assertThat(gateway.send(device(null), PAYLOAD)).isEqualTo(PushGateway.Result.INVALID_TOKEN);
    }

    @Test
    void keepsTheTokenOnOtherFailures() {
        production.respond(429, "{\"reason\":\"TooManyRequests\"}");
        assertThat(gateway.send(device(null), PAYLOAD)).isEqualTo(PushGateway.Result.FAILED);

        production.respond(500, "");
        assertThat(gateway.send(device(null), PAYLOAD)).isEqualTo(PushGateway.Result.FAILED);
    }

    @Test
    void signsAFreshProviderTokenAfterApnsCallsItExpired() {
        production.respond(403, "{\"reason\":\"ExpiredProviderToken\"}");
        assertThat(gateway.send(device(null), PAYLOAD)).isEqualTo(PushGateway.Result.FAILED);
        production.respond(200, "");
        gateway.send(device(null), PAYLOAD);

        List<String> tokens = production.requests().stream().map(ApnsPushGatewayTest::bearer).toList();
        assertThat(tokens.get(1)).isNotEqualTo(tokens.get(0));
    }

    @Test
    void readsTheP8KeyTheDeveloperPortalIssues() throws Exception {
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(UTF_8)).encodeToString(keys.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";

        assertThat(ApnsAuthToken.readPrivateKey(pem).getEncoded()).isEqualTo(keys.getPrivate().getEncoded());
    }

    @Test
    void refusesToStartWithoutCredentials() {
        PushProperties.Ios missingKey = new PushProperties.Ios(
                PushProperties.Ios.Provider.APNS, "TEAM123456", "KEY1234567", null, null, "com.insightgiver.app");

        assertThatThrownBy(() -> ApnsPushGateway.create(missingKey, Duration.ofHours(24), json, clock))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APNS_PRIVATE_KEY");
    }

    private static Device device(ApnsEnvironment environment) {
        return new Device(UUID.randomUUID(), UUID.randomUUID(), Platform.IOS, TOKEN, environment);
    }

    private static String bearer(FakeApns.Request request) {
        return request.header("authorization").substring("bearer ".length());
    }

    private Map<?, ?> decode(String part) {
        return json.readValue(Base64.getUrlDecoder().decode(part), Map.class);
    }

    /** Stands in for one APNs host, recording requests and answering with a configurable response. */
    private static final class FakeApns {

        record Request(String method, String path, Map<String, List<String>> headers, String body) {

            String header(String name) {
                return headers.entrySet().stream()
                        .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                        .map(entry -> entry.getValue().getFirst())
                        .findFirst()
                        .orElse(null);
            }
        }

        private final HttpServer server;
        private final List<Request> requests = new CopyOnWriteArrayList<>();
        private volatile int status = 200;
        private volatile String body = "";

        FakeApns() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            server.createContext("/", exchange -> {
                requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        Map.copyOf(exchange.getRequestHeaders()), new String(exchange.getRequestBody().readAllBytes(), UTF_8)));
                byte[] response = body.getBytes(UTF_8);
                exchange.sendResponseHeaders(status, response.length == 0 ? -1 : response.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(response);
                }
            });
            server.start();
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        void respond(int status, String body) {
            this.status = status;
            this.body = body;
        }

        List<Request> requests() {
            return List.copyOf(requests);
        }

        void stop() {
            server.stop(0);
        }
    }
}
