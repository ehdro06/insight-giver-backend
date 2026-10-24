package com.insightgiver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** The mobile app's side of the API, for integration tests. */
public final class ApiClient {

    public record Session(UUID accountId, String token) {
    }

    private final MockMvcTester mvc;

    public ApiClient(MockMvcTester mvc) {
        this.mvc = mvc;
    }

    public Session register(String timeZone) throws Exception {
        MvcTestResult result = mvc.post().uri("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"timeZone\":\"%s\"}".formatted(timeZone))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        String body = result.getResponse().getContentAsString();
        return new Session(UUID.fromString(JsonPath.read(body, "$.accountId")), JsonPath.read(body, "$.token"));
    }

    /** Registers a push token shaped like the platform's: hex for APNs, free-form for FCM. */
    public void registerDevice(Session session, String platform, String timeZone) {
        String pushToken = platform.equals("IOS")
                ? session.accountId().toString().replace("-", "").repeat(2)
                : "fcm-" + session.accountId();
        assertThat(putDevice(session, "{\"platform\":\"%s\",\"pushToken\":\"%s\",\"timeZone\":\"%s\"}"
                .formatted(platform, pushToken, timeZone)))
                .hasStatus(HttpStatus.NO_CONTENT);
    }

    public MvcTestResult putDevice(Session session, String json) {
        return mvc.put().uri("/api/v1/me/device")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    /** Posts an entry and returns the id the server assigned. */
    public UUID postEntry(Session session, String clientId, String content, Instant writtenAt) throws Exception {
        MvcTestResult result = mvc.post().uri("/api/v1/entries")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientId\":\"%s\",\"content\":\"%s\",\"writtenAt\":\"%s\"}".formatted(clientId, content, writtenAt))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.ACCEPTED);
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    public MvcTestResult latestInsight(Session session) {
        return mvc.get().uri("/api/v1/insights/latest")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                .exchange();
    }
}
