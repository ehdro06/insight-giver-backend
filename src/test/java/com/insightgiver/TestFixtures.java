package com.insightgiver;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.insightgiver.account.Device;
import com.insightgiver.push.PushGateway;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** A settable clock and a push gateway that records instead of sending. */
@TestConfiguration(proxyBeanMethods = false)
public class TestFixtures {

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock(Instant.parse("2026-10-23T02:00:00Z"));
    }

    @Bean
    @Primary
    RecordingPushGateway recordingPushGateway() {
        return new RecordingPushGateway();
    }

    public static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        public void set(Instant now) {
            this.now = now;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }

    public static final class RecordingPushGateway implements PushGateway {

        public record Sent(Device device, Map<String, String> data) {
        }

        private final List<Sent> sent = new CopyOnWriteArrayList<>();

        @Override
        public Result send(Device device, Map<String, String> data) {
            sent.add(new Sent(device, data));
            return Result.SENT;
        }

        public List<Sent> sent() {
            return List.copyOf(sent);
        }

        public void clear() {
            sent.clear();
        }
    }
}
