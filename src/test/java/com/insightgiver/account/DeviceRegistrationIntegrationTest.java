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
class DeviceRegistrationIntegrationTest {

    private static final String APNS_TOKEN = "0f".repeat(32);

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private DeviceRepository devices;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc);
    }

    @Test
    void remembersWhichApnsHostAnIosTokenBelongsTo() throws Exception {
        Session session = api.register("Asia/Seoul");

        assertThat(api.putDevice(session, """
                {"platform":"IOS","pushToken":"%s","apnsEnvironment":"SANDBOX","timeZone":"Asia/Seoul"}
                """.formatted(APNS_TOKEN))).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(devices.findByAccount(session.accountId()))
                .singleElement()
                .satisfies(device -> {
                    assertThat(device.pushToken()).isEqualTo(APNS_TOKEN);
                    assertThat(device.apnsEnvironment()).isEqualTo(ApnsEnvironment.SANDBOX);
                });
    }

    @Test
    void assumesProductionForIosAndNothingForAndroid() throws Exception {
        Session ios = api.register("UTC");
        Session android = api.register("UTC");

        api.registerDevice(ios, "IOS", "UTC");
        api.registerDevice(android, "ANDROID", "UTC");

        assertThat(devices.findByAccount(ios.accountId()).getFirst().apnsEnvironment()).isEqualTo(ApnsEnvironment.PRODUCTION);
        assertThat(devices.findByAccount(android.accountId()).getFirst().apnsEnvironment()).isNull();
    }

    @Test
    void replacesTheOldTokenWhenTheDeviceGetsANewOne() throws Exception {
        Session session = api.register("UTC");

        api.putDevice(session, "{\"platform\":\"IOS\",\"pushToken\":\"%s\",\"timeZone\":\"UTC\"}".formatted("aa".repeat(32)));
        api.putDevice(session, "{\"platform\":\"IOS\",\"pushToken\":\"%s\",\"timeZone\":\"UTC\"}".formatted("bb".repeat(32)));

        assertThat(devices.findByAccount(session.accountId())).extracting(Device::pushToken).containsExactly("bb".repeat(32));
    }

    @Test
    void rejectsIosTokensThatAreNotApnsTokens() throws Exception {
        Session session = api.register("UTC");

        assertThat(api.putDevice(session, "{\"platform\":\"IOS\",\"pushToken\":\"../../evil\",\"timeZone\":\"UTC\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }
}
