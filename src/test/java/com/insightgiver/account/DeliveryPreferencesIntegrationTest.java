package com.insightgiver.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.insightgiver.ApiClient;
import com.insightgiver.ApiClient.Session;
import com.insightgiver.IntegrationTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class DeliveryPreferencesIntegrationTest {

    @Autowired
    private MockMvcTester mvc;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc);
    }

    @Test
    void newAccountsGetTheirInsightInTheMorning() throws Exception {
        Session session = api.register("Asia/Seoul");

        assertThat(api.delivery(session)).hasStatusOk().bodyJson()
                .isLenientlyEqualTo("{\"mode\":\"MORNING\",\"time\":null}");
    }

    @Test
    void remembersThePickedTimeAcrossModes() throws Exception {
        Session session = api.register("Asia/Seoul");

        assertThat(api.putDelivery(session, "{\"mode\":\"FIXED\",\"time\":\"21:30\"}")).hasStatusOk().bodyJson()
                .isLenientlyEqualTo("{\"mode\":\"FIXED\",\"time\":\"21:30\"}");
        assertThat(api.putDelivery(session, "{\"mode\":\"SURPRISE\"}")).hasStatusOk();
        assertThat(api.delivery(session)).hasStatusOk().bodyJson()
                .isLenientlyEqualTo("{\"mode\":\"SURPRISE\",\"time\":\"21:30\"}");
        assertThat(api.putDelivery(session, "{\"mode\":\"FIXED\"}")).hasStatusOk().bodyJson()
                .isLenientlyEqualTo("{\"mode\":\"FIXED\",\"time\":\"21:30\"}");
    }

    @Test
    void rejectsTimesItCannotKeep() throws Exception {
        Session session = api.register("Asia/Seoul");

        assertThat(api.putDelivery(session, "{\"mode\":\"FIXED\"}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(api.putDelivery(session, "{\"mode\":\"FIXED\",\"time\":\"21:10\"}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(api.putDelivery(session, "{\"mode\":\"FIXED\",\"time\":\"25:00\"}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(api.putDelivery(session, "{\"mode\":\"FIXED\",\"time\":\"9pm\"}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(api.putDelivery(session, "{\"mode\":\"WEEKLY\"}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(api.delivery(session)).hasStatusOk().bodyJson().extractingPath("$.mode").isEqualTo("MORNING");
    }

    @Test
    void needsAToken() {
        assertThat(mvc.get().uri("/api/v1/me/delivery")).hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
